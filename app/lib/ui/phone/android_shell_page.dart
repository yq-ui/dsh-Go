import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../l10n/l10n.dart';
import '../../phone/android_shell_channel.dart';
import '../../state/phone_state.dart';
import '../common/list_page_row.dart';
import '../shell/page_frame.dart';
import '../theme/app_icons.dart';
import 'phone_widgets.dart';

String androidShellErrorText(L10n l10n, String code) => switch (code) {
  'disabled' => l10n.androidShellErrorDisabled,
  'stopped' => l10n.androidShellErrorStopped,
  'notConnected' => l10n.androidShellErrorNotConnected,
  'permissionRequired' => l10n.androidShellErrorPermissionRequired,
  'rootStartFailed' => AppLocale.isEnglish
      ? 'Root backend did not start. Check that a root manager is available and retry.'
      : 'Root 后端未能启动；检查 root 管理器是否可用后重试。',
  'rootIdentityMismatch' => AppLocale.isEnglish
      ? 'Root identity verification failed (UID must be 0); the command did not run.'
      : 'Root 后端身份校验未通过（UID 必须为 0），命令未执行。',
  'connectFailed' => l10n.androidShellErrorConnectFailed,
  'identityChanged' => l10n.androidShellErrorIdentityChanged,
  'helperStartFailed' => l10n.androidShellErrorHelperStartFailed,
  'busy' => l10n.androidShellErrorBusy,
  'invalidArguments' => AppLocale.isEnglish
      ? 'The request was rejected; try again.'
      : '请求参数无效，请重试。',
  'cancelled' => l10n.androidShellErrorCancelled,
  'timeout' => l10n.androidShellErrorTimeout,
  'confirmationDenied' => l10n.androidShellErrorConfirmationDenied,
  'cleanupFailed' => l10n.androidShellErrorCleanupFailed,
  'locked' => l10n.androidShellErrorLocked,
  'confirmationUnavailable' => l10n.androidShellErrorConfirmationUnavailable,
  'transportLost' => l10n.androidShellErrorTransportLost,
  'protocolMismatch' => l10n.androidShellErrorProtocolMismatch,
  'nativeUnavailable' => l10n.androidShellErrorNativeUnavailable,
  'invalidResponse' => l10n.androidShellErrorInvalidResponse,
  'stopFailed' => l10n.androidShellErrorStopFailed,
  _ => l10n.androidShellErrorUnknown,
};

/// Setup is separate from screen control; only its master switch gates enabling.
class AndroidShellPage extends ConsumerStatefulWidget {
  const AndroidShellPage({super.key});

  @override
  ConsumerState<AndroidShellPage> createState() => _AndroidShellPageState();
}

class _AndroidShellPageState extends ConsumerState<AndroidShellPage> {
  static const _channel = AndroidShellChannel();
  static const _margin = EdgeInsets.fromLTRB(16, 12, 16, 12);

  late final AppLifecycleListener _lifecycle;
  AndroidShellStatus _status = const AndroidShellStatus();
  bool _loading = true;
  bool _busy = false;
  bool _stopping = false;
  String? _errorCode;
  int _read = 0;
  int _operation = 0;

  @override
  void initState() {
    super.initState();
    _lifecycle = AppLifecycleListener(
      onResume: () {
        unawaited(ref.read(phoneProvider.notifier).refreshStatus());
        unawaited(_refresh());
      },
    );
    unawaited(_refresh());
  }

  @override
  void dispose() {
    _lifecycle.dispose();
    super.dispose();
  }

  Future<void> _refresh() async {
    final read = ++_read;
    AndroidShellStatus status;
    try {
      status = await _channel.status().timeout(const Duration(seconds: 15));
    } catch (_) {
      // A wedged native status call must not leave every row disabled forever.
      if (mounted && read == _read && _loading) setState(() => _loading = false);
      return;
    }
    if (!mounted || read != _read) return;
    setState(() {
      _status = status;
      _loading = false;
    });
  }

  Future<void> _run(Future<AndroidShellResult> Function() action) async {
    if (_busy || _stopping || !_status.supported) return;
    final operation = ++_operation;
    setState(() {
      _busy = true;
      _errorCode = null;
    });
    try {
      final result = await action();
      if (!mounted || operation != _operation) return;
      setState(() => _errorCode = result.ok ? null : result.errorCode ?? 'invalidResponse');
      await _refresh();
    } finally {
      if (mounted && operation == _operation) setState(() => _busy = false);
    }
  }

  Future<void> _toggle(bool enabled) =>
      _run(() => _channel.configure(enabled: enabled, backend: _status.backend));

