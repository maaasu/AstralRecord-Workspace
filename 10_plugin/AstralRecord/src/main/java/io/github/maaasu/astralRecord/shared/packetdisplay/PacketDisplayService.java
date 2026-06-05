package io.github.maaasu.astralRecord.shared.packetdisplay;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.wrappers.WrappedChatComponent;
import com.comphenix.protocol.wrappers.WrappedDataValue;
import com.comphenix.protocol.wrappers.WrappedDataWatcher;
import io.github.maaasu.astralRecord.infrastructure.util.ColorCodeUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Location;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.joml.Vector3f;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * サーバ実体を作らず、viewer 単位の仮想 Display Entity をパケットで表示するサービスです。
 */
public final class PacketDisplayService {
    private static final AtomicInteger NEXT_ENTITY_ID = new AtomicInteger(1_500_000_000);

    private static final byte BILLBOARD_CENTER = 3;
    private static final byte TEXT_FLAG_SHADOW = 0x01;
    private static final byte TEXT_FLAG_SEE_THROUGH = 0x02;
    private static final byte ITEM_TRANSFORM_FIXED = 7;

    private static final int META_ENTITY_SILENT = 4;
    private static final int META_ENTITY_NO_GRAVITY = 5;
    private static final int META_DISPLAY_SCALE = 12;
    private static final int META_DISPLAY_BILLBOARD = 15;
    private static final int META_DISPLAY_VIEW_RANGE = 17;
    private static final int META_TEXT_TEXT = 23;
    private static final int META_TEXT_LINE_WIDTH = 24;
    private static final int META_TEXT_BACKGROUND = 25;
    private static final int META_TEXT_OPACITY = 26;
    private static final int META_TEXT_FLAGS = 27;
    private static final int META_ITEM_STACK = 23;
    private static final int META_ITEM_TRANSFORM = 24;

    private final ProtocolManager protocolManager = ProtocolLibrary.getProtocolManager();
    private final Player viewer;

    /**
     * 表示サービスを生成します。
     *
     * @param viewer 表示先プレイヤー
     */
    public PacketDisplayService(@NotNull Player viewer) {
        this.viewer = viewer;
    }

    /**
     * TextDisplay を viewer にだけ表示します。
     *
     * @param location 表示位置
     * @param options  表示オプション
     * @return 表示操作ハンドル
     */
    public @NotNull PacketDisplayHandle spawnText(
            @NotNull Location location,
            @NotNull PacketTextDisplayOptions options
    ) {
        int entityId = nextEntityId();
        spawn(entityId, EntityType.TEXT_DISPLAY, location);
        sendMetadata(entityId, textMetadata(options));
        return new PacketDisplayHandle(this, entityId, PacketDisplayType.TEXT);
    }

    /**
     * ItemDisplay を viewer にだけ表示します。
     *
     * @param location 表示位置
     * @param options  表示オプション
     * @return 表示操作ハンドル
     */
    public @NotNull PacketDisplayHandle spawnItem(
            @NotNull Location location,
            @NotNull PacketItemDisplayOptions options
    ) {
        int entityId = nextEntityId();
        spawn(entityId, EntityType.ITEM_DISPLAY, location);
        sendMetadata(entityId, itemMetadata(options));
        return new PacketDisplayHandle(this, entityId, PacketDisplayType.ITEM);
    }

    void teleport(int entityId, @NotNull Location location) {
        PacketContainer packet = protocolManager.createPacket(PacketType.Play.Server.ENTITY_TELEPORT);
        packet.getIntegers().writeSafely(0, entityId);
        packet.getDoubles().writeSafely(0, location.getX());
        packet.getDoubles().writeSafely(1, location.getY());
        packet.getDoubles().writeSafely(2, location.getZ());
        packet.getBytes().writeSafely(0, angle(location.getYaw()));
        packet.getBytes().writeSafely(1, angle(location.getPitch()));
        packet.getBooleans().writeSafely(0, false);
        send(packet);
    }

    void updateText(int entityId, @NotNull Component text) {
        sendMetadata(entityId, List.of(data(META_TEXT_TEXT, chatSerializer(), chat(text))));
    }

    void updateItem(int entityId, @NotNull ItemStack itemStack) {
        sendMetadata(entityId, List.of(data(META_ITEM_STACK, itemSerializer(), itemStack.clone())));
    }

    void destroy(int entityId) {
        PacketContainer packet = protocolManager.createPacket(PacketType.Play.Server.ENTITY_DESTROY);
        packet.getIntLists().writeSafely(0, List.of(entityId));
        send(packet);
    }

