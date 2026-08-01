package io.github.maaasu.astralRecord.feature.skill.model;

import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.Set;

/**
 * パッシブスキルのライフサイクル処理で使用するコンテキストです。
 *
 * @param player プレイヤー
 * @param skill スキル定義
 * @param activatedAt 活性化時刻
 * @param activeTicks 活性化後の経過 tick 数
 */
public record PassiveSkillContext(
    @NotNull AstPlayer player,
    @NotNull SkillDefinition skill,
    @NotNull Instant activatedAt,
    long activeTicks,
    @Nullable LearnedSkillInstance learnedSkill,
    @NotNull Set<String> effectiveSigilIds
) {
    public PassiveSkillContext(
        @NotNull AstPlayer player,
        @NotNull SkillDefinition skill,
        @NotNull Instant activatedAt,
        long activeTicks
    ) {
        this(player, skill, activatedAt, activeTicks, null, Set.of());
    }

    public PassiveSkillContext(
        @NotNull AstPlayer player,
        @NotNull SkillDefinition skill,
        @NotNull Instant activatedAt,
        long activeTicks,
        @Nullable LearnedSkillInstance learnedSkill
    ) {
        this(player, skill, activatedAt, activeTicks, learnedSkill,
            learnedSkill == null ? Set.of() : learnedSkill.getSigils().stream()
                .map(LearnedSkillSigil::getSigilId).collect(java.util.stream.Collectors.toSet()));
    }

    public PassiveSkillContext {
        effectiveSigilIds = effectiveSigilIds == null ? Set.of() : Set.copyOf(effectiveSigilIds);
    }

    public boolean hasSigil(@NotNull String sigilId) {
        return effectiveSigilIds.contains(sigilId);
    }
}
