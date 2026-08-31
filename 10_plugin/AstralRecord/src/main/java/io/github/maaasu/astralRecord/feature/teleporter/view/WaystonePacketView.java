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
import java.util.Map;
import java.util.UUID;

/**
 * プレイヤーごとの差分を packet-only Display entity として描画します。
 */
public final class WaystonePacketView {
    private static final long BEDROCK_FALLBACK_INTERVAL_TICKS = 10L;
    private static final double BEDROCK_FALLBACK_VIEW_DISTANCE_SQUARED = 64.0D * 64.0D;

    private final Plugin plugin;
    private final TeleporterService teleporterService;
    private final ParticleDisplayService particleDisplayService;
    private final WaystonePacketDisplay packetDisplay = new WaystonePacketDisplay();
    private final Map<UUID, List<WaystonePacketDisplay.PacketEntity>> entitiesByPlayer = new HashMap<>();
    private BukkitTask bedrockFallbackTask;

    public WaystonePacketView(
            @NotNull Plugin plugin,
            @NotNull TeleporterService teleporterService,
            @NotNull ParticleDisplayService particleDisplayService
    ) {
        this.plugin = plugin;
        this.teleporterService = teleporterService;
        this.particleDisplayService = particleDisplayService;
    }

    /** Bedrock Edition 向けの粒子フォールバックを開始します。 */
    public void start() {
        if (bedrockFallbackTask != null) {
            return;
        }
        bedrockFallbackTask = plugin.getServer().getScheduler().runTaskTimer(
                plugin,
                this::renderBedrockFallbacks,
                1L,
                BEDROCK_FALLBACK_INTERVAL_TICKS
        );
    }

    /** 粒子フォールバックを停止し、送信済み packet 表示を破棄します。 */
    public void stop() {
        if (bedrockFallbackTask != null) {
            bedrockFallbackTask.cancel();
            bedrockFallbackTask = null;
        }
        clearAll();
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
            for (WaystonePacketDisplay.PacketEntity entity : createEntities(definition, base, unlocked, astPlayer.isBedrock())) {
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

    private void renderBedrockFallbacks() {
        Collection<WaystoneDefinition> definitions = teleporterService.getAll();
        if (definitions.isEmpty()) {
            return;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            AstPlayer astPlayer = AstPlayerCache.get(player);
            if (astPlayer == null || !astPlayer.isBedrock()) {
                continue;
            }
            Location playerLocation = player.getLocation();
            for (WaystoneDefinition definition : definitions) {
                if (!definition.worldName().equals(player.getWorld().getName())) {
                    continue;
                }
                Location base = definition.toLocation();
                if (base == null || base.getWorld() != player.getWorld()
                        || playerLocation.distanceSquared(base) > BEDROCK_FALLBACK_VIEW_DISTANCE_SQUARED) {
                    continue;
                }
                boolean unlocked = teleporterService.isUnlocked(astPlayer, definition);
                Location marker = base.clone().add(0.5D, 1.65D, 0.5D);
                particleDisplayService.spawnForViewer(astPlayer, marker, waystoneParticle(unlocked));
            }
        }
    }

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
