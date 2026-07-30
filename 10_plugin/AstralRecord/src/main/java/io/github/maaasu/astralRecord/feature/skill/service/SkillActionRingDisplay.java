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
import io.github.maaasu.astralRecord.shared.display.PacketEntityIdAllocator;
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
import org.joml.Vector3f;
import org.joml.Vector3fc;

import java.lang.reflect.Constructor;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * アクションリングを閲覧者専用の packet-only Display entity として描画します。
 */
final class SkillActionRingDisplay {
    private static final float ITEM_SCALE = 0.55F;
    private static final int TEXT_LINE_WIDTH = 180;
    private static final int TELEPORT_DURATION_TICKS = 1;
    private static final float DEFAULT_VIEW_RANGE = 16.0F;
    private static final int ENTITY_SHARED_FLAGS_INDEX = 0;
    private static final byte ENTITY_FLAG_GLOWING = 0x40;
    private static final int DISPLAY_POSITION_ROTATION_DURATION_INDEX = 10;
    private static final int DISPLAY_SCALE_INDEX = 12;
    private static final int DISPLAY_BILLBOARD_INDEX = 15;
    private static final int DISPLAY_VIEW_RANGE_INDEX = 17;
    private static final int ITEM_DISPLAY_ITEM_INDEX = 23;
    private static final int ITEM_DISPLAY_TRANSFORM_INDEX = 24;
    private static final int TEXT_DISPLAY_TEXT_INDEX = 23;
    private static final int TEXT_DISPLAY_LINE_WIDTH_INDEX = 24;
    private static final int TEXT_DISPLAY_BACKGROUND_INDEX = 25;
    private static final int TEXT_DISPLAY_TEXT_OPACITY_INDEX = 26;
    private static final int TEXT_DISPLAY_FLAGS_INDEX = 27;
    private static final byte TEXT_DISPLAY_SHADOWED = 0x01;

    private final ProtocolManager protocolManager;
    private TeleportPacketLayout teleportPacketLayout;

    SkillActionRingDisplay(@NotNull Plugin plugin) {
        this.protocolManager = ProtocolLibrary.getProtocolManager();
    }

    DisplayEntity item(@NotNull Location location, @NotNull ItemStack itemStack, boolean glowing) {
        return new DisplayEntity(DisplayKind.ITEM, location, itemStack, null, ITEM_SCALE, glowing);
    }

    DisplayEntity text(@NotNull Location location, @NotNull Component text, float scale) {
        return new DisplayEntity(DisplayKind.TEXT, location, null, text, scale, false);
    }

    void updateItem(@NotNull Player player, @NotNull DisplayEntity entity, @NotNull ItemStack itemStack, boolean glowing) {
        entity.updateItem(player, itemStack, glowing);
    }

    void updateText(@NotNull Player player, @NotNull DisplayEntity entity, @NotNull Component text, float scale) {
        entity.updateText(player, text, scale);
    }

    private @NotNull List<WrappedDataValue> baseDisplayMetadata(float scale) {
        List<WrappedDataValue> values = new ArrayList<>();
        values.add(value(DISPLAY_POSITION_ROTATION_DURATION_INDEX, serializer(Integer.class), TELEPORT_DURATION_TICKS));
        values.add(value(DISPLAY_SCALE_INDEX, vectorSerializer(), new Vector3f(scale, scale, scale)));
        values.add(value(DISPLAY_BILLBOARD_INDEX, serializer(Byte.class), (byte) Display.Billboard.CENTER.ordinal()));
        values.add(value(DISPLAY_VIEW_RANGE_INDEX, serializer(Float.class), DEFAULT_VIEW_RANGE));
        return values;
    }

    private @NotNull WrappedDataValue itemValue(@NotNull ItemStack itemStack) {
        return value(ITEM_DISPLAY_ITEM_INDEX, WrappedDataWatcher.Registry.getItemStackSerializer(false), itemStack);
    }

    private @NotNull WrappedDataValue glowingValue(boolean glowing) {
        return value(ENTITY_SHARED_FLAGS_INDEX, serializer(Byte.class), glowing ? ENTITY_FLAG_GLOWING : (byte) 0);
    }

    private @NotNull WrappedDataValue textValue(@NotNull Component text) {
        return value(
            TEXT_DISPLAY_TEXT_INDEX,
            WrappedDataWatcher.Registry.getChatComponentSerializer(false),
            WrappedChatComponent.fromJson(GsonComponentSerializer.gson().serialize(text))
        );
    }

    private @NotNull WrappedDataValue value(int index, @NotNull WrappedDataWatcher.Serializer serializer, Object value) {
        return WrappedDataValue.fromWrappedValue(index, serializer, value);
    }

    private @NotNull WrappedDataWatcher.Serializer serializer(@NotNull Type type) {
        return WrappedDataWatcher.Registry.get(type);
    }

