package io.github.maaasu.astralRecord.shared.display;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.wrappers.WrappedDataValue;
import com.comphenix.protocol.wrappers.WrappedDataWatcher;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.resourcepack.service.ResourcePackService;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;
import org.joml.Quaternionf;
import org.joml.Quaternionfc;

import java.lang.reflect.Type;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 受付の装飾モデルをクライアントごとに同期します。メインスレッドでのみ使用します。 */
public final class ReceptionModelView implements Listener {
    private static final long BEDROCK_MARKER = 0x4153545245435054L; // ASTRECPT
    private static final int ENTITY_NO_GRAVITY_INDEX = 5;
    private static final int DROPPED_ITEM_STACK_INDEX = 8;
    private static final int DISPLAY_LEFT_ROTATION_INDEX = 13;
    private static final int ITEM_DISPLAY_ITEM_INDEX = 23;
    private static final int ITEM_DISPLAY_TRANSFORM_INDEX = 24;

    /** 受付の種別と、両エディションで使用するモデル識別子です。 */
    public enum Kind {
        BOSS(0, "boss_reception_altar", Material.IRON_SWORD),
        DUNGEON(1, "dungeon_reception_gate", Material.OBSIDIAN);

        private final int bedrockStyle;
        private final NamespacedKey itemModel;

        private final Material bedrockFallback;

        Kind(int bedrockStyle, String itemModel, Material bedrockFallback) {
            this.bedrockFallback = bedrockFallback;
            this.bedrockStyle = bedrockStyle;
            this.itemModel = new NamespacedKey("astralrecord", itemModel);
        }
    }

    private final ResourcePackService packs;
    private final ProtocolManager protocol = ProtocolLibrary.getProtocolManager();
    private final Map<Key, Placement> placements = new HashMap<>();
    private boolean warned;

    /**
     * 装飾モデルの描画を初期化します。
     * @param packs 接続ごとのリソースパック適用状態
     */
    public ReceptionModelView(ResourcePackService packs) {
        this.packs = packs;
    }

    /**
     * 同一ワールド内で受付のチャンクを受信済みの近隣プレイヤーへ表示差分を送ります。
     * @param kind 受付種別
     * @param id マスター上の受付ID
     * @param center 足元の座標と向き
     * @param nearby 同一ワールドの表示半径内にいるプレイヤー
     */
    public void sync(Kind kind, String id, Location center, Collection<Player> nearby) {
        Key key = new Key(kind, id);
        Placement placement = placements.get(key);
        if (placement != null && !placement.center.equals(center)) {
            placement.clear();
            placements.remove(key);
            placement = null;
        }
        long chunkKey = Chunk.getChunkKey(center.getBlockX() >> 4, center.getBlockZ() >> 4);
        Map<Player, Edition> visible = new HashMap<>();
        for (Player viewer : nearby) {
            if (!viewer.isOnline() || viewer.isDead() || !viewer.isChunkSent(chunkKey)) {
                continue;
            }
            AstPlayer astPlayer = AstPlayerCache.get(viewer);
            if (packs.isBedrock(viewer.getName()) || (astPlayer != null && astPlayer.isBedrock())) {
                visible.put(viewer, Edition.BEDROCK);
            } else if (packs.hasJavaReceptionDisplay(viewer.getUniqueId())) {
                visible.put(viewer, Edition.JAVA);
            }
        }
        if (placement != null) {
            placement.removeInvisible(visible);
        }
        if (visible.isEmpty()) {
            placements.remove(key);
            return;
        }
        if (placement == null) {
            placement = new Placement(kind, center.clone());
            placements.put(key, placement);
        }
        for (var entry : visible.entrySet()) {
            placement.show(entry.getKey(), entry.getValue());
        }
    }

    /**
     * ワールドのアンロードや定義更新などで表示対象外になった受付を回収します。
     * @param kind 今回走査した受付種別
     * @param activeIds 今回の表示候補になった受付ID。全回収する場合は空集合
     */
    public void retain(Kind kind, Set<String> activeIds) {
        placements.entrySet().removeIf(entry -> {
            Key key = entry.getKey();
            if (key.kind() != kind || activeIds.contains(key.id())) {
                return false;
            }
            entry.getValue().clear();
            return true;
        });
    }

