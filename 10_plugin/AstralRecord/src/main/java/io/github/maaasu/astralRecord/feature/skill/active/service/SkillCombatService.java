package io.github.maaasu.astralRecord.feature.skill.active.service;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.combat.model.AttackType;
import io.github.maaasu.astralRecord.feature.combat.model.DamageComponent;
import io.github.maaasu.astralRecord.feature.combat.model.DamageElement;
import io.github.maaasu.astralRecord.feature.combat.model.DamageResult;
import io.github.maaasu.astralRecord.feature.combat.model.DamageSource;
import io.github.maaasu.astralRecord.feature.combat.service.DamageService;
import io.github.maaasu.astralRecord.feature.condition.model.ConditionApplyReason;
import io.github.maaasu.astralRecord.feature.condition.model.ConditionApplyRequest;
import io.github.maaasu.astralRecord.feature.condition.model.ConditionType;
import io.github.maaasu.astralRecord.feature.condition.service.ConditionService;
import io.github.maaasu.astralRecord.feature.mob.model.MobState;
import io.github.maaasu.astralRecord.feature.mob.service.MobKnockbackService;
import io.github.maaasu.astralRecord.feature.mob.service.MobTauntService;
import io.github.maaasu.astralRecord.feature.player.AccountModeGuard;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.status.model.HealthRecoveryContext;
import io.github.maaasu.astralRecord.feature.status.model.StatusType;
import io.github.maaasu.astralRecord.feature.status.service.StatusService;
import io.github.maaasu.astralRecord.feature.skill.active.model.ActiveSkillCondition;
import org.bukkit.Location;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.List;

/**
 * 発動スキルから custom combat と状態異常へ接続する共通サービスです。
 */
public final class SkillCombatService {

    private final DamageService damageService;
    private final ConditionService conditionService;
    private final MobKnockbackService knockbackService;
    private final StatusService statusService;
    private final MobTauntService tauntService;

    /**
     * 戦闘サービスで初期化します。
     *
     * @param damageService custom combat ダメージサービス
     * @param conditionService 状態異常サービス
     * @param knockbackService ノックバック耐性を適用する共通サービス
     * @param statusService シールド回復と実増加量の解決に使用するステータスサービス
     */
    public SkillCombatService(
            @NotNull DamageService damageService,
            @NotNull ConditionService conditionService,
            @NotNull MobKnockbackService knockbackService,
            @NotNull StatusService statusService
    ) {
        this(damageService, conditionService, knockbackService, statusService, null);
    }

    /** 一時挑発サービスを含む戦闘サービスで初期化します。 */
    public SkillCombatService(
            @NotNull DamageService damageService,
            @NotNull ConditionService conditionService,
            @NotNull MobKnockbackService knockbackService,
            @NotNull StatusService statusService,
            MobTauntService tauntService
    ) {
        this.damageService = damageService;
        this.conditionService = conditionService;
        this.knockbackService = knockbackService;
        this.statusService = statusService;
        this.tauntService = tauntService;
    }

    /**
     * 単一属性のスキルダメージと、命中時状態異常を適用します。
     */
    public @NotNull DamageResult hit(
            @NotNull AstEntity attacker,
            @NotNull AstEntity target,
            @NotNull AttackType attackType,
            @NotNull DamageElement element,
            double ratio,
            @NotNull ActiveSkillCondition... conditions
    ) {
        return hit(attacker, target, attackType, List.of(new DamageComponent(element, ratio)), conditions);
    }

    /**
     * 複数属性成分のスキルダメージと、命中時状態異常を適用します。
     */
    public @NotNull DamageResult hit(
            @NotNull AstEntity attacker,
            @NotNull AstEntity target,
            @NotNull AttackType attackType,
            @NotNull List<DamageComponent> components,
            @NotNull ActiveSkillCondition... conditions
    ) {
        DamageResult result = damageService.attack(attacker, target, attackType, components, DamageSource.SKILL);
        if (!result.evaded() && (result.finalDamage() > 0.0D || result.shieldDamage() > 0.0D)) {
            Arrays.stream(conditions).forEach(condition -> applyCondition(attacker, target, attackType, condition));
        }
        return result;
    }

