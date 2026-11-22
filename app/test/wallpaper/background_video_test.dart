import 'dart:async';

import 'package:dsh_app/ui/wallpaper/background_video.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  testWidgets('background releases before the next frame; late starts cannot uncover the page', (tester) async {
    const channel = MethodChannel('dsh/wallpaper-video');
    final messenger = tester.binding.defaultBinaryMessenger;
    final calls = <MethodCall>[];
    final starts = <Completer<int?>>[];
    messenger.setMockMethodCallHandler(channel, (call) async {
      calls.add(call);
      if (call.method == 'start') {
        final reply = Completer<int?>();
        starts.add(reply);
        return reply.future;
      }
      return null;
    });
    addTearDown(() => messenger.setMockMethodCallHandler(channel, null));
    Widget root({bool reduce = false}) => MediaQuery(
      data: MediaQueryData(disableAnimations: reduce),
      child: const Directionality(
        textDirection: TextDirection.ltr,
        child: RootVideoBackdrop(child: SizedBox()),
      ),
    );
    final binding = tester.binding;
    binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
    await tester.pumpWidget(root(reduce: true));
    expect(calls, isEmpty);
    await tester.pumpWidget(root());
    expect(starts, hasLength(1));
    expect(wallpaperVideoRunning.value, isFalse);
    binding.handleAppLifecycleStateChanged(AppLifecycleState.hidden);
    expect(calls.last.method, 'stop');
    binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
    starts.first.complete(70);
    await tester.pump();
    expect(wallpaperVideoRunning.value, isFalse);
    starts.last.complete(71);
    await tester.pump();
    expect(tester.widget<Texture>(find.byType(Texture)).textureId, 71);
    expect(wallpaperVideoRunning.value, isTrue);
    await tester.pumpWidget(root(reduce: true));
    expect(wallpaperVideoRunning.value, isFalse);
    expect(find.byType(Texture), findsNothing);
    expect(calls.map((c) => c.method), ['start', 'stop', 'start', 'stop']);
    await tester.pumpWidget(root());
    starts.last.complete(null); // native prepare error / first-frame timeout
    await tester.pump();
    expect(find.byType(Texture), findsNothing);
    expect(wallpaperVideoRunning.value, isFalse);
    await tester.pumpWidget(const SizedBox());
  });
}
