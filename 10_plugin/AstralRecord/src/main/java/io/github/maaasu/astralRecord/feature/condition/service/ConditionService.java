package io.github.maaasu.astralRecord.feature.condition.service;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.combat.model.AttackType;
import io.github.maaasu.astralRecord.feature.combat.model.DamageElement;
import io.github.maaasu.astralRecord.feature.combat.model.DamageType;
import io.github.maaasu.astralRecord.feature.condition.display.ConditionDisplayService;
import io.github.maaasu.astralRecord.feature.condition.model.ActiveCondition;
import io.github.maaasu.astralRecord.feature.condition.model.ConditionApplyReason;
import io.github.maaasu.astralRecord.feature.condition.model.ConditionApplyRequest;
import io.github.maaasu.astralRecord.feature.condition.model.ConditionApplyResult;
import io.github.maaasu.astralRecord.feature.condition.model.ConditionCategory;
import io.github.maaasu.astralRecord.feature.condition.model.ConditionEffect;
import io.github.maaasu.astralRecord.feature.condition.model.ConditionRejectReason;
import io.github.maaasu.astralRecord.feature.condition.model.ConditionStackPolicy;
import io.github.maaasu.astralRecord.feature.condition.model.ConditionType;
import io.github.maaasu.astralRecord.feature.mob.model.MobCategory;
import io.github.maaasu.astralRecord.feature.mob.model.MobState;
import io.github.maaasu.astralRecord.feature.player.death.PlayerDeathService;
import io.github.maaasu.astralRecord.feature.status.model.StatusType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * プレイヤーと Mob の状態異常をメモリ上で管理します。
 */
public final class ConditionService {
    private static final long MS_PER_TICK = 50L;

    private final Map<UUID, Map<ConditionType, ActiveCondition>> activeByTarget = new ConcurrentHashMap<>();
    private final ConditionDisplayService displayService;
    private final PlayerDeathService playerDeathService;

    public ConditionService(
            @NotNull ConditionDisplayService displayService,
            @Nullable PlayerDeathService playerDeathService
    ) {
        this.displayService = displayService;
        this.playerDeathService = playerDeathService;
    }

    /**
     * 状態異常を付与または更新します。
     *
     * @param request 付与要求
     * @return 付与結果
     */
    public @NotNull ConditionApplyResult applyCondition(@NotNull ConditionApplyRequest request) {
        ConditionRejectReason rejection = rejectReason(request.target());
        if (rejection != ConditionRejectReason.NONE) {
            return ConditionApplyResult.rejected(rejection);
        }
        if (request.durationTicks() < 1L) {
            return ConditionApplyResult.rejected(ConditionRejectReason.INVALID_DURATION);
        }
        if (request.chance() < 100.0D && ThreadLocalRandom.current().nextDouble(0.0D, 100.0D) >= request.chance()) {
            return ConditionApplyResult.rejected(ConditionRejectReason.CHANCE_FAILED);
        }

        Resistance resistance = resistance(request.target(), request.type().category());
        long durationTicks = Math.round(request.durationTicks() * resistance.durationMultiplier());
        if (durationTicks < 1L) {
            return ConditionApplyResult.rejected(ConditionRejectReason.RESISTED);
        }

        long nowMs = System.currentTimeMillis();
        long expiresAtMs = nowMs + durationTicks * MS_PER_TICK;
        Map<ConditionType, ActiveCondition> targetConditions =
                activeByTarget.computeIfAbsent(request.target().id(), ignored -> new EnumMap<>(ConditionType.class));
        ActiveCondition existing = targetConditions.get(request.type());
        ActiveCondition next = existing == null
                ? createCondition(request, nowMs, expiresAtMs, resistance.effectMultiplier())
                : updateCondition(existing, request, expiresAtMs, resistance.effectMultiplier());
        targetConditions.put(request.type(), next);

        if (next.type() == ConditionType.CHILLED && next.stack() >= ConditionType.CHILLED.maxStack()) {
            removeCondition(request.target(), ConditionType.CHILLED);
            ConditionApplyRequest frozen = new ConditionApplyRequest(
                    request.target(),
                    request.source(),
                    ConditionType.FROZEN,
                    ConditionType.FROZEN.defaultDurationTicks(),
                    100.0D,
                    1,
                    null,
                    null,
                    null,
                    DamageType.MAGIC,
                    DamageElement.ICE,
                    request.reason()
            );
            return applyCondition(frozen);
        }

        displayService.showApplied(next, getActiveConditions(request.target()));
        return existing == null ? ConditionApplyResult.applied(next) : ConditionApplyResult.updated(next);
    }

