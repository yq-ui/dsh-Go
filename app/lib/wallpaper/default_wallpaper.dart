// 默认壁纸：App 第一次启动时，把内置的那张图铺到首页、对话和抽屉后面。
// 只在「用户还没有自己的壁纸」时写入一次；之后用户在设置里换或关掉，都不会被覆盖。

import 'dart:convert';
import 'dart:io';

import 'package:shared_preferences/shared_preferences.dart';

import 'custom_wallpaper.dart';
import 'default_wallpaper_data.dart';
import 'wallpaper_import.dart';

const int _defaultWidth = 1080;
const int _defaultHeight = 1920;

/// Writes the bundled picture as the wallpaper when the person has not chosen one yet.
Future<void> ensureDefaultWallpaper() async {
  try {
    final prefs = await SharedPreferences.getInstance();
    // Any stored wallpaper means the person already decided; never overwrite that.
    if (prefs.containsKey(CustomWallpaper.prefsKey)) return;
    final directory = await WallpaperImport.wallpaperDirectory();
    final file = File('${directory.path}/default_wallpaper.jpg');
    if (!file.existsSync()) {
      await file.writeAsBytes(base64Decode(defaultWallpaperJpegBase64), flush: true);
    }
    await CustomWallpaper.save(
      CustomWallpaper(
        path: file.path,
        width: _defaultWidth,
        height: _defaultHeight,
        // Kept readable: only a light softening, with a modest page tint behind text.
        blur: 0.10,
        lighten: 0.18,
        dim: 0.42,
      ),
    );
  } catch (_) {
    // A wallpaper is decoration: never let it break start-up.
  }
}
