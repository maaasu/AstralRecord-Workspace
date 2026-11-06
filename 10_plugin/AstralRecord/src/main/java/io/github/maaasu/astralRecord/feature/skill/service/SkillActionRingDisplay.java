package io.github.maaasu.astralRecord.feature.skill.service;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.wrappers.WrappedChatComponent;
import com.comphenix.protocol.wrappers.WrappedDataValue;
import com.comphenix.protocol.wrappers.WrappedDataWatcher;
import io.github.maaasu.astralRecord.feature.skill.model.SkillBindPreset;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import io.github.maaasu.astralRecord.shared.display.PacketEntityIdAllocator;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Display;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix3f;
import org.joml.Quaternionf;
import org.joml.Quaternionfc;
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
    private static final NamespacedKey OUTLINE_MODEL = new NamespacedKey("astralrecord", "action_ring");
    private static final NamespacedKey PANEL_MODEL = new NamespacedKey("astralrecord", "action_ring_panel");
    private static final Color NORMAL_OUTLINE = Color.fromRGB(0xa6e8ff);
    private static final Color SELECTED_OUTLINE = Color.fromRGB(0xffcb70);
    private static final Color COOLDOWN_OUTLINE = Color.fromRGB(0x737c90);
    private static final Color COOLDOWN_ICON = Color.fromRGB(0x9e9e9e);
    private static final double BEDROCK_ITEM_OFFSET = 0.125D;
    private static final int TEXT_LINE_WIDTH = 132;
    private static final int INTERPOLATION_TICKS = 2;
    private static final float DEFAULT_VIEW_RANGE = 16.0F;
    private static final int ENTITY_SHARED_FLAGS_INDEX = 0;
    private static final byte ENTITY_FLAG_GLOWING = 0x40;
    private static final int ENTITY_NO_GRAVITY_INDEX = 5;
    private static final int DROPPED_ITEM_STACK_INDEX = 8;
    private static final int DISPLAY_INTERPOLATION_START_INDEX = 8;
    private static final int DISPLAY_INTERPOLATION_DURATION_INDEX = 9;
    private static final int DISPLAY_POSITION_ROTATION_DURATION_INDEX = 10;
    private static final int DISPLAY_SCALE_INDEX = 12;
    private static final int DISPLAY_LEFT_ROTATION_INDEX = 13;
    private static final int DISPLAY_BILLBOARD_INDEX = 15;
    private static final int DISPLAY_BRIGHTNESS_INDEX = 16;
    private static final int FULL_BRIGHT = (15 << 4) | (15 << 20);
    private static final int DISPLAY_VIEW_RANGE_INDEX = 17;
    private static final int ITEM_DISPLAY_ITEM_INDEX = 23;
    private static final int ITEM_DISPLAY_TRANSFORM_INDEX = 24;
    private static final int TEXT_DISPLAY_TEXT_INDEX = 23;
    private static final int TEXT_DISPLAY_LINE_WIDTH_INDEX = 24;
    private static final int TEXT_DISPLAY_BACKGROUND_INDEX = 25;
    private static final int TEXT_DISPLAY_TEXT_OPACITY_INDEX = 26;
    private static final int TEXT_DISPLAY_FLAGS_INDEX = 27;
    private static final byte TEXT_DISPLAY_SHADOWED = 0x01;

    private final ProtocolManager protocolManager = ProtocolLibrary.getProtocolManager();
    private TeleportPacketLayout teleportPacketLayout;

    /**
     * 指定した大きさでアイコン用の表示 entity を生成します。
     *
     * @param location 初期位置
     * @param itemStack 表示するアイテム
     * @param scale アイテム表示の拡大率
     * @param bedrockIconUuid Geyser 向けのアイコン識別子。Java 版では null
     * @return 未生成の表示 entity
     */
    DisplayEntity item(@NotNull Location location, @NotNull ItemStack itemStack, float scale, @Nullable UUID bedrockIconUuid) {
        DisplayKind kind = bedrockIconUuid != null ? DisplayKind.DROPPED_ITEM : DisplayKind.ITEM;
        return new DisplayEntity(kind, location, itemStack, null, scale, bedrockIconUuid);
    }

    DisplayEntity text(@NotNull Location location, @NotNull Component text, float scale) {
        return text(location, text, scale, null);
    }

    DisplayEntity text(@NotNull Location location, @NotNull Component text, float scale, @Nullable UUID uuid) {
        return new DisplayEntity(DisplayKind.TEXT, location, null, text, scale, uuid);
    }

    /**
     * アイコンの内容、発光状態および大きさを更新します。
     *
     * @param player 表示を受け取るプレイヤー
     * @param entity 更新対象の表示 entity
     * @param itemStack 表示するアイテム
     * @param glowing 発光表示する場合は {@code true}
     * @param scale アイテム表示の拡大率
     */
    void updateItem(
        @NotNull Player player,
        @NotNull DisplayEntity entity,
        @NotNull ItemStack itemStack,
        boolean glowing,
        float scale
    ) {
        entity.updateItem(player, itemStack, glowing, scale);
    }

    void updateText(@NotNull Player player, @NotNull DisplayEntity entity, @NotNull Component text, float scale) {
        entity.updateText(player, text, scale);
    }

    Outline outline(boolean bedrock, boolean javaPanel) {
        return new Outline(bedrock, javaPanel);
    }

    private @NotNull List<WrappedDataValue> baseDisplayMetadata(float scale, Display.Billboard billboard) {
        List<WrappedDataValue> values = new ArrayList<>();
        values.add(value(DISPLAY_INTERPOLATION_DURATION_INDEX, serializer(Integer.class), INTERPOLATION_TICKS));
        values.add(value(DISPLAY_POSITION_ROTATION_DURATION_INDEX, serializer(Integer.class), INTERPOLATION_TICKS));
        values.add(scaleValue(scale));
        values.add(value(DISPLAY_BILLBOARD_INDEX, serializer(Byte.class), (byte) billboard.ordinal()));
        values.add(value(DISPLAY_VIEW_RANGE_INDEX, serializer(Float.class), DEFAULT_VIEW_RANGE));
        return values;
    }

    private WrappedDataValue scaleValue(float scale) {
        return value(DISPLAY_SCALE_INDEX, vectorSerializer(), new Vector3f(scale, scale, scale));
    }

    private WrappedDataValue interpolationStart() {
        return value(DISPLAY_INTERPOLATION_START_INDEX, serializer(Integer.class), 0);
    }

    private WrappedDataValue rotationValue(Quaternionf rotation) {
        WrappedDataWatcher.Serializer quaternionSerializer;
        try {
            quaternionSerializer = serializer(Quaternionf.class);
        } catch (IllegalArgumentException ignored) {
            quaternionSerializer = serializer(Quaternionfc.class);
        }
        return value(DISPLAY_LEFT_ROTATION_INDEX, quaternionSerializer, rotation);
    }

    private @NotNull WrappedDataValue itemValue(int index, @NotNull ItemStack itemStack) {
        return value(index, WrappedDataWatcher.Registry.getItemStackSerializer(false), itemStack);
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
        private final @Nullable UUID markerUuid;
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
            @Nullable UUID markerUuid
        ) {
            this.kind = kind;
            this.markerUuid = markerUuid;
            this.location = renderLocation(location);
            this.itemStack = itemStack == null ? null : itemStack.clone();
            this.text = text;
            this.scale = scale;
        }

        void spawn(@NotNull Player player) {
            if (entity != null) {
                return;
            }
            entity = createPacketEntity();
            entity.spawn(player, initialMetadata());
        }

        void setMotionSnapping(Player player, boolean snap) {
            if (entity != null) {
                entity.setMotionSnapping(player, snap);
            }
        }

        void teleport(@NotNull Player player, @NotNull Location center) {
            Location nextLocation = renderLocation(center);
            if (location.equals(nextLocation)) {
                return;
            }
            boolean worldChanged = location.getWorld() != nextLocation.getWorld();
            location = nextLocation;
            if (entity != null) {
                if (worldChanged) {
                    entity.destroy(player);
                    entity = createPacketEntity();
                    entity.spawn(player, initialMetadata());
                } else {
                    entity.teleport(player, location);
                }
            }
        }

        private Location renderLocation(Location center) {
            Location target = withoutRotation(center);
            // Geyser の AddItemEntity が加える描画オフセットを打ち消します。
            return kind == DisplayKind.DROPPED_ITEM ? target.subtract(0, BEDROCK_ITEM_OFFSET, 0) : target;
        }

        private PacketEntity createPacketEntity() {
            return markerUuid == null
                ? new PacketEntity(kind.type, location)
                : new PacketEntity(PacketEntityIdAllocator.nextEntityId(), markerUuid, kind.type, location);
        }

        /**
         * アイコンの内容、発光状態および表示倍率を差分送信します。
         *
         * @param player 表示を受け取るプレイヤー
         * @param nextItemStack 次に表示するアイテム
         * @param nextGlowing 次の発光状態
         * @param nextScale 次の表示倍率
         */
        void updateItem(@NotNull Player player, @NotNull ItemStack nextItemStack, boolean nextGlowing, float nextScale) {
            if (kind == DisplayKind.DROPPED_ITEM) {
                return;
            }
            boolean itemChanged = !nextItemStack.equals(itemStack);
            boolean glowingChanged = glowing != nextGlowing;
            boolean scaleChanged = Float.compare(scale, nextScale) != 0;
            if (!itemChanged && !glowingChanged && !scaleChanged) {
                return;
            }
            glowing = nextGlowing;
            scale = nextScale;
            if (itemChanged) {
                itemStack = nextItemStack.clone();
            }
            if (entity == null) {
                return;
            }
            List<WrappedDataValue> values = new ArrayList<>();
            if (itemChanged) {
                values.add(itemValue(ITEM_DISPLAY_ITEM_INDEX, itemStack));
            }
            if (glowingChanged) {
                values.add(glowingValue(glowing));
            }
            if (scaleChanged) {
                values.add(interpolationStart());
                values.add(scaleValue(scale));
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
                values.add(interpolationStart());
                values.add(scaleValue(scale));
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
            if (kind == DisplayKind.DROPPED_ITEM) {
                return List.of(
                    value(ENTITY_NO_GRAVITY_INDEX, serializer(Boolean.class), true),
                    itemValue(DROPPED_ITEM_STACK_INDEX, itemStack)
                );
            }
            List<WrappedDataValue> values = baseDisplayMetadata(scale, Display.Billboard.CENTER);
            if (kind == DisplayKind.ITEM) {
                values.add(glowingValue(glowing));
                values.add(itemValue(ITEM_DISPLAY_ITEM_INDEX, itemStack));
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

    /** リングの一体型モデルと、旧パックの輪郭モデルを送信します。 */
    final class Outline {
        private final boolean bedrock;
        private final boolean javaPanel;
        private final int bedrockId;
        private PacketEntity entity;
        private Location location;
        private Quaternionf rotation;
        private float scale;
        private int selected;
        private int cooldowns;
        private long bedrockState;
        private ActionRingPanel panel;

        private Outline(boolean bedrock, boolean javaPanel) {
            this.bedrock = bedrock;
            this.javaPanel = javaPanel;
            this.bedrockId = bedrock ? PacketEntityIdAllocator.nextEntityId() : 0;
        }

        @Nullable UUID iconUuid(int index) {
            return bedrock ? ActionRingFrame.icon(bedrockId, index) : null;
        }

        @Nullable UUID fallbackUuid(int part) {
            return bedrock ? ActionRingFrame.fallback(bedrockId, part) : null;
        }

        /** 一体型パックを使えず生成した統合版アイコンに初期縮尺を反映します。 */
        void syncBedrockIcons(Player player) {
            if (bedrock && entity != null) {
                sendMarker(player, ActionRingFrame.marker(bedrockId, bedrockState));
            }
        }

        /**
         * 輪郭を生成し、移動・変形・色の差分を送信します。
         *
         * @param player 閲覧者
         * @param center 輪郭の中心
         * @param normal 視点からリングへ向かう法線
         * @param right リングの右方向
         * @param up リングの上方向
         * @param nextScale モデルの表示幅
         * @param nextSelected 選択中の枠番号。0 は選択なし
         * @param nextCooldowns クールダウン中の枠を表すビットマスク
         * @param nextPanel スプライトとゲージの表示状態
         */
        void update(Player player, Location center, Vector normal, Vector right, Vector up,
                    float nextScale, int nextSelected, int nextCooldowns, ActionRingPanel nextPanel) {
            Location target = withoutRotation(center);
            if (location != null && location.getWorld() != target.getWorld()) {
                destroy(player);
            }
            if (entity == null) {
                spawn(player, target, normal, right, up, nextScale, nextSelected, nextCooldowns, nextPanel);
                return;
            }
            if (!target.equals(location)) {
                entity.teleport(player, target);
                location = target;
            }
            if (bedrock) {
                long nextState = ActionRingFrame.pack(normal, up, nextScale, nextSelected, nextCooldowns);
                if (bedrockState != nextState) {
                    bedrockState = nextState;
                    sendMarker(player, ActionRingFrame.marker(bedrockId, nextState));
                }
                if (panel.icons() != nextPanel.icons()) {
                    sendMarker(player, ActionRingFrame.panelIcons(bedrockId, nextPanel.icons()));
                }
                if (panel.gauges() != nextPanel.gauges()) {
                    sendMarker(player, ActionRingFrame.panelGauges(bedrockId, nextPanel.gauges()));
                }
                panel = nextPanel;
                return;
            }
            List<WrappedDataValue> values = new ArrayList<>();
            Quaternionf nextRotation = outlineRotation(normal, right, up);
            if (!nextRotation.equals(rotation)) {
                rotation = nextRotation;
                values.add(rotationValue(rotation));
            }
            if (Float.compare(scale, nextScale) != 0) {
                scale = nextScale;
                values.add(scaleValue(scale));
            }
            if (!values.isEmpty()) {
                values.add(interpolationStart());
            }
            if (selected != nextSelected || cooldowns != nextCooldowns || javaPanel && !nextPanel.equals(panel)) {
                selected = nextSelected;
                cooldowns = nextCooldowns;
                panel = nextPanel;
                values.add(itemValue(ITEM_DISPLAY_ITEM_INDEX, outlineItem(selected, cooldowns, javaPanel ? panel : null)));
            }
            entity.updateMetadata(player, values);
        }

        private void spawn(Player player, Location center, Vector normal, Vector right, Vector up,
                           float initialScale, int initialSelected, int initialCooldowns, ActionRingPanel initialPanel) {
            location = center;
            panel = initialPanel;
            if (bedrock) {
                bedrockState = ActionRingFrame.pack(normal, up, initialScale, initialSelected, initialCooldowns);
                entity = new PacketEntity(bedrockId, ActionRingFrame.marker(bedrockId, bedrockState), EntityType.TEXT_DISPLAY, center);
                entity.spawn(player, List.of());
                sendMarker(player, ActionRingFrame.panelIcons(bedrockId, panel.icons()));
                sendMarker(player, ActionRingFrame.panelGauges(bedrockId, panel.gauges()));
                return;
            }
            entity = new PacketEntity(EntityType.ITEM_DISPLAY, center);
            rotation = outlineRotation(normal, right, up);
            scale = initialScale;
            selected = initialSelected;
            cooldowns = initialCooldowns;
            List<WrappedDataValue> values = baseDisplayMetadata(scale, Display.Billboard.FIXED);
            values.add(rotationValue(rotation));
            values.add(value(DISPLAY_BRIGHTNESS_INDEX, serializer(Integer.class), FULL_BRIGHT));
            values.add(itemValue(ITEM_DISPLAY_ITEM_INDEX, outlineItem(selected, cooldowns, javaPanel ? panel : null)));
            values.add(value(ITEM_DISPLAY_TRANSFORM_INDEX, serializer(Byte.class),
                (byte) ItemDisplay.ItemDisplayTransform.FIXED.ordinal()));
            entity.spawn(player, values);
        }

        private void sendMarker(Player player, UUID uuid) {
            int id = PacketEntityIdAllocator.nextEntityId();
            PacketEntity marker = new PacketEntity(id, uuid, EntityType.TEXT_DISPLAY, location);
            // Geyser はマーカーを描画せず、すでに存在するモデルへ状態を反映します。
            marker.spawn(player, List.of());
            marker.destroy(player);
        }

        void setMotionSnapping(Player player, boolean snap) {
            if (entity != null) {
                entity.setMotionSnapping(player, snap);
            }
        }

        void destroy(Player player) {
            if (entity != null) {
                entity.destroy(player);
                entity = null;
            }
        }
    }

    private static Location withoutRotation(Location center) {
        Location target = center.clone();
        target.setYaw(0);
        target.setPitch(0);
        return target;
    }

    private static Quaternionf outlineRotation(Vector normal, Vector right, Vector up) {
        // ItemDisplay の Y 軸 180 度回転後に南面が視点を向きます。
        Matrix3f basis = new Matrix3f()
            .setColumn(0, (float) -right.getX(), (float) -right.getY(), (float) -right.getZ())
            .setColumn(1, (float) up.getX(), (float) up.getY(), (float) up.getZ())
            .setColumn(2, (float) normal.getX(), (float) normal.getY(), (float) normal.getZ());
        return new Quaternionf().setFromNormalized(basis);
    }

    @SuppressWarnings("UnstableApiUsage")
    private static ItemStack outlineItem(int selected, int cooldowns, @Nullable ActionRingPanel panel) {
        ItemStack stack = new ItemStack(Material.PAPER);
        ItemMeta meta = stack.getItemMeta();
        meta.setItemModel(panel == null ? OUTLINE_MODEL : PANEL_MODEL);
        var modelData = meta.getCustomModelDataComponent();
        List<Color> colors = new ArrayList<>();
        for (int slot = 0; slot < SkillBindPreset.ACTION_RING_SLOT_COUNT; slot++) {
            Color color = NORMAL_OUTLINE;
            if (slot + 1 == selected) {
                color = SELECTED_OUTLINE;
            } else if ((cooldowns & (1 << slot)) != 0) {
                color = COOLDOWN_OUTLINE;
            }
            colors.add(color);
        }
        if (panel != null) {
            for (int slot = 0; slot < SkillBindPreset.ACTION_RING_SLOT_COUNT; slot++) {
                colors.add((cooldowns & (1 << slot)) == 0 ? Color.WHITE : COOLDOWN_ICON);
            }
            colors.add(Color.WHITE);
            modelData.setFloats(panel.modelValues(selected));
        }
        modelData.setColors(colors);
        meta.setCustomModelDataComponent(modelData);
        stack.setItemMeta(meta);
        return stack;
    }

    private final class PacketEntity {
        private final int entityId;
        private final UUID uuid;
        private final EntityType entityType;
        private final Location location;

        private PacketEntity(@NotNull EntityType entityType, @NotNull Location location) {
            this(PacketEntityIdAllocator.nextEntityId(), UUID.randomUUID(), entityType, location);
        }

        private PacketEntity(int entityId, UUID uuid, EntityType entityType, Location location) {
            this.entityId = entityId;
            this.uuid = uuid;
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

        private void setMotionSnapping(Player player, boolean snap) {
            int duration = snap ? 0 : INTERPOLATION_TICKS;
            updateMetadata(player, List.of(
                value(DISPLAY_INTERPOLATION_DURATION_INDEX, serializer(Integer.class), duration),
                value(DISPLAY_POSITION_ROTATION_DURATION_INDEX, serializer(Integer.class), duration)
            ));
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
        ITEM(EntityType.ITEM_DISPLAY),
        DROPPED_ITEM(EntityType.ITEM),
        TEXT(EntityType.TEXT_DISPLAY);

        private final EntityType type;

        DisplayKind(EntityType type) {
            this.type = type;
        }
    }
}
