package io.github.maaasu.astralRecord.feature.world.service;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.shared.effect.ParticleDisplayService;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;

/**
 * 各ワールドの既定スポーン地点に END_ROD の周回パーティクルを表示するタスクです。
 */
public class WorldSpawnParticleTask {

    private static final long PERIOD_TICKS = 2L;
    private static final int RING_POINTS = 10;

    private final AstralRecord plugin;
    private final WorldService worldService;
    private final ParticleDisplayService particleDisplayService;
    private BukkitTask task;
    private long frame;

    public WorldSpawnParticleTask(
        @NotNull AstralRecord plugin,
        @NotNull WorldService worldService,
        @NotNull ParticleDisplayService particleDisplayService
    ) {
        this.plugin = plugin;
        this.worldService = worldService;
        this.particleDisplayService = particleDisplayService;
    }

    /**
     * スポーン地点演出タスクを開始します。
     */
    public void start() {
        if (task != null) {
            return;
        }
        frame = 0L;
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 0L, PERIOD_TICKS);
    }

    /**
     * スポーン地点演出タスクを停止します。
     */
    public void stop() {
        if (task == null) {
            return;
        }
        task.cancel();
        task = null;
    }

    private void tick() {
        double baseAngle = frame * 0.22D;
        for (var worldData : worldService.getAll()) {
            Location spawn = worldService.resolveSpawnLocation(worldData);
            if (spawn == null) {
                continue;
            }
            renderSpawnAnimation(spawn, baseAngle);
        }
        frame++;
    }

    private void renderSpawnAnimation(@NotNull Location spawn, double baseAngle) {
        World world = spawn.getWorld();
        if (world == null) {
            return;
        }

        double pulse = 1.5D + (Math.sin(baseAngle * 0.65D) * 0.12D);
        for (int i = 0; i < RING_POINTS; i++) {
            double angle = baseAngle + ((Math.PI * 2.0D * i) / RING_POINTS);
            double x = Math.cos(angle) * pulse;
            double z = Math.sin(angle) * pulse;
            double y = 1.15D + (Math.sin((baseAngle * 1.4D) + (i * 0.45D)) * 0.25D);

            particleDisplayService.spawnForNearbyViewers(
                spawn.clone().add(x, y, z),
                SharedParticleDefinitions.WORLD_SPAWN_RING_END_ROD
            );
        }
    }
}
