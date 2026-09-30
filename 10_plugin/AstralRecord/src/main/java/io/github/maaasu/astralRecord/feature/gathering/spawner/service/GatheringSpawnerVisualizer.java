package io.github.maaasu.astralRecord.feature.gathering.spawner.service;

import io.github.maaasu.astralRecord.feature.gathering.spawner.model.GatheringSpawnerLocation;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgResource;
import io.github.maaasu.astralRecord.feature.spawner.service.SpawnerVisualizer;
import io.github.maaasu.astralRecord.shared.effect.ParticleDisplayService;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;

/** 採集スポナーの配置と表示内容を共通の管理表示へ渡します。 */
final class GatheringSpawnerVisualizer extends SpawnerVisualizer<GatheringSpawnerLocation> {
    private final GatheringSpawnerService spawnerService;

    GatheringSpawnerVisualizer(@NotNull Plugin plugin, @NotNull GatheringSpawnerService spawnerService,
                              @NotNull ParticleDisplayService particleDisplayService) {
        super(plugin, particleDisplayService);
        this.spawnerService = spawnerService;
    }

    @Override
    protected @NotNull Collection<GatheringSpawnerLocation> locations() {
        return spawnerService.getLocations();
    }

    @Override
    protected @Nullable Location location(@NotNull GatheringSpawnerLocation spawner) {
        return spawner.toLocation();
    }

    @Override
    protected @NotNull String key(@NotNull GatheringSpawnerLocation spawner) {
        return spawner.locationKey() + ":" + spawner.spawnerId();
    }

    @Override
    protected @NotNull Material material(@NotNull GatheringSpawnerLocation spawner) {
        return spawnerService.getDisplayMaterial(spawner.spawnerId());
    }

    @Override
    protected @NotNull Component label(@NotNull GatheringSpawnerLocation spawner) {
        return PlayerMsgResource.formatComponent(PlayerMsgId.P_5729.getId(),
            spawnerService.getSpawnerDisplayName(spawner.spawnerId()));
    }
}
