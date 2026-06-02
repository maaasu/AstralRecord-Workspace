package io.github.maaasu.astralRecord.feature.world.event;

import io.github.maaasu.astralRecord.core.event.AbstractEventHandler;
import io.github.maaasu.astralRecord.feature.mob.service.MobService;
import io.github.maaasu.astralRecord.feature.world.model.WorldMasterData;
import io.github.maaasu.astralRecord.feature.world.service.WorldService;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mob;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

/**
 * AstralRecord 管理ワールドで、バニラ由来の Mob 生成と残存 Mob を抑止します。
 */
public class WorldNaturalSpawnBlockEventHandler extends AbstractEventHandler {

    private final Plugin plugin;
    private final WorldService worldService;
    private final MobService mobService;

    /**
     * ハンドラを初期化します。
     *
     * @param plugin       プラグイン本体
     * @param worldService WorldMasterData サービス
     * @param mobService   AstralRecord Mob サービス
     */
    public WorldNaturalSpawnBlockEventHandler(
            @NotNull Plugin plugin,
            @NotNull WorldService worldService,
            @NotNull MobService mobService
    ) {
        this.plugin = plugin;
        this.worldService = worldService;
        this.mobService = mobService;
    }

    @Override
    public void initialize() {
        super.initialize();
        for (World world : Bukkit.getWorlds()) {
            if (isManagedWorld(world)) {
                worldService.applyRpgGameRules(world);
                removeUnmanagedMobs(world);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        if (!(event.getEntity() instanceof Mob mob)) {
            return;
        }

        World world = event.getLocation().getWorld();
        if (world == null || !isManagedWorld(world)) {
            return;
        }

        if (isAstralRecordMob(mob)) {
            return;
        }

        if (event.getSpawnReason() == CreatureSpawnEvent.SpawnReason.CUSTOM) {
            scheduleCustomSpawnValidation(mob);
            return;
        }

        event.setCancelled(true);
        mob.remove();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onWorldLoad(WorldLoadEvent event) {
        World world = event.getWorld();
        if (!isManagedWorld(world)) {
            return;
        }

        worldService.applyRpgGameRules(world);
        removeUnmanagedMobs(world);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onChunkLoad(ChunkLoadEvent event) {
        World world = event.getWorld();
        if (!isManagedWorld(world)) {
            return;
        }

        removeUnmanagedMobs(event.getChunk());
    }

    private void scheduleCustomSpawnValidation(@NotNull Mob mob) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (mob.isValid() && !mob.isDead() && !isAstralRecordMob(mob) && isManagedWorld(mob.getWorld())) {
                mob.remove();
            }
        });
    }

    private void removeUnmanagedMobs(@NotNull World world) {
        for (Entity entity : world.getEntities()) {
            removeUnmanagedMob(entity);
        }
    }

    private void removeUnmanagedMobs(@NotNull Chunk chunk) {
        for (Entity entity : chunk.getEntities()) {
            removeUnmanagedMob(entity);
        }
    }

    private void removeUnmanagedMob(@NotNull Entity entity) {
        if (entity instanceof Mob mob && !isAstralRecordMob(mob)) {
            mob.remove();
        }
    }

    private boolean isAstralRecordMob(@NotNull Entity entity) {
        return mobService.entityController().readInstanceId(entity) != null;
    }

    private boolean isManagedWorld(@NotNull World world) {
        WorldMasterData worldData = worldService.findByBukkitWorld(world);
        return worldData != null;
    }
}
