package io.github.maaasu.astralRecord.feature.account.service;

import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.feature.account.model.AccountModel;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** アカウントモードのローカル確定とオンラインプレイヤーへの反映を一括で行います。 */
public final class AccountModeApplicationService {
    private final AccountService accountService;
    private final InventoryService inventoryService;
    private final Map<UUID, ModeChangeState> modeChangeStates = new ConcurrentHashMap<>();

    public AccountModeApplicationService(@NotNull AccountService accountService, @NotNull InventoryService inventoryService) {
        this.accountService = accountService;
        this.inventoryService = inventoryService;
    }

    /**
     * 対象accountがオンラインcacheに存在するか判定します。
     *
     * @param accountId 対象account
     * @return オンラインcacheに存在する場合は {@code true}
     */
    public boolean isAccountOnline(@NotNull UUID accountId) {
        return AstPlayerCache.getAll().stream().anyMatch(player -> player.getAccount().getUuid().equals(accountId));
    }

    /**
     * offline管理対象をAPI保存します。非同期処理から呼び、成功後にmain threadで結果を通知します。
     * @param current 取得済みaccount
     * @param mode 保存するmode
     * @param updatedBy 管理操作の実行者
     * @return API保存済みの結果
     * @throws IllegalStateException 対象がオンラインになった場合、または未保存進行がある場合
     */
    public @NotNull PersistedModeChange persistOfflineModeChange(
        @NotNull AccountModel current, @NotNull AccountMode mode, @NotNull UUID updatedBy
    ) {
        UUID accountId = current.getUuid();
        ModeChangeState state = modeChangeStates.computeIfAbsent(accountId, ignored -> new ModeChangeState());
        synchronized (state.persistenceMonitor) {
            if (isAccountOnline(accountId)) {
                throw new IllegalStateException("Account became online: " + accountId);
            }
            AccountModel saved = accountService.saveOfflineMode(accountId, mode, updatedBy);
            return new PersistedModeChange(saved, state.persistedGeneration.incrementAndGet(), true);
        }
    }

    /**
     * アカウントモードを更新し、オンライン中の対象アカウントへ反映します。
     *
     * @param accountUuid 更新対象アカウント UUID
     * @param mode 更新後のアカウントモード
     * @param updatedBy 更新者 UUID
     * @return 更新後のアカウント
     */
    public @NotNull AccountModel changeMode(@NotNull UUID accountUuid, @NotNull AccountMode mode, @NotNull UUID updatedBy) {
        PersistedModeChange persisted = persistModeChange(accountUuid, mode, updatedBy);
        applyPersistedMode(persisted);
        return persisted.account();
    }

    /**
     * モード変更をローカルstateへ確定します。
     * Bukkit API を直接呼ばず、API応答を待ってローカル値を戻すことはありません。
     *
     * @param accountUuid 更新対象アカウント UUID
     * @param mode 更新後のモード
     * @param updatedBy 更新者 UUID
     * @return 永続化結果とアカウント単位の世代
     */
    public @NotNull PersistedModeChange persistModeChange(
        @NotNull UUID accountUuid,
        @NotNull AccountMode mode,
        @NotNull UUID updatedBy
    ) {
        AccountModel current = AstPlayerCache.getAll().stream()
            .filter(player -> player.getAccount().getUuid().equals(accountUuid))
            .findFirst()
            .map(player -> player.getAccount())
            .orElseGet(() -> accountService.getAccount(accountUuid));
        if (current == null) {
            throw new IllegalArgumentException("Account was not found: " + accountUuid);
        }
        return persistModeChange(current, mode, updatedBy);
    }

