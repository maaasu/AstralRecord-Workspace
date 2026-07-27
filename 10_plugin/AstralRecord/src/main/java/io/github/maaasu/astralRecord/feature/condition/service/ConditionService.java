package io.github.maaasu.astralRecord.feature.condition.service;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.condition.display.ConditionDisplayService;
import io.github.maaasu.astralRecord.feature.condition.model.ActiveCondition;
import io.github.maaasu.astralRecord.feature.condition.model.ConditionApplyRequest;
import io.github.maaasu.astralRecord.feature.condition.model.ConditionApplyResult;
import io.github.maaasu.astralRecord.feature.condition.model.ConditionCategory;
import io.github.maaasu.astralRecord.feature.condition.model.ConditionEffect;
import io.github.maaasu.astralRecord.feature.condition.model.ConditionRejectReason;
import io.github.maaasu.astralRecord.feature.condition.model.ConditionType;
import io.github.maaasu.astralRecord.feature.mob.model.MobCategory;
import io.github.maaasu.astralRecord.feature.mob.model.MobState;
import io.github.maaasu.astralRecord.feature.player.death.PlayerDeathService;
import io.github.maaasu.astralRecord.feature.status.model.StatusType;
import io.github.maaasu.astralRecord.feature.status.service.StatusService;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/** プレイヤーと Mob の状態異常をメモリ上で管理します。 */
public final class ConditionService {
    private static final long MS_PER_TICK = 50L;

    private final Map<UUID, Map<ConditionType, ActiveCondition>> activeByTarget = new ConcurrentHashMap<>();
    private final ConditionDisplayService displayService;
    private final PlayerDeathService playerDeathService;
    private StatusService statusService;

    public ConditionService(
            @NotNull ConditionDisplayService displayService,
            @Nullable PlayerDeathService playerDeathService
    ) {
        this.displayService = displayService;
        this.playerDeathService = playerDeathService;
    }

    /** 状態異常による移動速度補正を StatusService に反映できるよう関連付けます。 */
    public void setStatusService(@Nullable StatusService statusService) {
        this.statusService = statusService;
    }

    /**
     * 状態異常を付与または更新します。
     * 同種が有効な場合は強い効果を保持し、終了時刻は現在値より後ろにだけ延長します。
     */
    public @NotNull ConditionApplyResult applyCondition(@NotNull ConditionApplyRequest request) {
        ConditionRejectReason rejection = rejectReason(request.target());
        if (rejection != ConditionRejectReason.NONE) {
            return ConditionApplyResult.rejected(rejection);
        }

        double chance = effectiveApplyChance(request);
        if (chance < 100.0D && ThreadLocalRandom.current().nextDouble(0.0D, 100.0D) >= chance) {
            return ConditionApplyResult.rejected(ConditionRejectReason.CHANCE_FAILED);
        }

        long durationTicks = adjustedDurationTicks(request.target(), request.type(), request.durationTicks());
        if (durationTicks < 1L) {
            return ConditionApplyResult.rejected(ConditionRejectReason.RESISTED);
        }

        long nowMs = System.currentTimeMillis();
        long expiresAtMs = nowMs + durationTicks * MS_PER_TICK;
        Map<ConditionType, ActiveCondition> targetConditions =
                activeByTarget.computeIfAbsent(request.target().id(), ignored -> new EnumMap<>(ConditionType.class));
        ActiveCondition existing = targetConditions.get(request.type());
        ActiveCondition next = existing == null
                ? createCondition(request, nowMs, expiresAtMs)
                : updateCondition(existing, request, expiresAtMs);
        targetConditions.put(request.type(), next);

        refreshConditionDependentStatus(request.target());
        displayService.showApplied(next, getActiveConditions(request.target()));
        return existing == null ? ConditionApplyResult.applied(next) : ConditionApplyResult.updated(next);
    }

    /** 対象から指定状態異常を解除します。 */
    public int removeCondition(@NotNull AstEntity target, @NotNull ConditionType type) {
        Map<ConditionType, ActiveCondition> conditions = activeByTarget.get(target.id());
        if (conditions == null) {
            return 0;
        }
        ActiveCondition removed = conditions.remove(type);
        if (conditions.isEmpty()) {
            activeByTarget.remove(target.id());
        }
        if (removed == null) {
            return 0;
        }

        displayService.clearCondition(target, type, getActiveConditions(target));
        refreshConditionDependentStatus(target);
        return 1;
    }

