package io.github.maaasu.astralRecord.feature.world.event;

import io.github.maaasu.astralRecord.core.event.AbstractEventHandler;
import io.github.maaasu.astralRecord.feature.mob.service.MobService;
import io.github.maaasu.astralRecord.feature.world.model.WorldMasterData;
import io.github.maaasu.astralRecord.feature.world.service.WorldService;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.jetbrains.annotations.NotNull;

import java.util.EnumSet;
import java.util.Set;

/**
 * ワールド設定に応じてバニラ自然スポーンを抑止します。
 */
public class WorldNaturalSpawnBlockEventHandler extends AbstractEventHandler {

    private static final Set<CreatureSpawnEvent.SpawnReason> NATURAL_REASONS = EnumSet.of(
            CreatureSpawnEvent.SpawnReason.NATURAL,
            CreatureSpawnEvent.SpawnReason.JOCKEY,
            CreatureSpawnEvent.SpawnReason.MOUNT,
            CreatureSpawnEvent.SpawnReason.PATROL,
            CreatureSpawnEvent.SpawnReason.REINFORCEMENTS,
            CreatureSpawnEvent.SpawnReason.VILLAGE_DEFENSE,
            CreatureSpawnEvent.SpawnReason.VILLAGE_INVASION
    );

    private final WorldService worldService;
    private final MobService mobService;

    /**
     * ハンドラを初期化します。
     *
     * @param worldService WorldMasterData サービス
     * @param mobService AstralRecord Mob サービス
     */
    public WorldNaturalSpawnBlockEventHandler(
            @NotNull WorldService worldService,
            @NotNull MobService mobService
    ) {
        this.worldService = worldService;
        this.mobService = mobService;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        if (!NATURAL_REASONS.contains(event.getSpawnReason())) {
            return;
        }

        World world = event.getLocation().getWorld();
        if (world == null) {
            return;
        }

        WorldMasterData worldData = worldService.findByBukkitWorld(world);
        if (worldData == null || worldData.allowMobSpawn()) {
            return;
        }

        if (mobService.entityController().readInstanceId(event.getEntity()) != null) {
            return;
        }

        event.setCancelled(true);
        event.getEntity().remove();
    }
}