    private void spawn(int entityId, @NotNull EntityType entityType, @NotNull Location location) {
        PacketContainer packet = protocolManager.createPacket(PacketType.Play.Server.SPAWN_ENTITY);
        packet.getIntegers().writeSafely(0, entityId);
        packet.getUUIDs().writeSafely(0, UUID.randomUUID());
        packet.getEntityTypeModifier().writeSafely(0, entityType);
        packet.getDoubles().writeSafely(0, location.getX());
        packet.getDoubles().writeSafely(1, location.getY());
        packet.getDoubles().writeSafely(2, location.getZ());
        packet.getBytes().writeSafely(0, angle(location.getPitch()));
        packet.getBytes().writeSafely(1, angle(location.getYaw()));
        packet.getBytes().writeSafely(2, angle(location.getYaw()));
        packet.getIntegers().writeSafely(1, 0);
        send(packet);
    }

    private void sendMetadata(int entityId, @NotNull List<WrappedDataValue> values) {
        PacketContainer packet = protocolManager.createPacket(PacketType.Play.Server.ENTITY_METADATA);
        packet.getIntegers().writeSafely(0, entityId);
        packet.getDataValueCollectionModifier().writeSafely(0, values);
        send(packet);
    }

    private @NotNull List<WrappedDataValue> textMetadata(@NotNull PacketTextDisplayOptions options) {
        List<WrappedDataValue> values = baseDisplayMetadata(options.scale(), options.viewRange());
        byte flags = 0;
        if (options.shadowed()) {
            flags |= TEXT_FLAG_SHADOW;
        }
        if (options.seeThrough()) {
            flags |= TEXT_FLAG_SEE_THROUGH;
        }
        values.add(data(META_TEXT_TEXT, chatSerializer(), chat(options.text())));
        values.add(data(META_TEXT_LINE_WIDTH, integerSerializer(), options.lineWidth()));
        values.add(data(META_TEXT_BACKGROUND, integerSerializer(), options.backgroundColor().asARGB()));
        values.add(data(META_TEXT_OPACITY, byteSerializer(), (byte) -1));
        values.add(data(META_TEXT_FLAGS, byteSerializer(), flags));
        return values;
    }

    private @NotNull List<WrappedDataValue> itemMetadata(@NotNull PacketItemDisplayOptions options) {
        List<WrappedDataValue> values = baseDisplayMetadata(options.scale(), options.viewRange());
        values.add(data(META_ITEM_STACK, itemSerializer(), options.itemStack().clone()));
        values.add(data(META_ITEM_TRANSFORM, byteSerializer(), ITEM_TRANSFORM_FIXED));
        return values;
    }

    private @NotNull List<WrappedDataValue> baseDisplayMetadata(float scale, float viewRange) {
        List<WrappedDataValue> values = new ArrayList<>();
        values.add(data(META_ENTITY_SILENT, booleanSerializer(), true));
        values.add(data(META_ENTITY_NO_GRAVITY, booleanSerializer(), true));
        values.add(data(META_DISPLAY_SCALE, vector3fSerializer(), new Vector3f(scale, scale, scale)));
        values.add(data(META_DISPLAY_BILLBOARD, byteSerializer(), BILLBOARD_CENTER));
        values.add(data(META_DISPLAY_VIEW_RANGE, floatSerializer(), viewRange));
        return values;
    }

    private @NotNull WrappedDataValue data(
            int index,
            @NotNull WrappedDataWatcher.Serializer serializer,
            @NotNull Object value
    ) {
        return WrappedDataValue.fromWrappedValue(index, serializer, value);
    }

    private @NotNull Object chat(@NotNull Component component) {
        String legacy = LegacyComponentSerializer.legacySection().serialize(component);
        legacy = ColorCodeUtil.translateAlternateColorCodes(legacy);
        return WrappedChatComponent.fromLegacyText(legacy).getHandle();
    }

    private @NotNull WrappedDataWatcher.Serializer byteSerializer() {
        return WrappedDataWatcher.Registry.get((Type) Byte.class);
    }

    private @NotNull WrappedDataWatcher.Serializer booleanSerializer() {
        return WrappedDataWatcher.Registry.get((Type) Boolean.class);
    }

    private @NotNull WrappedDataWatcher.Serializer integerSerializer() {
        return WrappedDataWatcher.Registry.get((Type) Integer.class);
    }

    private @NotNull WrappedDataWatcher.Serializer floatSerializer() {
        return WrappedDataWatcher.Registry.get((Type) Float.class);
    }

    private @NotNull WrappedDataWatcher.Serializer vector3fSerializer() {
        return WrappedDataWatcher.Registry.get(Vector3f.class);
    }

    private @NotNull WrappedDataWatcher.Serializer chatSerializer() {
        return WrappedDataWatcher.Registry.getChatComponentSerializer();
    }

    private @NotNull WrappedDataWatcher.Serializer itemSerializer() {
        return WrappedDataWatcher.Registry.getItemStackSerializer(false);
    }

    private int nextEntityId() {
        return NEXT_ENTITY_ID.getAndIncrement();
    }

    private byte angle(float angle) {
        return (byte) ((int) (angle * 256.0F / 360.0F));
    }

    private void send(@NotNull PacketContainer packet) {
        if (viewer.isOnline()) {
            protocolManager.sendServerPacket(viewer, packet);
        }
    }
}
