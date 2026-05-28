package io.github.maaasu.astralRecord.shared.display;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.wrappers.WrappedChatComponent;
import com.comphenix.protocol.wrappers.WrappedDataValue;
import com.comphenix.protocol.wrappers.WrappedDataWatcher;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import io.github.maaasu.astralRecord.infrastructure.util.ColorCodeUtil;
import org.bukkit.Location;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ProtocolLib の packet entity として各種 display を送信する共有サービスです。
 *
 * <p>実体 Entity をワールドへ spawn せず、指定プレイヤーのクライアントだけに
 * TextDisplay / ItemDisplay / Item の spawn・metadata・teleport・destroy を送信します。</p>
 */
public class PacketDisplayService {

    private static final int ENTITY_ID_BASE = 3_000_000;
    private static final AtomicInteger ENTITY_ID_SEQUENCE = new AtomicInteger(ENTITY_ID_BASE);

    private static final int ENTITY_METADATA_NO_GRAVITY_INDEX = 5;

    private static final int ITEM_ENTITY_METADATA_ITEM_INDEX = 8;

    private static final int DISPLAY_METADATA_BILLBOARD_INDEX = 15;
    private static final byte BILLBOARD_CENTER = 3;

    private static final int TEXT_DISPLAY_METADATA_TEXT_INDEX = 23;
    private static final int TEXT_DISPLAY_METADATA_LINE_WIDTH_INDEX = 24;
    private static final int TEXT_DISPLAY_METADATA_BACKGROUND_COLOR_INDEX = 25;
    private static final int TEXT_DISPLAY_METADATA_TEXT_OPACITY_INDEX = 26;
    private static final int TEXT_DISPLAY_METADATA_FLAGS_INDEX = 27;
    private static final int TEXT_LINE_WIDTH = 240;
    private static final int TEXT_TRANSPARENT_BACKGROUND = 0;
    private static final byte TEXT_FULL_OPACITY = (byte) 0xFF;
    private static final byte TEXT_DEFAULT_FLAGS = 0;

    private static final int ITEM_DISPLAY_METADATA_ITEM_INDEX = 23;

    private final ProtocolManager protocolManager;

    /**
     * サービスを初期化します。
     *
     * @param plugin プラグイン本体。ProtocolLib 取得前提の明示依存として受け取ります。
     */
    public PacketDisplayService(@NotNull Plugin plugin) {
        this.protocolManager = ProtocolLibrary.getProtocolManager();
    }

    /**
     * packet display 用の entity 識別子を採番します。
     *
     * @return entityId と UUID を持つ display handle
     */
    @NotNull
    public DisplayHandle allocateHandle() {
        return new DisplayHandle(ENTITY_ID_SEQUENCE.incrementAndGet(), UUID.randomUUID());
    }

    /**
     * TextDisplay を指定プレイヤーへ spawn します。
     *
     * @param viewer   送信先プレイヤー
     * @param handle   display の識別子
     * @param location 表示位置
     * @param text     表示する legacy color code 対応テキスト
     */
    public void spawnTextDisplay(
            @NotNull Player viewer,
            @NotNull DisplayHandle handle,
            @NotNull Location location,
            @NotNull String text
    ) {
        spawnEntity(viewer, handle, EntityType.TEXT_DISPLAY, location);
        updateTextDisplay(viewer, handle.entityId(), text);
    }

    /**
     * ItemDisplay を指定プレイヤーへ spawn します。
     *
     * @param viewer   送信先プレイヤー
     * @param handle   display の識別子
     * @param location 表示位置
     * @param item     表示するアイテム
     */
    public void spawnItemDisplay(
            @NotNull Player viewer,
            @NotNull DisplayHandle handle,
            @NotNull Location location,
            @NotNull ItemStack item
    ) {
        spawnEntity(viewer, handle, EntityType.ITEM_DISPLAY, location);
        updateItemDisplay(viewer, handle.entityId(), item);
    }

    /**
     * ドロップアイテム型の packet entity を指定プレイヤーへ spawn します。
     *
     * @param viewer   送信先プレイヤー
     * @param handle   display の識別子
     * @param location 表示位置
     * @param item     表示するアイテム
     */
    public void spawnDroppedItem(
            @NotNull Player viewer,
            @NotNull DisplayHandle handle,
            @NotNull Location location,
            @NotNull ItemStack item
    ) {
        spawnEntity(viewer, handle, EntityType.ITEM, location);
        updateDroppedItem(viewer, handle.entityId(), item);
    }

    /**
     * TextDisplay のテキスト metadata を更新します。
     *
     * @param viewer   送信先プレイヤー
     * @param entityId 更新対象 entityId
     * @param text     新しい legacy color code 対応テキスト
     */
    public void updateTextDisplay(@NotNull Player viewer, int entityId, @NotNull String text) {
        List<WrappedDataValue> values = new ArrayList<>();
        values.add(dataValue(
                TEXT_DISPLAY_METADATA_TEXT_INDEX,
                WrappedDataWatcher.Registry.getChatComponentSerializer(),
                WrappedChatComponent.fromLegacyText(toLegacyText(text))
        ));
        values.add(dataValue(TEXT_DISPLAY_METADATA_LINE_WIDTH_INDEX, Integer.class, TEXT_LINE_WIDTH));
        values.add(dataValue(TEXT_DISPLAY_METADATA_BACKGROUND_COLOR_INDEX, Integer.class, TEXT_TRANSPARENT_BACKGROUND));
        values.add(dataValue(TEXT_DISPLAY_METADATA_TEXT_OPACITY_INDEX, Byte.class, TEXT_FULL_OPACITY));
        values.add(dataValue(TEXT_DISPLAY_METADATA_FLAGS_INDEX, Byte.class, TEXT_DEFAULT_FLAGS));
        values.add(dataValue(DISPLAY_METADATA_BILLBOARD_INDEX, Byte.class, BILLBOARD_CENTER));
        values.add(dataValue(ENTITY_METADATA_NO_GRAVITY_INDEX, Boolean.class, true));
        sendMetadata(viewer, entityId, values);
    }

