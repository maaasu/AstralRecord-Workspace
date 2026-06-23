package io.github.maaasu.astralRecord.feature.teleporter.view;

import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.teleporter.model.WaystoneDefinition;
import io.github.maaasu.astralRecord.feature.teleporter.service.TeleporterService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * プレイヤーごとの差分を packet-only Display entity として描画します。
 */
public final class WaystonePacketView {
    private final TeleporterService teleporterService;
    private final WaystonePacketDisplay packetDisplay = new WaystonePacketDisplay();
    private final Map<UUID, List<WaystonePacketDisplay.PacketEntity>> entitiesByPlayer = new HashMap<>();

    public WaystonePacketView(@NotNull TeleporterService teleporterService) {
        this.teleporterService = teleporterService;
    }

    /**
     * 指定プレイヤー向け表示を現在状態に同期します。
     *
     * @param player 同期対象
     */
    public void syncForPlayer(@NotNull Player player) {
        clearPlayer(player);
        AstPlayer astPlayer = AstPlayerCache.get(player);
        if (astPlayer == null || player.getWorld() == null) {
            return;
        }

        List<WaystonePacketDisplay.PacketEntity> spawned = new ArrayList<>();
        for (WaystoneDefinition definition : teleporterService.getAll()) {
            if (!definition.worldName().equals(player.getWorld().getName())) {
                continue;
            }
            Location base = definition.toLocation();
            if (base == null) {
                continue;
            }
            boolean unlocked = teleporterService.isUnlocked(astPlayer, definition);
            for (WaystonePacketDisplay.PacketEntity entity : createEntities(definition, base, unlocked)) {
                entity.spawn(player);
                spawned.add(entity);
            }
        }
        entitiesByPlayer.put(player.getUniqueId(), spawned);
    }

    /**
     * オンラインプレイヤー全員の表示を同期します。
     */
    public void syncAll() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            syncForPlayer(player);
        }
    }

    /**
     * 指定プレイヤーに送信済みの表示を破棄します。
     *
     * @param player 破棄対象
     */
    public void clearPlayer(@NotNull Player player) {
        List<WaystonePacketDisplay.PacketEntity> entities = entitiesByPlayer.remove(player.getUniqueId());
        if (entities == null) {
            return;
        }
        for (WaystonePacketDisplay.PacketEntity entity : entities) {
            entity.destroy(player);
        }
    }

    /**
     * すべてのプレイヤーの表示を破棄します。
     */
    public void clearAll() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            clearPlayer(player);
        }
        entitiesByPlayer.clear();
    }

    @NotNull
    private Collection<WaystonePacketDisplay.PacketEntity> createEntities(
            @NotNull WaystoneDefinition definition,
            @NotNull Location base,
            boolean unlocked
    ) {
        List<WaystonePacketDisplay.PacketEntity> entities = new ArrayList<>();
        Location origin = base.clone().add(0.5D, 0.0D, 0.5D);
        entities.add(packetDisplay.block(origin, Material.DEEPSLATE_BRICKS, new Vector3f(-0.45F, 0.0F, -0.45F), new Vector3f(0.9F, 0.28F, 0.9F)));
        entities.add(packetDisplay.block(origin.clone().add(0.0D, 0.28D, 0.0D), Material.STONE_BRICKS, new Vector3f(-0.35F, 0.0F, -0.35F), new Vector3f(0.7F, 0.9F, 0.7F)));
        entities.add(packetDisplay.block(origin.clone().add(0.0D, 1.05D, 0.0D), unlocked ? Material.AMETHYST_BLOCK : Material.COPPER_BLOCK, new Vector3f(-0.28F, 0.0F, -0.28F), new Vector3f(0.56F, 0.42F, 0.56F)));
        entities.add(packetDisplay.block(origin.clone().add(0.0D, 1.45D, 0.0D), unlocked ? Material.SEA_LANTERN : Material.IRON_BARS, new Vector3f(-0.18F, 0.0F, -0.18F), new Vector3f(0.36F, 0.36F, 0.36F)));
        entities.add(packetDisplay.text(origin.clone().add(0.0D, 2.15D, 0.0D), label(definition, unlocked), 0.85F));
        return entities;
    }

    @NotNull
    private Component label(@NotNull WaystoneDefinition definition, boolean unlocked) {
        if (unlocked) {
            return Component.text(definition.name(), NamedTextColor.AQUA);
        }
        return Component.text("Locked: " + definition.name(), NamedTextColor.RED)
                .append(Component.newline())
                .append(Component.text(definition.unlockGold() + " Gold", NamedTextColor.GOLD));
    }
}