    /**
     * 対象から指定状態異常を解除します。
     *
     * @param target 対象 entity
     * @param type 解除する状態異常種別
     * @return 解除件数
     */
    public int removeCondition(@NotNull AstEntity target, @NotNull ConditionType type) {
        Map<ConditionType, ActiveCondition> conditions = activeByTarget.get(target.id());
        if (conditions == null) {
            return 0;
        }
        ActiveCondition removed = conditions.remove(type);
        if (conditions.isEmpty()) {
            activeByTarget.remove(target.id());
        }
        if (removed != null) {
            displayService.clearCondition(target, type, getActiveConditions(target));
            return 1;
        }
        return 0;
    }

    /**
     * 対象の全状態異常を解除します。
     *
     * @param target 対象 entity
     * @return 解除件数
     */
    public int clearAll(@NotNull AstEntity target) {
        Map<ConditionType, ActiveCondition> removed = activeByTarget.remove(target.id());
        if (removed == null || removed.isEmpty()) {
            displayService.clearAll(target);
            return 0;
        }
        displayService.clearAll(target);
        return removed.size();
    }

    /**
     * 対象の有効な状態異常一覧を返します。
     *
     * @param target 対象 entity
     * @return 有効な状態異常一覧
     */
    public @NotNull List<ActiveCondition> getActiveConditions(@NotNull AstEntity target) {
        purgeExpired(target, System.currentTimeMillis());
        Map<ConditionType, ActiveCondition> conditions = activeByTarget.get(target.id());
        if (conditions == null || conditions.isEmpty()) {
            return List.of();
        }
        return List.copyOf(conditions.values());
    }

    /**
     * 全 active condition のスナップショットを返します。
     *
     * @return active condition snapshot
     */
    public @NotNull List<ActiveCondition> snapshotAllActiveConditions() {
        long nowMs = System.currentTimeMillis();
        List<ActiveCondition> snapshot = new ArrayList<>();
        for (Map<ConditionType, ActiveCondition> conditions : activeByTarget.values()) {
            for (ActiveCondition condition : conditions.values()) {
                if (!condition.expired(nowMs)) {
                    snapshot.add(condition);
                }
            }
        }
        return snapshot;
    }

    /**
     * 表示更新対象を返します。
     *
     * @return active condition を持つ対象一覧
     */
    public @NotNull Set<AstEntity> snapshotVisibleTargets() {
        Set<AstEntity> targets = new HashSet<>();
        for (ActiveCondition condition : snapshotAllActiveConditions()) {
            targets.add(condition.target());
        }
        return targets;
    }

    /**
     * 移動可能かを返します。
     *
     * @param target 対象 entity
     * @return 移動可能なら true
     */
    public boolean canMove(@NotNull AstEntity target) {
        return getActiveConditions(target).stream().noneMatch(condition -> effect(condition).movementBlocked());
    }

    /**
     * 通常攻撃可能かを返します。
     *
     * @param target 対象 entity
     * @return 通常攻撃可能なら true
     */
    public boolean canAttack(@NotNull AstEntity target) {
        return getActiveConditions(target).stream().noneMatch(condition -> effect(condition).attackBlocked());
    }

    /**
     * スキル使用可能かを返します。
     *
     * @param target 対象 entity
     * @return スキル使用可能なら true
     */
    public boolean canCastSkill(@NotNull AstEntity target) {
        return getActiveConditions(target).stream().noneMatch(condition -> effect(condition).skillBlocked());
    }

