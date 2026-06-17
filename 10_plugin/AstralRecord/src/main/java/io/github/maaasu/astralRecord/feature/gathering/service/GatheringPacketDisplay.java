package io.github.maaasu.astralRecord.feature.gathering.service;

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
import org.bukkit.entity.Player;
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

final class GatheringPacketDisplay {
    private static final AtomicInteger NEXT_ENTITY_ID = new AtomicInteger(2_600_000);
    private static final float DEFAULT_VIEW_RANGE = 96.0F;
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
    private static final int TEXT_DISPLAY_TEXT_INDEX = 23;
    private static final int TEXT_DISPLAY_LINE_WIDTH_INDEX = 24;
    private static final int TEXT_DISPLAY_BACKGROUND_INDEX = 25;
    private static final int TEXT_DISPLAY_TEXT_OPACITY_INDEX = 26;
    private static final int TEXT_DISPLAY_FLAGS_INDEX = 27;
    private static final byte TEXT_DISPLAY_SHADOWED = 0x01;

    private final ProtocolManager protocolManager = ProtocolLibrary.getProtocolManager();

    PacketEntity block(@NotNull Location location, @NotNull Material material, @NotNull Vector3f scale) {
        List<WrappedDataValue> metadata = baseDisplayMetadata(
                new Vector3f(-scale.x() * 0.5F, 0.0F, -scale.z() * 0.5F),
                scale,
                Display.Billboard.FIXED
        );
        metadata.add(blockValue(material));
        return new PacketEntity(EntityType.BLOCK_DISPLAY, location, metadata);
    }

    PacketEntity text(@NotNull Location location, @NotNull Component text, float scale) {
        List<WrappedDataValue> metadata = baseDisplayMetadata(
                new Vector3f(),
                new Vector3f(scale, scale, scale),
                Display.Billboard.CENTER
        );
        metadata.add(textValue(text));
        metadata.add(value(TEXT_DISPLAY_LINE_WIDTH_INDEX, serializer(Integer.class), 180));
        metadata.add(value(TEXT_DISPLAY_BACKGROUND_INDEX, serializer(Integer.class), 0));
        metadata.add(value(TEXT_DISPLAY_TEXT_OPACITY_INDEX, serializer(Byte.class), (byte) -1));
        metadata.add(value(TEXT_DISPLAY_FLAGS_INDEX, serializer(Byte.class), TEXT_DISPLAY_SHADOWED));
        return new PacketEntity(EntityType.TEXT_DISPLAY, location, metadata);
    }

    void updateText(@NotNull Player player, @NotNull PacketEntity entity, @NotNull Component text) {
        PacketContainer packet = protocolManager.createPacket(PacketType.Play.Server.ENTITY_METADATA);
        packet.getIntegers().writeSafely(0, entity.entityId);
        packet.getDataValueCollectionModifier().writeSafely(0, List.of(textValue(text)));
        send(player, packet);
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

    private WrappedDataValue blockValue(@NotNull Material material) {
        return value(
                BLOCK_DISPLAY_BLOCK_INDEX,
                WrappedDataWatcher.Registry.getBlockDataSerializer(false),
                WrappedBlockData.createData(material)
        );
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
            Logger.log(LogId.W_9000, "gathering_packet:" + packet.getType().name(), player.getWorld().getName(), exception.getClass().getSimpleName());
        }
    }

    final class PacketEntity {
        private final int entityId = NEXT_ENTITY_ID.getAndIncrement();
        private final UUID uuid = UUID.randomUUID();
        private final EntityType entityType;
        private final Location location;
        private final List<WrappedDataValue> metadata;

        private PacketEntity(@NotNull EntityType entityType, @NotNull Location location, @NotNull List<WrappedDataValue> metadata) {
            this.entityType = entityType;
            this.location = location.clone();
            this.metadata = List.copyOf(metadata);
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