    /**
     * 取得済みaccountをローカルstateへ確定します。
     *
     * @param current 現在のaccount正本
     * @param mode 変更後のmode
     * @param updatedBy 更新者
     * @return ローカル確定後のaccountと適用世代
     */
    public @NotNull PersistedModeChange persistModeChange(
        @NotNull AccountModel current,
        @NotNull AccountMode mode,
        @NotNull UUID updatedBy
    ) {
        UUID accountUuid = current.getUuid();
        ModeChangeState state = modeChangeStates.computeIfAbsent(accountUuid, ignored -> new ModeChangeState());
        PersistedModeChange change;
        synchronized (state.persistenceMonitor) {
            var onlinePlayer = AstPlayerCache.getAll().stream()
                .filter(player -> player.getAccount().getUuid().equals(accountUuid))
                .findFirst()
                .orElse(null);
            AccountModel latest = onlinePlayer == null ? current : onlinePlayer.getAccount();
            if (onlinePlayer == null) {
                throw new IllegalStateException("Offline mode requires API persistence: " + accountUuid);
            }
            AccountModel updated = inventoryService.executeLocalPlayerMutation(
                    accountUuid,
                    () -> accountService.setMode(latest, mode, updatedBy)
                );
            long generation = state.persistedGeneration.incrementAndGet();
            change = new PersistedModeChange(updated, generation);
        }
        return change;
    }

    /**
     * 永続化済みの最新モードだけをオンラインプレイヤーへ反映します。
     * より新しい永続化が完了済みの場合は古い結果を破棄します。メインスレッドから呼び出してください。
     *
     * @param persisted 永続化結果
     * @return 最新世代を反映した場合は {@code true}
     */
    public boolean applyPersistedMode(@NotNull PersistedModeChange persisted) {
        AccountModel updated = persisted.account();
        ModeChangeState state = modeChangeStates.get(updated.getUuid());
        if (state == null || state.persistedGeneration.get() != persisted.generation()) {
            return false;
        }
        for (var astPlayer : AstPlayerCache.getAll()) {
            if (!astPlayer.getAccount().getUuid().equals(updated.getUuid())) {
                continue;
            }
            inventoryService.executeLocalPlayerMutation(updated.getUuid(), () -> {
                var previousMode = astPlayer.getAccount().getMode();
                if (isToolInventoryMode(previousMode) && previousMode != updated.getMode()) {
                    inventoryService.saveToolInventorySnapshot(astPlayer);
                }
                astPlayer.applyAccountMode(persisted.savedRemotely()
                    ? accountService.mergeSavedOfflineMode(astPlayer.getAccount(), updated) : updated);
                if (updated.getMode().shouldReflectInventoryToGui()) {
                    if (isToolInventoryMode(previousMode) && previousMode != updated.getMode()) {
                        inventoryService.applyInventoriesToGuiForModeSwitch(astPlayer);
                    } else {
                        inventoryService.applyInventoriesToGui(astPlayer);
                    }
                } else if (isToolInventoryMode(updated.getMode())) {
                    inventoryService.applyToolInventoryToGui(astPlayer);
                } else {
                    inventoryService.clearGuiInventory(astPlayer);
                }
                return null;
            });
        }
        // account と inventory/UI のローカル反映を終え、state lock を解放してから保存を要求する。
        if (!persisted.savedRemotely()) {
            accountService.requestLocalPlayerSave(updated.getUuid());
        }
        return true;
    }

    private boolean isToolInventoryMode(@NotNull AccountMode mode) {
        return mode == AccountMode.ADMIN;
    }

    /** アカウントモードの永続化結果と、同一アカウント内での完了世代です。 */
    public record PersistedModeChange(@NotNull AccountModel account, long generation, boolean savedRemotely) {
        /**
         * ローカル確定したmode変更結果を構築します。
         *
         * @param account ローカル確定後のaccount
         * @param generation 同一account内の確定世代
         */
        public PersistedModeChange(@NotNull AccountModel account, long generation) {
            this(account, generation, false);
        }
    }

    private static final class ModeChangeState {
        private final Object persistenceMonitor = new Object();
        private final AtomicLong persistedGeneration = new AtomicLong();
    }
}
