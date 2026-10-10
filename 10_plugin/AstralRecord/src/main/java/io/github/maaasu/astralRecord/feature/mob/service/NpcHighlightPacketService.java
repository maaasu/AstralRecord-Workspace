package io.github.maaasu.astralRecord.feature.mob.service;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.wrappers.EnumWrappers;
import com.comphenix.protocol.wrappers.Pair;
import com.comphenix.protocol.wrappers.WrappedAttribute;
import com.comphenix.protocol.wrappers.WrappedDataValue;
import com.comphenix.protocol.wrappers.WrappedDataWatcher;
import io.github.maaasu.astralRecord.feature.mob.model.MobInstance;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import io.github.maaasu.astralRecord.shared.display.PacketEntityIdAllocator;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * NPC と同じ型・表示 metadata の発光輪郭を、一人のクライアントへだけ送ります。
 */
final class NpcHighlightPacketService {
    private static final byte ENTITY_FLAG_INVISIBLE = (byte) 0x20;
    private static final byte ENTITY_FLAG_GLOWING = (byte) 0x40;

    private final Plugin plugin;
    private final Map<Integer, HighlightTarget> targetsByFakeEntityId = new ConcurrentHashMap<>();
    private @Nullable ProtocolManager protocolManager;
    private boolean interactionRemapperRegistered;

    /**
     * packet 複製サービスを生成します。
     *
     * @param plugin packet listener の登録元プラグイン
     */
    NpcHighlightPacketService(@NotNull Plugin plugin) {
        this.plugin = plugin;
    }

    /**
     * 現在の表示実体を一度だけ複製し、専用 packet entity の輪郭を表示します。
     *
     * @param viewer 表示対象。メインスレッド上のオンラインプレイヤー
     * @param instance 実体が有効な NPC インスタンス
     * @return この複製だけを除去する冪等な処理。送信できない場合は {@code null}
     */
    @Nullable Runnable show(@NotNull Player viewer, @NotNull MobInstance instance) {
        ProtocolManager manager = ensureProtocolManager();
        if (manager == null || !viewer.isOnline()) {
            return null;
        }
        UUID sourceId = instance.displayEntityId() == null
                ? instance.bukkitEntityId() : instance.displayEntityId();
        Entity source = sourceId == null ? null : Bukkit.getEntity(sourceId);
        if (source == null || !source.isValid() || source.getWorld() != viewer.getWorld() || instance.entityId() < 0) {
            return null;
        }

        int fakeEntityId = PacketEntityIdAllocator.nextEntityId();
        UUID fakeUuid = UUID.randomUUID();
        targetsByFakeEntityId.put(fakeEntityId, new HighlightTarget(viewer.getUniqueId(), instance.entityId()));
        try {
            List<WrappedDataValue> metadata = glowingOverlayMetadata(source);
            Location location = source.getLocation();
            if (!send(viewer, spawnPacket(manager, fakeEntityId, fakeUuid, source, location))
                    || !send(viewer, metadataPacket(manager, fakeEntityId, metadata))
                    || !sendScale(viewer, manager, fakeEntityId, source)
                    || !sendEquipment(viewer, manager, fakeEntityId, source)
                    || !send(viewer, headRotationPacket(manager, fakeEntityId, location.getYaw()))) {
                remove(viewer, fakeEntityId);
                return null;
            }
        } catch (RuntimeException exception) {
            Logger.log(LogId.W_5707, exception, PacketType.Play.Server.SPAWN_ENTITY.name(),
                    viewer.getWorld().getName());
            remove(viewer, fakeEntityId);
            return null;
        }

        AtomicBoolean removed = new AtomicBoolean();
        return () -> {
            if (removed.compareAndSet(false, true)) {
                remove(viewer, fakeEntityId);
            }
        };
    }

    /**
     * ProtocolLib を解決し、偽 entity への操作を実体へ転送する listener を一度だけ登録します。
     *
     * @return 利用可能な packet manager。ProtocolLib が無効なら {@code null}
     */
    private @Nullable ProtocolManager ensureProtocolManager() {
        if (protocolManager == null) {
            if (!Bukkit.getPluginManager().isPluginEnabled("ProtocolLib")) {
                return null;
            }
            try {
                protocolManager = ProtocolLibrary.getProtocolManager();
            } catch (RuntimeException exception) {
                return null;
            }
        }
        if (!interactionRemapperRegistered) {
            protocolManager.addPacketListener(new PacketAdapter(
                    plugin, ListenerPriority.NORMAL, PacketType.Play.Client.USE_ENTITY
            ) {
                @Override
                public void onPacketReceiving(@NotNull PacketEvent event) {
                    Integer fakeEntityId = event.getPacket().getIntegers().readSafely(0);
                    HighlightTarget target = fakeEntityId == null ? null : targetsByFakeEntityId.get(fakeEntityId);
                    if (target != null && target.viewerId().equals(event.getPlayer().getUniqueId())) {
                        event.getPacket().getIntegers().write(0, target.realEntityId());
                    }
                }
            });
            interactionRemapperRegistered = true;
        }
        return protocolManager;
    }