  /// Stop remains available while a connection request is pending.
  Future<void> _stop() async {
    if (_stopping) return;
    final operation = ++_operation;
    setState(() {
      _stopping = true;
      _errorCode = null;
    });
    try {
      final stopped = await _channel.stop();
      if (!mounted || operation != _operation) return;
      setState(() => _errorCode = stopped ? null : 'stopFailed');
      await _refresh();
    } finally {
      if (mounted && operation == _operation) {
        setState(() {
          _stopping = false;
          _busy = false;
        });
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final l10n = context.l10n;
    final phone = ref.watch(phoneProvider);
    final canSetup = !_loading && _status.supported && !_busy && !_stopping;
    // Android Shell is independent of screen-control/accessibility readiness.
    final canEnable = canSetup && phone.loaded;
    final reasonCode = _status.reasonCode;
    final timeout = _status.limits['maxTimeoutMs'];
    final output = _status.limits['maxOutputBytes'];
    return AppPage.list(
      title: l10n.androidShellTitle,
      children: [
        PhoneNote(tone: NoteTone.warn, text: l10n.androidShellConfirmationNote),
        if (!phone.status.enabled) PhoneNote(text: l10n.androidShellMasterOff),
        if (!phone.loaded) PhoneNote(text: l10n.phoneNotReadyYet),
        ListPageCard(
          margin: _margin,
          children: [
            ListPageRow(
              icon: AppIcons.terminal,
              title: l10n.androidShellEnable,
              titleMaxLines: 2,
              trailing: Switch(
                key: const ValueKey('android-shell-enable'),
                value: _status.enabled,
                onChanged: canEnable || (canSetup && _status.enabled) ? (value) => unawaited(_toggle(value)) : null,
              ),
            ),
            ListPageRow(
              title: l10n.androidShellStatus,
              subtitle: _loading
                  ? l10n.phoneNotReadyYet
                  : !_status.supported
                  ? androidShellErrorText(l10n, reasonCode ?? 'invalidResponse')
                  : reasonCode != null
                  ? androidShellErrorText(l10n, reasonCode)
                  : !_status.enabled
                  ? l10n.androidShellErrorDisabled
                  : _status.ready
                  ? l10n.androidShellReady
                  : l10n.androidShellErrorNotConnected,
              subtitleMaxLines: 6,
            ),
            ListPageRow(title: l10n.androidShellRefresh, onTap: _loading ? null : () => unawaited(_refresh())),
          ],
        ),
        if (_errorCode case final code?)
          PhoneNote(
            key: const ValueKey('android-shell-operation-error'),
            tone: NoteTone.warn,
            text: androidShellErrorText(l10n, code),
          ),
        ListPageCard(
          margin: _margin,
          title: l10n.androidShellBackend,
          children: [
            ListPageRow(
              key: const ValueKey('android-shell-backend-root'),
              leading: const Icon(AppIcons.radioOn),
              title: 'Root (su)',
              titleMaxLines: 2,
            ),
          ],
        ),
        if (!_status.ready)
          ListPageCard(
            margin: _margin,
            children: [
              ListPageRow(
                key: ValueKey('android-shell-connect-${_status.backend.name}'),
                title: AppLocale.isEnglish ? 'Connect Root Shell' : '连接 Root Shell',
                titleMaxLines: 2,
                subtitle: !_status.enabled ? l10n.androidShellErrorDisabled : null,
                onTap: canSetup && _status.enabled ? () => unawaited(_run(_channel.connect)) : null,
              ),
            ],
          ),
        ListPageCard(
          margin: _margin,
          children: [
            ListPageRow(
              key: const ValueKey('android-shell-stop'),
              icon: AppIcons.stop,
              title: l10n.androidShellStop,
              titleMaxLines: 2,
              danger: true,
              onTap: _loading || _stopping ? null : () => unawaited(_stop()),
            ),
          ],
        ),
        PhoneOptionsFold(
          key: const ValueKey('android-shell-details'),
          title: l10n.androidShellDetails,
          children: [
            PhoneNote(text: l10n.androidShellSubtitle),
            PhoneNote(text: l10n.androidShellEnableHint),
            PhoneNote(text: AppLocale.isEnglish
                ? 'The root manager will request access on connect. DSH verifies the actual UID is 0. Commands still require individual confirmation. The Linux guest and Android host are separate environments.'
                : '连接时由 root 管理器弹窗授权；连接成功后会校验实际 UID=0。命令仍需逐条确认。Linux guest 与 Android 主机是不同执行环境。'),
            ListPageCard(
              margin: _margin,
              children: [
                ListPageRow(
                  key: const ValueKey('android-shell-uid'),
                  title: l10n.androidShellUid,
                  value: _status.uid?.toString() ?? l10n.androidShellUidUnknown,
                ),
                if (timeout != null && output != null)
                  ListPageRow(title: l10n.androidShellLimits(timeout ~/ 1000, output), titleMaxLines: 3),
                if (reasonCode != null)
                  ListPageRow(
                    key: const ValueKey('android-shell-reason'),
                    title: l10n.androidShellErrorCode(reasonCode),
                    titleMaxLines: 3,
                  ),
                if (_errorCode case final code?) ListPageRow(title: l10n.androidShellErrorCode(code), titleMaxLines: 3),
                _fact('su 通道已连接', _status.ready && _status.uid == 0),
                ListPageRow(title: '后端', value: 'Root (su)'),
                if (_status.rootSu != null) ListPageRow(title: 'su 路径', value: _status.rootSu!, titleMaxLines: 2),
                if (_status.rootError != null)
                  ListPageRow(title: '最近一次失败', value: _status.rootError!, titleMaxLines: 5),
              ],
            ),
            PhoneNote(text: l10n.androidShellStopHint),
          ],
        ),
      ],
    );
  }

  Widget _fact(String title, bool ready) => ListPageRow(
    title: title,
    titleMaxLines: 2,
    value: ready ? context.l10n.phoneReady : context.l10n.phoneNotReadyYet,
  );
}
