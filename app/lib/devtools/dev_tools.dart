import 'package:flutter/services.dart';

/// 开发者工具: bridges to the JGit side of the app.
///
/// Every call carries a host path (the engine's files are plain files here) and the native
/// side refuses anything outside the roots it was given.
class DevTools {
  static const MethodChannel _channel = MethodChannel('dsh/devtools');

  /// Roots the native side accepts, and the workspace directory inside the engine.
  static Future<DevToolsInfo> info() async {
    final raw = await _channel.invokeMapMethod<String, Object?>('info');
    return DevToolsInfo.fromMap(raw);
  }

  static Future<GitStatus> status(String path) async =>
      GitStatus.fromMap(await _channel.invokeMapMethod<String, Object?>('gitStatus', {'path': path}));

  static Future<List<GitCommit>> log(String path, {int limit = 20}) async {
    final raw = await _channel.invokeMapMethod<String, Object?>('gitLog', {'path': path, 'limit': limit});
    final entries = (raw?['entries'] as List?) ?? const [];
    return [
      for (final entry in entries)
        if (entry is Map) GitCommit.fromMap(entry.cast<String, Object?>()),
    ];
  }

  static Future<String> commit(String path, String message) async {
    final raw = await _channel.invokeMapMethod<String, Object?>('gitCommit', {'path': path, 'message': message});
    return (raw?['id'] as String?) ?? '';
  }

  static Future<void> init(String path) => _channel.invokeMethod<void>('gitInit', {'path': path});
}

class DevToolsInfo {
  const DevToolsInfo({required this.roots, required this.workspace});

  final List<String> roots;

  /// Where the engine keeps the person's workspace, when it is installed.
  final String? workspace;

  static DevToolsInfo fromMap(Map<String, Object?>? raw) => DevToolsInfo(
    roots: [for (final root in (raw?['roots'] as List?) ?? const []) if (root is String) root],
    workspace: raw?['workspace'] as String?,
  );
}

class GitStatus {
  const GitStatus({
    required this.clean,
    required this.branch,
    required this.added,
    required this.changed,
    required this.modified,
    required this.untracked,
  });

  final bool clean;
  final String branch;
  final List<String> added;
  final List<String> changed;
  final List<String> modified;
  final List<String> untracked;

  int get pending => added.length + changed.length + modified.length + untracked.length;

  static GitStatus fromMap(Map<String, Object?>? raw) {
    List<String> list(String key) => [
      for (final item in (raw?[key] as List?) ?? const []) if (item is String) item,
    ];
    return GitStatus(
      clean: raw?['clean'] == true,
      branch: (raw?['branch'] as String?) ?? '',
      added: list('added'),
      changed: list('changed'),
      modified: list('modified'),
      untracked: list('untracked'),
    );
  }
}

class GitCommit {
  const GitCommit({required this.id, required this.message, required this.author, required this.time});

  final String id;
  final String message;
  final String author;
  final String time;

  static GitCommit fromMap(Map<String, Object?> raw) => GitCommit(
    id: (raw['id'] as String?) ?? '',
    message: (raw['message'] as String?) ?? '',
    author: (raw['author'] as String?) ?? '',
    time: (raw['time'] as String?) ?? '',
  );
}
