package io.github.maaasu.astralRecord.feature.mob.service;

import io.github.maaasu.astralRecord.feature.mob.model.MobInstance;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Mob AI の移動量、経路追従、環境補正を計算するクラス。
 */
final class MobAiCalculator {

    private static final String MOVEMENT_SPEED_STATUS = "MOVEMENT_SPEED";
    private static final double STANDARD_MOVEMENT_SPEED = 100.0;
    private static final double VANILLA_ZOMBIE_STEP_PER_TICK = 0.115;
    private static final double BASE_SPEED_MULTIPLIER = 1.5;
    private static final double MAX_SPEED_MULTIPLIER = 3.0;
    private static final double LIQUID_SPEED_MULTIPLIER = 0.45;
    private static final double LIQUID_BUOYANCY_PER_TICK = 0.04;
    private static final double MAX_STEP_UP_HEIGHT = 1.0;
    private static final double STEP_DOWN_PER_TICK = 0.30;
    private static final double MAX_HORIZONTAL_SUBSTEP = 0.12;
    private static final double WAYPOINT_REACHED_DISTANCE_SQ = 0.09;
    private static final double TARGET_DRIFT_DISTANCE_SQ = 0.36;
    private static final long NAV_RECOMPUTE_INTERVAL = 2L;
    private static final long JUMP_AFTER_BLOCKED_TICKS = 8L;
    private static final double JUMP_ASSIST_UP_PER_TICK = 0.42;
    private static final double HIGHER_TARGET_THRESHOLD = 0.5;
    private static final int WANDER_PAUSE_MIN_TICKS = 20;
    private static final int WANDER_PAUSE_MAX_TICKS = 60;

    /**
     * Mob を指定位置へ 1 tick 分だけ移動させます。
     *
     * @param instance        移動対象 Mob
     * @param target          目標位置
     * @param aiSpeedModifier AI 設定側の速度倍率
     * @param currentTick     AI 内部 tick
     * @return 位置を更新した場合は {@code true}
     */
    boolean moveToward(
            @NotNull MobInstance instance,
            @NotNull Location target,
            double aiSpeedModifier,
            long currentTick) {

        Location current = instance.currentLocation();
        if (current.getWorld() == null || current.getWorld() != target.getWorld()) return false;

        double step = movementStepPerTick(instance, aiSpeedModifier);
        if (step <= 0.0) return false;

        Location waypoint = nextWaypoint(instance, current, target, currentTick);
        if (waypoint == null) {
            return tryJumpAssist(instance, current, target, step, currentTick);
        }

        double dx = waypoint.getX() - current.getX();
        double dz = waypoint.getZ() - current.getZ();
        double hDist = Math.sqrt(dx * dx + dz * dz);
        if (hDist < 0.01) {
            return tryJumpAssist(instance, current, target, step, currentTick);
        }

        World world = current.getWorld();
        boolean liquid = isLiquidAround(world, current.getX(), current.getY(), current.getZ());
        double adjustedStep = liquid ? step * LIQUID_SPEED_MULTIPLIER : step;
        int substeps = Math.max(1, (int) Math.ceil(adjustedStep / MAX_HORIZONTAL_SUBSTEP));
        double segmentStep = adjustedStep / substeps;
        double dirX = dx / hDist;
        double dirZ = dz / hDist;
        Location next = current.clone();
        boolean moved = false;

        for (int i = 0; i < substeps; i++) {
            double newX = next.getX() + dirX * segmentStep;
            double newZ = next.getZ() + dirZ * segmentStep;
            int terrainCellY = MobNavigator.findStandableY(
                    world,
                    (int) Math.floor(newX),
                    (int) Math.floor(next.getY()),
                    (int) Math.floor(newZ)
            );
            double terrainFeetY = terrainCellY < 0
                    ? -1.0
                    : MobNavigator.findStandableFeetY(world, newX, terrainCellY, newZ);
            if (terrainFeetY < 0.0 && liquid) {
                terrainFeetY = next.getY();
            }
            if (terrainFeetY < 0.0) {
                markNavigationBlocked(instance, currentTick);
                return moved || tryJumpAssist(instance, next, waypoint, adjustedStep, currentTick);
            }

            double newY = nextVerticalPosition(world, next, newX, newZ, terrainFeetY, liquid);
            if (newY < 0.0 || !MobNavigator.hasBodyClearance(world, newX, newY, newZ)) {
                markNavigationBlocked(instance, currentTick);
                return moved || tryJumpAssist(instance, next, waypoint, adjustedStep, currentTick);
            }

            next.setX(newX);
            next.setY(newY);
            next.setZ(newZ);
            moved = true;
        }

        float movementYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        next.setYaw(movementYaw);
        next.setPitch(0.0f);
        instance.currentLocation(next);
        instance.navBlockedSinceTick(-1L);
        return moved;
    }