    /**
     * この一撃だけのシールドブレイク倍率を指定して単一属性スキルダメージを適用します。
     *
     * @param attacker 発動者
     * @param target 対象
     * @param attackType 攻撃種別
     * @param element 属性
     * @param ratio ダメージ倍率
     * @param shieldBreakMultiplier シールドダメージ倍率
     * @return 実際に適用したHP・シールドダメージ
     */
    public @NotNull DamageResult hit(
            @NotNull AstEntity attacker,
            @NotNull AstEntity target,
            @NotNull AttackType attackType,
            @NotNull DamageElement element,
            double ratio,
            double shieldBreakMultiplier
    ) {
        return damageService.attack(
                attacker,
                target,
                attackType,
                List.of(new DamageComponent(element, ratio)),
                DamageSource.SKILL,
                1.0D,
                shieldBreakMultiplier
        );
    }

    /**
     * 攻撃者のシールドブレイク値に対する比率を、この一撃のシールドダメージとして適用します。
     *
     * @param attacker 発動者
     * @param target 対象
     * @param attackType 攻撃種別
     * @param element 属性
     * @param ratio HPダメージ倍率
     * @param shieldBreakRatio 攻撃者のシールドブレイク値へ乗算する比率
     * @return 実際に適用したHP・シールドダメージ
     */
    public @NotNull DamageResult hitWithShieldBreakRatio(
            @NotNull AstEntity attacker,
            @NotNull AstEntity target,
            @NotNull AttackType attackType,
            @NotNull DamageElement element,
            double ratio,
            double shieldBreakRatio
    ) {
        return damageService.attackWithShieldBreakRatio(
                attacker,
                target,
                attackType,
                List.of(new DamageComponent(element, ratio)),
                DamageSource.SKILL,
                shieldBreakRatio
        );
    }

    /**
     * プレイヤーの現在シールドを既存回復規則で回復し、実際の増加量を返します。
     *
     * @param target 回復対象プレイヤー
     * @param amount 回復要求量
     * @return 上限・回復阻害を反映した実増加量
     */
    public double recoverShield(@NotNull AstEntity target, double amount) {
        if (!target.isPlayer() || target.player() == null || amount <= 0.0D) {
            return 0.0D;
        }
        double before = statusService.getStatus(target.player()).getCurrentShield();
        double after = statusService.recoverShield(target.player(), amount).getCurrentShield();
        return Math.max(0.0D, after - before);
    }

    /**
     * プレイヤーの現在エネルギーを既存の回復規則で回復します。
     *
     * @param target 回復対象プレイヤー
     * @param amount 回復要求量
     * @return 上限・回復阻害を反映した実増加量
     */
    public double recoverEnergy(@NotNull AstPlayer target, double amount) {
        if (amount <= 0.0D) {
            return 0.0D;
        }
        double before = statusService.getStatus(target).getCurrentEnergy();
        double after = statusService.recoverEnergy(target, amount).getCurrentEnergy();
        return Math.max(0.0D, after - before);
    }

    /**
     * プレイヤーの最大エネルギーに対する割合を現在エネルギーへ回復します。
     *
     * @param target 回復対象プレイヤー
     * @param maxRatio 最大エネルギーに対する回復割合（0.05 は5%）
     * @return 上限・回復阻害を反映した実増加量
     */
    public double recoverEnergyByMaxRatio(@NotNull AstPlayer target, double maxRatio) {
        if (!(maxRatio > 0.0D) || !Double.isFinite(maxRatio)) {
            return 0.0D;
        }
        double maxEnergy = statusService.getStatus(target).getMaxValue(StatusType.MAX_ENERGY);
        if (!(maxEnergy > 0.0D) || !Double.isFinite(maxEnergy)) {
            return 0.0D;
        }
        return recoverEnergy(target, maxEnergy * maxRatio);
    }

    /**
     * プレイヤーの現在HPを既存回復規則で回復し、実際の増加量を返します。
     *
     * @param target 回復対象プレイヤー
     * @param amount 回復要求量
     * @return 上限・回復阻害を反映した実増加量
     */
    public double recoverHp(@NotNull AstPlayer target, double amount) {
        return recoverHp(target, amount, HealthRecoveryContext.self("HP回復"));
    }

    /**
     * プレイヤーの現在HPを回復し、回復元を StatusService へ引き渡します。
     *
     * @param target 回復対象プレイヤー
     * @param amount 回復要求量
     * @param context 回復元と回復手段。{@code null} の場合は回復通知を抑止
     * @return 上限・回復阻害を反映した実増加量
     */
    public double recoverHp(
            @NotNull AstPlayer target,
            double amount,
            @Nullable HealthRecoveryContext context
    ) {
        if (amount <= 0.0D) {
            return 0.0D;
        }
        double before = statusService.getStatus(target).getCurrentHp();
        double after = statusService.recoverHp(target, amount, context).getCurrentHp();
        return Math.max(0.0D, after - before);
    }

