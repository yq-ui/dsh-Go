import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../../launch/launch_state.dart';

/// 背景视频: the moving backdrop, on by default in this build and switchable from the wallpaper
/// settings. The clip ships inside the APK, so nothing is read from the gallery and nothing is
/// downloaded.
final ValueNotifier<bool> wallpaperVideo = ValueNotifier<bool>(true);

/// True while the clip is really on screen. Pages keep their own backdrop until then, so a phone
/// that cannot play the clip keeps its wallpaper picture instead of showing a hole.
final ValueNotifier<bool> wallpaperVideoRunning = ValueNotifier<bool>(false);

const String _prefsKey = 'wallpaper_video';
const MethodChannel _channel = MethodChannel('dsh/wallpaper-video');

/// The bundled clip's shape (res/raw/background.mp4): it is cropped to cover the screen.
const double _clipAspect = 3840 / 2160;

/// Reads the stored choice once at start-up (absent means on).
Future<void> loadWallpaperVideoSetting() async {
  try {
    final prefs = await SharedPreferences.getInstance();
    wallpaperVideo.value = prefs.getBool(_prefsKey) ?? true;
  } catch (_) {
    wallpaperVideo.value = true;
  }
}

/// Puts the moving backdrop behind the whole app, once: one decoder for every page, and pages that
/// show it stay transparent instead of owning a player of their own.
class RootVideoBackdrop extends StatefulWidget {
  const RootVideoBackdrop({super.key, required this.child});

  final Widget child;

  @override
  State<RootVideoBackdrop> createState() => _RootVideoBackdropState();
}

class _RootVideoBackdropState extends State<RootVideoBackdrop> with WidgetsBindingObserver {
  static int _nextRequest = 0;
  bool _allowMotion = false;
  int? _request;
  int? _texture;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    wallpaperVideo.addListener(_sync);
    LaunchController.active.addListener(_sync);
    _channel.setMethodCallHandler(_nativeEvent);
  }

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    final highContrast = MediaQuery.maybeHighContrastOf(context) ?? false;
    final reduceMotion = MediaQuery.maybeDisableAnimationsOf(context) ?? false;
    _allowMotion = !highContrast && !reduceMotion;
    _sync();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) => _sync();

  void _sync() {
    final state = WidgetsBinding.instance.lifecycleState;
    final visible = state == AppLifecycleState.resumed || state == AppLifecycleState.inactive;
    final wanted = visible && _allowMotion && wallpaperVideo.value && LaunchController.active.value == null;
    if (wanted == (_request != null)) return;
    if (wanted) {
      final request = ++_nextRequest;
      _request = request;
      unawaited(_start(request));
    } else {
      _request = null;
      _clearTexture();
      // A paused app may not draw another frame; don't defer release to a widget rebuild.
      unawaited(_stop());
    }
  }

  Future<void> _start(int request) async {
    try {
      final id = await _channel.invokeMethod<int>('start', request);
      if (!mounted || request != _request || id == null) return;
      setState(() => _texture = id);
      wallpaperVideoRunning.value = true;
    } catch (_) {
      // Missing player or unsupported clip: keep the static wallpaper.
    }
  }

  Future<void> _nativeEvent(MethodCall call) async {
    if (mounted && call.method == 'ended' && call.arguments == _request) _clearTexture();
  }

  void _clearTexture() {
    if (_texture != null) setState(() => _texture = null);
    wallpaperVideoRunning.value = false;
  }

  Future<void> _stop() async {
    try {
      await _channel.invokeMethod<void>('stop');
    } catch (_) {}
  }

  @override
  void dispose() {
    wallpaperVideo.removeListener(_sync);
    LaunchController.active.removeListener(_sync);
    WidgetsBinding.instance.removeObserver(this);
    _channel.setMethodCallHandler(null);
    if (_request != null) unawaited(_stop());
    _request = null;
    wallpaperVideoRunning.value = false;
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => Stack(
    fit: StackFit.expand,
    children: [
      if (_texture case final int id) _VideoLayer(texture: id) else const SizedBox.shrink(),
      widget.child,
    ],
  );
}

class _VideoLayer extends StatelessWidget {
  const _VideoLayer({required this.texture});

  final int texture;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final dark = Theme.of(context).brightness == Brightness.dark;
    return Stack(
      fit: StackFit.expand,
      children: [
        // Cover, not fit: black bars around a moving picture look broken.
        ClipRect(
          child: FittedBox(
            fit: BoxFit.cover,
            child: SizedBox(
              width: _clipAspect * 1000,
              height: 1000,
              child: Texture(textureId: texture),
            ),
          ),
        ),
        IgnorePointer(
          child: ColoredBox(color: scheme.surface.withValues(alpha: dark ? 0.42 : 0.18)),
        ),
      ],
    );
  }
}