    /**
     * 実体の全表示 metadata を複製し、輪郭専用の不可視・発光フラグだけを変更します。
     *
     * @param source 表示元の実体
     * @return 元の watcher を変更しない新規 metadata 値リスト
     */
    private @NotNull List<WrappedDataValue> glowingOverlayMetadata(@NotNull Entity source) {
        List<WrappedDataValue> values = new ArrayList<>(
                WrappedDataWatcher.getEntityWatcher(source).toDataValueCollection()
        );
        for (WrappedDataValue value : values) {
            if (value.getIndex() == 0 && value.getValue() instanceof Byte flags) {
                value.setValue((byte) (flags | ENTITY_FLAG_INVISIBLE | ENTITY_FLAG_GLOWING));
                return values;
            }
        }
        values.add(WrappedDataValue.fromWrappedValue(
                0, WrappedDataWatcher.Registry.get((Type) Byte.class),
                (byte) (ENTITY_FLAG_INVISIBLE | ENTITY_FLAG_GLOWING)
        ));
        return values;
    }

    /**
     * 実体と同じ型・位置・回転の packet entity 生成パケットを構築します。
     *
     * @param manager packet manager
     * @param fakeEntityId 専用 entity ID
     * @param fakeUuid 専用 UUID
     * @param source 表示元の実体
     * @param location クリック時点の位置
     * @return 生成パケット
     */
    private @NotNull PacketContainer spawnPacket(
            @NotNull ProtocolManager manager, int fakeEntityId, @NotNull UUID fakeUuid,
            @NotNull Entity source, @NotNull Location location
    ) {
        PacketContainer packet = manager.createPacket(PacketType.Play.Server.SPAWN_ENTITY);
        packet.getIntegers().writeSafely(0, fakeEntityId);
        packet.getUUIDs().writeSafely(0, fakeUuid);
        packet.getEntityTypeModifier().writeSafely(0, source.getType());
        packet.getDoubles().writeSafely(0, location.getX());
        packet.getDoubles().writeSafely(1, location.getY());
        packet.getDoubles().writeSafely(2, location.getZ());
        packet.getBytes().writeSafely(0, angleToByte(location.getPitch()));
        packet.getBytes().writeSafely(1, angleToByte(location.getYaw()));
        packet.getBytes().writeSafely(2, angleToByte(location.getYaw()));
        return packet;
    }

    /**
     * 複製済み metadata を専用 entity ID へ送るパケットを構築します。
     *
     * @param manager packet manager
     * @param fakeEntityId 専用 entity ID
     * @param values 複製済み metadata
     * @return metadata パケット
     */
    private @NotNull PacketContainer metadataPacket(
            @NotNull ProtocolManager manager, int fakeEntityId, @NotNull List<WrappedDataValue> values
    ) {
        PacketContainer packet = manager.createPacket(PacketType.Play.Server.ENTITY_METADATA);
        packet.getIntegers().write(0, fakeEntityId);
        packet.getDataValueCollectionModifier().write(0, values);
        return packet;
    }

    /**
     * LivingEntity の実効 scale が既定値と異なる場合だけ複製します。
     *
     * @param viewer 表示対象
     * @param manager packet manager
     * @param fakeEntityId 専用 entity ID
     * @param source 表示元の実体
     * @return 送信不要または送信成功なら {@code true}
     */
    private boolean sendScale(
            @NotNull Player viewer, @NotNull ProtocolManager manager, int fakeEntityId, @NotNull Entity source
    ) {
        if (!(source instanceof LivingEntity living)) {
            return true;
        }
        AttributeInstance scale = living.getAttribute(Attribute.SCALE);
        if (scale == null || scale.getValue() == 1.0D) {
            return true;
        }
        PacketContainer packet = manager.createPacket(PacketType.Play.Server.UPDATE_ATTRIBUTES);
        packet.getIntegers().write(0, fakeEntityId);
        WrappedAttribute attribute = WrappedAttribute.newBuilder()
                .attributeKey(Attribute.SCALE.getKey().toString())
                .baseValue(scale.getValue())
                .build();
        packet.getAttributeCollectionModifier().write(0, List.of(attribute));
        return send(viewer, packet);
    }

