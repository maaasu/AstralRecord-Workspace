package io.github.maaasu.astralRecord.feature.spawner.service;

import io.github.maaasu.astralRecord.feature.spawner.model.MobSpawnerLocation;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgResource;
import io.github.maaasu.astralRecord.shared.effect.ParticleDisplayService;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;

/** Mob スポナーの配置と表示内容を共通の管理表示へ渡します。 */
final class MobSpawnerVisualizer extends SpawnerVisualizer<MobSpawnerLocation> {
    private final MobSpawnerService spawnerService;

    MobSpawnerVisualizer(@NotNull Plugin plugin, @NotNull MobSpawnerService spawnerService,
                         @NotNull ParticleDisplayService particleDisplayService) {
        super(plugin, particleDisplayService);
        this.spawnerService = spawnerService;
    }

    @Override
    protected @NotNull Collection<MobSpawnerLocation> locations() {
        return spawnerService.getLocations();
    }

    @Override
    protected @Nullable Location location(@NotNull MobSpawnerLocation spawner) {
        return spawner.toLocation();
    }

    @Override
    protected @NotNull String key(@NotNull MobSpawnerLocation spawner) {
        return spawner.locationKey() + ":" + spawner.spawnerId();
    }

    @Override
    protected @NotNull Material material(@NotNull MobSpawnerLocation spawner) {
        return spawnerService.getDisplayMaterial(spawner.spawnerId());
    }

    @Override
    protected @NotNull Component label(@NotNull MobSpawnerLocation spawner) {
        return PlayerMsgResource.formatComponent(PlayerMsgId.P_5730.getId(),
            spawnerService.getSpawnerDisplayName(spawner.spawnerId()));
    }
}
