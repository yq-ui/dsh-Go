import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:shared_preferences/shared_preferences.dart';

/// 背景视频: the moving backdrop, on by default in this build and switchable from the wallpaper
/// settings. It is a platform view playing the clip shipped inside the APK, so nothing is read
/// from the gallery and nothing is downloaded.
final ValueNotifier<bool> wallpaperVideo = ValueNotifier<bool>(true);

const String _prefsKey = 'wallpaper_video';

/// Reads the stored choice once at start-up (absent means on).
Future<void> loadWallpaperVideoSetting() async {
  try {
    final prefs = await SharedPreferences.getInstance();
    wallpaperVideo.value = prefs.getBool(_prefsKey) ?? true;
  } catch (_) {
    wallpaperVideo.value = true;
  }
}

/// Puts the moving backdrop behind the whole app, once: the clip is decoded by a single view for
/// every page, and pages that show it stay transparent instead of owning a player of their own.
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

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    final foreground = state == AppLifecycleState.resumed;
    if (foreground != _foreground) setState(() => _foreground = foreground);
  }

  @override
  Widget build(BuildContext context) {
    return ValueListenableBuilder<bool>(
      valueListenable: wallpaperVideo,
      builder: (context, on, _) {
        final show = on && _foreground && !(MediaQuery.maybeHighContrastOf(context) ?? false);
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
class WallpaperVideoBackdrop extends StatelessWidget {
  const WallpaperVideoBackdrop({super.key});

  @override
  Widget build(BuildContext context) {
    if (!Platform.isAndroid) return const SizedBox.shrink();
    final scheme = Theme.of(context).colorScheme;
    final dark = Theme.of(context).brightness == Brightness.dark;
    return Stack(
      fit: StackFit.expand,
      children: [
        const IgnorePointer(
          child: AndroidView(
            viewType: 'dsh/background-video',
            creationParamsCodec: StandardMessageCodec(),
          ),
        ),
        IgnorePointer(
          child: ColoredBox(color: scheme.surface.withValues(alpha: dark ? 0.42 : 0.18)),
        ),
      ],
    );
  }
}
