package io.github.maaasu.astralRecord.feature.skill.active.service;

import org.jetbrains.annotations.NotNull;

/**
 * 個別発動スキルへ渡す共有サービス群です。
 *
 * @param targeting 当たり判定サービス
 * @param combat ダメージ・状態異常サービス
 * @param effects 演出サービス
 * @param projectiles projectile サービス
 * @param movement 移動サービス
 * @param temporaryEffects 一時戦闘効果サービス
 * @param tasks 遅延・反復タスクサービス
 * @param prisms エレメンタルプリズムの設置と吸収判定
 * @param circles 発動者が維持する魔法陣の登録状態
 */
public record ActiveSkillServices(
        @NotNull SkillTargetingService targeting,
        @NotNull SkillCombatService combat,
        @NotNull SkillEffectService effects,
        @NotNull SkillProjectileService projectiles,
        @NotNull SkillMovementService movement,
        @NotNull TemporarySkillEffectService temporaryEffects,
        @NotNull SkillTaskService tasks,
        @NotNull ElementalPrismRuntimeService prisms,
        @NotNull SkillMagicCircleRegistry circles
) {
}