    /**
     * ワールド変更時に以前のワールドの表示を解除します。
     * @param event ワールド変更イベント
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        clearPlayer(event.getPlayer(), true);
    }

    /**
     * リスポーンでクライアントの表示が初期化される前に既存表示を解除します。
     * @param event リスポーンイベント
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        clearPlayer(event.getPlayer(), true);
    }

    /**
     * 切断した接続への送信状態を破棄します。
     * @param event 退出イベント
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        clearPlayer(event.getPlayer(), false);
    }

    /** 接続の表示状態を捨て、接続が続く場合だけ破棄パケットを送ります。 */
    private void clearPlayer(Player viewer, boolean sendDestroy) {
        for (Placement placement : placements.values()) {
            if (placement.viewers.remove(viewer) != null && sendDestroy) {
                placement.destroy(viewer);
            }
        }
    }

    /** 配信例外を一度記録し、定期表示や文字案内の更新を継続します。 */
    private void warn(Player viewer, RuntimeException exception) {
        if (!warned) {
            warned = true;
            Logger.log(LogId.W_9020, exception, viewer.getWorld().getName());
        }
    }

    /** 受付の座標に合わせたエンティティ生成パケットを構築します。 */
    private PacketContainer spawnPacket(int entityId, UUID uuid, EntityType type, Location center) {
        PacketContainer spawn = protocol.createPacket(PacketType.Play.Server.SPAWN_ENTITY);
        spawn.getIntegers().write(0, entityId);
        spawn.getUUIDs().write(0, uuid);
        spawn.getEntityTypeModifier().write(0, type);
        spawn.getDoubles().write(0, center.getX()).write(1, center.getY()).write(2, center.getZ());
        return spawn;
    }

    /** Java版のモデルと向きを、生成直後のメタデータとしてまとめます。 */
    private PacketContainer javaSpawn(int entityId, Kind kind, Location center, int yawTenths) {
        ItemStack item = new ItemStack(Material.PAPER);
        item.editMeta(meta -> meta.setItemModel(kind.itemModel));
        var rotation = new Quaternionf().rotationY((float) Math.toRadians(-yawTenths / 10.0D));
        WrappedDataWatcher.Serializer quaternion;
        try {
            quaternion = WrappedDataWatcher.Registry.get((Type) Quaternionf.class);
        } catch (IllegalArgumentException ignored) {
            quaternion = WrappedDataWatcher.Registry.get((Type) Quaternionfc.class);
        }
        var metadata = List.of(
                WrappedDataValue.fromWrappedValue(DISPLAY_LEFT_ROTATION_INDEX, quaternion, rotation),
                WrappedDataValue.fromWrappedValue(ITEM_DISPLAY_ITEM_INDEX,
                        WrappedDataWatcher.Registry.getItemStackSerializer(false), item),
                WrappedDataValue.fromWrappedValue(ITEM_DISPLAY_TRANSFORM_INDEX,
                        WrappedDataWatcher.Registry.get((Type) Byte.class), (byte) ItemDisplay.ItemDisplayTransform.FIXED.ordinal())
        );
        var spawn = spawnPacket(entityId, UUID.randomUUID(), EntityType.ITEM_DISPLAY, center);
        return spawnWithMetadata(entityId, spawn, metadata);
    }

    /** 統合版拡張へ渡す種別と向きを、空の TextDisplay のUUIDへ格納します。 */
    private PacketContainer bedrockSpawn(int entityId, int fallbackId, Kind kind, Location center, int yawTenths) {
        long bits = ((long) kind.bedrockStyle << 32) | ((long) yawTenths << 40);
        var model = spawnPacket(entityId, new UUID(BEDROCK_MARKER, bits | Integer.toUnsignedLong(entityId)),
                EntityType.TEXT_DISPLAY, center);
        var fallback = spawnPacket(fallbackId, new UUID(BEDROCK_MARKER, bits | Integer.toUnsignedLong(fallbackId)),
                EntityType.ITEM, center.clone().add(0, 0.7, 0));
        var serializer = WrappedDataWatcher.Registry.get((Type) Boolean.class);
        var noGravity = new WrappedDataValue(ENTITY_NO_GRAVITY_INDEX, serializer, true);
        var item = WrappedDataValue.fromWrappedValue(DROPPED_ITEM_STACK_INDEX,
                WrappedDataWatcher.Registry.getItemStackSerializer(false), new ItemStack(kind.bedrockFallback));
        return bundle(List.of(model, metadataPacket(entityId, List.of(noGravity)),
                fallback, metadataPacket(fallbackId, List.of(noGravity, item))));
    }

