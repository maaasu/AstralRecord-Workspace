package io.github.maaasu.astralRecord.feature.combat.model;

import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * ダメージ計算に必要な入力情報をまとめたコンテキストです。
 * <p>
 * Bukkit 由来のイベント情報と、攻撃種別／属性成分の判定結果を保持します。
 * Bukkit API への依存はこのモデルと {@code from(...)} ファクトリのみに閉じ込め、
 * {@link io.github.maaasu.astralRecord.feature.combat.service.DamageCalculator} は
 * 本コンテキストの数値情報のみを参照して計算します。
 *
 * @param attacker     攻撃者エンティティ。環境ダメージ等で攻撃者が無い場合は {@code null}
 * @param victim       被弾者エンティティ
 * @param baseDamage   固定ダメージ経路で使う基礎ダメージ。`ATTACKER_STATUS` では攻撃力との大きい方を使う
 * @param attackType   攻撃種別（近接 / 間接 / 魔法）
 * @param components   属性別ダメージ倍率。空の場合は無属性100%として扱う
 * @param source       通常攻撃・スキルなどの発生元
 * @param attackerDamageMultiplier 攻撃者固有のダメージ倍率。攻撃力解決後、防御・会心より前に適用する
 */
public record DamageContext(
        @Nullable AstEntity attacker,
        @NotNull AstEntity victim,
        double baseDamage,
        @NotNull AttackType attackType,
        @NotNull List<DamageComponent> components,
        @NotNull DamageScaling scaling,
        @NotNull DamageSource source,
        double attackerDamageMultiplier
) {

    public DamageContext(
            @Nullable AstEntity attacker,
            @NotNull AstEntity victim,
            double baseDamage,
            @NotNull AttackType attackType,
            @NotNull List<DamageComponent> components,
            @NotNull DamageScaling scaling,
            @NotNull DamageSource source
    ) {
        this(attacker, victim, baseDamage, attackType, components, scaling, source, 1.0D);
    }

    public DamageContext(
            @Nullable AstEntity attacker,
            @NotNull AstEntity victim,
            double baseDamage,
            @NotNull AttackType attackType,
            @NotNull List<DamageComponent> components,
            @NotNull DamageScaling scaling
    ) {
        this(attacker, victim, baseDamage, attackType, components, scaling, DamageSource.NORMAL_ATTACK, 1.0D);
    }

    public DamageContext(
            @Nullable AstEntity attacker,
            @NotNull AstEntity victim,
            double baseDamage,
            @NotNull AttackType attackType,
            @NotNull DamageScaling scaling
    ) {
        this(attacker, victim, baseDamage, attackType, List.of(DamageComponent.defaultComponent()), scaling, DamageSource.NORMAL_ATTACK, 1.0D);
    }

    public DamageContext {
        components = components == null || components.isEmpty()
                ? List.of(DamageComponent.defaultComponent())
                : List.copyOf(components);
        attackerDamageMultiplier = Double.isFinite(attackerDamageMultiplier) && attackerDamageMultiplier >= 0.0D
                ? attackerDamageMultiplier
                : 1.0D;
    }

    /**
     * {@link EntityDamageByEntityEvent} からコンテキストを構築します。
     * <p>
     * 現状は近接・無属性ダメージ既定で生成します。攻撃種別／属性成分の高度な判定は
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
                List.of(DamageComponent.defaultComponent()),
                DamageScaling.ATTACKER_STATUS
        );
    }
}
