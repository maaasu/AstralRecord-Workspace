package io.github.maaasu.astralRecord.feature.skill.active.service;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.skill.active.model.SkillBallisticProjectileLaunch;
import io.github.maaasu.astralRecord.feature.skill.active.model.SkillBallisticProjectileSpec;
import io.github.maaasu.astralRecord.feature.skill.active.model.SkillEffectLineSegment;
import io.github.maaasu.astralRecord.feature.skill.active.model.SkillLineTargetHit;
import io.github.maaasu.astralRecord.feature.skill.active.model.SkillProjectileSpec;
import io.github.maaasu.astralRecord.feature.skill.active.model.SkillProjectileTermination;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Iterator;
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
        launchInternal(player, origin, direction, spec, 0.0D, 0.0D, onHit, onFinish, null);
    }

    /**
     * 発動者の視線方向へ仮想 projectile を発射し、終了種別を受け取ります。
     *
     * @param player 発動者
     * @param origin 発射位置
     * @param direction 発射方向
     * @param spec projectile 仕様
     * @param onEntityHit Mob 命中時に対象と命中位置を受け取る処理
     * @param onTerminate Entity・Block・最大射程の終了種別と地点を受け取る処理
     */
    public void launchWithTermination(
            @NotNull Player player,
            @NotNull Location origin,
            @NotNull Vector direction,
            @NotNull SkillProjectileSpec spec,
            @NotNull BiConsumer<AstEntity, Location> onEntityHit,
            @NotNull Consumer<SkillProjectileTermination> onTerminate
    ) {
        launchInternal(player, origin, direction, spec, 0.0D, 0.0D, onEntityHit, ignored -> { }, onTerminate);
    }

    /**
     * 初速と重力を持つ仮想飛翔体を発射し、終了種別を通知します。
     *
     * @param player 発動者
     * @param origin 発射位置
     * @param spec 初速・重力・衝突・演出仕様
     * @param onEntityHit Mob命中時に対象と命中位置を受け取る処理
     * @param onTerminate Entity・Block・寿命／距離上限の終了種別と地点を受け取る処理
     */
    public void launchBallisticWithTermination(
            @NotNull Player player,
            @NotNull Location origin,
            @NotNull SkillBallisticProjectileSpec spec,
            @NotNull BiConsumer<AstEntity, Location> onEntityHit,
            @NotNull Consumer<SkillProjectileTermination> onTerminate
    ) {
        Vector[] velocity = {spec.initialVelocity()};
        Location[] current = {origin.clone()};
        double[] travelled = {0.0D};
        Set<UUID> hitIds = new HashSet<>();
        boolean[] finished = {false};
        String scope = "ballistic-projectile:" + UUID.randomUUID();

        taskService.repeat(player.getUniqueId(), scope, 0L, 1L, spec.maxTicks(), tick -> {
            if (finished[0]) {
                return;
            }
            double remainingDistance = spec.maxDistance() - travelled[0];
            double intendedDistance = velocity[0].length();
            if (remainingDistance <= 1.0E-6D || intendedDistance <= 1.0E-8D) {
                finish(
                        player.getUniqueId(), scope, current[0], SkillProjectileTermination.Type.RANGE, current[0],
                        ignored -> { }, onTerminate, finished
                );
                return;
            }

            double stepDistance = Math.min(intendedDistance, remainingDistance);
            Vector direction = velocity[0].clone().normalize();
            Location blockImpact = targetingService.blockImpact(current[0], direction, stepDistance);
            double collisionRange = blockImpact == null ? stepDistance : current[0].distance(blockImpact);
            double visibleRange = blockImpact == null ? stepDistance : Math.max(0.0D, collisionRange - 0.1D);
            Location next = current[0].clone().add(direction.clone().multiply(visibleRange));
            double actualDistance = current[0].distance(next);

            List<SkillLineTargetHit> candidates = targetingService.lineTargetHits(
                    player, current[0], direction, collisionRange,
                    spec.hitRadius(), spec.maxHits(), blockImpact == null
            );
            for (SkillLineTargetHit candidate : candidates) {
                if (!hitIds.add(candidate.target().id())) {
                    continue;
                }
                Location impact = candidate.location();
                if (spec.impact() != null) {
                    effectService.point(impact, spec.impact());
                }
                onEntityHit.accept(candidate.target(), impact);
                if (!spec.piercing() || hitIds.size() >= spec.maxHits()) {
                    effectService.line(current[0], impact, 0.45D, spec.trail());
                    finish(
                            player.getUniqueId(), scope, impact, SkillProjectileTermination.Type.ENTITY, impact,
                            ignored -> { }, onTerminate, finished
                    );
                    return;
                }
            }

            effectService.line(current[0], next, 0.45D, spec.trail());
            travelled[0] += actualDistance;
            current[0] = next;
            if (blockImpact != null) {
                finish(
                        player.getUniqueId(), scope, next, SkillProjectileTermination.Type.BLOCK, blockImpact,
                        ignored -> { }, onTerminate, finished
                );
            } else if (travelled[0] + 1.0E-6D >= spec.maxDistance() || tick + 1 >= spec.maxTicks()) {
                finish(
                        player.getUniqueId(), scope, next, SkillProjectileTermination.Type.RANGE, next,
                        ignored -> { }, onTerminate, finished
                );
            } else {
                velocity[0].add(new Vector(0.0D, -spec.gravityPerTick(), 0.0D));
            }
        });
    }

    /**
     * 重力付き飛翔体群を指定本数ずつ毎tick追加し、単一taskで一斉射撃を進めます。
     *
     * @param player 発動者
     * @param launches 全飛翔体の発射位置と仕様
     * @param launchesPerTick 1 tickに追加する本数
     * @param onEntityHit Mob命中処理
     * @param onTerminate 各飛翔体の終了通知
     */
    public void launchBallisticVolley(
            @NotNull Player player,
            @NotNull List<SkillBallisticProjectileLaunch> launches,
            int launchesPerTick,
            @NotNull BiConsumer<AstEntity, Location> onEntityHit,
            @NotNull Consumer<SkillProjectileTermination> onTerminate
    ) {
        if (launches.isEmpty()) {
            return;
        }
        List<SkillBallisticProjectileLaunch> queued = List.copyOf(launches);
        int safePerTick = Math.max(1, launchesPerTick);
        int waveCount = (queued.size() + safePerTick - 1) / safePerTick;
        int longestLife = queued.stream().mapToInt(launch -> launch.spec().maxTicks()).max().orElse(1);
        int executions = waveCount + longestLife;
        String scope = "ballistic-volley:" + UUID.randomUUID();
        List<BallisticState> active = new ArrayList<>();

        taskService.repeat(player.getUniqueId(), scope, 0L, 1L, executions, tick -> {
            int first = tick * safePerTick;
            for (int index = first; index < Math.min(first + safePerTick, queued.size()); index++) {
                active.add(new BallisticState(queued.get(index)));
            }

            List<SkillEffectLineSegment> trailSegments = new ArrayList<>();
            Iterator<BallisticState> iterator = active.iterator();
            while (iterator.hasNext()) {
                BallisticState state = iterator.next();
                SkillProjectileTermination termination = advanceBallistic(
                        player, state, trailSegments, onEntityHit
                );
                if (termination != null) {
                    iterator.remove();
                    onTerminate.accept(termination);
                }
            }
            if (!trailSegments.isEmpty()) {
                effectService.lines(
                        player.getLocation(), trailSegments, 0.45D, queued.getFirst().spec().trail()
                );
            }
            if (first >= queued.size() && active.isEmpty()) {
                taskService.cancel(player.getUniqueId(), scope);
            }
        });
    }

    private @Nullable SkillProjectileTermination advanceBallistic(
            @NotNull Player player,
            @NotNull BallisticState state,
            @NotNull List<SkillEffectLineSegment> trailSegments,
            @NotNull BiConsumer<AstEntity, Location> onEntityHit
    ) {
        SkillBallisticProjectileSpec spec = state.spec;
        double remainingDistance = spec.maxDistance() - state.travelled;
        double intendedDistance = state.velocity.length();
        if (remainingDistance <= 1.0E-6D || intendedDistance <= 1.0E-8D) {
            return termination(SkillProjectileTermination.Type.RANGE, state.current, state.current);
        }

        double stepDistance = Math.min(intendedDistance, remainingDistance);
        Vector direction = state.velocity.clone().normalize();
        Location blockImpact = targetingService.blockImpact(state.current, direction, stepDistance);
        double collisionRange = blockImpact == null ? stepDistance : state.current.distance(blockImpact);
        double visibleRange = blockImpact == null ? stepDistance : Math.max(0.0D, collisionRange - 0.1D);
        Location next = state.current.clone().add(direction.clone().multiply(visibleRange));
        double actualDistance = state.current.distance(next);
        List<SkillLineTargetHit> candidates = targetingService.lineTargetHits(
                player, state.current, direction, collisionRange,
                spec.hitRadius(), spec.maxHits(), blockImpact == null
        );
        for (SkillLineTargetHit candidate : candidates) {
            if (!state.hitIds.add(candidate.target().id())) {
                continue;
            }
            Location impact = candidate.location();
            if (spec.impact() != null) {
                effectService.point(impact, spec.impact());
            }
            onEntityHit.accept(candidate.target(), impact);
            if (!spec.piercing() || state.hitIds.size() >= spec.maxHits()) {
                trailSegments.add(new SkillEffectLineSegment(state.current, impact));
                return termination(SkillProjectileTermination.Type.ENTITY, impact, impact);
            }
        }

        trailSegments.add(new SkillEffectLineSegment(state.current, next));
        state.travelled += actualDistance;
        state.current = next;
        state.age++;
        if (blockImpact != null) {
            return termination(SkillProjectileTermination.Type.BLOCK, blockImpact, next);
        }
        if (state.travelled + 1.0E-6D >= spec.maxDistance() || state.age >= spec.maxTicks()) {
            return termination(SkillProjectileTermination.Type.RANGE, next, next);
        }
        state.velocity.add(new Vector(0.0D, -spec.gravityPerTick(), 0.0D));
        return null;
    }

    private static @NotNull SkillProjectileTermination termination(
            @NotNull SkillProjectileTermination.Type type,
            @NotNull Location location,
            @NotNull Location effectLocation
    ) {
        return new SkillProjectileTermination(type, location.clone(), effectLocation.clone());
    }

    private static final class BallisticState {
        private final SkillBallisticProjectileSpec spec;
        private final Set<UUID> hitIds = new HashSet<>();
        private Location current;
        private Vector velocity;
        private double travelled;
        private int age;

        private BallisticState(@NotNull SkillBallisticProjectileLaunch launch) {
            this.spec = launch.spec();
            this.current = launch.origin();
            this.velocity = spec.initialVelocity();
        }
    }

    /** 近くの対象へ毎tick進行方向を補正する仮想 projectile を発射します。 */
    public void launchHoming(
            @NotNull Player player,
            @NotNull Location origin,
            @NotNull Vector direction,
            @NotNull SkillProjectileSpec spec,
            double homingStrength,
            double homingRange,
            @NotNull BiConsumer<AstEntity, Location> onHit,
            @NotNull Consumer<Location> onFinish
    ) {
        launchInternal(
            player, origin, direction, spec,
            Math.max(0.0D, Math.min(1.0D, homingStrength)),
            Math.max(0.0D, homingRange),
            onHit, onFinish, null
        );
    }

    private void launchInternal(
            @NotNull Player player,
            @NotNull Location origin,
            @NotNull Vector direction,
            @NotNull SkillProjectileSpec spec,
            double homingStrength,
            double homingRange,
            @NotNull BiConsumer<AstEntity, Location> onHit,
            @NotNull Consumer<Location> onFinish,
            Consumer<SkillProjectileTermination> onTerminate
    ) {
        Vector initialDirection = direction.lengthSquared() <= 1.0E-8D
                ? new Vector(0.0D, 0.0D, 1.0D)
                : direction.clone().normalize();
        Vector[] currentDirection = {initialDirection};
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
            if (homingStrength > 0.0D && homingRange > 0.0D) {
                List<AstEntity> nearby = targetingService.inRadius(
                    player, current[0], homingRange, homingRange, 1, true
                );
                if (!nearby.isEmpty()) {
                    Vector desired = nearby.getFirst().location()
                        .add(0.0D, 1.0D, 0.0D)
                        .toVector()
                        .subtract(current[0].toVector());
                    if (desired.lengthSquared() > 1.0E-8D) {
                        currentDirection[0] = currentDirection[0].clone()
                            .multiply(1.0D - homingStrength)
                            .add(desired.normalize().multiply(homingStrength))
                            .normalize();
                    }
                }
            }
            double stepDistance = Math.min(spec.speed(), spec.range() - travelled[0]);
            Location blockImpact = targetingService.blockImpact(current[0], currentDirection[0], stepDistance);
            Location next = targetingService.clippedEnd(current[0], currentDirection[0], stepDistance);
            double actualDistance = current[0].distance(next);
            effectService.line(current[0], next, 0.45D, spec.trail());

            List<AstEntity> candidates = blockImpact == null
                    ? targetingService.inLine(
                            player,
                            current[0],
                            currentDirection[0],
                            Math.max(0.05D, actualDistance),
                            spec.hitRadius(),
                            spec.maxHits()
                    )
                    : targetingService.inLineBeforeBlock(
                            player,
                            current[0],
                            currentDirection[0],
                            current[0].distance(blockImpact),
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
                    finish(
                            player.getUniqueId(), scope, next, SkillProjectileTermination.Type.ENTITY, impact,
                            onFinish, onTerminate, finished
                    );
                    return;
                }
            }

            travelled[0] += actualDistance;
            current[0] = next;
            if (blockImpact != null) {
                finish(
                        player.getUniqueId(), scope, next, SkillProjectileTermination.Type.BLOCK, blockImpact,
                        onFinish, onTerminate, finished
                );
            } else if (travelled[0] + 1.0E-6D >= spec.range()) {
                finish(
                        player.getUniqueId(), scope, next, SkillProjectileTermination.Type.RANGE, next,
                        onFinish, onTerminate, finished
                );
            }
        });
    }

    private void finish(
            @NotNull UUID casterId,
            @NotNull String scope,
            @NotNull Location end,
            @NotNull SkillProjectileTermination.Type terminationType,
            @NotNull Location terminationLocation,
            @NotNull Consumer<Location> onFinish,
            Consumer<SkillProjectileTermination> onTerminate,
            boolean @NotNull [] finished
    ) {
        if (finished[0]) {
            return;
        }
        finished[0] = true;
        taskService.cancel(casterId, scope);
        onFinish.accept(end.clone());
        if (onTerminate != null) {
            onTerminate.accept(new SkillProjectileTermination(
                    terminationType,
                    terminationLocation.clone(),
                    end.clone()
            ));
        }
    }
}