    /**
     * ItemDisplay のアイテム metadata を更新します。
     *
     * @param viewer   送信先プレイヤー
     * @param entityId 更新対象 entityId
     * @param item     新しい表示アイテム
     */
    public void updateItemDisplay(@NotNull Player viewer, int entityId, @NotNull ItemStack item) {
        sendMetadata(viewer, entityId, List.of(
                dataValue(ITEM_DISPLAY_METADATA_ITEM_INDEX,
                        WrappedDataWatcher.Registry.getItemStackSerializer(false),
                        item.clone()),
                dataValue(DISPLAY_METADATA_BILLBOARD_INDEX, Byte.class, BILLBOARD_CENTER),
                dataValue(ENTITY_METADATA_NO_GRAVITY_INDEX, Boolean.class, true)
        ));
    }

    /**
     * ドロップアイテム型 packet entity のアイテム metadata を更新します。
     *
     * @param viewer   送信先プレイヤー
     * @param entityId 更新対象 entityId
     * @param item     新しい表示アイテム
     */
    public void updateDroppedItem(@NotNull Player viewer, int entityId, @NotNull ItemStack item) {
        sendMetadata(viewer, entityId, List.of(
                dataValue(ITEM_ENTITY_METADATA_ITEM_INDEX,
                        WrappedDataWatcher.Registry.getItemStackSerializer(false),
                        item.clone()),
                dataValue(ENTITY_METADATA_NO_GRAVITY_INDEX, Boolean.class, true)
        ));
    }

    /**
     * packet display entity を指定位置へ teleport します。
     *
     * @param viewer   送信先プレイヤー
     * @param entityId 移動対象 entityId
     * @param location 新しい位置
     */
    public void teleport(@NotNull Player viewer, int entityId, @NotNull Location location) {
        PacketContainer packet = protocolManager.createPacket(PacketType.Play.Server.ENTITY_TELEPORT);
        packet.getIntegers().write(0, entityId);
        writeTeleportPosition(packet, location);
        packet.getBooleans().writeSafely(0, false);
        send(viewer, packet);
    }

    /**
     * packet display entity を指定プレイヤーのクライアントから破棄します。
     *
     * @param viewer   送信先プレイヤー
     * @param entityId 破棄対象 entityId
     */
    public void destroy(@NotNull Player viewer, int entityId) {
        PacketContainer packet = protocolManager.createPacket(PacketType.Play.Server.ENTITY_DESTROY);
        packet.getIntLists().write(0, List.of(entityId));
        send(viewer, packet);
    }

    private void spawnEntity(
            @NotNull Player viewer,
            @NotNull DisplayHandle handle,
            @NotNull EntityType entityType,
            @NotNull Location location
    ) {
        PacketContainer packet = protocolManager.createPacket(PacketType.Play.Server.SPAWN_ENTITY);
        packet.getIntegers().write(0, handle.entityId());
        packet.getUUIDs().write(0, handle.uuid());
        packet.getEntityTypeModifier().write(0, entityType);
        packet.getDoubles()
                .write(0, location.getX())
                .write(1, location.getY())
                .write(2, location.getZ());
        packet.getBytes()
                .writeSafely(0, toAngle(location.getPitch()))
                .writeSafely(1, toAngle(location.getYaw()))
                .writeSafely(2, toAngle(location.getYaw()));
        send(viewer, packet);
    }

    private void sendMetadata(@NotNull Player viewer, int entityId, @NotNull List<WrappedDataValue> values) {
        PacketContainer packet = protocolManager.createPacket(PacketType.Play.Server.ENTITY_METADATA);
        packet.getIntegers().write(0, entityId);
        packet.getDataValueCollectionModifier().write(0, values);
        send(viewer, packet);
    }

    private WrappedDataValue dataValue(int index, @NotNull Type type, @NotNull Object value) {
        return dataValue(index, WrappedDataWatcher.Registry.get(type), value);
    }

    private WrappedDataValue dataValue(
            int index,
            @NotNull WrappedDataWatcher.Serializer serializer,
            @NotNull Object value
    ) {
        return WrappedDataValue.fromWrappedValue(index, serializer, value);
    }

    private byte toAngle(float degrees) {
        return (byte) (degrees * 256.0F / 360.0F);
    }

    private void writeTeleportPosition(@NotNull PacketContainer packet, @NotNull Location location) {
        var positionMoveRotation = packet.getStructures().read(0);
        positionMoveRotation.getVectors()
                .write(0, location.toVector())
                .write(1, new Vector());
        positionMoveRotation.getFloat()
                .write(0, location.getYaw())
                .write(1, location.getPitch());
    }

    private String toLegacyText(@NotNull String text) {
        return ColorCodeUtil.translateAlternateColorCodes(text);
    }

    private void send(@NotNull Player viewer, @NotNull PacketContainer packet) {
        try {
            protocolManager.sendServerPacket(viewer, packet);
        } catch (RuntimeException ex) {
            Logger.log(LogId.W_5706, ex.getMessage());
        }
    }

    /**
     * packet display entity のクライアント側識別子です。
     *
     * @param entityId packet entityId
     * @param uuid     packet entity UUID
     */
    public record DisplayHandle(int entityId, @NotNull UUID uuid) {
    }
}
