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
  /// False while the app is in the background: the player is released instead of decoding a clip
  /// nobody can see.
  bool _foreground = true;

  /// False while the opening animation still covers the app: a 4K clip has no business taking
  /// decoder and CPU time from the engine boot that happens behind it.
  bool _launched = LaunchController.active.value == null;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    LaunchController.active.addListener(_launchChanged);
  }

  @override
  void dispose() {
    LaunchController.active.removeListener(_launchChanged);
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  void _launchChanged() {
    final launched = LaunchController.active.value == null;
    if (launched != _launched) setState(() => _launched = launched);
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    // inactive still shows the page (a dialog, the shade being pulled): keep the clip running there.
    final foreground = state == AppLifecycleState.resumed || state == AppLifecycleState.inactive;
    if (foreground != _foreground) setState(() => _foreground = foreground);
  }

  @override
  Widget build(BuildContext context) {
    return ValueListenableBuilder<bool>(
      valueListenable: wallpaperVideo,
      builder: (context, on, _) {
        final show =
            on && _foreground && _launched && !(MediaQuery.maybeHighContrastOf(context) ?? false);
        return Stack(
          fit: StackFit.expand,
          children: [
            if (show) const WallpaperVideoBackdrop() else const SizedBox.shrink(),
            widget.child,
          ],
        );
      },
    );
  }
}

/// The video layer itself, with a page-coloured veil so text keeps its contrast over the picture.
///
/// The clip plays into a Flutter texture rather than a platform view: the texture is part of
/// Flutter's own scene, so the pages are always drawn over it. A platform view lives in the
/// activity's view hierarchy and ended up on top of every page on this device.
class WallpaperVideoBackdrop extends StatefulWidget {
  const WallpaperVideoBackdrop({super.key});

  @override
  State<WallpaperVideoBackdrop> createState() => _WallpaperVideoBackdropState();
}

class _WallpaperVideoBackdropState extends State<WallpaperVideoBackdrop> {
  int? _texture;

  @override
  void initState() {
    super.initState();
    unawaited(_start());
  }

  Future<void> _start() async {
    try {
      final id = await _channel.invokeMethod<int>('start');
      if (!mounted || id == null) return;
      setState(() => _texture = id);
      wallpaperVideoRunning.value = true;
    } catch (_) {
      // No player: the wallpaper picture stays, which is the right thing to show.
    }
  }

  @override
  void dispose() {
    wallpaperVideoRunning.value = false;
    unawaited(_stop());
    super.dispose();
  }

  Future<void> _stop() async {
    try {
      await _channel.invokeMethod<void>('stop');
    } catch (_) {}
  }

  @override
  Widget build(BuildContext context) {
    final texture = _texture;
    if (texture == null) return const SizedBox.shrink();
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