    /** 対象の全状態異常を解除します。 */
    public int clearAll(@NotNull AstEntity target) {
        Map<ConditionType, ActiveCondition> removed = activeByTarget.remove(target.id());
        displayService.clearAll(target);
        refreshConditionDependentStatus(target);
        return removed == null ? 0 : removed.size();
    }

    /** 対象の有効な状態異常一覧を返します。 */
    public @NotNull List<ActiveCondition> getActiveConditions(@NotNull AstEntity target) {
        purgeExpired(target, System.currentTimeMillis());
        Map<ConditionType, ActiveCondition> conditions = activeByTarget.get(target.id());
        return conditions == null || conditions.isEmpty() ? List.of() : List.copyOf(conditions.values());
    }

    /** 全 active condition のスナップショットを返します。 */
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
     * 全対象から期限切れ状態異常を解除します。
     *
     * @return 解除した状態異常数
     */
    public int purgeExpiredConditions() {
        return purgeExpiredConditions(System.currentTimeMillis());
    }

    int purgeExpiredConditions(long nowMs) {
        List<ActiveCondition> expired = new ArrayList<>();
        for (Map<ConditionType, ActiveCondition> conditions : activeByTarget.values()) {
            for (ActiveCondition condition : conditions.values()) {
                if (condition.expired(nowMs)) {
                    expired.add(condition);
                }
            }
        }
        for (ActiveCondition condition : expired) {
            removeCondition(condition.target(), condition.type());
        }
        return expired.size();
    }

    /** 表示更新対象を返します。 */
    public @NotNull Set<AstEntity> snapshotVisibleTargets() {
        Set<AstEntity> targets = new HashSet<>();
        for (ActiveCondition condition : snapshotAllActiveConditions()) {
            targets.add(condition.target());
        }
        return targets;
    }

    /** 移動可能かを返します。凍結と感電の間欠拘束を含みます。 */
    public boolean canMove(@NotNull AstEntity target) {
        long nowMs = System.currentTimeMillis();
        return getActiveConditions(target).stream().noneMatch(condition ->
                effect(condition).movementBlocked() || condition.intermittentControlActive(nowMs));
    }

    /** 通常攻撃可能かを返します。 */
    public boolean canAttack(@NotNull AstEntity target) {
        return getActiveConditions(target).stream().noneMatch(condition -> effect(condition).attackBlocked());
    }

    /** スキル使用可能かを返します。 */
    public boolean canCastSkill(@NotNull AstEntity target) {
        return getActiveConditions(target).stream().noneMatch(condition -> effect(condition).skillBlocked());
    }

    /** プレイヤーが通常の操作・インタラクションを実行可能かを返します。 */
    public boolean canInteract(@NotNull AstEntity target) {
        return getActiveConditions(target).stream().noneMatch(condition ->
                effect(condition).movementBlocked()
                        && effect(condition).attackBlocked()
                        && effect(condition).skillBlocked());
    }

    /** Mob AI が実行可能かを返します。 */
    public boolean canRunAi(@NotNull AstEntity target) {
        return getActiveConditions(target).stream().noneMatch(condition -> effect(condition).aiBlocked());
    }

    /** 互換 API。新仕様には無敵状態がないため常に false です。 */
    public boolean isDamageImmune(@NotNull AstEntity target) {
        return false;
    }

    /** 互換 API。被ダメージ増加状態は新仕様にないため常に等倍です。 */
    public double damageTakenMultiplier(@NotNull AstEntity target) {
        return 1.0D;
    }

    /** 冷気・凍結・感電を合成した移動速度倍率を返します。 */
    public double movementSpeedMultiplier(@NotNull AstEntity target) {
        if (!canMove(target)) {
            return 0.0D;
        }
        double multiplier = 1.0D;
        for (ActiveCondition condition : getActiveConditions(target)) {
            multiplier = Math.min(multiplier, effect(condition).movementSpeedMultiplier());
        }
        return Math.max(0.0D, multiplier);
    }

    /** 冷気などを合成した詠唱時間倍率を返します。 */
    public double castTimeMultiplier(@NotNull AstEntity target) {
        double multiplier = 1.0D;
        for (ActiveCondition condition : getActiveConditions(target)) {
            multiplier = Math.max(multiplier, effect(condition).castTimeMultiplier());
        }
        return Math.max(1.0D, multiplier);
    }