    /**
     * ランダム徘徊で次の目標を選ぶ前に停止する tick 数を返します。
     *
     * @return 停止 tick 数
     */
    long randomWanderPauseTicks() {
        return ThreadLocalRandom.current().nextLong(WANDER_PAUSE_MIN_TICKS, WANDER_PAUSE_MAX_TICKS + 1L);
    }

    private double movementStepPerTick(@NotNull MobInstance instance, double aiSpeedModifier) {
        double statusSpeed = instance.template().statValue(MOVEMENT_SPEED_STATUS, STANDARD_MOVEMENT_SPEED);
        double statusMultiplier = Math.max(0.0, statusSpeed) / STANDARD_MOVEMENT_SPEED;
        double speedMultiplier = Math.min(statusMultiplier * Math.max(0.0, aiSpeedModifier), MAX_SPEED_MULTIPLIER);
        return VANILLA_ZOMBIE_STEP_PER_TICK * BASE_SPEED_MULTIPLIER * speedMultiplier;
    }

    private void markNavigationBlocked(@NotNull MobInstance instance, long currentTick) {
        long blockedSinceTick = instance.navBlockedSinceTick();
        instance.clearNavPath();
        instance.navBlockedSinceTick(blockedSinceTick < 0L ? currentTick : blockedSinceTick);
    }

    private boolean tryJumpAssist(
            @NotNull MobInstance instance,
            @NotNull Location current,
            @NotNull Location target,
            double horizontalStep,
            long currentTick) {

        if (instance.navBlockedSinceTick() < 0L) {
            instance.navBlockedSinceTick(currentTick);
            return false;
        }
        if (currentTick - instance.navBlockedSinceTick() < JUMP_AFTER_BLOCKED_TICKS) {
            return false;
        }

        World world = current.getWorld();
        if (world == null) return false;
        double dx = target.getX() - current.getX();
        double dz = target.getZ() - current.getZ();
        double hDist = Math.sqrt(dx * dx + dz * dz);
        if (hDist < 0.01) return false;

        double forwardStep = Math.min(horizontalStep, hDist);
        double nextX = current.getX() + dx / hDist * forwardStep;
        double nextZ = current.getZ() + dz / hDist * forwardStep;
        int standY = MobNavigator.findStandableY(
                world,
                (int) Math.floor(nextX),
                (int) Math.floor(current.getY()),
                (int) Math.floor(nextZ)
        );
        if (standY < 0) return false;
        double standFeetY = MobNavigator.findStandableFeetY(world, nextX, standY, nextZ);
        if (standFeetY < 0.0) return false;

        double rise = standFeetY - current.getY();
        if (rise > MAX_STEP_UP_HEIGHT) return false;

        boolean targetHigher = target.getY() > current.getY() + HIGHER_TARGET_THRESHOLD;
        boolean blockedAtCurrentHeight = !MobNavigator.hasBodyClearance(world, nextX, current.getY(), nextZ);
        if (!targetHigher && rise <= HIGHER_TARGET_THRESHOLD && !blockedAtCurrentHeight) {
            return false;
        }

        double jumpY = rise > HIGHER_TARGET_THRESHOLD
                ? standFeetY
                : Math.min(current.getY() + JUMP_ASSIST_UP_PER_TICK, standFeetY);
        if (jumpY <= current.getY() + 0.01) return false;
        if (!MobNavigator.hasBodyClearance(world, nextX, jumpY, nextZ)) {
            return false;
        }

        float movementYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        instance.currentLocation(new Location(world, nextX, jumpY, nextZ, movementYaw, 0.0f));
        return true;
    }