    /**
     * Mob AI が実行可能かを返します。
     *
     * @param target 対象 entity
     * @return AI 実行可能なら true
     */
    public boolean canRunAi(@NotNull AstEntity target) {
        return getActiveConditions(target).stream().noneMatch(condition -> effect(condition).aiBlocked());
    }

    /**
     * ダメージ無効状態かを返します。
     *
     * @param target 対象 entity
     * @return 無効なら true
     */
    public boolean isDamageImmune(@NotNull AstEntity target) {
        return getActiveConditions(target).stream().anyMatch(condition -> effect(condition).damageImmune());
    }

    /**
     * 被ダメージ倍率を返します。
     *
     * @param target 対象 entity
     * @return 0.1 から 5.0 の被ダメージ倍率
     */
    public double damageTakenMultiplier(@NotNull AstEntity target) {
        double multiplier = 1.0D;
        for (ActiveCondition condition : getActiveConditions(target)) {
            ConditionEffect effect = effect(condition);
            if (condition.type() == ConditionType.VULNERABLE) {
                multiplier *= Math.pow(effect.damageTakenMultiplier(), condition.stack());
            } else {
                multiplier *= effect.damageTakenMultiplier();
            }
        }
        return Math.max(0.1D, Math.min(5.0D, multiplier));
    }

    /**
     * 受ける回復量倍率を返します。
     *
     * @param target 対象 entity
     * @return 0.5 から 2.0 の回復倍率
     */
    public double healingReceivedMultiplier(@NotNull AstEntity target) {
        double multiplier = 1.0D;
        for (ActiveCondition condition : getActiveConditions(target)) {
            ConditionEffect effect = effect(condition);
            if (condition.type() == ConditionType.POISON) {
                multiplier *= Math.pow(effect.healingReceivedMultiplier(), condition.stack());
            } else {
                multiplier *= effect.healingReceivedMultiplier();
            }
        }
        return Math.max(0.5D, Math.min(2.0D, multiplier));
    }

    /**
     * tick 後の状態を表示します。
     *
     * @param condition tick 処理済み状態異常
     */
    public void pulse(@NotNull ActiveCondition condition) {
        displayService.pulse(condition);
    }

    /**
     * 全 runtime 状態を破棄します。
     */
    public void clearAllRuntimeState() {
        for (Map<ConditionType, ActiveCondition> conditions : activeByTarget.values()) {
            for (ActiveCondition condition : conditions.values()) {
                displayService.clearAll(condition.target());
            }
        }
        activeByTarget.clear();
    }

    private @NotNull ActiveCondition createCondition(
            @NotNull ConditionApplyRequest request,
            long nowMs,
            long expiresAtMs,
            double effectMultiplier
    ) {
        ConditionEffect effect = request.type().defaultEffect();
        int interval = request.tickIntervalTicks() == null ? effect.tickIntervalTicks() : Math.max(0, request.tickIntervalTicks());
        return new ActiveCondition(
                UUID.randomUUID(),
                request.type(),
                request.target(),
                request.source(),
                nowMs,
                expiresAtMs,
                interval <= 0 ? Long.MAX_VALUE : nowMs + interval * MS_PER_TICK,
                Math.min(request.stack(), request.type().maxStack()),
                snapshotPower(request, effect) * effectMultiplier,
                interval,
                request.damageType() == null ? effect.damageType() : request.damageType(),
                request.damageElement() == null ? effect.damageElement() : request.damageElement()
        );
    }

    private @NotNull ActiveCondition updateCondition(
            @NotNull ActiveCondition existing,
            @NotNull ConditionApplyRequest request,
            long expiresAtMs,
            double effectMultiplier
    ) {
        ConditionStackPolicy policy = request.type().stackPolicy();
        double requestedPower = snapshotPower(request, request.type().defaultEffect()) * effectMultiplier;
        if (policy == ConditionStackPolicy.IGNORE_IF_ACTIVE) {
            return existing;
        }
        if (policy == ConditionStackPolicy.REPLACE_IF_STRONGER && requestedPower <= existing.snapshotPower()) {
            return existing;
        }
        if (policy == ConditionStackPolicy.STACK_POWER_REFRESH_DURATION) {
            existing.stack(Math.min(request.type().maxStack(), existing.stack() + request.stack()));
            existing.snapshotPower(Math.max(existing.snapshotPower(), requestedPower));
        } else {
            existing.snapshotPower(requestedPower);
        }
        existing.expiresAtMs(Math.max(existing.expiresAtMs(), expiresAtMs));
        if (request.tickIntervalTicks() != null) {
            existing.tickIntervalTicks(Math.max(0, request.tickIntervalTicks()));
        }
        if (request.damageType() != null) {
            existing.damageType(request.damageType());
        }
        if (request.damageElement() != null) {
            existing.damageElement(request.damageElement());
        }
        return existing;
    }

