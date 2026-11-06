package io.github.maaasu.astralRecord.feature.teleporter.view;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.wrappers.WrappedChatComponent;
import com.comphenix.protocol.wrappers.WrappedDataValue;
import com.comphenix.protocol.wrappers.WrappedDataWatcher;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import io.github.maaasu.astralRecord.shared.display.PacketEntityIdAllocator;
import io.github.maaasu.astralRecord.shared.display.WorldDisplayModel;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.bukkit.Location;
import org.bukkit.entity.Display;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.joml.Quaternionf;
import org.joml.Quaternionfc;
import org.joml.Vector3f;
import org.joml.Vector3fc;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * ウェイストーン用 packet-only Display entity を送信します。
 */
final class WaystonePacketDisplay {
    private static final float DEFAULT_VIEW_RANGE = 96.0F;
    private static final int NO_GRAVITY_INDEX = 5;
    private static final int DISPLAY_INTERPOLATION_START_INDEX = 8;
    private static final int DISPLAY_INTERPOLATION_DURATION_INDEX = 9;
    private static final int DISPLAY_POSITION_ROTATION_DURATION_INDEX = 10;
    private static final int DISPLAY_TRANSLATION_INDEX = 11;
    private static final int DISPLAY_SCALE_INDEX = 12;
    private static final int DISPLAY_LEFT_ROTATION_INDEX = 13;
    private static final int DISPLAY_RIGHT_ROTATION_INDEX = 14;
    private static final int DISPLAY_BILLBOARD_INDEX = 15;
    private static final int DISPLAY_VIEW_RANGE_INDEX = 17;
    private static final int DISPLAY_WIDTH_INDEX = 20;
    private static final int DISPLAY_HEIGHT_INDEX = 21;
    private static final int ITEM_DISPLAY_ITEM_INDEX = 23;
    private static final int ITEM_DISPLAY_TRANSFORM_INDEX = 24;
    private static final int TEXT_DISPLAY_TEXT_INDEX = 23;
    private static final int TEXT_DISPLAY_LINE_WIDTH_INDEX = 24;
    private static final int TEXT_DISPLAY_BACKGROUND_INDEX = 25;
    private static final int TEXT_DISPLAY_TEXT_OPACITY_INDEX = 26;
    private static final int TEXT_DISPLAY_FLAGS_INDEX = 27;
    private static final byte TEXT_DISPLAY_SHADOWED = 0x01;

    private final ProtocolManager protocolManager = ProtocolLibrary.getProtocolManager();

    /**
     * @param origin 石碑の足元の座標
     * @param model 閲覧者から見た解除状態に対応するモデル
     * @param bedrock 統合版向けのモデル生成マーカーを使う場合は true
     * @return 対象エディション用の本体表示
     */
    PacketEntity model(@NotNull Location origin, @NotNull WorldDisplayModel model, boolean bedrock) {
        if (bedrock) {
            // 本文を送ると Geyser が行数に応じて座標を補正するため、空の実体で送信する。
            return new PacketEntity(EntityType.TEXT_DISPLAY, origin,
                    List.of(value(NO_GRAVITY_INDEX, serializer(Boolean.class), true)), model);
        }
        return item(origin.clone().add(0.0D, 0.5D, 0.0D), model.createItem(), 1.0F,
                Display.Billboard.FIXED, ItemDisplay.ItemDisplayTransform.NONE);
    }

    PacketEntity text(@NotNull Location location, @NotNull Component text, float scale) {
        List<WrappedDataValue> metadata = baseDisplayMetadata(new Vector3f(), new Vector3f(scale, scale, scale), Display.Billboard.CENTER);
        metadata.add(textValue(text));
        metadata.add(value(TEXT_DISPLAY_LINE_WIDTH_INDEX, serializer(Integer.class), 240));
        metadata.add(value(TEXT_DISPLAY_BACKGROUND_INDEX, serializer(Integer.class), 0));
        metadata.add(value(TEXT_DISPLAY_TEXT_OPACITY_INDEX, serializer(Byte.class), (byte) -1));
        metadata.add(value(TEXT_DISPLAY_FLAGS_INDEX, serializer(Byte.class), TEXT_DISPLAY_SHADOWED));
        return new PacketEntity(EntityType.TEXT_DISPLAY, location, metadata);
    }

    /**
     * ドロップアイテム風の packet-only ItemDisplay を作成します。
     *
     * @param location 表示座標
     * @param itemStack 表示アイテム
     * @param scale 表示倍率
     * @return 送信前の packet entity
     */
    PacketEntity item(@NotNull Location location, @NotNull ItemStack itemStack, float scale) {
        return item(location, itemStack, scale, Display.Billboard.CENTER, ItemDisplay.ItemDisplayTransform.GROUND);
    }

    /**
     * @param location 描画の原点
     * @param itemStack 表示するアイテム
     * @param scale 描画倍率
     * @param billboard カメラに対する回転方式
     * @param transform 選択するアイテム表示姿勢
     * @return 送信前のアイテム表示
     */
    private PacketEntity item(
            @NotNull Location location,
            @NotNull ItemStack itemStack,
            float scale,
            @NotNull Display.Billboard billboard,
            @NotNull ItemDisplay.ItemDisplayTransform transform
    ) {
        List<WrappedDataValue> metadata = baseDisplayMetadata(
                new Vector3f(),
                new Vector3f(scale, scale, scale),
                billboard
        );
        metadata.add(value(
                ITEM_DISPLAY_ITEM_INDEX,
                WrappedDataWatcher.Registry.getItemStackSerializer(false),
                itemStack
        ));
        metadata.add(value(
                ITEM_DISPLAY_TRANSFORM_INDEX,
                serializer(Byte.class),
                (byte) transform.ordinal()
        ));
        return new PacketEntity(EntityType.ITEM_DISPLAY, location, metadata);
    }

