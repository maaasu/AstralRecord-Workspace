package io.github.maaasu.astralRecord.feature.mob.service;

import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Map;

/** Mob 専用 executor の定義と Mob への割当を保持します。 */
public final class MobSkillCatalog {
    private static final Map<String, List<String>> SKILLS = Map.of(
        "midgard_grassboar", List.of(GrassboarTuskStrikeSkillExecutor.SKILL_ID),
        "twilight_colossus", List.of(
            TwilightColossusGateSlamSkillExecutor.SKILL_ID,
            TwilightColossusRuneBoltSkillExecutor.SKILL_ID
        )
    );

    private MobSkillCatalog() { }

    /** Mob 用の組み込みスキル定義を返します。 */
    public static @NotNull List<SkillDefinition> definitions() {
        return List.of(
            GrassboarTuskStrikeSkillExecutor.definition(),
            TwilightColossusGateSlamSkillExecutor.definition(),
            TwilightColossusRuneBoltSkillExecutor.definition()
        );
    }

    /** Mob template ID に割り当てられた専用スキルを返します。 */
    public static @NotNull List<String> skillIdsFor(@NotNull String mobId) {
        return SKILLS.getOrDefault(mobId, List.of());
    }
}
