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
 * @param superStarCriticalMode 超星会心倍率の適用方法。ROLLは設定率を100%へ加算し、FORCEは設定率だけを乗算する
 * @param superStarCriticalRateOverride 一撃だけ使用する超星会心率（null の場合は攻撃者の status を使用）
 */
public record DamageContext(
        @Nullable AstEntity attacker,
        @NotNull AstEntity victim,
        double baseDamage,
        @NotNull AttackType attackType,
        @NotNull List<DamageComponent> components,
        @NotNull DamageScaling scaling,
        @NotNull DamageSource source,
        double attackerDamageMultiplier,
        @NotNull SuperStarCriticalMode superStarCriticalMode,
        @Nullable Double superStarCriticalRateOverride
) {

    /**
     * 発生率判定を行う通常の超星会心モードでコンテキストを作成します。
     *
     * @param attacker 攻撃者。環境ダメージでは {@code null}
     * @param victim 被弾者
     * @param baseDamage 外部から渡された基礎ダメージ
     * @param attackType 攻撃種別
     * @param components 属性別ダメージ倍率
     * @param scaling 基礎ダメージの解決方法
     * @param source ダメージの発生元
     */
    public DamageContext(
            @Nullable AstEntity attacker,
            @NotNull AstEntity victim,
            double baseDamage,
            @NotNull AttackType attackType,
            @NotNull List<DamageComponent> components,
            @NotNull DamageScaling scaling,
            @NotNull DamageSource source
    ) {
        this(attacker, victim, baseDamage, attackType, components, scaling, source,
                1.0D, SuperStarCriticalMode.ROLL, null);
    }

    public DamageContext(
            @Nullable AstEntity attacker,
            @NotNull AstEntity victim,
            double baseDamage,
            @NotNull AttackType attackType,
            @NotNull List<DamageComponent> components,
            @NotNull DamageScaling scaling,
            @NotNull DamageSource source,
            double attackerDamageMultiplier
    ) {
        this(attacker, victim, baseDamage, attackType, components, scaling, source,
                attackerDamageMultiplier, SuperStarCriticalMode.ROLL, null);
    }

    public DamageContext(
            @Nullable AstEntity attacker,
            @NotNull AstEntity victim,
            double baseDamage,
            @NotNull AttackType attackType,
            @NotNull List<DamageComponent> components,
            @NotNull DamageScaling scaling,
            @NotNull DamageSource source,
            @NotNull SuperStarCriticalMode superStarCriticalMode
    ) {
        this(attacker, victim, baseDamage, attackType, components, scaling, source,
                1.0D, superStarCriticalMode, null);
    }

    /**
     * 攻撃者固有倍率と超星会心モードを指定してコンテキストを作成します。
     * 一撃限定の超星会心率は指定せず、攻撃者の status を使用します。
     *
     * @param attacker 攻撃者。環境ダメージでは {@code null}
     * @param victim 被弾者
     * @param baseDamage 外部から渡された基礎ダメージ
     * @param attackType 攻撃種別
     * @param components 属性別ダメージ倍率
     * @param scaling 基礎ダメージの解決方法
     * @param source ダメージの発生元
     * @param attackerDamageMultiplier 攻撃者固有ダメージ倍率
     * @param superStarCriticalMode 超星会心倍率の適用方法
     */
    public DamageContext(
            @Nullable AstEntity attacker,
            @NotNull AstEntity victim,
            double baseDamage,
            @NotNull AttackType attackType,
            @NotNull List<DamageComponent> components,
            @NotNull DamageScaling scaling,
            @NotNull DamageSource source,
            double attackerDamageMultiplier,
            @NotNull SuperStarCriticalMode superStarCriticalMode
    ) {
        this(attacker, victim, baseDamage, attackType, components, scaling, source,
                attackerDamageMultiplier, superStarCriticalMode, null);
    }

    /**
     * 一撃限定の超星会心率を指定してコンテキストを作成します。
     *
     * @param attacker 攻撃者。環境ダメージでは {@code null}
     * @param victim 被弾者
     * @param baseDamage 外部から渡された基礎ダメージ
     * @param attackType 攻撃種別
     * @param components 属性別ダメージ倍率
     * @param scaling 基礎ダメージの解決方法
     * @param source ダメージの発生元
     * @param superStarCriticalMode 超星会心倍率の適用方法
     * @param superStarCriticalRateOverride 一撃だけ使用する超星会心率。null なら攻撃者の status を使用
     */
    public DamageContext(
            @Nullable AstEntity attacker,
            @NotNull AstEntity victim,
            double baseDamage,
            @NotNull AttackType attackType,
            @NotNull List<DamageComponent> components,
            @NotNull DamageScaling scaling,
            @NotNull DamageSource source,
            @NotNull SuperStarCriticalMode superStarCriticalMode,
            @Nullable Double superStarCriticalRateOverride
    ) {
        this(attacker, victim, baseDamage, attackType, components, scaling, source,
                1.0D, superStarCriticalMode, superStarCriticalRateOverride);
    }

    public DamageContext(
            @Nullable AstEntity attacker,
            @NotNull AstEntity victim,
            double baseDamage,
            @NotNull AttackType attackType,
            @NotNull List<DamageComponent> components,
            @NotNull DamageScaling scaling
    ) {
        this(attacker, victim, baseDamage, attackType, components, scaling,
                DamageSource.NORMAL_ATTACK, 1.0D, SuperStarCriticalMode.ROLL, null);
    }

    public DamageContext(
            @Nullable AstEntity attacker,
            @NotNull AstEntity victim,
            double baseDamage,
            @NotNull AttackType attackType,
            @NotNull DamageScaling scaling
    ) {
        this(attacker, victim, baseDamage, attackType, List.of(DamageComponent.defaultComponent()), scaling,
                DamageSource.NORMAL_ATTACK, 1.0D, SuperStarCriticalMode.ROLL, null);
    }

    public DamageContext {
        components = components == null || components.isEmpty()
                ? List.of(DamageComponent.defaultComponent())
                : List.copyOf(components);
        attackerDamageMultiplier = Double.isFinite(attackerDamageMultiplier) && attackerDamageMultiplier >= 0.0D
                ? attackerDamageMultiplier
                : 1.0D;
        superStarCriticalMode = superStarCriticalMode == null
                ? SuperStarCriticalMode.ROLL
                : superStarCriticalMode;
        superStarCriticalRateOverride = superStarCriticalRateOverride == null
                ? null
                : Double.isFinite(superStarCriticalRateOverride)
                        ? Math.max(0.0D, superStarCriticalRateOverride)
                        : null;
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
