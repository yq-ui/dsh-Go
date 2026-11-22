import 'package:dsh_app/state/features_state.dart';
import 'package:dsh_app/ui/common/list_page_row.dart';
import 'package:dsh_app/ui/features/skills_page.dart';
import 'package:dsh_client/dsh_client.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import 'support/harness.dart';

void main() {
  setUpAll(loadTestFonts);

  testWidgets('large catalogs build only a viewport and still reach the last skill', (tester) async {
    usePhoneView(tester);
    final skills = List.generate(256, (i) => SkillInfo(name: 'guide-$i', description: 'Guide'));
    final harness = ChatHarness(extra: [skillsProvider.overrideWith((ref, id) async => skills)]);
    await tester.pumpWidget(testApp(const SkillsPage(sessionId: 's1'), overrides: harness.overrides));
    await pumpFrames(tester);
    expect(find.text('/guide-0'), findsOneWidget);
    expect(find.byType(ListPageRow).evaluate().length, lessThan(64));
    expect(find.text('/guide-255'), findsNothing);
    await tester.scrollUntilVisible(find.text('/guide-255'), 640, maxScrolls: 80,
        scrollable: find.byType(Scrollable).first);
    expect(find.text('/guide-255'), findsOneWidget);
  });

  testWidgets('an empty catalog can retry the same session without sending a message', (tester) async {
    usePhoneView(tester);
    final ids = <String>[];
    final harness = ChatHarness(extra: [skillsProvider.overrideWith((ref, id) async {
      ids.add(id);
      return ids.length == 1 ? const <SkillInfo>[] : const [SkillInfo(name: 'dsh-go-guide', description: 'Guide')];
    })]);
    await tester.pumpWidget(testApp(const SkillsPage(sessionId: 's1'), overrides: harness.overrides));
    await pumpFrames(tester);
    await tester.tap(find.text('重试'));
    await pumpFrames(tester);
    expect(ids, ['s1', 's1']);
    expect(find.text('/dsh-go-guide'), findsOneWidget);
    expect(harness.sessions.creates, 0);
  });
}