    private double snapshotPower(@NotNull ConditionApplyRequest request, @NotNull ConditionEffect effect) {
        double base = request.basePower() == null ? effect.basePower() : request.basePower();
        double coefficient = request.powerCoefficient() == null ? effect.sourceAttackCoefficient() : request.powerCoefficient();
        double sourceAttack = request.source() == null ? 0.0D : request.source().statValue(StatusType.ATTACK);
        double targetMaxHealth = Math.max(0.0D, request.target().maxHealth());
        return Math.max(0.0D, base
                + sourceAttack * coefficient
                + sourceTypedAttack(request.source()) * effect.sourceTypedAttackCoefficient()
                + targetMaxHealth * effect.targetMaxHealthCoefficient());
    }

    private double sourceTypedAttack(@Nullable AstEntity source) {
        if (source == null) {
            return 0.0D;
        }
        return Math.max(
                source.statValue(StatusType.MELEE_ATTACK),
                Math.max(source.statValue(StatusType.RANGED_ATTACK), source.statValue(StatusType.MAGIC_ATTACK))
        );
    }

    private @NotNull ConditionEffect effect(@NotNull ActiveCondition condition) {
        return condition.type().defaultEffect();
    }

    private void purgeExpired(@NotNull AstEntity target, long nowMs) {
        Map<ConditionType, ActiveCondition> conditions = activeByTarget.get(target.id());
        if (conditions == null) {
            return;
        }
        List<ConditionType> expired = conditions.values().stream()
                .filter(condition -> condition.expired(nowMs))
                .map(ActiveCondition::type)
                .toList();
        for (ConditionType type : expired) {
            removeCondition(target, type);
        }
    }

    private @NotNull ConditionRejectReason rejectReason(@NotNull AstEntity target) {
        if (!target.isManaged()) {
            return ConditionRejectReason.UNMANAGED_TARGET;
        }
        if (target.isPlayer()) {
            if (playerDeathService != null && playerDeathService.isDead(target.id())) {
                return ConditionRejectReason.DEAD_TARGET;
            }
            return ConditionRejectReason.NONE;
        }
        if (target.isMob() && target.mob() != null) {
            if (target.mob().template().category() == MobCategory.NPC) {
                return ConditionRejectReason.NPC_TARGET;
            }
            if (target.mob().state() == MobState.DEAD) {
                return ConditionRejectReason.DEAD_TARGET;
            }
        }
        return ConditionRejectReason.NONE;
    }

    private @NotNull Resistance resistance(@NotNull AstEntity target, @NotNull ConditionCategory category) {
        if (!target.isMob() || target.mob() == null) {
            return Resistance.NONE;
        }
        MobCategory mobCategory = target.mob().template().category();
        if (mobCategory == MobCategory.NPC) {
            return Resistance.IMMUNE;
        }
        if (mobCategory == MobCategory.BOSS) {
            if (category == ConditionCategory.CONTROL) {
                return new Resistance(0.25D, 0.25D);
            }
            if (category == ConditionCategory.AMPLIFIER) {
                return new Resistance(1.0D, 0.5D);
            }
        }
        return Resistance.NONE;
    }

    private record Resistance(double durationMultiplier, double effectMultiplier) {
        private static final Resistance NONE = new Resistance(1.0D, 1.0D);
        private static final Resistance IMMUNE = new Resistance(0.0D, 0.0D);
    }
}
