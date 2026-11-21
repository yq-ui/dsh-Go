import 'package:dsh_app/phone/android_shell_channel.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('root backend status requires a connected channel and uid zero', () {
    final status = AndroidShellStatus.fromMap({
      'v': 1,
      'enabled': true,
      'backend': 'root',
      'ready': true,
      'uid': 0,
      'root': {'connected': true},
      'limits': const <String, Object?>{},
    });

    expect(status.supported, isTrue);
    expect(status.backend, AndroidShellBackend.root);
    expect(status.ready, isTrue);
    expect(status.uid, 0);
    expect(status.rootConnected, isTrue);
  });

  test('root cannot be reported ready without verified uid and channel evidence', () {
    for (final contradictory in [
      {'enabled': true, 'uid': 2000, 'root': {'connected': true}},
      {'enabled': true, 'uid': 0, 'root': {'connected': false}},
      {'enabled': true, 'uid': null, 'root': {'connected': true}},
      {'enabled': false, 'uid': 0, 'root': {'connected': true}},
    ]) {
      final status = AndroidShellStatus.fromMap({
        'v': 1,
        'enabled': contradictory['enabled'],
        'backend': 'root',
        'ready': true,
        'uid': contradictory['uid'],
        'root': contradictory['root'],
        'limits': const <String, Object?>{},
      });
      expect(status.ready, isFalse);
    }
  });

  test('root transport is not inferred from a device root claim', () {
    final status = AndroidShellStatus.fromMap({
      'v': 1,
      'enabled': true,
      'backend': 'root',
      'ready': false,
      'uid': null,
      'root': {'connected': false},
      'limits': const <String, Object?>{},
    });

    expect(status.supported, isTrue);
    expect(status.ready, isFalse);
    expect(status.rootConnected, isFalse);
  });

  test('old status missing root fields remains parseable', () {
    final status = AndroidShellStatus.fromMap({
      'v': 1,
      'enabled': false,
      'backend': 'shizuku',
      'ready': false,
      'limits': const <String, Object?>{},
    });

    expect(status.supported, isTrue);
    expect(status.rootConnected, isFalse);
  });
}
