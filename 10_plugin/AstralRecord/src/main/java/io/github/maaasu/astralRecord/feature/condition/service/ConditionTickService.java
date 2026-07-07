package io.github.maaasu.astralRecord.feature.condition.service;

import io.github.maaasu.astralRecord.feature.combat.service.DamageService;
import io.github.maaasu.astralRecord.feature.condition.model.ActiveCondition;
import org.jetbrains.annotations.NotNull;

/**
 * 状態異常の periodic effect を処理します。
 */
public final class ConditionTickService {
    private final ConditionService conditionService;
    private final DamageService damageService;

    public ConditionTickService(
            @NotNull ConditionService conditionService,
            @NotNull DamageService damageService
    ) {
        this.conditionService = conditionService;
        this.damageService = damageService;
    }

    /**
     * 1 件の状態異常 tick を処理します。
     *
     * @param condition 対象状態異常
     * @param nowMs 現在時刻ミリ秒
     */
    public void tickCondition(@NotNull ActiveCondition condition, long nowMs) {
        if (condition.expired(nowMs)) {
            conditionService.removeCondition(condition.target(), condition.type());
            return;
        }
        if (condition.tickIntervalTicks() <= 0 || condition.nextTickAtMs() > nowMs) {
            return;
        }

        double damage = Math.max(0.0D, condition.snapshotPower() * Math.max(1, condition.stack()));
        double maxTickDamage = condition.type().defaultEffect().maxTickDamage();
        if (maxTickDamage > 0.0D) {
            damage = Math.min(damage, maxTickDamage);
        }
        if (damage > 0.0D) {
            damageService.applyConditionDamage(
                    condition.source(),
                    condition.target(),
                    damage,
                    condition.damageType(),
                    condition.damageElement(),
                    condition.type()
            );
            conditionService.pulse(condition);
        }

        condition.lastTickAtMs(nowMs);
        condition.nextTickAtMs(nowMs + condition.tickIntervalTicks() * 50L);
    }
}
