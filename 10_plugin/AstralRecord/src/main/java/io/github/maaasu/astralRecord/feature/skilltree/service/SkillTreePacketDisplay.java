package io.github.maaasu.astralRecord.feature.skilltree.service;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.wrappers.WrappedBlockData;
import com.comphenix.protocol.wrappers.WrappedChatComponent;
import com.comphenix.protocol.wrappers.WrappedDataValue;
import com.comphenix.protocol.wrappers.WrappedDataWatcher;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Display;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.joml.Quaternionf;
import org.joml.Quaternionfc;
import org.joml.Vector3f;
import org.joml.Vector3fc;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * スキルツリーワールド用の packet-only Display entity 送信を扱います。
 */
final class SkillTreePacketDisplay {
    private static final AtomicInteger NEXT_ENTITY_ID = new AtomicInteger(2_000_000);
    private static final float DEFAULT_VIEW_RANGE = 96.0F;
    private static final int ENTITY_SHARED_FLAGS_INDEX = 0;
    private static final byte ENTITY_FLAG_GLOWING = 0x40;
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
    private static final int BLOCK_DISPLAY_BLOCK_INDEX = 23;
    private static final int ITEM_DISPLAY_ITEM_INDEX = 23;
    private static final int ITEM_DISPLAY_TRANSFORM_INDEX = 24;
    private static final int TEXT_DISPLAY_TEXT_INDEX = 23;
    private static final int TEXT_DISPLAY_LINE_WIDTH_INDEX = 24;
    private static final int TEXT_DISPLAY_BACKGROUND_INDEX = 25;
    private static final int TEXT_DISPLAY_TEXT_OPACITY_INDEX = 26;
    private static final int TEXT_DISPLAY_FLAGS_INDEX = 27;
    private static final byte TEXT_DISPLAY_SHADOW_AND_SEE_THROUGH = 0x03;

    private final Plugin plugin;
    private final ProtocolManager protocolManager;

    SkillTreePacketDisplay(@NotNull Plugin plugin) {
        this.plugin = plugin;
        this.protocolManager = ProtocolLibrary.getProtocolManager();
    }

    PacketEntity item(
            Location location,
            ItemStack itemStack,
            float scale,
            ItemDisplay.ItemDisplayTransform displayTransform,
            boolean glowing
    ) {
        List<WrappedDataValue> metadata = baseDisplayMetadata(
                new Vector3f(),
                new Vector3f(scale, scale, scale),
                new Quaternionf(),
                Display.Billboard.CENTER
        );
        if (glowing) {
            metadata.add(value(
                    ENTITY_SHARED_FLAGS_INDEX,
                    serializer(Byte.class),
                    ENTITY_FLAG_GLOWING
            ));
        }
        metadata.add(value(
                ITEM_DISPLAY_ITEM_INDEX,
                WrappedDataWatcher.Registry.getItemStackSerializer(false),
                itemStack
        ));
        metadata.add(value(
                ITEM_DISPLAY_TRANSFORM_INDEX,
                serializer(Byte.class),
                (byte) displayTransform.ordinal()
        ));
        return new PacketEntity(EntityType.ITEM_DISPLAY, location, metadata);
    }

    PacketEntity text(Location location, Component text, float scale) {
        List<WrappedDataValue> metadata = baseDisplayMetadata(
                new Vector3f(),
                new Vector3f(scale, scale, scale),
                new Quaternionf(),
                Display.Billboard.CENTER
        );
        metadata.add(value(
                TEXT_DISPLAY_TEXT_INDEX,
                WrappedDataWatcher.Registry.getChatComponentSerializer(false),
                WrappedChatComponent.fromJson(GsonComponentSerializer.gson().serialize(text))
        ));
        metadata.add(value(TEXT_DISPLAY_LINE_WIDTH_INDEX, serializer(Integer.class), 160));
        metadata.add(value(TEXT_DISPLAY_BACKGROUND_INDEX, serializer(Integer.class), 0));
        metadata.add(value(TEXT_DISPLAY_TEXT_OPACITY_INDEX, serializer(Byte.class), (byte) -1));
        metadata.add(value(TEXT_DISPLAY_FLAGS_INDEX, serializer(Byte.class), TEXT_DISPLAY_SHADOW_AND_SEE_THROUGH));
        return new PacketEntity(EntityType.TEXT_DISPLAY, location, metadata);
    }

