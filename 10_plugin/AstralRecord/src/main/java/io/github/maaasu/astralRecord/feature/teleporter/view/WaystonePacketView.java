package io.github.maaasu.astralRecord.feature.teleporter.view;

import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.teleporter.model.WaystoneDefinition;
import io.github.maaasu.astralRecord.feature.teleporter.service.TeleporterService;
import io.github.maaasu.astralRecord.infrastructure.util.ColorCodeUtil;
import io.github.maaasu.astralRecord.shared.effect.ParticleDisplayService;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinition;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.util.Map;
import java.util.UUID;

/**
 * プレイヤーごとの差分を packet-only Display entity として描画します。
 */
public final class WaystonePacketView {
    private static final long VIEW_SYNC_INTERVAL_TICKS = 10L;

    private final Plugin plugin;
    private final TeleporterService teleporterService;
    private final ParticleDisplayService particleDisplayService;
    private final WaystonePacketDisplay packetDisplay = new WaystonePacketDisplay();
    private final Map<UUID, Map<String, DisplayEntry>> entitiesByPlayer = new HashMap<>();
    private BukkitTask viewSyncTask;

    public WaystonePacketView(
            @NotNull Plugin plugin,
            @NotNull TeleporterService teleporterService,
            @NotNull ParticleDisplayService particleDisplayService
    ) {
        this.plugin = plugin;
        this.teleporterService = teleporterService;
        this.particleDisplayService = particleDisplayService;
    }

    /** メインスレッドで近距離の差分同期とBE粒子表示を10 tick間隔で開始します。重複開始は行いません。 */
    public void start() {
        if (viewSyncTask != null) {
            return;
        }
        viewSyncTask = plugin.getServer().getScheduler().runTaskTimer(
                plugin,
                this::tickViews,
                1L,
                VIEW_SYNC_INTERVAL_TICKS
        );
    }

    /** 差分同期と粒子表示を停止し、送信済み packet 表示を破棄します。 */
    public void stop() {
        if (viewSyncTask != null) {
            viewSyncTask.cancel();
            viewSyncTask = null;
        }
        clearAll();
    }

    /**
     * 同一ワールドの64 block以内の表示を、ウェイストーン単位で差分同期します。
     * メインスレッドから呼び出し、追加・表示状態変更・範囲外への退出だけを送信します。
     *
     * @param player 同期対象
     */
    public void syncForPlayer(@NotNull Player player) {
        AstPlayer astPlayer = AstPlayerCache.get(player);
        if (!player.isOnline() || player.isDead() || astPlayer == null) {
            clearPlayer(player);
            return;
        }
        Map<String, DisplayEntry> displayed = entitiesByPlayer.computeIfAbsent(player.getUniqueId(), ignored -> new HashMap<>());
        Set<String> nearbyIds = new HashSet<>();
        for (WaystoneDefinition definition : teleporterService.getNearbyForView(player.getLocation())) {
            Location base = definition.toLocation();
            if (base == null || base.getWorld() != player.getWorld()) {
                continue;
            }
            nearbyIds.add(definition.id());
            boolean unlocked = teleporterService.isUnlockedForView(astPlayer, definition);
            DisplayState state = new DisplayState(player.getWorld().getUID(), definition.x(), definition.y(), definition.z(),
                    definition.name(), unlocked ? 0L : definition.unlockGold(), definition.displayIcon(), unlocked, astPlayer.isBedrock());
            DisplayEntry previous = displayed.get(definition.id());
            if (previous != null && previous.state().equals(state)) {
                continue;
            }
            if (previous != null) {
                destroyEntry(player, previous);
            }
            List<WaystonePacketDisplay.PacketEntity> entities = List.copyOf(createEntities(definition, base, unlocked, astPlayer.isBedrock()));
            for (WaystonePacketDisplay.PacketEntity entity : entities) {
                entity.spawn(player);
            }
            displayed.put(definition.id(), new DisplayEntry(state, entities));
        }
        displayed.entrySet().removeIf(entry -> {
            if (nearbyIds.contains(entry.getKey())) {
                return false;
            }
            destroyEntry(player, entry.getValue());
            return true;
        });
        if (displayed.isEmpty()) {
            entitiesByPlayer.remove(player.getUniqueId());
        }
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
        Map<String, DisplayEntry> entries = entitiesByPlayer.remove(player.getUniqueId());
        if (entries != null) {
            for (DisplayEntry entry : entries.values()) {
                destroyEntry(player, entry);
            }
        }
    }

