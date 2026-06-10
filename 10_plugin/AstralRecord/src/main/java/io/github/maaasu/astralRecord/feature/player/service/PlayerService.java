package io.github.maaasu.astralRecord.feature.player.service;

import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.feature.account.model.AccountModel;
import io.github.maaasu.astralRecord.feature.account.service.AccountService;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.inventory.state.InventoryPersistence;
import io.github.maaasu.astralRecord.feature.inventory.state.PlayerInventoryState;
import io.github.maaasu.astralRecord.feature.inventory.state.PlayerInventoryStateRegistry;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.save.PlayerSaveCoordinator;
import io.github.maaasu.astralRecord.feature.player.save.PlayerSaveTrigger;
import io.github.maaasu.astralRecord.feature.status.service.StatusService;
import io.github.maaasu.astralRecord.feature.user.model.UserModel;
import io.github.maaasu.astralRecord.feature.user.service.UserService;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * プレイヤー機能のビジネスロジックを担うサービスクラス。
 * ログイン時の AstPlayer 構築・キャッシュ登録・OP権限付与を管理します。
 */
public class PlayerService {

    private final UserService userService;
    private final AccountService accountService;
    private final InventoryService inventoryService;
    private final InventoryPersistence inventoryPersistence;
    private final PlayerInventoryStateRegistry inventoryStateRegistry;
    private final StatusService statusService;
    private final PlayerSaveCoordinator playerSaveCoordinator;

    public PlayerService(
        UserService userService,
        AccountService accountService,
        InventoryService inventoryService,
        InventoryPersistence inventoryPersistence,
        PlayerInventoryStateRegistry inventoryStateRegistry,
        StatusService statusService,
        PlayerSaveCoordinator playerSaveCoordinator
    ) {
        this.userService = userService;
        this.accountService = accountService;
        this.inventoryService = inventoryService;
        this.inventoryPersistence = inventoryPersistence;
        this.inventoryStateRegistry = inventoryStateRegistry;
        this.statusService = statusService;
        this.playerSaveCoordinator = playerSaveCoordinator;
    }

    /**
     * プレイヤーのログインに必要な外部データを取得します。
     * <p>
     * Repository 層で HTTP API / DB 通信が発生するため、必ず Bukkit メインスレッド外から呼び出してください。
     *
     * @param playerUuid ログインしたプレイヤー UUID
     * @param playerName ログ出力用のプレイヤー名
     * @return ログイン反映に必要なデータ。取得できない場合は null
     */
    public @Nullable PlayerJoinData loadPlayerJoinData(@NotNull UUID playerUuid, @NotNull String playerName) {
        var user = loadPlayerJoinUser(playerUuid, playerName);
        if (user == null) {
            return null;
        }

        var account = loadPlayerJoinAccount(user, playerName);
        if (account == null) {
            return null;
        }

        // インベントリ・装備ロードアウトを API から取得し、in-memory state として構築する。
        // 以降のゲームロジックは PlayerInventoryState のみを参照し、API 通信は autosave/logout のみで発生する。
        loadPlayerJoinInventoryState(account);

        return new PlayerJoinData(user, account);
    }

    /**
     * プレイヤー参加時に必要なユーザーデータを取得します。
     * <p>
     * API 通信を伴うため Bukkit メインスレッド外から呼び出してください。取得できない場合は警告ログを残し、
     * 後続の参加ロードを中断できるよう {@code null} を返します。
     *
     * @param playerUuid ログインしたプレイヤー UUID
     * @param playerName ログ出力用のプレイヤー名
     * @return ユーザーデータ。取得できない場合は {@code null}
     */
    public @Nullable UserModel loadPlayerJoinUser(@NotNull UUID playerUuid, @NotNull String playerName) {
        var user = userService.getUser(playerUuid);
        if (user == null) {
            Logger.log(LogId.W_5070, playerName);
        }
        return user;
    }

    /**
     * プレイヤー参加時に使用する選択中アカウントを取得します。
     * <p>
     * API 通信を伴うため Bukkit メインスレッド外から呼び出してください。
     *
     * @param user ユーザーデータ
     * @param playerName ログ出力用のプレイヤー名
     * @return 選択中アカウント。取得できない場合は {@code null}
     */
    public @Nullable AccountModel loadPlayerJoinAccount(@NotNull UserModel user, @NotNull String playerName) {
        var account = accountService.getSelectedAccount(user.getUuid(), user.getAccountId());
        if (account == null) {
            Logger.log(LogId.W_5070, playerName);
        }
        return account;
    }