    /** 衰弱を含む、攻撃元が与える最終ダメージ倍率を返します。 */
    public double damageDealtMultiplier(@Nullable AstEntity source) {
        if (source == null) {
            return 1.0D;
        }
        double multiplier = 1.0D;
        for (ActiveCondition condition : getActiveConditions(source)) {
            multiplier *= effect(condition).damageDealtMultiplier();
        }
        return Math.max(0.0D, multiplier);
    }

    /** 回復阻害中かを返します。 */
    public boolean isHealingBlocked(@NotNull AstEntity target) {
        return getActiveConditions(target).stream().anyMatch(condition -> effect(condition).healingBlocked());
    }

    /** 互換 API。回復阻害中は 0、それ以外は 1 を返します。 */
    public double healingReceivedMultiplier(@NotNull AstEntity target) {
        return isHealingBlocked(target) ? 0.0D : 1.0D;
    }

    /**
     * 状態異常 DoT の増加・耐性・貫通を合成します。
     * 貫通は DoT 耐性だけを相殺し、付与耐性には影響しません。
     */
    public double conditionDamageMultiplier(
            @Nullable AstEntity source,
            @NotNull AstEntity target,
            @NotNull ConditionType type
    ) {
        StatusType increaseStatus = type.damageIncreaseStatus();
        StatusType resistanceStatus = type.damageResistanceStatus();
        StatusType penetrationStatus = type.damagePenetrationStatus();
        if (increaseStatus == null || resistanceStatus == null || penetrationStatus == null) {
            return 1.0D;
        }

        double increase = source == null ? 0.0D : source.statValue(increaseStatus);
        double penetration = source == null ? 0.0D : source.statValue(penetrationStatus);
        double resistance = target.statValue(resistanceStatus);
        double effectiveResistance = Math.max(0.0D, resistance - penetration);
        return Math.max(0.0D, 1.0D + increase / 100.0D)
                * Math.max(0.0D, 1.0D - effectiveResistance / 100.0D);
    }

    /** tick 後の状態を表示します。 */
    public void pulse(@NotNull ActiveCondition condition) {
        displayService.pulse(condition);
    }

    /** 全 runtime 状態を破棄します。 */
    public void clearAllRuntimeState() {
        Set<AstEntity> targets = new HashSet<>();
        for (Map<ConditionType, ActiveCondition> conditions : activeByTarget.values()) {
            for (ActiveCondition condition : conditions.values()) {
                targets.add(condition.target());
                displayService.clearAll(condition.target());
            }
        }
        activeByTarget.clear();
        targets.forEach(this::refreshConditionDependentStatus);
    }

    private @NotNull ActiveCondition createCondition(
            @NotNull ConditionApplyRequest request,
            long nowMs,
            long expiresAtMs
    ) {
        ConditionEffect effect = request.type().defaultEffect();
        int interval = request.tickIntervalTicks() == null
                ? effect.tickIntervalTicks()
                : Math.max(0, request.tickIntervalTicks());
        double healthRate = request.healthRate() == null
                ? effect.healthRate()
                : Math.max(0.0D, request.healthRate());
        long nextControlAtMs = nextControlAtMs(effect, nowMs);
        return new ActiveCondition(
                UUID.randomUUID(),
                request.type(),
                request.target(),
                request.source(),
                nowMs,
                expiresAtMs,
                interval <= 0 ? Long.MAX_VALUE : nowMs + interval * MS_PER_TICK,
                nextControlAtMs,
                request.strength(),
                snapshotPower(request, effect),
                healthRate,
                interval
        );
    }

    private @NotNull ActiveCondition updateCondition(
            @NotNull ActiveCondition existing,
            @NotNull ConditionApplyRequest request,
            long expiresAtMs
    ) {
        ConditionEffect effect = request.type().defaultEffect();
        double requestedPower = snapshotPower(request, effect);
        double requestedHealthRate = request.healthRate() == null
                ? effect.healthRate()
                : Math.max(0.0D, request.healthRate());
        int requestedInterval = request.tickIntervalTicks() == null
                ? effect.tickIntervalTicks()
                : Math.max(0, request.tickIntervalTicks());
        boolean stronger = request.strength() > existing.strength()
                || (request.strength() == existing.strength()
                && conditionMagnitude(
                        request.type(), request.target(), requestedPower, requestedHealthRate, requestedInterval)
                > conditionMagnitude(
                        existing.type(), existing.target(), existing.snapshotPower(), existing.healthRate(),
                        existing.tickIntervalTicks()));

        if (stronger) {
            existing.source(request.source());
            existing.strength(request.strength());
            existing.snapshotPower(requestedPower);
            existing.healthRate(requestedHealthRate);
            existing.tickIntervalTicks(requestedInterval);
            existing.nextTickAtMs(requestedInterval <= 0
                    ? Long.MAX_VALUE
                    : System.currentTimeMillis() + requestedInterval * MS_PER_TICK);
        }
        existing.expiresAtMs(Math.max(existing.expiresAtMs(), expiresAtMs));
        return existing;
    }

