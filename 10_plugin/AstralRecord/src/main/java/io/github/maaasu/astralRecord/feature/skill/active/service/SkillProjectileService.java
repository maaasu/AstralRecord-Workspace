package io.github.maaasu.astralRecord.feature.skill.active.service;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.skill.active.model.SkillProjectileSpec;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Bukkit Entity を生成せず、swept capsule で衝突判定する軽量 projectile です。
 */
public final class SkillProjectileService {

    private final SkillTargetingService targetingService;
    private final SkillEffectService effectService;
    private final SkillTaskService taskService;

    /** 共有サービスで初期化します。 */
    public SkillProjectileService(
            @NotNull SkillTargetingService targetingService,
            @NotNull SkillEffectService effectService,
            @NotNull SkillTaskService taskService
    ) {
        this.targetingService = targetingService;
        this.effectService = effectService;
        this.taskService = taskService;
    }

    /**
     * 発動者の視線方向へ仮想 projectile を発射します。
     *
     * @param player 発動者
     * @param origin 発射位置
     * @param direction 発射方向
     * @param spec projectile 仕様
     * @param onHit 命中対象と命中位置を受け取る処理
     * @param onFinish 終端位置を受け取る処理
     */
    public void launch(
            @NotNull Player player,
            @NotNull Location origin,
            @NotNull Vector direction,
            @NotNull SkillProjectileSpec spec,
            @NotNull BiConsumer<AstEntity, Location> onHit,
            @NotNull Consumer<Location> onFinish
    ) {
        Vector normalized = direction.lengthSquared() <= 1.0E-8D
                ? new Vector(0.0D, 0.0D, 1.0D)
                : direction.clone().normalize();
        int ticks = Math.max(1, (int) Math.ceil(spec.range() / spec.speed()));
        String scope = "projectile:" + UUID.randomUUID();
        Location[] current = {origin.clone()};
        double[] travelled = {0.0D};
        Set<UUID> hitIds = new HashSet<>();
        boolean[] finished = {false};

        taskService.repeat(player.getUniqueId(), scope, 0L, 1L, ticks, ignored -> {
            if (finished[0]) {
                return;
            }
            double stepDistance = Math.min(spec.speed(), spec.range() - travelled[0]);
            Location next = targetingService.clippedEnd(current[0], normalized, stepDistance);
            double actualDistance = current[0].distance(next);
            effectService.line(current[0], next, 0.45D, spec.trail());

            List<AstEntity> candidates = targetingService.inLine(
                    player,
                    current[0],
                    normalized,
                    Math.max(0.05D, actualDistance),
                    spec.hitRadius(),
                    spec.maxHits()
            );
            for (AstEntity candidate : candidates) {
                if (!hitIds.add(candidate.id())) {
                    continue;
                }
                Location impact = candidate.location().add(0.0D, 1.0D, 0.0D);
                if (spec.impact() != null) {
                    effectService.point(impact, spec.impact());
                }
                onHit.accept(candidate, impact);
                if (!spec.piercing() || hitIds.size() >= spec.maxHits()) {
                    finish(player.getUniqueId(), scope, next, onFinish, finished);
                    return;
                }
            }

            travelled[0] += actualDistance;
            current[0] = next;
            boolean blocked = actualDistance + 1.0E-6D < stepDistance;
            if (blocked || travelled[0] + 1.0E-6D >= spec.range()) {
                finish(player.getUniqueId(), scope, next, onFinish, finished);
            }
        });
    }

    private void finish(
            @NotNull UUID casterId,
            @NotNull String scope,
            @NotNull Location end,
            @NotNull Consumer<Location> onFinish,
            boolean @NotNull [] finished
    ) {
        if (finished[0]) {
            return;
        }
        finished[0] = true;
        taskService.cancel(casterId, scope);
        onFinish.accept(end.clone());
    }
}