    /**
     * 指定したバフをプレイヤーへ付与し、付与できたかを返します。
     * <p>
     * 有効なプレイヤーを対象とし、同じIDのバフは既存の {@code BuffService} 規則で置き換えられて
     * 有効期限が更新され、付与後に {@code StatusService} がステータスを再計算します。
     *
     * @param target バフ対象プレイヤー（有効なプレイヤー）
     * @param buffId 付与するバフ ID（バフマスターに定義済み）
     * @return バフが有効な状態で付与された場合 true
     */
    public boolean applyBuff(@NotNull AstPlayer target, @NotNull String buffId) {
        statusService.applyBuff(target, buffId);
        return statusService.getActiveBuffs(target).stream()
                .anyMatch(buff -> buff.getType().getId().equalsIgnoreCase(buffId));
    }

    /**
     * 対象に指定した有効な状態異常が付与されているかを返します。
     *
     * @param target 判定対象
     * @param type   判定する状態異常の種別
     * @return 指定した状態異常が有効なら true
     */
    public boolean hasCondition(@NotNull AstEntity target, @NotNull ConditionType type) {
        return conditionService.getActiveConditions(target).stream()
                .anyMatch(condition -> condition.type() == type);
    }

    /** 対象 Mob を発動者へ向け、脅威値を加算します。 */
    public void provoke(@NotNull AstEntity attacker, @NotNull AstEntity target, double threat) {
        if (!attacker.isPlayer() || attacker.player() == null || !AccountModeGuard.isGameplayPlayer(attacker.player())
                || target.mob() == null) {
            return;
        }
        double highestThreat = target.mob().threatTable().snapshot().values().stream()
                .mapToDouble(Double::doubleValue)
                .max()
                .orElse(0.0D);
        double currentThreat = target.mob().threatTable().snapshot()
                .getOrDefault(attacker.id(), 0.0D);
        double guaranteedLead = highestThreat + Math.max(0.0D, threat);
        target.mob().threatTable().add(attacker.id(), Math.max(0.0D, guaranteedLead - currentThreat));
        target.mob().targetId(attacker.id());
        if (target.mob().state() == MobState.IDLE) {
            target.mob().state(MobState.AGGRO);
        }
    }

    /** 脅威値を変更せず、対象 Mob の攻撃対象を発動者へ一時固定します。 */
    public void taunt(@NotNull AstEntity attacker, @NotNull AstEntity target, long durationTicks) {
        if (tauntService == null || !attacker.isPlayer() || attacker.player() == null
                || !AccountModeGuard.isGameplayPlayer(attacker.player()) || target.mob() == null) {
            return;
        }
        tauntService.apply(target.mob(), attacker.id(), durationTicks);
    }

    /** 対象 Mob を指定地点から押し出します。 */
    public void knockback(
            @NotNull AstEntity target,
            @NotNull Location source,
            double horizontalStrength,
            double verticalStrength
    ) {
        knockbackService.applyWithStrength(target, source, horizontalStrength, verticalStrength);
    }

    /** ノックバック耐性で減衰する任意方向の速度を対象へ加算します。 */
    public void velocity(@NotNull AstEntity target, @NotNull Vector velocity) {
        knockbackService.applyVelocityWithResistance(target, velocity);
    }

    /**
     * 対象Mobの移動速度へ一時的なフラット減少を適用します。
     * 減少値と持続時間の検証、および既存の減少との合成は {@link ConditionService} に委ねます。
     *
     * @param target 対象Mob
     * @param reduction 移動速度ステータスから減算する値
     * @param durationTicks 効果時間（tick）
     * @return 有効なMobへ適用できた場合は {@code true}
     */
    public boolean applyTemporaryMovementSpeedReduction(
            @NotNull AstEntity target,
            double reduction,
            long durationTicks
    ) {
        return conditionService.applyTemporaryMovementSpeedReduction(target, reduction, durationTicks);
    }

    private void applyCondition(
            @NotNull AstEntity attacker,
            @NotNull AstEntity target,
            @NotNull AttackType attackType,
            @NotNull ActiveSkillCondition condition
    ) {
        conditionService.applyCondition(new ConditionApplyRequest(
                target,
                attacker,
                condition.type(),
                attackType,
                condition.durationTicks(),
                condition.chance(),
                condition.strength(),
                null,
                null,
                null,
                null,
                ConditionApplyReason.SKILL
        ));
    }
}
