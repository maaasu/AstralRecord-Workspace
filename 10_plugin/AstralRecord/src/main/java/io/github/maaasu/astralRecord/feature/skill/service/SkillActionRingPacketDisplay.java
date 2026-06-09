package io.github.maaasu.astralRecord.feature.skill.service;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.wrappers.WrappedChatComponent;
import com.comphenix.protocol.wrappers.WrappedDataValue;
import com.comphenix.protocol.wrappers.WrappedDataWatcher;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.bukkit.Location;
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

final class SkillActionRingPacketDisplay {
    private static final AtomicInteger NEXT_ENTITY_ID = new AtomicInteger(2_200_000);
    private static final byte ENTITY_FLAG_GLOWING = 0x40;
    private static final int ENTITY_FLAGS_INDEX = 0;
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
    private static final byte TEXT_DISPLAY_BILLBOARD_CENTER = 3;
    private static final byte TEXT_DISPLAY_SHADOW_AND_SEE_THROUGH = 0x03;
    private static final float DEFAULT_VIEW_RANGE = 16.0F;

    private final ProtocolManager protocolManager;

    SkillActionRingPacketDisplay(@NotNull Plugin plugin) {
        this.protocolManager = ProtocolLibrary.getProtocolManager();
    }

    PacketEntity item(@NotNull Location location, @NotNull ItemStack itemStack, boolean glowing) {
        return new PacketEntity(EntityType.ITEM_DISPLAY, location, itemMetadata(itemStack, glowing));
    }

    PacketEntity text(@NotNull Location location, @NotNull Component text, float scale) {
        return new PacketEntity(EntityType.TEXT_DISPLAY, location, textMetadata(text, scale));
    }

    void updateItem(@NotNull Player player, @NotNull PacketEntity entity, @NotNull ItemStack itemStack, boolean glowing) {
        entity.updateMetadata(player, itemMetadata(itemStack, glowing));
    }

    void updateText(@NotNull Player player, @NotNull PacketEntity entity, @NotNull Component text, float scale) {
        entity.updateMetadata(player, textMetadata(text, scale));
    }

    private List<WrappedDataValue> itemMetadata(@NotNull ItemStack itemStack, boolean glowing) {
        List<WrappedDataValue> values = baseDisplayMetadata(new Vector3f(0.0F, -0.10F, 0.0F), new Vector3f(0.55F, 0.55F, 0.55F));
        values.add(value(ENTITY_FLAGS_INDEX, serializer(Byte.class), glowing ? ENTITY_FLAG_GLOWING : (byte) 0));
        values.add(value(ITEM_DISPLAY_ITEM_INDEX, WrappedDataWatcher.Registry.getItemStackSerializer(false), itemStack));
        values.add(value(ITEM_DISPLAY_TRANSFORM_INDEX, serializer(Byte.class), (byte) ItemDisplay.ItemDisplayTransform.GUI.ordinal()));
        return values;
    }

    private List<WrappedDataValue> textMetadata(@NotNull Component text, float scale) {
        List<WrappedDataValue> values = baseDisplayMetadata(new Vector3f(), new Vector3f(scale, scale, scale));
        values.add(value(DISPLAY_BILLBOARD_INDEX, serializer(Byte.class), TEXT_DISPLAY_BILLBOARD_CENTER));
        values.add(value(
            TEXT_DISPLAY_TEXT_INDEX,
            WrappedDataWatcher.Registry.getChatComponentSerializer(false),
            WrappedChatComponent.fromJson(GsonComponentSerializer.gson().serialize(text))
        ));
        values.add(value(TEXT_DISPLAY_LINE_WIDTH_INDEX, serializer(Integer.class), 180));
        values.add(value(TEXT_DISPLAY_BACKGROUND_INDEX, serializer(Integer.class), 0));
        values.add(value(TEXT_DISPLAY_TEXT_OPACITY_INDEX, serializer(Byte.class), (byte) -1));
        values.add(value(TEXT_DISPLAY_FLAGS_INDEX, serializer(Byte.class), TEXT_DISPLAY_SHADOW_AND_SEE_THROUGH));
        return values;
    }

