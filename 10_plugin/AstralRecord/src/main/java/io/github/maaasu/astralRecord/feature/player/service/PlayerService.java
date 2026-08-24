package io.github.maaasu.astralRecord.feature.player.service;

import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.feature.account.model.AccountModel;
import io.github.maaasu.astralRecord.feature.account.service.AccountService;
import io.github.maaasu.astralRecord.feature.inventory.service.InventorySaveCoordinator;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.inventory.state.InventoryPersistence;
import io.github.maaasu.astralRecord.feature.inventory.state.PlayerInventoryState;
import io.github.maaasu.astralRecord.feature.inventory.state.PlayerInventoryStateRegistry;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.GameModeChangeGuard;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.save.PlayerSaveCoordinator;
import io.github.maaasu.astralRecord.feature.player.save.PlayerSaveTrigger;
import io.github.maaasu.astralRecord.feature.status.service.StatusService;
import io.github.maaasu.astralRecord.feature.user.model.UserModel;
import io.github.maaasu.astralRecord.feature.user.service.UserService;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import org.bukkit.GameMode;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * プレイヤー機能のビジネスロジックを担うサービスクラス。
 * ログイン時の AstPlayer 構築・キャッシュ登録・OP権限付与を管理します。
 */
public class PlayerService {

    private final UserService userService;
    private final AccountService accountService;
    private final InventoryService inventoryService;
    private final InventorySaveCoordinator inventorySaveCoordinator;
    private final InventoryPersistence inventoryPersistence;
    private final PlayerInventoryStateRegistry inventoryStateRegistry;
    private final StatusService statusService;
    private final PlayerSaveCoordinator playerSaveCoordinator;
    private final PlayerRegionService playerRegionService;