    private double conditionMagnitude(
            @NotNull ConditionType type,
            @NotNull AstEntity target,
            double power,
            double healthRate,
            int tickIntervalTicks
    ) {
        double healthBase = type.defaultEffect().currentHealthBased()
                ? target.currentHealth()
                : target.maxHealth();
        double tickPower = power + Math.max(0.0D, healthBase) * healthRate;
        return tickIntervalTicks <= 0 ? tickPower : tickPower / tickIntervalTicks;
    }

    private double effectiveApplyChance(@NotNull ConditionApplyRequest request) {
        double applyIncrease = request.source() == null
                ? 0.0D
                : request.source().statValue(request.type().applyChanceStatus());
        double resistance = request.target().statValue(request.type().resistanceStatus());
        return calculateApplyChance(request.chance(), applyIncrease, resistance);
    }

    static double calculateApplyChance(double baseChance, double applyIncrease, double resistance) {
        return clampPercent(baseChance
                * (1.0D + applyIncrease / 100.0D)
                * (1.0D - resistance / 100.0D));
    }

    private double snapshotPower(@NotNull ConditionApplyRequest request, @NotNull ConditionEffect effect) {
        double base = request.basePower() == null ? effect.basePower() : request.basePower();
        double coefficient = request.powerCoefficient() == null
                ? effect.sourceAttackCoefficient()
                : request.powerCoefficient();
        double sourceAttack = request.source() == null ? 0.0D : request.source().statValue(StatusType.ATTACK);
        return Math.max(0.0D, base + sourceAttack * coefficient);
    }

    private long adjustedDurationTicks(
            @NotNull AstEntity target,
            @NotNull ConditionType type,
            long requestedDurationTicks
    ) {
        long durationTicks = requestedDurationTicks <= 0L ? type.defaultDurationTicks() : requestedDurationTicks;
        if (!target.isMob() || target.mob() == null || target.mob().template().category() != MobCategory.BOSS) {
            return durationTicks;
        }
        return type.category() == ConditionCategory.CONTROL
                ? Math.round(durationTicks * 0.25D)
                : durationTicks;
    }

    private long nextControlAtMs(@NotNull ConditionEffect effect, long nowMs) {
        if (effect.controlIntervalMaxTicks() <= 0) {
            return Long.MAX_VALUE;
        }
        int min = Math.max(1, effect.controlIntervalMinTicks());
        int max = Math.max(min, effect.controlIntervalMaxTicks());
        int interval = ThreadLocalRandom.current().nextInt(min, max + 1);
        return nowMs + interval * MS_PER_TICK;
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

    private void refreshConditionDependentStatus(@NotNull AstEntity target) {
        if (statusService != null && target.isPlayer() && target.player() != null) {
            statusService.refreshStatus(target.player());
        }
    }

    private @NotNull ConditionRejectReason rejectReason(@NotNull AstEntity target) {
        if (!target.isManaged()) {
            return ConditionRejectReason.UNMANAGED_TARGET;
        }
        if (target.isPlayer()) {
            return playerDeathService != null && playerDeathService.isDead(target.id())
                    ? ConditionRejectReason.DEAD_TARGET
                    : ConditionRejectReason.NONE;
        }
        if (target.mob() != null) {
            if (target.mob().template().category() == MobCategory.NPC) {
                return ConditionRejectReason.NPC_TARGET;
            }
            if (target.mob().state() == MobState.DEAD) {
                return ConditionRejectReason.DEAD_TARGET;
            }
        }
        return ConditionRejectReason.NONE;
    }

    private static double clampPercent(double value) {
        return Math.max(0.0D, Math.min(100.0D, value));
    }
}
