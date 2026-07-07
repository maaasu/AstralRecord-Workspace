package io.github.maaasu.astralRecord.feature.combat.model;

import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * ダメージ計算に必要な入力情報をまとめたコンテキストです。
 * <p>
 * Bukkit 由来のイベント情報と、攻撃種別／ダメージ種別の判定結果を保持します。
 * Bukkit API への依存はこのモデルと {@code from(...)} ファクトリのみに閉じ込め、
 * {@link io.github.maaasu.astralRecord.feature.combat.service.DamageCalculator} は
 * 本コンテキストの数値情報のみを参照して計算します。
 *
 * @param attacker     攻撃者エンティティ。環境ダメージ等で攻撃者が無い場合は {@code null}
 * @param victim       被弾者エンティティ
 * @param baseDamage   Bukkit 側のベースダメージ（{@link EntityDamageByEntityEvent#getDamage()}）
 * @param attackType   攻撃種別（近接 / 間接 / 魔法）
 * @param damageType   ダメージ種別（物理 / 魔法 / 純粋）
 */
public record DamageContext(
        @Nullable AstEntity attacker,
        @NotNull AstEntity victim,
        double baseDamage,
        @NotNull AttackType attackType,
        @NotNull DamageType damageType,
        @NotNull DamageElement damageElement,
        @NotNull DamageScaling scaling
) {

    public DamageContext(
            @Nullable AstEntity attacker,
            @NotNull AstEntity victim,
            double baseDamage,
            @NotNull AttackType attackType,
            @NotNull DamageType damageType,
            @NotNull DamageScaling scaling
    ) {
        this(attacker, victim, baseDamage, attackType, damageType, DamageElement.NEUTRAL, scaling);
    }

    /**
     * {@link EntityDamageByEntityEvent} からコンテキストを構築します。
     * <p>
     * 現状は近接物理ダメージ既定で生成します。攻撃種別／ダメージ種別の高度な判定は
     * 将来、攻撃者の装備・スキル文脈をもとに拡張する想定です。
     *
     * @param event Bukkit のエンティティ間ダメージイベント
     * @return 構築したダメージコンテキスト
     */
    public static @NotNull DamageContext from(@NotNull EntityDamageByEntityEvent event) {
        return new DamageContext(
                AstEntity.bukkit(event.getDamager()),
                AstEntity.bukkit(event.getEntity()),
                event.getDamage(),
                AttackType.MELEE,
                DamageType.PHYSICAL,
                DamageElement.NEUTRAL,
                DamageScaling.ATTACKER_STATUS
        );
    }
}
