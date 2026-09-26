package io.github.maaasu.astralRecord.feature.player.event;

import com.destroystokyo.paper.event.player.PlayerConnectionCloseEvent;
import io.github.maaasu.astralRecord.core.event.AbstractEventHandler;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgResource;
import io.github.maaasu.astralRecord.feature.player.service.PlayerCapacityService;
import io.github.maaasu.astralRecord.feature.network.NetworkAuthorityRegistry;
import io.github.maaasu.astralRecord.feature.network.NetworkChannelAccessRegistry;
import io.github.maaasu.astralRecord.feature.user.model.UserPermission;
import io.github.maaasu.astralRecord.feature.user.model.UserModel;
import io.github.maaasu.astralRecord.feature.user.service.UserService;
import io.github.maaasu.astralRecord.feature.vip.repository.AccountBenefitsRepository;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * 権限別のプレイヤー接続人数制限を接続前イベントへ適用します。
 * <p>
 * {@link UserService} の接続前処理より後に実行し、既存ユーザーの権限を確認してから
 * 通常枠・寄付者枠・管理者枠のいずれかへ接続を予約します。
 */
public final class PlayerCapacityEventHandler extends AbstractEventHandler {

    private final UserService userService;
    private final PlayerCapacityService playerCapacityService;
    private final boolean networkManaged;

    /**
     * 接続人数制限イベントハンドラーを初期化します。
     *
     * @param userService 接続プレイヤーの権限を取得するユーザーサービス
     * @param playerCapacityService 接続人数制限サービス
     */
    public PlayerCapacityEventHandler(
            @NotNull UserService userService,
            @NotNull PlayerCapacityService playerCapacityService
    ) {
        this(userService, playerCapacityService, false);
    }

    /**
     * 接続人数制限イベントハンドラーを初期化します。
     *
     * @param userService 接続プレイヤーの権限を取得するユーザーサービス
     * @param playerCapacityService 接続人数制限サービス
     * @param networkManaged Proxyが接続人数を制御する場合は{@code true}
     */
    public PlayerCapacityEventHandler(
            @NotNull UserService userService,
            @NotNull PlayerCapacityService playerCapacityService,
            boolean networkManaged
    ) {
        this.userService = userService;
        this.playerCapacityService = playerCapacityService;
        this.networkManaged = networkManaged;
    }

    /**
     * 接続前イベントへ権限別の接続上限を適用します。
     * <p>
     * whitelist や BAN などで既に拒否されているイベントには追加処理を行いません。
     *
     * @param event 接続前イベント
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onAsyncPreLogin(@NotNull AsyncPlayerPreLoginEvent event) {
        if (networkManaged) {
            return;
        }
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            return;
        }

        UUID playerUuid = event.getUniqueId();
        UserModel user = userService.getUser(playerUuid);
        int permission = user == null
                ? UserPermission.PLAYER.getValue()
                : user.getPermission();
        permission = NetworkAuthorityRegistry.effectivePermission(playerUuid, permission);
        boolean activeVip = resolveActiveVip(user);
        if (!playerCapacityService.tryReserve(playerUuid, permission, activeVip)) {
            event.disallow(
                    AsyncPlayerPreLoginEvent.Result.KICK_FULL,
                    PlayerMsgResource.getComponent(PlayerMsgId.P_7140.getId())
            );
        }
    }

    /**
     * 非管理サーバーの接続前に選択中アカウントのVIPをAPIから確認します。
     * @param user 接続ユーザー。未作成時はnull
     * @return 有効なVIP期限が確認できた場合true。取得失敗時は通常枠
     */
    private boolean resolveActiveVip(UserModel user) {
        if (user == null || user.getAccountId() == null) return false;
        try {
            var snapshot = AccountBenefitsRepository.snapshot(
                new AccountBenefitsRepository().request(user.getAccountId(), "", null));
            return !"NONE".equals(snapshot.tier()) && snapshot.expiresAt() != null
                && snapshot.expiresAt().isAfter(java.time.Instant.now());
        } catch (Exception exception) {
            if (exception instanceof InterruptedException) Thread.currentThread().interrupt();
            Logger.error(LogId.E_7600, exception, user.getAccountId());
            return false;
        }
    }

    /**
     * Bukkit 標準のログイン段階で拒否された接続の予約を解放します。
     *
     * @param event ログインイベント
     */
    @SuppressWarnings("deprecation")
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerLogin(@NotNull PlayerLoginEvent event) {
        if (networkManaged) {
            if (event.getResult() == PlayerLoginEvent.Result.KICK_FULL
                && NetworkChannelAccessRegistry.isAllowed(event.getPlayer().getUniqueId())) {
                event.allow();
            }
            return;
        }
        if (event.getResult() != PlayerLoginEvent.Result.ALLOWED) {
            playerCapacityService.release(event.getPlayer().getUniqueId());
        }
    }

    /**
     * ワールド参加前に切断された接続の予約を解放します。
     * このPaperイベントは非同期で発火する場合があるため、Bukkit APIを参照せず予約だけを解放します。
     *
     * @param event 接続切断イベント
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerConnectionClose(@NotNull PlayerConnectionCloseEvent event) {
        if (networkManaged) {
            return;
        }
        playerCapacityService.release(event.getPlayerUniqueId());
    }

    /**
     * 参加が確定したプレイヤーの接続前予約を解放します。
     *
     * @param event 参加イベント
     */
    @EventHandler(priority = EventPriority.NORMAL)
    public void onPlayerJoin(@NotNull PlayerJoinEvent event) {
        if (networkManaged) {
            return;
        }
        playerCapacityService.recordPlayerJoin(event.getPlayer().getUniqueId());
    }

    /**
     * 退出したプレイヤーの接続前予約を解放します。
     *
     * @param event 退出イベント
     */
    @EventHandler(priority = EventPriority.NORMAL)
    public void onPlayerQuit(@NotNull PlayerQuitEvent event) {
        if (networkManaged) {
            return;
        }
        playerCapacityService.recordPlayerQuit(event.getPlayer().getUniqueId());
    }
}
