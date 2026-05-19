package io.github.maaasu.astralRecord.feature.mob.view;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.PacketContainer;
import io.github.maaasu.astralRecord.feature.mob.model.MobInstance;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * 実体MobをProtocolLibのEntityパケットとして表示します。
 */
public class PacketMobView {

    private final Plugin plugin;
    private final ProtocolManager protocolManager;

    /**
     * コンストラクタ。
     *
     * @param plugin プラグイン
     */
    public PacketMobView(@NotNull Plugin plugin) {
        this.plugin = plugin;
        this.protocolManager = ProtocolLibrary.getProtocolManager();
    }

    /**
     * 指定プレイヤーにMobのspawn packetを送信します。
     *
     * @param player   送信先プレイヤー
     * @param instance Mobインスタンス
     */
    public void spawn(@NotNull Player player, @NotNull MobInstance instance) {
        PacketContainer packet = protocolManager.createPacket(PacketType.Play.Server.SPAWN_ENTITY);
        Location location = instance.location();

        packet.getIntegers().write(0, instance.entityId());
        packet.getUUIDs().write(0, instance.instanceId());
        packet.getEntityTypeModifier().write(0, instance.template().entityType());
        packet.getDoubles()
                .write(0, location.getX())
                .write(1, location.getY())
                .write(2, location.getZ());
        packet.getBytes()
                .writeSafely(0, toAngle(location.getPitch()))
                .writeSafely(1, toAngle(location.getYaw()))
                .writeSafely(2, toAngle(location.getYaw()));

        send(player, packet);
    }

    /**
     * 指定プレイヤーからMobを削除します。
     *
     * @param player   送信先プレイヤー
     * @param instance Mobインスタンス
     */
    public void destroy(@NotNull Player player, @NotNull MobInstance instance) {
        PacketContainer packet = protocolManager.createPacket(PacketType.Play.Server.ENTITY_DESTROY);
        packet.getIntLists().write(0, List.of(instance.entityId()));
        send(player, packet);
    }

    private byte toAngle(float degrees) {
        return (byte) (degrees * 256.0F / 360.0F);
    }

    private void send(@NotNull Player player, @NotNull PacketContainer packet) {
        try {
            protocolManager.sendServerPacket(player, packet);
        } catch (RuntimeException ex) {
            plugin.getLogger().warning("Failed to send mob packet: " + ex.getMessage());
        }
    }
}