    /**
     * LivingEntity の手持ち・防具を一括 packet で複製します。
     *
     * @param viewer 表示対象
     * @param manager packet manager
     * @param fakeEntityId 専用 entity ID
     * @param source 表示元の実体
     * @return 送信不要または送信成功なら {@code true}
     */
    private boolean sendEquipment(
            @NotNull Player viewer, @NotNull ProtocolManager manager, int fakeEntityId, @NotNull Entity source
    ) {
        if (!(source instanceof LivingEntity living)) {
            return true;
        }
        EntityEquipment equipment = living.getEquipment();
        if (equipment == null) {
            return true;
        }
        List<Pair<EnumWrappers.ItemSlot, ItemStack>> slots = new ArrayList<>();
        addEquipment(slots, EnumWrappers.ItemSlot.MAINHAND, equipment.getItemInMainHand());
        addEquipment(slots, EnumWrappers.ItemSlot.OFFHAND, equipment.getItemInOffHand());
        addEquipment(slots, EnumWrappers.ItemSlot.FEET, equipment.getBoots());
        addEquipment(slots, EnumWrappers.ItemSlot.LEGS, equipment.getLeggings());
        addEquipment(slots, EnumWrappers.ItemSlot.CHEST, equipment.getChestplate());
        addEquipment(slots, EnumWrappers.ItemSlot.HEAD, equipment.getHelmet());
        if (slots.isEmpty()) {
            return true;
        }
        PacketContainer packet = manager.createPacket(PacketType.Play.Server.ENTITY_EQUIPMENT);
        packet.getIntegers().write(0, fakeEntityId);
        packet.getSlotStackPairLists().write(0, slots);
        return send(viewer, packet);
    }

    /**
     * 空でない装備だけを packet 用のスロット一覧へ複製します。
     *
     * @param slots 追加先のスロット一覧
     * @param slot 装備スロット
     * @param item 実体の装備。空なら追加しない
     */
    private void addEquipment(
            @NotNull List<Pair<EnumWrappers.ItemSlot, ItemStack>> slots,
            @NotNull EnumWrappers.ItemSlot slot, @Nullable ItemStack item
    ) {
        if (item != null && !item.getType().isAir()) {
            slots.add(new Pair<>(slot, item.clone()));
        }
    }

    /**
     * 複製 entity の頭部回転パケットを構築します。
     *
     * @param manager packet manager
     * @param fakeEntityId 専用 entity ID
     * @param yaw 頭部の向き
     * @return 頭部回転パケット
     */
    private @NotNull PacketContainer headRotationPacket(
            @NotNull ProtocolManager manager, int fakeEntityId, float yaw
    ) {
        PacketContainer packet = manager.createPacket(PacketType.Play.Server.ENTITY_HEAD_ROTATION);
        packet.getIntegers().write(0, fakeEntityId);
        packet.getBytes().write(0, angleToByte(yaw));
        return packet;
    }

    /**
     * 操作転送を解除し、オンラインの表示対象へ破棄 packet を送ります。
     *
     * @param viewer 表示対象
     * @param fakeEntityId 削除する専用 entity ID
     */
    private void remove(@NotNull Player viewer, int fakeEntityId) {
        targetsByFakeEntityId.remove(fakeEntityId);
        ProtocolManager manager = protocolManager;
        if (manager == null || !viewer.isOnline()) {
            return;
        }
        try {
            PacketContainer packet = manager.createPacket(PacketType.Play.Server.ENTITY_DESTROY);
            packet.getIntLists().write(0, List.of(fakeEntityId));
            send(viewer, packet);
        } catch (RuntimeException exception) {
            Logger.log(LogId.W_5707, exception, PacketType.Play.Server.ENTITY_DESTROY.name(),
                    viewer.getWorld().getName());
        }
    }

    /**
     * 一人の表示対象へ packet を送信し、失敗をログへ残します。
     *
     * @param viewer 表示対象
     * @param packet 送信する packet
     * @return 送信成功なら {@code true}
     */
    private boolean send(@NotNull Player viewer, @NotNull PacketContainer packet) {
        try {
            protocolManager.sendServerPacket(viewer, packet);
            return true;
        } catch (RuntimeException exception) {
            Logger.log(LogId.W_5707, exception, packet.getType().name(), viewer.getWorld().getName());
            return false;
        }
    }

    /**
     * 度数法の回転角を packet の1バイト角度へ変換します。
     *
     * @param angle 度数法の角度
     * @return packet の角度値
     */
    private byte angleToByte(float angle) {
        return (byte) Math.floorMod(Math.round(angle * 256.0F / 360.0F), 256);
    }

    private record HighlightTarget(@NotNull UUID viewerId, int realEntityId) {
    }
}
