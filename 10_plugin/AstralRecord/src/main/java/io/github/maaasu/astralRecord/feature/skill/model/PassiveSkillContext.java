package io.github.maaasu.astralRecord.feature.skill.model;

import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import org.jetbrains.annotations.NotNull;

import java.time.Instant;

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
    long activeTicks
) {
}
