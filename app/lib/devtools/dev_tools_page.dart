import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../l10n/l10n.dart';
import '../ui/common/list_page_row.dart';
import '../ui/features/feature_widgets.dart';
import '../ui/shell/page_frame.dart';
import '../ui/theme/theme.dart';
import 'dev_tools.dart';

/// 开发者工具 › Git: status, log and a plain commit, through JGit in the app process.
///
/// The path is a host path — the engine's files are ordinary files here — and the native side
/// refuses anything outside its own roots.
class DevToolsPage extends StatefulWidget {
  const DevToolsPage({super.key});

  @override
  State<DevToolsPage> createState() => _DevToolsPageState();
}

class _DevToolsPageState extends State<DevToolsPage> {
  final _path = TextEditingController();
  final _message = TextEditingController();
  final _editorPath = TextEditingController();
  bool _editorOpen = false;
  GitStatus? _status;
  List<GitCommit> _log = const [];
  String? _error;
  String? _note;
  bool _busy = false;

  @override
  void initState() {
    super.initState();
    _load();
  }

  @override
  void dispose() {
    _path.dispose();
    _message.dispose();
    _editorPath.dispose();
    super.dispose();
  }

  Future<void> _load() async {
    try {
      final info = await DevTools.info();
      if (!mounted) return;
      final suggested = info.workspace ?? info.roots.firstOrNull ?? '';
      setState(() => _path.text = suggested);
    } catch (e) {
      if (mounted) setState(() => _error = '$e');
    }
  }

  Future<void> _run(Future<void> Function() action) async {
    setState(() {
      _busy = true;
      _error = null;
      _note = null;
    });
    try {
      await action();
    } catch (e) {
      if (mounted) setState(() => _error = '$e');
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _refresh() => _run(() async {
    final path = _path.text.trim();
    final status = await DevTools.status(path);
    final log = await DevTools.log(path);
    if (!mounted) return;
    setState(() {
      _status = status;
      _log = log;
    });
  });

  static const MethodChannel _editorChannel = MethodChannel('dsh/devtools/editor');

  Future<void> _editor(String method) async {
    try {
      await _editorChannel.invokeMethod<void>(method);
    } catch (e) {
      if (mounted) setState(() => _error = '$e');
    }
  }

  Future<void> _commit() => _run(() async {
    final id = await DevTools.commit(_path.text.trim(), _message.text);
    _message.clear();
    final status = await DevTools.status(_path.text.trim());
    final log = await DevTools.log(_path.text.trim());
    if (!mounted) return;
    setState(() {
      _status = status;
      _log = log;
      _note = id.isEmpty ? null : '提交 $id';
    });
  });

  Future<void> _init() => _run(() async {
    await DevTools.init(_path.text.trim());
    await _refresh();
  });

  @override
  Widget build(BuildContext context) {
    final l10n = context.l10n;
    final theme = Theme.of(context);
    final status = _status;
    return AppPage.list(
      title: l10n.catalogDevTools,
      subtitle: Text(l10n.catalogDevToolsSummary),
      children: [
        Padding(
          padding: const EdgeInsets.fromLTRB(16, 0, 16, 8),
          child: TextField(
            controller: _path,
            decoration: const InputDecoration(labelText: 'Path', border: OutlineInputBorder()),
            style: AppType.monoLabel(color: theme.colorScheme.onSurface),
          ),
        ),
        Padding(
          padding: const EdgeInsets.symmetric(horizontal: 16),
          child: Wrap(
            spacing: 8,
            runSpacing: 8,
            children: [
              FilledButton(onPressed: _busy ? null : _refresh, child: const Text('状态与日志')),
              OutlinedButton(onPressed: _busy ? null : _init, child: const Text('初始化仓库')),
            ],
          ),
        ),
        if (_busy) const Padding(padding: EdgeInsets.all(16), child: LinearProgressIndicator()),
        if (_error case final error?) FeatureNote(error),
        if (_note case final note?) FeatureNote(note),
        if (status != null) ...[
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 16, 16, 8),
            child: Text(
              status.clean ? '干净 · 分支 ${status.branch}' : '待提交 ${status.pending} 项 · 分支 ${status.branch}',
              style: theme.textTheme.titleSmall,
            ),
          ),
          if (!status.clean)
            ListPageCard(
              children: [
                for (final path in [...status.modified, ...status.changed, ...status.added, ...status.untracked].take(30))
                  ListPageRow(icon: Icons.circle, title: path, titleStyle: AppType.monoLabel(color: theme.colorScheme.onSurface)),
              ],
            ),
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 16, 16, 8),
            child: Row(
              children: [
                Expanded(
                  child: TextField(
                    controller: _message,
                    decoration: const InputDecoration(labelText: 'Commit message', border: OutlineInputBorder()),
                  ),
                ),
                const SizedBox(width: 8),
                FilledButton(onPressed: _busy ? null : _commit, child: const Text('提交')),
              ],
            ),
          ),
        ],
        if (_log.isNotEmpty)
          ListPageCard(
            children: [
              for (final commit in _log)
                ListPageRow(
                  icon: Icons.commit,
                  title: commit.message.isEmpty ? commit.id : commit.message,
                  subtitle: '${commit.id} · ${commit.author} · ${commit.time}',
                  subtitleMaxLines: 1,
                ),
            ],
          ),
        const SizedBox(height: 32),
        Padding(
          padding: const EdgeInsets.fromLTRB(16, 0, 16, 8),
          child: TextField(
            controller: _editorPath,
            decoration: const InputDecoration(labelText: 'Edit file (host path)', border: OutlineInputBorder()),
            style: AppType.monoLabel(color: theme.colorScheme.onSurface),
          ),
        ),
        Padding(
          padding: const EdgeInsets.symmetric(horizontal: 16),
          child: Wrap(
            spacing: 8,
            children: [
              FilledButton(
                onPressed: _editorPath.text.trim().isEmpty ? null : () => setState(() => _editorOpen = true),
                child: const Text('打开编辑器'),
              ),
              OutlinedButton(
                onPressed: _editorOpen ? () => _editor('save') : null,
                child: const Text('保存'),
              ),
              OutlinedButton(
                onPressed: _editorOpen ? () => _editor('reload') : null,
                child: const Text('重新载入'),
              ),
            ],
          ),
        ),
        if (_editorOpen)
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 12, 16, 0),
            child: SizedBox(
              height: 360,
              child: ClipRRect(
                borderRadius: BorderRadius.circular(12),
                child: AndroidView(
                  viewType: 'dsh/devtools/editor',
                  creationParams: {'path': _editorPath.text.trim()},
                  creationParamsCodec: const StandardMessageCodec(),
                ),
              ),
            ),
          ),
        const SizedBox(height: 24),
      ],
    );
  }
}