    /**
     * プレイヤー参加時のインベントリ state を API から読み込み、メモリへ登録します。
     * <p>
     * API 通信を伴うため Bukkit メインスレッド外から呼び出してください。以降のゲームロジックは
     * {@link PlayerInventoryState} を正本として参照します。
     *
     * @param account 選択中アカウント
     */
    public void loadPlayerJoinInventoryState(@NotNull AccountModel account) {
        PlayerInventoryState inventoryState = inventoryPersistence.load(account.getUuid());
        inventoryStateRegistry.put(inventoryState);
    }

    /**
     * 取得済みデータを Bukkit プレイヤーへ反映します。
     * <p>
     * {@link AstPlayer} の構築、権限・ゲームモード・インベントリ GUI の反映は Bukkit API を触るため、
     * 必ず Bukkit メインスレッドから呼び出してください。
     *
     * @param player ログインした Bukkit プレイヤー
     * @param joinData 非同期で取得済みのログインデータ
     */
    public void applyPlayerJoin(@NotNull Player player, @NotNull PlayerJoinData joinData) {
        if (!player.isOnline() || AstPlayerCache.contains(player.getUniqueId())) {
            return;
        }

        var astPlayer = new AstPlayer(player, joinData.user(), joinData.account());
        AstPlayerCache.put(astPlayer);
        if (joinData.account().getMode().shouldReflectInventoryToGui()) {
            inventoryService.applyInventoriesToGuiOnJoin(astPlayer);
        } else if (isToolInventoryMode(joinData.account().getMode())) {
            inventoryService.applyToolInventoryToGui(astPlayer);
        }
        statusService.refreshStatus(astPlayer);
    }

    /**
     * プレイヤーのログアウト処理を行います。
     * {@link AstPlayerCache} からプレイヤーを削除します。
     *
     * @param player ログアウトした Bukkit プレイヤー
     */
    public void onPlayerQuit(Player player) {
        var astPlayer = AstPlayerCache.get(player);
        if (astPlayer != null) {
            // ログアウト時にだけ API へ反映。失敗時は warn ログのみ残しデータロスを許容する。
            playerSaveCoordinator.save(astPlayer, PlayerSaveTrigger.LOGOUT);
            inventoryService.clearClickGuard(astPlayer.getAccount().getUuid());
            inventoryStateRegistry.remove(astPlayer.getAccount().getUuid());
            inventoryPersistence.clearAccount(astPlayer.getAccount().getUuid());
        }
        AstPlayerCache.remove(player.getUniqueId());
    }

    /**
     * プレイヤーログイン履歴を API へ登録します。
     *
     * @param playerUuid プレイヤー UUID
     * @param playerName プレイヤー名
     */
    public void recordLoginHistory(@NotNull UUID playerUuid, @NotNull String playerName) {
        userService.recordUserHistory(playerUuid, "PLAYER_LOGIN", "PLUGIN", "Player login: " + playerName);
    }

    /**
     * プレイヤーログアウト履歴を API へ登録します。
     *
     * @param playerUuid プレイヤー UUID
     * @param playerName プレイヤー名
     */
    public void recordLogoutHistory(@NotNull UUID playerUuid, @NotNull String playerName) {
        userService.recordUserHistory(playerUuid, "PLAYER_LOGOUT", "PLUGIN", "Player logout: " + playerName);
    }

    /**
     * キャッシュ済みオンラインプレイヤーを保存してキャッシュを空にします。
     * プラグイン停止時に呼び出し、/reload 相当の再起動でセッション情報が残らないようにします。
     */
    public void saveAllOnlinePlayersAndClear() {
        for (AstPlayer astPlayer : AstPlayerCache.getAll()) {
            playerSaveCoordinator.save(astPlayer, PlayerSaveTrigger.PLUGIN_DISABLE);
            inventoryStateRegistry.remove(astPlayer.getAccount().getUuid());
            inventoryPersistence.clearAccount(astPlayer.getAccount().getUuid());
        }
        AstPlayerCache.clear();
    }

    /**
     * ログイン反映に必要な外部データ。
     *
     * @param user ユーザーデータ
     * @param account 選択中アカウントデータ
     */
    public record PlayerJoinData(@NotNull UserModel user, @NotNull AccountModel account) {
    }

    private boolean isToolInventoryMode(@NotNull AccountMode mode) {
        return mode == AccountMode.BUILDER || mode == AccountMode.ADMIN;
    }
}