    private List<WrappedDataValue> baseDisplayMetadata(@NotNull Vector3f translation, @NotNull Vector3f scale) {
        List<WrappedDataValue> values = new ArrayList<>();
        values.add(value(DISPLAY_INTERPOLATION_START_INDEX, serializer(Integer.class), 0));
        values.add(value(DISPLAY_INTERPOLATION_DURATION_INDEX, serializer(Integer.class), 0));
        values.add(value(DISPLAY_POSITION_ROTATION_DURATION_INDEX, serializer(Integer.class), 0));
        values.add(value(DISPLAY_TRANSLATION_INDEX, vectorSerializer(), translation));
        values.add(value(DISPLAY_SCALE_INDEX, vectorSerializer(), scale));
        values.add(value(DISPLAY_LEFT_ROTATION_INDEX, quaternionSerializer(), new Quaternionf()));
        values.add(value(DISPLAY_RIGHT_ROTATION_INDEX, quaternionSerializer(), new Quaternionf()));
        values.add(value(DISPLAY_BILLBOARD_INDEX, serializer(Byte.class), (byte) Display.Billboard.CENTER.ordinal()));
        values.add(value(DISPLAY_VIEW_RANGE_INDEX, serializer(Float.class), DEFAULT_VIEW_RANGE));
        values.add(value(DISPLAY_WIDTH_INDEX, serializer(Float.class), 0.0F));
        values.add(value(DISPLAY_HEIGHT_INDEX, serializer(Float.class), 0.0F));
        return values;
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
            Logger.log(LogId.W_9000, "action_ring_packet", player.getWorld().getName(), exception.getClass().getSimpleName());
        }
    }

    final class PacketEntity {
        private final int entityId = NEXT_ENTITY_ID.getAndIncrement();
        private final UUID uuid = UUID.randomUUID();
        private final EntityType entityType;
        private Location location;
        private List<WrappedDataValue> metadata;
        private boolean spawned;

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
            sendMetadata(player);
            spawned = true;
        }

        void teleport(@NotNull Player player, @NotNull Location location) {
            this.location = location.clone();
            if (!spawned) {
                return;
            }
            PacketContainer packet = protocolManager.createPacket(PacketType.Play.Server.ENTITY_TELEPORT);
            packet.getIntegers().writeSafely(0, entityId);
            packet.getDoubles().writeSafely(0, location.getX());
            packet.getDoubles().writeSafely(1, location.getY());
            packet.getDoubles().writeSafely(2, location.getZ());
            packet.getBytes().writeSafely(0, angle(location.getYaw()));
            packet.getBytes().writeSafely(1, angle(location.getPitch()));
            packet.getBooleans().writeSafely(0, false);
            send(player, packet);
        }

        void updateMetadata(@NotNull Player player, @NotNull List<WrappedDataValue> metadata) {
            this.metadata = List.copyOf(metadata);
            if (spawned) {
                sendMetadata(player);
            }
        }

        void destroy(@NotNull Player player) {
            if (!spawned) {
                return;
            }
            PacketContainer destroy = protocolManager.createPacket(PacketType.Play.Server.ENTITY_DESTROY);
            destroy.getIntLists().writeSafely(0, List.of(entityId));
            destroy.getIntegerArrays().writeSafely(0, new int[]{entityId});
            send(player, destroy);
            spawned = false;
        }

        private void sendMetadata(@NotNull Player player) {
            PacketContainer metadataPacket = protocolManager.createPacket(PacketType.Play.Server.ENTITY_METADATA);
            metadataPacket.getIntegers().writeSafely(0, entityId);
            metadataPacket.getDataValueCollectionModifier().writeSafely(0, metadata);
            send(player, metadataPacket);
        }

        private byte angle(float degree) {
            return (byte) Math.floorMod((int) (degree * 256.0F / 360.0F), 256);
        }
    }
}