    /**
     * プレイヤーサービスを構築します。
     *
     * @param userService ユーザーサービス
     * @param accountService アカウントサービス
     * @param inventoryService インベントリサービス
     * @param inventorySaveCoordinator インベントリ保存コーディネーター
     * @param inventoryPersistence インベントリ永続化
     * @param inventoryStateRegistry インベントリ状態レジストリ
     * @param statusService ステータスサービス
     * @param playerSaveCoordinator プレイヤー保存コーディネーター
     * @param playerRegionService プレイヤー地域サービス
     */
    public PlayerService(
        UserService userService,
        AccountService accountService,
        InventoryService inventoryService,
        InventorySaveCoordinator inventorySaveCoordinator,
        InventoryPersistence inventoryPersistence,
        PlayerInventoryStateRegistry inventoryStateRegistry,
        StatusService statusService,
        PlayerSaveCoordinator playerSaveCoordinator,
        PlayerRegionService playerRegionService
    ) {
        this.userService = userService;
        this.accountService = accountService;
        this.inventoryService = inventoryService;
        this.inventorySaveCoordinator = inventorySaveCoordinator;
        this.inventoryPersistence = inventoryPersistence;
        this.inventoryStateRegistry = inventoryStateRegistry;
        this.statusService = statusService;
        this.playerSaveCoordinator = playerSaveCoordinator;
        this.playerRegionService = playerRegionService;
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
        PlayerJoinInventoryState inventoryState = loadPlayerJoinInventoryState(account);

        return new PlayerJoinData(user, account, inventoryState);
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
     * プレイヤー参加時のインベントリ state を API から読み込みます。
     * <p>
     * API 通信を伴うため Bukkit メインスレッド外から呼び出してください。レジストリへの公開は、
     * ログイン試行が現在も有効と確認した後に {@link #applyPlayerJoin(Player, PlayerJoinData)} が行います。
     *
     * @param account 選択中アカウント
     * @return 読み込んだインベントリ state と保持 state 引き継ぎ情報
     */
    public @NotNull PlayerJoinInventoryState loadPlayerJoinInventoryState(@NotNull AccountModel account) {
        inventorySaveCoordinator.awaitQueuedSaves(account.getUuid()).join();
        InventorySaveCoordinator.RetainedStateLease retainedLease =
            inventorySaveCoordinator.claimRetainedState(account.getUuid());
        if (retainedLease != null) {
            return new PlayerJoinInventoryState(retainedLease.state(), retainedLease);
        }
        PlayerInventoryState inventoryState = inventoryPersistence.load(account.getUuid());
        return new PlayerJoinInventoryState(inventoryState, null);
    }

    /**
     * 中断されたログイン試行が保持 state を取得していた場合、後続試行へ返却します。
     *
     * @param inventoryState 中断したログイン試行のインベントリ state
     */
    public void discardPlayerJoinInventoryState(@NotNull PlayerJoinInventoryState inventoryState) {
        if (inventoryState.retainedLease() != null) {
            inventorySaveCoordinator.releaseRetainedStateLease(inventoryState.retainedLease());
            return;
        }
        inventoryStateRegistry.remove(inventoryState.state().getAccountId(), inventoryState.state());
    }

    /**
     * 取得済みデータを Bukkit プレイヤーへ反映します。
     * <p>
     * {@link AstPlayer} の構築、権限・ゲームモード・インベントリ GUI の反映は Bukkit API を触るため、
     * 必ず Bukkit メインスレッドから呼び出してください。
     *
     * @param player ログインした Bukkit プレイヤー
     * @param joinData 非同期で取得済みのログインデータ
     * @return ログインデータを反映できた場合は {@code true}
     */
    public boolean applyPlayerJoin(@NotNull Player player, @NotNull PlayerJoinData joinData) {
        PlayerJoinApplication application = applyPlayerJoinTransactional(player, joinData);
        if (application == null) {
            return false;
        }
        try {
            commitPlayerJoin(application);
            return true;
        } catch (RuntimeException | Error failure) {
            rollbackPlayerJoin(application);
            throw failure;
        }
    }

    /**
     * 取得済みデータをロールバック可能な参加反映として Bukkit プレイヤーへ適用します。
     * <p>
     * メソッド内で例外が発生した場合は、公開済みの GUI・地域・キャッシュ・inventory state と
     * Bukkit プレイヤー状態を逆順に復元してから例外を再送出します。呼び出し後の処理に失敗した場合は、
     * 戻り値を {@link #rollbackPlayerJoin(PlayerJoinApplication)} へ渡してください。
     *
     * @param player ログインした Bukkit プレイヤー
     * @param joinData 非同期で取得済みのログインデータ
     * @return 反映済み参加処理。参加条件を満たさない場合は {@code null}
     * @throws RuntimeException 参加反映中に処理が失敗した場合
     */
    public @Nullable PlayerJoinApplication applyPlayerJoinTransactional(
        @NotNull Player player,
        @NotNull PlayerJoinData joinData
    ) {
        if (!player.isOnline() || AstPlayerCache.contains(player.getUniqueId())) {
            return null;
        }

        PlayerJoinApplication application = new PlayerJoinApplication(
            player,
            joinData,
            capturePlayerRuntime(player),
            inventoryStateRegistry.get(joinData.account().getUuid())
        );
        try {
            application.astPlayer = new AstPlayer(player, joinData.user(), joinData.account());
            inventoryStateRegistry.put(joinData.inventoryState().state());
            application.inventoryPublished = true;
            application.inventorySnapshot = inventoryService.snapshotState(joinData.account().getUuid());

            AstPlayerCache.put(application.astPlayer);
            application.cachePublished = true;
            application.regionInitialized = true;
            playerRegionService.initializeRegion(application.astPlayer);
            // AstPlayerCache 登録後にコマンドツリーを再送し、permission 反映後の公開条件で同期する。
            player.updateCommands();
            if (joinData.account().getMode().shouldReflectInventoryToGui()) {
                inventoryService.applyInventoriesToGuiOnJoin(application.astPlayer);
            } else if (isToolInventoryMode(joinData.account().getMode())) {
                inventoryService.applyToolInventoryToGui(application.astPlayer);
            }
            statusService.refreshStatus(application.astPlayer);
            return application;
        } catch (RuntimeException | Error failure) {
            rollbackPlayerJoin(application);
            throw failure;
        }
    }

    /**
     * 参加反映後の後続処理が失敗した場合に、当該参加処理が公開した状態だけを破棄します。
     * 同じ処理を複数回渡しても二重解放せず、後続セッションのキャッシュは削除しません。
     *
     * @param application {@link #applyPlayerJoinTransactional(Player, PlayerJoinData)} の戻り値
     */
    public void rollbackPlayerJoin(@NotNull PlayerJoinApplication application) {
        if (!application.beginRollback()) {
            return;
        }

        Player player = application.player;
        UUID playerId = player.getUniqueId();
        UUID accountId = application.joinData.account().getUuid();

        runJoinRollbackStep(player, player::closeInventory);
        runJoinRollbackStep(player, () -> restoreInventoryContents(player, application.runtimeSnapshot.inventoryContents()));
        if (application.inventorySnapshot != null) {
            runJoinRollbackStep(player, () -> inventoryService.restoreState(application.inventorySnapshot));
        }
        if (application.regionInitialized) {
            runJoinRollbackStep(player, () -> playerRegionService.clearPlayer(playerId));
        }
        if (application.cachePublished && application.astPlayer != null) {
            runJoinRollbackStep(player, () -> AstPlayerCache.remove(playerId, application.astPlayer));
        }
        runJoinRollbackStep(player, () -> inventoryService.clearClickGuard(accountId));
        if (application.inventoryPublished) {
            runJoinRollbackStep(player, () -> discardPlayerJoinInventoryState(application.joinData.inventoryState()));
            PlayerInventoryState previousState = application.previousInventoryState;
            if (previousState != null
                && previousState != application.joinData.inventoryState().state()
                && inventoryStateRegistry.get(accountId) == null) {
                runJoinRollbackStep(player, () -> inventoryStateRegistry.put(previousState));
            }
        }
        restorePlayerRuntime(player, application.runtimeSnapshot);
        runJoinRollbackStep(player, player::updateInventory);
        runJoinRollbackStep(player, player::updateCommands);
    }

    /**
     * 後続 feature を含む参加反映が完了したことを確定し、retained-state lease をオンライン状態へ移します。
     * より新しいログイン試行が lease を取得済みの場合は確定せず、呼び出し元でロールバックできるよう例外を返します。
     *
     * @param application 確定する参加反映
     * @throws IllegalStateException retained-state lease の所有世代が失効している場合
     */
    public void commitPlayerJoin(@NotNull PlayerJoinApplication application) {
        if (application.committed || application.rolledBack) {
            return;
        }
        InventorySaveCoordinator.RetainedStateLease retainedLease =
            application.joinData.inventoryState().retainedLease();
        if (retainedLease != null && !inventorySaveCoordinator.commitRetainedStateLease(retainedLease)) {
            throw new IllegalStateException("retained inventory state lease is no longer current");
        }
        application.committed = true;
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
            UUID accountId = astPlayer.getAccount().getUuid();
            playerRegionService.clearPlayer(player.getUniqueId());
            // Bukkit inventory の参照だけをメインスレッドで完了させ、API I/O は保存キューへ委譲する。
            playerSaveCoordinator.prepare(astPlayer, PlayerSaveTrigger.LOGOUT);
            PlayerInventoryState state = inventoryStateRegistry.get(accountId);
            inventorySaveCoordinator.saveOnLogout(
                accountId,
                state,
                () -> playerSaveCoordinator.save(astPlayer, PlayerSaveTrigger.LOGOUT)
            );
            inventoryService.clearClickGuard(accountId);
            inventoryService.clearEquippedSetEffectDisplayCounts(accountId);
        }
        statusService.clearShieldRuntimeState(player.getUniqueId());
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
     * キャッシュ済みオンラインプレイヤーの停止保存をアカウントlaneへ登録します。
     * accepted済みオーブ操作と正本照合の後ろで保存し、成功したセッションだけstate/cacheを解放します。
     *
     * @return 各プレイヤーの停止保存future
     */
    public @NotNull List<CompletableFuture<Boolean>> saveAllOnlinePlayersAndClear() {
        List<CompletableFuture<Boolean>> saves = new ArrayList<>();
        for (AstPlayer astPlayer : List.copyOf(AstPlayerCache.getAll())) {
            UUID accountId = astPlayer.getAccount().getUuid();
            if (inventorySaveCoordinator.hasUnresolvedExternalOperation(accountId)) {
                continue;
            }
            inventoryService.refreshEquipmentDisplaysForSave(astPlayer);
            playerSaveCoordinator.prepare(astPlayer, PlayerSaveTrigger.PLUGIN_DISABLE);
            PlayerInventoryState state = inventoryStateRegistry.get(accountId);
            CompletableFuture<Boolean> save = inventorySaveCoordinator.saveOnLogout(
                accountId,
                state,
                () -> playerSaveCoordinator.save(astPlayer, PlayerSaveTrigger.PLUGIN_DISABLE)
            );
            save.whenComplete((succeeded, throwable) -> {
                if (throwable == null && Boolean.TRUE.equals(succeeded)) {
                    AstPlayerCache.remove(astPlayer.getBukkit().getUniqueId(), astPlayer);
                }
            });
            saves.add(save);
        }
        return List.copyOf(saves);
    }

    /**
     * ログイン反映に必要な外部データ。
     *
     * @param user ユーザーデータ
     * @param account 選択中アカウントデータ
     * @param inventoryState 公開待ちのインベントリ state
     */
    public record PlayerJoinData(
        @NotNull UserModel user,
        @NotNull AccountModel account,
        @NotNull PlayerJoinInventoryState inventoryState
    ) {
    }

    /** ログイン試行で読み込んだインベントリ state と保持 state の引き継ぎ情報です。 */
    public record PlayerJoinInventoryState(
        @NotNull PlayerInventoryState state,
        @Nullable InventorySaveCoordinator.RetainedStateLease retainedLease
    ) {
    }

    /**
     * ロールバック対象となる一回のプレイヤー参加反映です。
     * インスタンスの生成と破棄は {@link PlayerService} が管理します。
     */
    public static final class PlayerJoinApplication {
        private final Player player;
        private final PlayerJoinData joinData;
        private final PlayerRuntimeSnapshot runtimeSnapshot;
        private final PlayerInventoryState previousInventoryState;
        private AstPlayer astPlayer;
        private InventoryService.InventoryStateSnapshot inventorySnapshot;
        private boolean inventoryPublished;
        private boolean cachePublished;
        private boolean regionInitialized;
        private boolean rolledBack;
        private boolean committed;

        private PlayerJoinApplication(
            @NotNull Player player,
            @NotNull PlayerJoinData joinData,
            @NotNull PlayerRuntimeSnapshot runtimeSnapshot,
            @Nullable PlayerInventoryState previousInventoryState
        ) {
            this.player = player;
            this.joinData = joinData;
            this.runtimeSnapshot = runtimeSnapshot;
            this.previousInventoryState = previousInventoryState;
        }

        private boolean beginRollback() {
            if (rolledBack || committed) {
                return false;
            }
            rolledBack = true;
            return true;
        }
    }

    private @NotNull PlayerRuntimeSnapshot capturePlayerRuntime(@NotNull Player player) {
        PlayerInventory inventory = player.getInventory();
        ItemStack[] contents = inventory == null ? null : cloneContents(inventory.getContents());
        return new PlayerRuntimeSnapshot(
            player.isOp(),
            player.getGameMode(),
            attributeBaseValue(player, Attribute.ENTITY_INTERACTION_RANGE),
            attributeBaseValue(player, Attribute.BLOCK_INTERACTION_RANGE),
            contents
        );
    }

    private void restorePlayerRuntime(
        @NotNull Player player,
        @NotNull PlayerRuntimeSnapshot snapshot
    ) {
        if (snapshot.gameMode() != null) {
            runJoinRollbackStep(player, () -> GameModeChangeGuard.setGameMode(player, snapshot.gameMode()));
        }
        runJoinRollbackStep(player, () -> player.setOp(snapshot.op()));
        restoreAttributeBaseValue(player, Attribute.ENTITY_INTERACTION_RANGE, snapshot.entityInteractionRange());
        restoreAttributeBaseValue(player, Attribute.BLOCK_INTERACTION_RANGE, snapshot.blockInteractionRange());
    }

    private void restoreInventoryContents(@NotNull Player player, @Nullable ItemStack[] contents) {
        if (contents == null) {
            return;
        }
        PlayerInventory inventory = player.getInventory();
        if (inventory != null) {
            inventory.setContents(cloneContents(contents));
        }
    }

    private @Nullable Double attributeBaseValue(@NotNull Player player, @NotNull Attribute attribute) {
        AttributeInstance instance = player.getAttribute(attribute);
        return instance == null ? null : instance.getBaseValue();
    }

    private void restoreAttributeBaseValue(
        @NotNull Player player,
        @NotNull Attribute attribute,
        @Nullable Double value
    ) {
        if (value == null) {
            return;
        }
        runJoinRollbackStep(player, () -> {
            AttributeInstance instance = player.getAttribute(attribute);
            if (instance != null) {
                instance.setBaseValue(value);
            }
        });
    }

    private @Nullable ItemStack[] cloneContents(@Nullable ItemStack[] contents) {
        if (contents == null) {
            return null;
        }
        ItemStack[] copied = new ItemStack[contents.length];
        for (int index = 0; index < contents.length; index++) {
            ItemStack item = contents[index];
            copied[index] = item == null ? null : item.clone();
        }
        return copied;
    }

    private void runJoinRollbackStep(@NotNull Player player, @NotNull Runnable rollbackStep) {
        try {
            rollbackStep.run();
        } catch (RuntimeException rollbackFailure) {
            Logger.log(LogId.E_5070, rollbackFailure, player.getName());
        }
    }

    private record PlayerRuntimeSnapshot(
        boolean op,
        @Nullable GameMode gameMode,
        @Nullable Double entityInteractionRange,
        @Nullable Double blockInteractionRange,
        @Nullable ItemStack[] inventoryContents
    ) {
    }

    private boolean isToolInventoryMode(@NotNull AccountMode mode) {
        return mode == AccountMode.ADMIN;
    }
}