    /**
     * 指定ウェイストーンの送信済み表示だけを破棄します。
     *
     * @param player 閲覧者
     * @param entry 破棄する表示一式
     */
    private void destroyEntry(@NotNull Player player, @NotNull DisplayEntry entry) {
        for (WaystonePacketDisplay.PacketEntity entity : entry.entities()) {
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

    /** 近距離表示を同期し、BEの解除状態ごとに独立した目印の粒子を一括送信します。 */
    private void tickViews() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            syncForPlayer(player);
            AstPlayer astPlayer = AstPlayerCache.get(player);
            Map<String, DisplayEntry> displayed = entitiesByPlayer.get(player.getUniqueId());
            if (astPlayer == null || !astPlayer.isBedrock() || displayed == null) {
                continue;
            }
            List<Location> unlockedMarkers = new ArrayList<>();
            List<Location> lockedMarkers = new ArrayList<>();
            for (DisplayEntry entry : displayed.values()) {
                DisplayState state = entry.state();
                Location marker = new Location(player.getWorld(), state.x() + 0.5D, state.y() + 1.65D, state.z() + 0.5D);
                (state.unlocked() ? unlockedMarkers : lockedMarkers).add(marker);
            }
            particleDisplayService.spawnBurstsForViewer(player, unlockedMarkers, waystoneParticle(true));
            particleDisplayService.spawnBurstsForViewer(player, lockedMarkers, waystoneParticle(false));
        }
    }

    private record DisplayState(UUID worldId, double x, double y, double z, String name, long unlockGold,
                                Material icon, boolean unlocked, boolean bedrock) { }

    private record DisplayEntry(DisplayState state, List<WaystonePacketDisplay.PacketEntity> entities) { }

    /**
     * ウェイストーン本体、名称、アイコンの packet-only Display 一式を作成します。
     *
     * @param definition 表示対象ウェイストーン
     * @param base ウェイストーン基準座標
     * @param unlocked 表示対象プレイヤーから見て解除済みの場合 true
     * @param bedrock Bedrock Edition プレイヤー向けの簡易表示にする場合 true
     * @return spawn する Display entity 一覧
     */
    @NotNull
    private Collection<WaystonePacketDisplay.PacketEntity> createEntities(
            @NotNull WaystoneDefinition definition,
            @NotNull Location base,
            boolean unlocked,
            boolean bedrock
    ) {
        List<WaystonePacketDisplay.PacketEntity> entities = new ArrayList<>(packetDisplayEntityCount(bedrock));
        Location origin = base.clone().add(0.5D, 0.0D, 0.5D);
        if (!bedrock) {
            entities.add(packetDisplay.block(origin, Material.DEEPSLATE_BRICKS, new Vector3f(-0.45F, 0.0F, -0.45F), new Vector3f(0.9F, 0.28F, 0.9F)));
            entities.add(packetDisplay.block(origin.clone().add(0.0D, 0.28D, 0.0D), Material.STONE_BRICKS, new Vector3f(-0.35F, 0.0F, -0.35F), new Vector3f(0.7F, 0.9F, 0.7F)));
            entities.add(packetDisplay.block(origin.clone().add(0.0D, 1.05D, 0.0D), Material.POLISHED_ANDESITE, new Vector3f(-0.28F, 0.0F, -0.28F), new Vector3f(0.56F, 0.42F, 0.56F)));
            entities.add(packetDisplay.block(origin.clone().add(0.0D, 1.45D, 0.0D), unlocked ? Material.SEA_LANTERN : Material.REDSTONE_LAMP, new Vector3f(-0.18F, 0.0F, -0.18F), new Vector3f(0.36F, 0.36F, 0.36F)));
        }
        entities.add(packetDisplay.text(origin.clone().add(0.0D, 2.15D, 0.0D), label(definition, unlocked), 0.85F));
        if (!bedrock) {
            entities.add(packetDisplay.item(origin.clone().add(0.0D, 2.85D, 0.0D), new ItemStack(definition.displayIcon()), 0.65F));
        }
        return entities;
    }

    /**
     * Waystone の版別 packet 表示数を返します。BE では文字表示だけを残します。
     *
     * @param bedrock BE プレイヤーの場合 true
     * @return 生成する Display entity 数
     */
    static int packetDisplayEntityCount(boolean bedrock) {
        return bedrock ? 1 : 6;
    }

    @NotNull
    private SharedParticleDefinition waystoneParticle(boolean unlocked) {
        return unlocked
                ? SharedParticleDefinitions.BEDROCK_WAYSTONE_UNLOCKED_DUST
                : SharedParticleDefinitions.BEDROCK_WAYSTONE_LOCKED_DUST;
    }

    @NotNull
    private Component label(@NotNull WaystoneDefinition definition, boolean unlocked) {
        if (unlocked) {
            return ColorCodeUtil.toComponent(definition.name(), definition.id(), NamedTextColor.AQUA);
        }
        return Component.text("未解除: ", NamedTextColor.RED)
                .append(ColorCodeUtil.toComponent(definition.name(), definition.id(), NamedTextColor.RED))
                .append(Component.newline())
                .append(Component.text(definition.unlockGold() + " ゴールド", NamedTextColor.GOLD));
    }
}
