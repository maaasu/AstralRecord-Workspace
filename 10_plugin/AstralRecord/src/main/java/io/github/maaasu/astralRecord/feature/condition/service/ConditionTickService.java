package io.github.maaasu.astralRecord.feature.condition.service;

import io.github.maaasu.astralRecord.feature.combat.service.DamageService;
import io.github.maaasu.astralRecord.feature.condition.model.ActiveCondition;
import io.github.maaasu.astralRecord.feature.condition.model.ConditionEffect;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.ThreadLocalRandom;

/** 状態異常の periodic effect を処理します。 */
public final class ConditionTickService {
    private static final long MS_PER_TICK = 50L;

    private final ConditionService conditionService;
    private final DamageService damageService;

    public ConditionTickService(
            @NotNull ConditionService conditionService,
            @NotNull DamageService damageService
    ) {
        this.conditionService = conditionService;
        this.damageService = damageService;
    }

    /** 1 件の状態異常 tick を処理します。 */
    public void tickCondition(@NotNull ActiveCondition condition, long nowMs) {
        if (condition.expired(nowMs)) {
            conditionService.removeCondition(condition.target(), condition.type());
            return;
        }

        processIntermittentControl(condition, nowMs);
        if (condition.tickIntervalTicks() <= 0 || condition.nextTickAtMs() > nowMs) {
            return;
        }

        double damage = Math.max(0.0D, condition.snapshotPower());
        if (damage > 0.0D) {
            damageService.applyConditionDamage(
                    condition.source(),
                    condition.target(),
                    damage,
                    condition.type()
            );
            conditionService.pulse(condition);
        }

        condition.lastTickAtMs(nowMs);
        condition.nextTickAtMs(nowMs + condition.tickIntervalTicks() * MS_PER_TICK);
    }

    private void processIntermittentControl(@NotNull ActiveCondition condition, long nowMs) {
        ConditionEffect effect = condition.type().defaultEffect();
        if (effect.controlIntervalMaxTicks() <= 0 || condition.nextControlAtMs() > nowMs) {
            return;
        }

        condition.controlBlockedUntilMs(nowMs + Math.max(1, effect.controlDurationTicks()) * MS_PER_TICK);
        int min = Math.max(1, effect.controlIntervalMinTicks());
        int max = Math.max(min, effect.controlIntervalMaxTicks());
        int nextInterval = ThreadLocalRandom.current().nextInt(min, max + 1);
        condition.nextControlAtMs(nowMs + nextInterval * MS_PER_TICK);
        conditionService.pulse(condition);
    }
}
