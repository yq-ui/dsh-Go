// Native mirror of the official skill catalog (`dsh-client-ui-skill`, the
// skill group of the "/" menu): skills the user can call with `/name` in a
// conversation, from `skills/list`. A skill's sheet puts `/name ` into this
// conversation's composer or a new one (skill_use.dart).

import 'package:dsh_client/dsh_client.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../l10n/l10n.dart';
import '../../state/connection_state.dart';
import '../../state/features_state.dart';
import '../common/app_sheet.dart';
import '../plugins/plugin_widgets.dart';
import '../common/list_page_row.dart';
import '../shell/page_frame.dart';
import '../theme/app_icons.dart';
import '../theme/theme.dart';
import 'feature_widgets.dart';
import 'skill_use.dart';

class SkillsPage extends ConsumerWidget {
  const SkillsPage({super.key, required this.sessionId});

  /// Project skills depend on the conversation's directory.
  final String sessionId;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = context.l10n;
    final connected = ref.watch(activeConnectionProvider).value != null;
    final skills = ref.watch(skillsProvider(sessionId));
    final list = skills.value;
    if (connected && list != null && list.isNotEmpty && !skills.hasError) {
      return AppPage(
        title: l10n.featuresSkills,
        subtitle: Text(l10n.featuresSkillsCallHint),
        slivers: [
          SliverPadding(
            padding: const EdgeInsets.symmetric(horizontal: 16),
            sliver: DecoratedSliver(
              decoration: BoxDecoration(
                color: Theme.of(context).colorScheme.surfaceContainer,
                borderRadius: AppShape.radiusLg,
              ),
              sliver: SliverPadding(
                padding: const EdgeInsets.symmetric(vertical: 4),
                sliver: SliverList.builder(
                  itemCount: list.length,
                  itemBuilder: (context, index) {
                    final skill = list[index];
                    return Material(
                      key: ValueKey(skill.name),
                      type: MaterialType.transparency,
                      child: _SkillRow(skill: skill, onTap: () => _show(context, skill)),
                    );
                  },
                ),
              ),
            ),
          ),
          SliverToBoxAdapter(child: FeatureNote(l10n.featuresSkillsSourceHint)),
        ],
      );
    }
    void retry() => ref.invalidate(skillsProvider(sessionId));
    return AppPage.list(
      title: l10n.featuresSkills,
      subtitle: Text(l10n.featuresSkillsCallHint),
      children: [
        if (!connected)
          const FeatureOffline()
        else if (skills.hasError)
          FeatureMessage.error(title: l10n.featuresSkillsReadFailed, error: skills.error!, onRetry: retry)
        else if (list == null || skills.isLoading)
          const FeatureLoading()
        else
          FeatureMessage(
            icon: AppIcons.skill,
            title: l10n.featuresSkillsEmpty,
            body: l10n.featuresSkillsEmptyHint,
            action: (l10n.commonRetry, retry),
          ),
      ],
    );
  }

  Future<void> _show(BuildContext context, SkillInfo skill) => showAppSheet<void>(
    context,
    builder: (sheet) {
      final theme = Theme.of(sheet);
      final colors = DshColors.of(sheet);
      return SafeArea(
        top: false,
        child: SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              SheetHeader('/${skill.name}'),
              Padding(
                padding: const EdgeInsets.fromLTRB(20, 0, 20, 8),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    if (skill.description.isNotEmpty)
                      Text(skill.description, style: theme.textTheme.bodyMedium?.copyWith(height: 1.6)),
                    if (skill.whenToUse case final usage? when usage.trim().isNotEmpty) ...[
                      const SizedBox(height: 10),
                      Text(
                        context.l10n.featuresSkillUsage(usage.trim()),
                        style: theme.textTheme.bodySmall?.copyWith(color: colors.textTertiary),
                      ),
                    ],
                    if (skill.path case final path?) ...[
                      const SizedBox(height: 10),
                      Text(path, style: AppType.monoLabel(color: colors.textTertiary)),
                    ],
                  ],
                ),
              ),
              Padding(
                padding: const EdgeInsets.fromLTRB(kSheetGutter, 16, kSheetGutter, 12),
                child: SkillUseActions(skill: skill, sessionId: sessionId),
              ),
            ],
          ),
        ),
      );
    },
  );
}

class _SkillRow extends StatelessWidget {
  const _SkillRow({required this.skill, required this.onTap});

  final SkillInfo skill;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final l10n = context.l10n;
    final theme = Theme.of(context);
    return ListPageRow(
      icon: AppIcons.skill,
      title: '/${skill.name}',
      titleStyle: AppType.monoLabel(color: theme.colorScheme.onSurface).copyWith(fontSize: 14, height: 1.45),
      badge: skill.modelInvocable ? null : PluginBadge(l10n.featuresSkillManual),
      subtitle: skill.description.isEmpty ? null : skill.description,
      onTap: onTap,
    );
  }
}
