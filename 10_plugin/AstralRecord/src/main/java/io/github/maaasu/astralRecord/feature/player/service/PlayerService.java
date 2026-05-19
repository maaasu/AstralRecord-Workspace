package io.github.maaasu.astralRecord.feature.player.service;

import io.github.maaasu.astralRecord.feature.account.model.AccountModel;
import io.github.maaasu.astralRecord.feature.account.service.AccountService;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
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
    private final StatusService statusService;
    private final PlayerSaveCoordinator playerSaveCoordinator;

    public PlayerService(
        UserService userService,
        AccountService accountService,
        InventoryService inventoryService,
        StatusService statusService,
        PlayerSaveCoordinator playerSaveCoordinator
    ) {
        this.userService = userService;
        this.accountService = accountService;
        this.inventoryService = inventoryService;
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
        var user = userService.getUser(playerUuid);
        if (user == null) {
            Logger.log(LogId.W_5070, playerName);
            return null;
        }

        var account = accountService.getSelectedAccount(user.getUuid(), user.getAccountId());
        if (account == null) {
            Logger.log(LogId.W_5070, playerName);
            return null;
        }

        return new PlayerJoinData(user, account);
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
            inventoryService.applyInventoriesToGui(astPlayer);
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
            playerSaveCoordinator.save(astPlayer, PlayerSaveTrigger.LOGOUT);
        }
        AstPlayerCache.remove(player.getUniqueId());
    }

    /**
     * キャッシュ済みオンラインプレイヤーを保存してキャッシュを空にします。
     * プラグイン停止時に呼び出し、/reload 相当の再起動でセッション情報が残らないようにします。
     */
    public void saveAllOnlinePlayersAndClear() {
        for (AstPlayer astPlayer : AstPlayerCache.getAll()) {
            playerSaveCoordinator.save(astPlayer, PlayerSaveTrigger.PLUGIN_DISABLE);
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
}

