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
    private static final double MAX_SPEED_MULTIPLIER = 3.0;
    private static final double LIQUID_SPEED_MULTIPLIER = 0.45;
    private static final double LIQUID_BUOYANCY_PER_TICK = 0.04;
    private static final double STEP_UP_PER_TICK = 0.20;
    private static final double STEP_DOWN_PER_TICK = 0.30;
    private static final double WAYPOINT_REACHED_DISTANCE_SQ = 0.09;
    private static final double TARGET_DRIFT_DISTANCE_SQ = 4.0;
    private static final long NAV_RECOMPUTE_INTERVAL = 5L;
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
        if (waypoint == null) return false;

        double dx = waypoint.getX() - current.getX();
        double dz = waypoint.getZ() - current.getZ();
        double hDist = Math.sqrt(dx * dx + dz * dz);
        if (hDist < 0.01) return false;

        World world = current.getWorld();
        boolean liquid = isLiquidAround(world, current.getX(), current.getY(), current.getZ());
        double adjustedStep = liquid ? step * LIQUID_SPEED_MULTIPLIER : step;

        double scale = Math.min(adjustedStep / hDist, 1.0);
        double newX = current.getX() + dx * scale;
        double newZ = current.getZ() + dz * scale;

        int terrainY = MobNavigator.findStandableY(
                world,
                (int) Math.floor(newX),
                (int) Math.floor(current.getY()),
                (int) Math.floor(newZ)
        );
        if (terrainY < 0 && liquid) {
            terrainY = (int) Math.floor(current.getY());
        }
        if (terrainY < 0) {
            instance.clearNavPath();
            return false;
        }

        double newY = nextVerticalPosition(world, current, newX, newZ, terrainY, liquid);
        if (terrainY > current.getY() + 0.01) {
            newX = current.getX();
            newZ = current.getZ();
        }

        if (!MobNavigator.hasBodyClearance(world, newX, newY, newZ)) {
            instance.clearNavPath();
            return false;
        }

        float movementYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        instance.currentLocation(new Location(world, newX, newY, newZ, movementYaw, 0.0f));
        return true;
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
        return VANILLA_ZOMBIE_STEP_PER_TICK * speedMultiplier;
    }

    private double nextVerticalPosition(
            @NotNull World world,
            @NotNull Location current,
            double newX,
            double newZ,
            int terrainY,
            boolean liquid) {

        double currentY = current.getY();
        double targetY = terrainY;
        if (liquid && canFloatUp(world, newX, currentY, newZ)) {
            targetY = Math.max(targetY, currentY + LIQUID_BUOYANCY_PER_TICK);
        }
        if (targetY > currentY) {
            return Math.min(currentY + STEP_UP_PER_TICK, targetY);
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
                return null;
            }
            return waypoint;
        }

        return sameBlock(current, target) && MobNavigator.hasBodyClearance(
                current.getWorld(),
                target.getX(),
                target.getY(),
                target.getZ()
        ) ? target : null;
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