    private @NotNull WrappedDataWatcher.Serializer vectorSerializer() {
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
            Logger.log(LogId.W_9000, "skill_action_ring_packet:" + packet.getType().name(), player.getWorld().getName(), exception.getClass().getSimpleName());
        }
    }

    final class DisplayEntity {
        private final DisplayKind kind;
        private Location location;
        private ItemStack itemStack;
        private Component text;
        private float scale;
        private boolean glowing;
        private PacketEntity entity;

        private DisplayEntity(
            @NotNull DisplayKind kind,
            @NotNull Location location,
            ItemStack itemStack,
            Component text,
            float scale,
            boolean glowing
        ) {
            this.kind = kind;
            this.location = location.clone();
            this.itemStack = itemStack == null ? null : itemStack.clone();
            this.text = text;
            this.scale = scale;
            this.glowing = glowing;
        }

        void spawn(@NotNull Player player) {
            if (entity != null) {
                return;
            }
            entity = new PacketEntity(kind == DisplayKind.ITEM ? EntityType.ITEM_DISPLAY : EntityType.TEXT_DISPLAY, location);
            entity.spawn(player, initialMetadata());
        }

        void teleport(@NotNull Player player, @NotNull Location nextLocation) {
            if (location.equals(nextLocation)) {
                return;
            }
            boolean worldChanged = location.getWorld() != nextLocation.getWorld();
            location = nextLocation.clone();
            if (entity != null) {
                if (worldChanged) {
                    entity.destroy(player);
                    entity = new PacketEntity(kind == DisplayKind.ITEM ? EntityType.ITEM_DISPLAY : EntityType.TEXT_DISPLAY, location);
                    entity.spawn(player, initialMetadata());
                } else {
                    entity.teleport(player, location);
                }
            }
        }

        void updateItem(@NotNull Player player, @NotNull ItemStack nextItemStack, boolean nextGlowing) {
            ItemStack cloned = nextItemStack.clone();
            boolean itemChanged = itemStack == null || !itemStack.equals(cloned);
            boolean glowingChanged = glowing != nextGlowing;
            if (!itemChanged && !glowingChanged) {
                return;
            }
            itemStack = cloned;
            glowing = nextGlowing;
            if (entity == null) {
                return;
            }
            List<WrappedDataValue> values = new ArrayList<>();
            if (itemChanged) {
                values.add(itemValue(itemStack));
            }
            if (glowingChanged) {
                values.add(glowingValue(glowing));
            }
            entity.updateMetadata(player, values);
        }

        void updateText(@NotNull Player player, @NotNull Component nextText, float nextScale) {
            boolean textChanged = text == null || !text.equals(nextText);
            boolean scaleChanged = Float.compare(scale, nextScale) != 0;
            if (!textChanged && !scaleChanged) {
                return;
            }
            text = nextText;
            scale = nextScale;
            if (entity == null) {
                return;
            }
            List<WrappedDataValue> values = new ArrayList<>();
            if (textChanged) {
                values.add(textValue(text));
            }
            if (scaleChanged) {
                values.add(value(DISPLAY_SCALE_INDEX, vectorSerializer(), new Vector3f(scale, scale, scale)));
            }
            entity.updateMetadata(player, values);
        }

        void destroy(@NotNull Player player) {
            if (entity != null) {
                entity.destroy(player);
                entity = null;
            }
        }

        private @NotNull List<WrappedDataValue> initialMetadata() {
            List<WrappedDataValue> values = baseDisplayMetadata(scale);
            if (kind == DisplayKind.ITEM) {
                values.add(glowingValue(glowing));
                values.add(itemValue(itemStack == null ? new ItemStack(org.bukkit.Material.AIR) : itemStack));
                values.add(value(ITEM_DISPLAY_TRANSFORM_INDEX, serializer(Byte.class), (byte) ItemDisplay.ItemDisplayTransform.GUI.ordinal()));
                return values;
            }
            values.add(textValue(text == null ? Component.empty() : text));
            values.add(value(TEXT_DISPLAY_LINE_WIDTH_INDEX, serializer(Integer.class), TEXT_LINE_WIDTH));
            values.add(value(TEXT_DISPLAY_BACKGROUND_INDEX, serializer(Integer.class), 0));
            values.add(value(TEXT_DISPLAY_TEXT_OPACITY_INDEX, serializer(Byte.class), (byte) -1));
            values.add(value(TEXT_DISPLAY_FLAGS_INDEX, serializer(Byte.class), TEXT_DISPLAY_SHADOWED));
            return values;
        }
    }

    private final class PacketEntity {
        private final int entityId = PacketEntityIdAllocator.nextEntityId();
        private final UUID uuid = UUID.randomUUID();
        private final EntityType entityType;
        private final Location location;

        private PacketEntity(@NotNull EntityType entityType, @NotNull Location location) {
            this.entityType = entityType;
            this.location = location.clone();
        }

        private void spawn(@NotNull Player player, @NotNull List<WrappedDataValue> metadata) {
            PacketContainer spawn = protocolManager.createPacket(PacketType.Play.Server.SPAWN_ENTITY);
            spawn.getIntegers().writeSafely(0, entityId);
            spawn.getUUIDs().writeSafely(0, uuid);
            spawn.getEntityTypeModifier().writeSafely(0, entityType);
            spawn.getDoubles().writeSafely(0, location.getX());
            spawn.getDoubles().writeSafely(1, location.getY());
            spawn.getDoubles().writeSafely(2, location.getZ());
            send(player, spawn);

            updateMetadata(player, metadata);
        }

        private void teleport(@NotNull Player player, @NotNull Location nextLocation) {
            PacketContainer packet = protocolManager.createPacket(PacketType.Play.Server.ENTITY_TELEPORT);
            packet.getIntegers().writeSafely(0, entityId);
            writeTeleportTarget(packet, nextLocation);
            packet.getBooleans().writeSafely(0, true);
            send(player, packet);
        }

        private void updateMetadata(@NotNull Player player, @NotNull List<WrappedDataValue> metadata) {
            if (metadata.isEmpty()) {
                return;
            }
            PacketContainer packet = protocolManager.createPacket(PacketType.Play.Server.ENTITY_METADATA);
            packet.getIntegers().writeSafely(0, entityId);
            packet.getDataValueCollectionModifier().writeSafely(0, metadata);
            send(player, packet);
        }

        private void destroy(@NotNull Player player) {
            PacketContainer packet = protocolManager.createPacket(PacketType.Play.Server.ENTITY_DESTROY);
            packet.getIntLists().writeSafely(0, List.of(entityId));
            packet.getIntegerArrays().writeSafely(0, new int[]{entityId});
            send(player, packet);
        }
    }

    private void writeTeleportTarget(@NotNull PacketContainer packet, @NotNull Location location) {
        if (packet.getDoubles().size() >= 3) {
            packet.getDoubles().write(0, location.getX());
            packet.getDoubles().write(1, location.getY());
            packet.getDoubles().write(2, location.getZ());
            packet.getBytes().writeSafely(0, angleToByte(location.getYaw()));
            packet.getBytes().writeSafely(1, angleToByte(location.getPitch()));
            return;
        }

        try {
            TeleportPacketLayout layout = teleportPacketLayout;
            if (layout == null) {
                layout = TeleportPacketLayout.resolve(packet);
                teleportPacketLayout = layout;
            }
            packet.getModifier().write(1, layout.createChange(location));
            packet.getModifier().write(2, Set.of());
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Unsupported entity teleport packet structure", exception);
        }
    }

    private record TeleportPacketLayout(
        @NotNull Constructor<?> vectorConstructor,
        @NotNull Constructor<?> changeConstructor
    ) {
        private static @NotNull TeleportPacketLayout resolve(@NotNull PacketContainer packet)
            throws ReflectiveOperationException {
            if (packet.getModifier().size() < 3) {
                throw new NoSuchFieldException("Entity teleport packet does not contain movement and relative fields");
            }
            Class<?> changeType = packet.getModifier().getField(1).getType();
            Constructor<?> changeConstructor = null;
            for (Constructor<?> candidate : changeType.getConstructors()) {
                Class<?>[] parameterTypes = candidate.getParameterTypes();
                if (parameterTypes.length == 4
                    && parameterTypes[0] == parameterTypes[1]
                    && parameterTypes[2] == float.class
                    && parameterTypes[3] == float.class) {
                    changeConstructor = candidate;
                    break;
                }
            }
            if (changeConstructor == null) {
                throw new NoSuchMethodException("PositionMoveRotation constructor was not found");
            }
            Class<?> vectorType = changeConstructor.getParameterTypes()[0];
            Constructor<?> vectorConstructor = vectorType.getConstructor(double.class, double.class, double.class);
            return new TeleportPacketLayout(vectorConstructor, changeConstructor);
        }

        private @NotNull Object createChange(@NotNull Location location) throws ReflectiveOperationException {
            Object position = vectorConstructor.newInstance(location.getX(), location.getY(), location.getZ());
            Object velocity = vectorConstructor.newInstance(0.0D, 0.0D, 0.0D);
            return changeConstructor.newInstance(position, velocity, location.getYaw(), location.getPitch());
        }
    }

    private static byte angleToByte(float angle) {
        return (byte) Math.floor(angle * 256.0F / 360.0F);
    }

    private enum DisplayKind {
        ITEM,
        TEXT
    }
}