    /** 生成と初期メタデータを同じバンドルで送れるようにまとめます。 */
    private PacketContainer spawnWithMetadata(int entityId, PacketContainer spawn, List<WrappedDataValue> values) {
        return bundle(List.of(spawn, metadataPacket(entityId, values)));
    }

    private PacketContainer metadataPacket(int entityId, List<WrappedDataValue> values) {
        var metadata = protocol.createPacket(PacketType.Play.Server.ENTITY_METADATA);
        metadata.getIntegers().write(0, entityId);
        metadata.getDataValueCollectionModifier().write(0, values);
        return metadata;
    }

    private PacketContainer bundle(List<PacketContainer> packets) {
        var bundle = protocol.createPacket(PacketType.Play.Server.BUNDLE);
        bundle.getPacketBundles().write(0, packets);
        return bundle;
    }

    private enum Edition { JAVA, BEDROCK }

    private record Key(Kind kind, String id) { }

    private final class Placement {
        private final int entityId = PacketEntityIdAllocator.nextEntityId();
        private int fallbackId;
        private final Kind kind;
        private final Location center;
        private final Map<Player, Edition> viewers = new HashMap<>();
        private PacketContainer javaPacket;
        private PacketContainer bedrockPacket;
        private PacketContainer destroyPacket;

        /** @param kind 受付種別
         * @param center 受付座標と向きのスナップショット
         */
        private Placement(Kind kind, Location center) {
            this.kind = kind;
            this.center = center;
        }

        /** まだ表示していない閲覧者だけに版別の生成パケットを送ります。 */
        private void show(Player viewer, Edition edition) {
            if (viewers.containsKey(viewer)) {
                return;
            }
            try {
                int yawTenths = Math.floorMod(Math.round((center.getYaw() % 360.0F) * 10.0F), 3600);
                if (edition == Edition.JAVA) {
                    if (javaPacket == null) javaPacket = javaSpawn(entityId, kind, center, yawTenths);
                    protocol.sendServerPacket(viewer, javaPacket, false);
                } else {
                    if (bedrockPacket == null) {
                        fallbackId = PacketEntityIdAllocator.nextEntityId();
                        destroyPacket = null;
                        bedrockPacket = bedrockSpawn(entityId, fallbackId, kind, center, yawTenths);
                    }
                    protocol.sendServerPacket(viewer, bedrockPacket, false);
                }
                viewers.put(viewer, edition);
            } catch (RuntimeException exception) {
                warn(viewer, exception);
                destroy(viewer);
            }
        }

        /** 閲覧範囲・チャンク・対応版の条件を外れた表示だけを回収します。 */
        private void removeInvisible(Map<Player, Edition> visible) {
            viewers.entrySet().removeIf(entry -> {
                if (visible.get(entry.getKey()) == entry.getValue()) {
                    return false;
                }
                destroy(entry.getKey());
                return true;
            });
        }

        /** この受付を表示していた全接続の表示を解除します。 */
        private void clear() {
            viewers.keySet().forEach(this::destroy);
            viewers.clear();
        }

        /** 接続中の閲覧者へ、この受付のエンティティ破棄パケットを送ります。 */
        private void destroy(Player viewer) {
            if (!viewer.isOnline()) {
                return;
            }
            try {
                if (destroyPacket == null) {
                    destroyPacket = protocol.createPacket(PacketType.Play.Server.ENTITY_DESTROY);
                    destroyPacket.getIntLists().write(0, fallbackId == 0 ? List.of(entityId) : List.of(entityId, fallbackId));
                }
                protocol.sendServerPacket(viewer, destroyPacket, false);
            } catch (RuntimeException exception) {
                warn(viewer, exception);
            }
        }
    }
}