    private double nextVerticalPosition(
            @NotNull World world,
            @NotNull Location current,
            double newX,
            double newZ,
            double terrainY,
            boolean liquid) {

        double currentY = current.getY();
        double targetY = terrainY;
        if (liquid && canFloatUp(world, newX, currentY, newZ)) {
            targetY = Math.max(targetY, currentY + LIQUID_BUOYANCY_PER_TICK);
        }
        if (targetY > currentY) {
            double rise = targetY - currentY;
            return rise <= MAX_STEP_UP_HEIGHT ? targetY : -1.0;
        }
        if (targetY < currentY) {
            return Math.max(currentY - STEP_DOWN_PER_TICK, targetY);
        }
        return currentY;
    }

    private boolean canFloatUp(@NotNull World world, double x, double y, double z) {
        return isLiquidAround(world, x, y, z)
                && MobNavigator.hasBodyClearance(world, x, y + LIQUID_BUOYANCY_PER_TICK, z);
    }

    @Nullable
    private Location nextWaypoint(
            @NotNull MobInstance instance,
            @NotNull Location current,
            @NotNull Location target,
            long currentTick) {

        boolean pathExhausted = instance.navPath() == null
                || instance.navPathIndex() >= instance.navPath().size();
        boolean targetDrifted = hasTargetDrifted(instance, target);

        if ((pathExhausted || targetDrifted) && currentTick - instance.navRecomputeTick() >= NAV_RECOMPUTE_INTERVAL) {
            List<Location> newPath = MobNavigator.findPath(current, target);
            instance.navPath(newPath.isEmpty() ? null : newPath);
            instance.navPathIndex(0);
            instance.navTargetX(target.getX());
            instance.navTargetZ(target.getZ());
            instance.navRecomputeTick(currentTick);
        }

        List<Location> path = instance.navPath();
        if (path != null && instance.navPathIndex() < path.size()) {
            Location waypoint = path.get(instance.navPathIndex());
            double dxW = waypoint.getX() - current.getX();
            double dzW = waypoint.getZ() - current.getZ();
            if (dxW * dxW + dzW * dzW < WAYPOINT_REACHED_DISTANCE_SQ) {
                instance.navPathIndex(instance.navPathIndex() + 1);
                if (instance.navPathIndex() < path.size()) {
                    return path.get(instance.navPathIndex());
                }
                return target;
            }
            return waypoint;
        }

        if (sameBlock(current, target) && MobNavigator.hasBodyClearance(
                current.getWorld(),
                target.getX(),
                target.getY(),
                target.getZ()
        )) {
            return target;
        }

        return target;
    }

    private boolean sameBlock(@NotNull Location a, @NotNull Location b) {
        return a.getWorld() == b.getWorld()
                && Math.floor(a.getX()) == Math.floor(b.getX())
                && Math.floor(a.getZ()) == Math.floor(b.getZ());
    }

    private boolean hasTargetDrifted(@NotNull MobInstance instance, @NotNull Location target) {
        double dx = target.getX() - instance.navTargetX();
        double dz = target.getZ() - instance.navTargetZ();
        return dx * dx + dz * dz > TARGET_DRIFT_DISTANCE_SQ;
    }

    private boolean isLiquidAround(@NotNull World world, double x, double y, double z) {
        return isLiquid(world.getBlockAt((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z)))
                || isLiquid(world.getBlockAt((int) Math.floor(x), (int) Math.floor(y + 1.0), (int) Math.floor(z)));
    }

    private boolean isLiquid(@NotNull Block block) {
        Material type = block.getType();
        return type == Material.WATER || type == Material.LAVA;
    }
}