    PacketEntity block(Location location, Material material, EdgeTransform transform) {
        List<WrappedDataValue> metadata = baseDisplayMetadata(
                transform.translation(),
                transform.scale(),
                transform.rotation(),
                Display.Billboard.FIXED
        );
        metadata.add(blockValue(material));
        return new PacketEntity(EntityType.BLOCK_DISPLAY, location, metadata);
    }

    void moveBlock(PacketEntity entity, Location location, Material material, EdgeTransform transform) {
        List<WrappedDataValue> metadata = baseDisplayMetadata(
                transform.translation(),
                transform.scale(),
                transform.rotation(),
                Display.Billboard.FIXED
        );
        metadata.add(blockValue(material));
        entity.move(location, metadata);
    }

    void updateBlock(Player player, PacketEntity entity, Material material) {
        PacketContainer packet = protocolManager.createPacket(PacketType.Play.Server.ENTITY_METADATA);
        packet.getIntegers().writeSafely(0, entity.entityId);
        packet.getDataValueCollectionModifier().writeSafely(0, List.of(blockValue(material)));
        send(player, packet);
    }

    private List<WrappedDataValue> baseDisplayMetadata(
            Vector3f translation,
            Vector3f scale,
            Quaternionf leftRotation,
            Display.Billboard billboard
    ) {
        List<WrappedDataValue> values = new ArrayList<>();
        values.add(value(DISPLAY_INTERPOLATION_START_INDEX, serializer(Integer.class), 0));
        values.add(value(DISPLAY_INTERPOLATION_DURATION_INDEX, serializer(Integer.class), 0));
        values.add(value(DISPLAY_POSITION_ROTATION_DURATION_INDEX, serializer(Integer.class), 0));
        values.add(value(DISPLAY_TRANSLATION_INDEX, vectorSerializer(), translation));
        values.add(value(DISPLAY_SCALE_INDEX, vectorSerializer(), scale));
        values.add(value(DISPLAY_LEFT_ROTATION_INDEX, quaternionSerializer(), leftRotation));
        values.add(value(DISPLAY_RIGHT_ROTATION_INDEX, quaternionSerializer(), new Quaternionf()));
        values.add(value(DISPLAY_BILLBOARD_INDEX, serializer(Byte.class), (byte) billboard.ordinal()));
        values.add(value(DISPLAY_VIEW_RANGE_INDEX, serializer(Float.class), DEFAULT_VIEW_RANGE));
        values.add(value(DISPLAY_WIDTH_INDEX, serializer(Float.class), 0.0F));
        values.add(value(DISPLAY_HEIGHT_INDEX, serializer(Float.class), 0.0F));
        return values;
    }

    private WrappedDataValue blockValue(Material material) {
        return value(
                BLOCK_DISPLAY_BLOCK_INDEX,
                WrappedDataWatcher.Registry.getBlockDataSerializer(false),
                WrappedBlockData.createData(material)
        );
    }

    private WrappedDataValue value(
            int index,
            WrappedDataWatcher.Serializer serializer,
            Object value
    ) {
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

    private void send(Player player, PacketContainer packet) {
        try {
            protocolManager.sendServerPacket(player, packet);
        } catch (RuntimeException exception) {
            Logger.log(LogId.W_9000, "packet:" + packet.getType().name(), player.getWorld().getName(), exception.getClass().getSimpleName());
        }
    }

    record EdgeTransform(Vector3f translation, Vector3f scale, Quaternionf rotation) {
    }

    final class PacketEntity {
        private final int entityId = NEXT_ENTITY_ID.getAndIncrement();
        private final UUID uuid = UUID.randomUUID();
        private final EntityType entityType;
        private Location location;
        private List<WrappedDataValue> metadata;

        private PacketEntity(EntityType entityType, Location location, List<WrappedDataValue> metadata) {
            this.entityType = entityType;
            this.location = location.clone();
            this.metadata = List.copyOf(metadata);
        }

        void move(Location location, List<WrappedDataValue> metadata) {
            this.location = location.clone();
            this.metadata = List.copyOf(metadata);
        }

        void move(Location location) {
            this.location = location.clone();
        }

        void spawn(Player player) {
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

        void destroy(Player player) {
            PacketContainer destroy = protocolManager.createPacket(PacketType.Play.Server.ENTITY_DESTROY);
            destroy.getIntLists().writeSafely(0, List.of(entityId));
            destroy.getIntegerArrays().writeSafely(0, new int[]{entityId});
            send(player, destroy);
        }
    }
}
