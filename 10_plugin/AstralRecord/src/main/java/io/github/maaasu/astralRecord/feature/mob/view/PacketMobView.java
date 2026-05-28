package io.github.maaasu.astralRecord.feature.mob.view;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.PacketContainer;
import io.github.maaasu.astralRecord.feature.mob.model.MobInstance;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 実体 Mob を ProtocolLib のパケットエンティティとしてクライアントに表示するビュー。
 *
 * <p>バニラエンティティを {@code World.spawnEntity} で生成しないため、本ビューがクライアント側に
 * 見える Mob を完全に制御する。当たり判定（クリック検出・ヒットボックス推定）はバニラの
 * {@code EntityType} を参考にプラグイン側で独自実装する。</p>
 */
public class PacketMobView {

    private final Plugin plugin;
    private final ProtocolManager protocolManager;

    /** インスタンスごとに、最後に送出した座標を保持（差分計算用）。 */
    private final Map<UUID, Location> lastSentLocation = new HashMap<>();

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
     * 指定プレイヤーに Mob の spawn パケットを送信します。
     *
     * @param player   送信先プレイヤー
     * @param instance Mob インスタンス
     */
    public void spawn(@NotNull Player player, @NotNull MobInstance instance) {
        PacketContainer packet = protocolManager.createPacket(PacketType.Play.Server.SPAWN_ENTITY);
        Location location = instance.currentLocation();

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
        lastSentLocation.put(instance.instanceId(), location.clone());
    }

    /**
     * 指定プレイヤーから Mob を消します（クライアント側エンティティを削除）。
     *
     * @param player   送信先プレイヤー
     * @param instance Mob インスタンス
     */
    public void destroy(@NotNull Player player, @NotNull MobInstance instance) {
        PacketContainer packet = protocolManager.createPacket(PacketType.Play.Server.ENTITY_DESTROY);
        packet.getIntLists().write(0, List.of(instance.entityId()));
        send(player, packet);
        lastSentLocation.remove(instance.instanceId());
    }

    /**
     * 指定プレイヤーへ Mob の位置・回転更新パケットを送信します。
     * 前回送出位置との差分が小さければ相対移動、大きければテレポートを使用します。
     * 位置パケット送信後に頭部回転パケットも送信します。
     *
     * @param player   送信先プレイヤー
     * @param instance Mob インスタンス
     */
    public void move(@NotNull Player player, @NotNull MobInstance instance) {
        Location current = instance.currentLocation();
        Location previous = lastSentLocation.get(instance.instanceId());

        if (previous == null || previous.getWorld() != current.getWorld()) {
            sendTeleport(player, instance, current);
        } else {
            double dx = current.getX() - previous.getX();
            double dy = current.getY() - previous.getY();
            double dz = current.getZ() - previous.getZ();
            double distanceSq = dx * dx + dy * dy + dz * dz;

            // 8 ブロック超の移動はテレポート、それ未満は相対移動とする
            if (distanceSq > 64.0) {
                sendTeleport(player, instance, current);
            } else {
                sendRelativeMove(player, instance, current, dx, dy, dz);
            }
        }

        sendHeadRotation(player, instance);
    }

    /**
     * 指定プレイヤーへ Mob のアニメーションパケットを送信します。
     *
     * @param player        送信先プレイヤー
     * @param instance      Mob インスタンス
     * @param animationCode アニメーションコード（0: swing main hand, 1: take damage 等）
     */
    public void animate(@NotNull Player player, @NotNull MobInstance instance, int animationCode) {
        PacketContainer packet = protocolManager.createPacket(PacketType.Play.Server.ANIMATION);
        packet.getIntegers().write(0, instance.entityId()).write(1, animationCode);
        send(player, packet);
    }

    private void sendTeleport(@NotNull Player player, @NotNull MobInstance instance, @NotNull Location target) {
        PacketContainer packet = protocolManager.createPacket(PacketType.Play.Server.ENTITY_TELEPORT);
        packet.getIntegers().write(0, instance.entityId());
        packet.getDoubles()
                .write(0, target.getX())
                .write(1, target.getY())
                .write(2, target.getZ());
        packet.getBytes()
                .writeSafely(0, toAngle(target.getYaw()))
                .writeSafely(1, toAngle(target.getPitch()));
        packet.getBooleans().writeSafely(0, false);
        send(player, packet);
        lastSentLocation.put(instance.instanceId(), target.clone());
    }

    private void sendRelativeMove(
            @NotNull Player player,
            @NotNull MobInstance instance,
            @NotNull Location target,
            double dx, double dy, double dz) {
        // 1 ブロックあたり 4096 単位の short fixed-point
        short dxShort = (short) (dx * 4096);
        short dyShort = (short) (dy * 4096);
        short dzShort = (short) (dz * 4096);

        PacketContainer packet = protocolManager.createPacket(PacketType.Play.Server.REL_ENTITY_MOVE_LOOK);
        packet.getIntegers().write(0, instance.entityId());
        packet.getShorts().write(0, dxShort).write(1, dyShort).write(2, dzShort);
        packet.getBytes()
                .writeSafely(0, toAngle(target.getYaw()))
                .writeSafely(1, toAngle(target.getPitch()));
        packet.getBooleans().writeSafely(0, false);
        send(player, packet);
        lastSentLocation.put(instance.instanceId(), target.clone());
    }

    /**
     * 指定プレイヤーへ Mob の頭部回転パケット（ENTITY_HEAD_ROTATION）を送信します。
     * 体の向きと独立して頭部 yaw を設定します。
     *
     * @param player   送信先プレイヤー
     * @param instance Mob インスタンス
     */
    private void sendHeadRotation(@NotNull Player player, @NotNull MobInstance instance) {
        PacketContainer packet = protocolManager.createPacket(PacketType.Play.Server.ENTITY_HEAD_ROTATION);
        packet.getIntegers().write(0, instance.entityId());
        packet.getBytes().writeSafely(0, toAngle(instance.headYaw()));
        send(player, packet);
    }

    /**
     * 角度（度）を Minecraft の 1/256 単位の byte 表現に変換します。
     *
     * @param degrees 角度（度）
     * @return byte 表現
     */
    private byte toAngle(float degrees) {
        return (byte) (degrees * 256.0F / 360.0F);
    }

    private void send(@NotNull Player player, @NotNull PacketContainer packet) {
        try {
            protocolManager.sendServerPacket(player, packet);
        } catch (RuntimeException ex) {
            Logger.log(LogId.W_5706, ex.getMessage());
        }
    }
}
