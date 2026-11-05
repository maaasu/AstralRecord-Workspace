package io.github.maaasu.astralRecord.feature.item.view;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import io.github.maaasu.astralRecord.shared.display.PacketEntityIdAllocator;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Geyser へ本人の長押し用仮想トライデントに重ねる手持ちモデルを通知します。
 * このクラスの状態は Bukkit メインスレッドで扱います。
 */
final class BedrockHeldWeaponDisplay {
    static final int MAX_MODEL_ID = 0xFF_FFFF;

    // Geyser 拡張の HeldWeaponModel と共有する TEXT_DISPLAY の UUID 形式です。
    private static final long MARKER_MAGIC = 0x4152574D4F44454CL;
    private static final long RENEWAL_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(2);

    private final Map<UUID, Signal> signals = new HashMap<>();

    /**
     * モデル番号が変わったときに通知し、表示中は2秒ごとに更新します。
     *
     * @param player 通知を受け取る統合版プレイヤー
     * @param modelId 使用するモデル番号。バニラ表示の場合は0
     */
    void sync(@NotNull Player player, int modelId) {
        UUID playerId = player.getUniqueId();
        long now = System.nanoTime();
        Signal previous = signals.get(playerId);
        if (previous != null && previous.modelId() == modelId
            && ((previous.sent() && modelId == 0) || now - previous.atNanos() < RENEWAL_INTERVAL_NANOS)) {
            return;
        }

        boolean sent = false;
        try {
            send(player, modelId);
            sent = true;
        } catch (RuntimeException exception) {
            Logger.log(LogId.W_5211, exception, playerId, modelId);
        }
        signals.put(playerId, new Signal(modelId, now, sent));
    }

    /**
     * 退出したプレイヤーの送信履歴を除去します。
     *
     * @param playerId 対象プレイヤーの UUID
     */
    void forget(@NotNull UUID playerId) {
        signals.remove(playerId);
    }

    /**
     * Geyser が受け取るマーカーを本人へ送り、直後に削除します。
     *
     * @param player 通知を受け取る統合版プレイヤー
     * @param modelId マーカーに入れるモデル番号
     */
    private void send(@NotNull Player player, int modelId) {
        var protocol = ProtocolLibrary.getProtocolManager();
        int entityId = PacketEntityIdAllocator.nextEntityId();
        var location = player.getLocation();

        var spawn = protocol.createPacket(PacketType.Play.Server.SPAWN_ENTITY);
        spawn.getIntegers().write(0, entityId);
        spawn.getUUIDs().write(0, new UUID(MARKER_MAGIC, Long.MIN_VALUE | modelId));
        spawn.getEntityTypeModifier().write(0, EntityType.TEXT_DISPLAY);
        spawn.getDoubles().write(0, location.getX());
        spawn.getDoubles().write(1, location.getY());
        spawn.getDoubles().write(2, location.getZ());

        var remove = protocol.createPacket(PacketType.Play.Server.ENTITY_DESTROY);
        remove.getIntLists().write(0, List.of(entityId));
        protocol.sendServerPacket(player, spawn, false);
        protocol.sendServerPacket(player, remove, false);
    }

    private record Signal(int modelId, long atNanos, boolean sent) {}
}
