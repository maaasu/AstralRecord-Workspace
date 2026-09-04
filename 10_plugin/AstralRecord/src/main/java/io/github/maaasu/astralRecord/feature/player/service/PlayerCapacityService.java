package io.github.maaasu.astralRecord.feature.player.service;

import io.github.maaasu.astralRecord.feature.user.model.UserPermission;
import io.github.maaasu.astralRecord.infrastructure.config.ConfigProperties;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * プレイヤーの権限別接続人数制限と、接続中プレイヤー数の予約を管理します。
 * <p>
 * 通常枠、寄付者追加枠、管理者追加枠を分離し、接続前イベントが並行して処理されても
 * 同じ枠を二重に消費しないよう予約操作を直列化します。
 */
public final class PlayerCapacityService {

    private int onlinePlayerCount;
    private final Object reservationLock = new Object();
    private final Set<UUID> reservedPlayerUuids = new HashSet<>();
    private @Nullable Integer originalServerMaxPlayers;

    /**
     * Bukkit のオンラインプレイヤー数を初期値にした本番用サービスを生成します。
     * このコンストラクタは Bukkit メインスレッドから呼び出してください。
     * 以後のオンライン人数は Bukkit API を非同期参照せず、参加・退出イベントで更新します。
     *
     * @throws IllegalStateException メインスレッド以外から呼び出した場合
     */
    public PlayerCapacityService() {
        this(getInitialOnlinePlayerCount());
    }

    PlayerCapacityService(int initialOnlinePlayerCount) {
        this.onlinePlayerCount = Math.max(0, initialOnlinePlayerCount);
    }

    /**
     * 指定権限のプレイヤーが利用できる接続上限を返します。
     * <p>
     * `DONOR` 以上は通常枠と寄付者追加枠を、`ADMIN` 以上はさらに管理者追加枠を利用できます。
     *
     * @param permission 判定する user.permission 値
     * @return 指定権限が利用できる接続上限
     */
    public int getMaximumPlayersForPermission(int permission) {
        ConfigProperties properties = ConfigProperties.getInstance();
        int normalPlayers = Math.max(1, properties.getPlayerCapacityMaxPlayers());
        int donorExtraPlayers = Math.max(0, properties.getPlayerCapacityDonorExtraPlayers());
        int adminExtraPlayers = Math.max(0, properties.getPlayerCapacityAdminExtraPlayers());

        if (permission >= UserPermission.ADMIN.getValue()) {
            return safeAdd(safeAdd(normalPlayers, donorExtraPlayers), adminExtraPlayers);
        }
        if (permission >= UserPermission.DONOR.getValue()) {
            return safeAdd(normalPlayers, donorExtraPlayers);
        }
        return normalPlayers;
    }

    /**
     * 設定値から算出したサーバー全体の接続上限を返します。
     *
     * @return 通常枠、寄付者追加枠、管理者追加枠の合計
     */
    public int getConfiguredMaximumPlayers() {
        ConfigProperties properties = ConfigProperties.getInstance();
        return safeAdd(
                safeAdd(
                        Math.max(1, properties.getPlayerCapacityMaxPlayers()),
                        Math.max(0, properties.getPlayerCapacityDonorExtraPlayers())
                ),
                Math.max(0, properties.getPlayerCapacityAdminExtraPlayers())
        );
    }

    /**
     * 接続前のプレイヤーを人数枠へ予約します。
     * <p>
     * 予約済みの UUID は同じ接続試行の再入として扱い、二重予約せず許可します。
     *
     * @param playerUuid 接続するプレイヤー UUID
     * @param permission user.permission 値
     * @return 利用可能な枠を確保できた場合は {@code true}
     */
    public boolean tryReserve(@Nullable UUID playerUuid, int permission) {
        if (playerUuid == null) {
            return false;
        }

        synchronized (reservationLock) {
            if (reservedPlayerUuids.contains(playerUuid)) {
                return true;
            }

            int occupiedPlayers = safeAdd(onlinePlayerCount, reservedPlayerUuids.size());
            if (occupiedPlayers >= getMaximumPlayersForPermission(permission)) {
                return false;
            }

            reservedPlayerUuids.add(playerUuid);
            return true;
        }
    }

    /**
     * 接続失敗または参加前切断が確定したプレイヤーの接続前予約を解放します。
     *
     * @param playerUuid 解放するプレイヤー UUID
     */
    public void release(@Nullable UUID playerUuid) {
        if (playerUuid == null) {
            return;
        }
        synchronized (reservationLock) {
            reservedPlayerUuids.remove(playerUuid);
        }
    }

    /**
     * 参加が確定したプレイヤーをオンライン人数へ加算し、接続前予約を解放します。
     * Bukkit メインスレッドの {@code PlayerJoinEvent} から呼び出してください。
     *
     * @param playerUuid 参加したプレイヤー UUID
     */
    public void recordPlayerJoin(@NotNull UUID playerUuid) {
        synchronized (reservationLock) {
            onlinePlayerCount = safeAdd(onlinePlayerCount, 1);
            reservedPlayerUuids.remove(playerUuid);
        }
    }

    /**
     * 退出したプレイヤーをオンライン人数から減算し、接続前予約を解放します。
     * Bukkit メインスレッドの {@code PlayerQuitEvent} から呼び出してください。
     *
     * @param playerUuid 退出したプレイヤー UUID
     */
    public void recordPlayerQuit(@NotNull UUID playerUuid) {
        synchronized (reservationLock) {
            onlinePlayerCount = Math.max(0, onlinePlayerCount - 1);
            reservedPlayerUuids.remove(playerUuid);
        }
    }

    /**
     * Bukkit の実行時最大人数を設定合計へ変更します。
     * <p>
     * Bukkit のサーバー最大人数が設定合計より小さい場合でも、寄付者・管理者追加枠を利用できるように
     * 起動時の実行時値を上書きします。このメソッドは Bukkit メインスレッドから呼び出してください。
     *
     * @param server 最大人数を更新する Bukkit サーバー
     * @throws IllegalStateException メインスレッド以外から呼び出した場合
     */
    public void applyConfiguredMaximum(@NotNull Server server) {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("Server maximum must be changed on the primary thread");
        }
        synchronized (reservationLock) {
            if (originalServerMaxPlayers == null) {
                originalServerMaxPlayers = server.getMaxPlayers();
            }
            server.setMaxPlayers(getConfiguredMaximumPlayers());
        }
    }

    /**
     * {@link #applyConfiguredMaximum(Server)} で変更した実行時最大人数を起動前の値へ戻します。
     * <p>
     * このメソッドは Bukkit メインスレッドから呼び出してください。
     *
     * @param server 最大人数を復元する Bukkit サーバー
     * @throws IllegalStateException メインスレッド以外から呼び出した場合
     */
    public void restoreConfiguredMaximum(@NotNull Server server) {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("Server maximum must be restored on the primary thread");
        }
        synchronized (reservationLock) {
            if (originalServerMaxPlayers != null) {
                server.setMaxPlayers(originalServerMaxPlayers);
                originalServerMaxPlayers = null;
            }
            reservedPlayerUuids.clear();
        }
    }

    private static int getInitialOnlinePlayerCount() {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("Initial online player count must be read on the primary thread");
        }
        return Bukkit.getOnlinePlayers().size();
    }

    private int safeAdd(int first, int second) {
        long total = (long) first + second;
        return total >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) total;
    }
}