    private List<WrappedDataValue> baseDisplayMetadata(
            @NotNull Vector3f translation,
            @NotNull Vector3f scale,
            @NotNull Display.Billboard billboard
    ) {
        List<WrappedDataValue> values = new ArrayList<>();
        values.add(value(DISPLAY_INTERPOLATION_START_INDEX, serializer(Integer.class), 0));
        values.add(value(DISPLAY_INTERPOLATION_DURATION_INDEX, serializer(Integer.class), 0));
        values.add(value(DISPLAY_POSITION_ROTATION_DURATION_INDEX, serializer(Integer.class), 0));
        values.add(value(DISPLAY_TRANSLATION_INDEX, vectorSerializer(), translation));
        values.add(value(DISPLAY_SCALE_INDEX, vectorSerializer(), scale));
        values.add(value(DISPLAY_LEFT_ROTATION_INDEX, quaternionSerializer(), new Quaternionf()));
        values.add(value(DISPLAY_RIGHT_ROTATION_INDEX, quaternionSerializer(), new Quaternionf()));
        values.add(value(DISPLAY_BILLBOARD_INDEX, serializer(Byte.class), (byte) billboard.ordinal()));
        values.add(value(DISPLAY_VIEW_RANGE_INDEX, serializer(Float.class), DEFAULT_VIEW_RANGE));
        values.add(value(DISPLAY_WIDTH_INDEX, serializer(Float.class), 0.0F));
        values.add(value(DISPLAY_HEIGHT_INDEX, serializer(Float.class), 0.0F));
        return values;
    }

    private WrappedDataValue textValue(@NotNull Component text) {
        return value(
                TEXT_DISPLAY_TEXT_INDEX,
                WrappedDataWatcher.Registry.getChatComponentSerializer(false),
                WrappedChatComponent.fromJson(GsonComponentSerializer.gson().serialize(text))
        );
    }

    private WrappedDataValue value(int index, WrappedDataWatcher.Serializer serializer, Object value) {
        return WrappedDataValue.fromWrappedValue(index, serializer, value);
    }

    private WrappedDataWatcher.Serializer serializer(Type type) {
        return WrappedDataWatcher.Registry.get(type);
    }

    private WrappedDataWatcher.Serializer quaternionSerializer() {
        try {
            return serializer(Quaternionf.class);
        } catch (IllegalArgumentException ignored) {
            return serializer(Quaternionfc.class);
        }
    }

    private WrappedDataWatcher.Serializer vectorSerializer() {
        try {
            return serializer(Vector3f.class);
        } catch (IllegalArgumentException ignored) {
            return serializer(Vector3fc.class);
        }
    }

    private void send(@NotNull Player player, @NotNull PacketContainer packet) {
        try {
            protocolManager.sendServerPacket(player, packet);
        } catch (RuntimeException exception) {
            Logger.log(LogId.W_9000, "waystone_packet:" + packet.getType().name(), player.getWorld().getName(), exception.getClass().getSimpleName());
        }
    }

    final class PacketEntity {
        private final int entityId = PacketEntityIdAllocator.nextEntityId();
        private final UUID uuid;
        private final EntityType entityType;
        private final Location location;
        private final List<WrappedDataValue> metadata;

        /**
         * @param entityType 生成する表示の種別
         * @param location 表示座標
         * @param metadata 生成直後のメタデータ
         */
        private PacketEntity(@NotNull EntityType entityType, @NotNull Location location, @NotNull List<WrappedDataValue> metadata) {
            this(entityType, location, metadata, null);
        }

        /**
         * @param entityType 生成する表示の種別
         * @param location 表示座標
         * @param metadata 生成直後のメタデータ
         * @param model UUID に載せる統合版モデルの種別。通常の表示では null
         */
        private PacketEntity(EntityType entityType, Location location, List<WrappedDataValue> metadata, WorldDisplayModel model) {
            this.entityType = entityType;
            this.location = location.clone();
            this.metadata = List.copyOf(metadata);
            this.uuid = model == null ? UUID.randomUUID() : model.bedrockMarker(entityId, false);
        }

        void spawn(@NotNull Player player) {
            PacketContainer spawn = protocolManager.createPacket(PacketType.Play.Server.SPAWN_ENTITY);
            spawn.getIntegers().writeSafely(0, entityId);
            spawn.getUUIDs().writeSafely(0, uuid);
            spawn.getEntityTypeModifier().writeSafely(0, entityType);
            spawn.getDoubles().writeSafely(0, location.getX());
            spawn.getDoubles().writeSafely(1, location.getY());
            spawn.getDoubles().writeSafely(2, location.getZ());
            send(player, spawn);

            PacketContainer metadataPacket = protocolManager.createPacket(PacketType.Play.Server.ENTITY_METADATA);
            metadataPacket.getIntegers().writeSafely(0, entityId);
            metadataPacket.getDataValueCollectionModifier().writeSafely(0, metadata);
            send(player, metadataPacket);
        }

        void destroy(@NotNull Player player) {
            PacketContainer destroy = protocolManager.createPacket(PacketType.Play.Server.ENTITY_DESTROY);
            destroy.getIntLists().writeSafely(0, List.of(entityId));
            destroy.getIntegerArrays().writeSafely(0, new int[]{entityId});
            send(player, destroy);
        }
    }
}
