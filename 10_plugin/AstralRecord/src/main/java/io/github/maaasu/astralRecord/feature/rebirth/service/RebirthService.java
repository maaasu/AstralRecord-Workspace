package io.github.maaasu.astralRecord.feature.rebirth.service;

import io.github.maaasu.astralRecord.feature.account.model.AccountExperienceResult;
import io.github.maaasu.astralRecord.feature.account.model.AccountModel;
import io.github.maaasu.astralRecord.feature.account.service.AccountService;
import io.github.maaasu.astralRecord.feature.inventory.service.InventorySaveCoordinator;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.item.service.ItemService;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.rebirth.model.RebirthOperationResult;
import io.github.maaasu.astralRecord.feature.rebirth.model.RebirthRejectionReason;
import org.jetbrains.annotations.NotNull;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.plugin.Plugin;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** 転生状態、転生費用、転生中のEXPポイント変換を一体管理します。 */
public final class RebirthService {
    public static final String EXP_POINT_CURRENCY_ITEM_ID = "99a00017";
    public static final long GOLD_COST_PER_LEVEL = 10_000L;
    public static final long EARLY_END_ASTRALD_COST = 100L;

    private final AccountService accountService;
    private final InventoryService inventoryService;
    private final Plugin plugin;

    /**
     * 転生サービスを生成します。
     *
     * @param accountService アカウント進行サービス
     * @param inventoryService 通貨を含むプレイヤー状態サービス
     */
    public RebirthService(
        @NotNull Plugin plugin, @NotNull AccountService accountService,
        @NotNull InventoryService inventoryService
    ) {
        this.plugin = plugin;
        this.accountService = accountService;
        this.inventoryService = inventoryService;
    }

    /**
     * 現在レベルに基づく転生Gold費用を返します。
     *
     * @param level 転生前レベル
     * @return 1レベルあたり10,000 Goldの費用
     */
    public long startGoldCost(int level) {
        return Math.multiplyExact(Math.max(1, level), GOLD_COST_PER_LEVEL);
    }

    /**
     * アカウントが転生中かを返します。
     *
     * @param account 判定対象アカウント
     * @return 転生前レベルが現在レベルより高い場合はtrue
     */
    public boolean isActive(@NotNull AccountModel account) {
        Integer originalLevel = account.getRebirthOriginalLevel();
        return originalLevel != null && originalLevel > account.getLevel();
    }

    /**
     * 実際に獲得したプレイヤーEXPを反映し、転生中の100EXPごとに1EXPポイントを同時付与します。
     * クラスEXPはこのメソッドの対象外です。
     *
     * @param player 対象プレイヤー
     * @param experience 実際に獲得したプレイヤーEXP
     * @return プレイヤーレベルとEXPポイントの反映結果
     * @throws IllegalStateException プレイヤー状態またはEXPポイント通貨を更新できない場合
     */
    public @NotNull AccountExperienceResult grantExperience(
        @NotNull AstPlayer player,
        int experience
    ) {
        if (!isActive(player.getAccount())) {
            return accountService.grantExperienceCached(
                player.getAccount(), experience, player.getUser().getUuid());
        }
        UUID accountId = player.getAccount().getUuid();
        return inventoryService.executeLocalPlayerMutation(accountId, () -> {
            InventoryService.InventoryStateSnapshot inventoryBefore = inventoryService.snapshotState(accountId);
            AccountExperienceResult result = accountService.grantExperienceCached(
                player.getAccount(), experience, player.getUser().getUuid());
            try {
                if (result.grantedExpPoints() > 0 && !inventoryService.addCurrencyStateOnly(
                    accountId,
                    EXP_POINT_CURRENCY_ITEM_ID,
                    result.grantedExpPoints()
                )) {
                    throw new IllegalStateException("EXP point currency could not be granted");
                }
                if (result.grantedExpPoints() > 0) plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (player.getBukkit().isOnline()) player.getBukkit().playSound(
                        player.getBukkit().getLocation(), Sound.BLOCK_BONE_BLOCK_HIT, SoundCategory.PLAYERS, 1.0F, 2.0F);
                });
                return result;
            } catch (RuntimeException failure) {
                inventoryService.restoreState(inventoryBefore);
                accountService.restoreCachedProgress(result.previousAccount(), player.getUser().getUuid());
                throw failure;
            }
        });
    }

    /**
     * 現在レベル分のGoldを消費し、レベル1からの転生を開始します。
     * 呼出元スレッド上でGoldと進行を一体確定し、完成stateをwrite-behind保存します。
     *
     * @param player 転生するプレイヤー
     * @return ローカル確定後に完了する転生結果
     */
    public @NotNull CompletableFuture<RebirthOperationResult> start(@NotNull AstPlayer player) {
        UUID accountId = player.getAccount().getUuid();
        return inventoryService.executeResponsivePlayerMutation(accountId, () -> {
            AccountModel before = player.getAccount();
            if (isActive(before)) {
                throw new RebirthRejectedException(RebirthRejectionReason.ALREADY_ACTIVE);
            }
            if (before.getLevel() <= 1) {
                throw new RebirthRejectedException(RebirthRejectionReason.LEVEL_TOO_LOW);
            }
            long cost = startGoldCost(before.getLevel());
            InventoryService.InventoryStateSnapshot inventoryBefore = inventoryService.snapshotState(accountId);
            if (inventoryBefore == null) {
                throw new RebirthRejectedException(RebirthRejectionReason.PLAYER_STATE_UNAVAILABLE);
            }
            if (!inventoryService.consumeGold(accountId, cost)) {
                throw new RebirthRejectedException(RebirthRejectionReason.INSUFFICIENT_GOLD);
            }
            AccountModel updated;
            try {
                updated = accountService.startRebirthCached(before, player.getUser().getUuid());
                player.setAccount(updated);
            } catch (RuntimeException failure) {
                inventoryService.restoreState(inventoryBefore);
                accountService.restoreCachedProgress(before, player.getUser().getUuid());
                player.setAccount(before);
                throw failure;
            }
            return new InventorySaveCoordinator.CriticalMutation<>(
                new RebirthOperationResult(updated, before.getLevel(), cost),
                () -> { }
            );
        });
    }

    /**
     * 100アストラルドを消費して転生を終了し、転生前レベルへ戻します。
     * 呼出元スレッド上で通貨と進行を一体確定し、完成stateをwrite-behind保存します。
     *
     * @param player 転生を終了するプレイヤー
     * @return ローカル確定後に完了する終了結果
     */
    public @NotNull CompletableFuture<RebirthOperationResult> endEarly(@NotNull AstPlayer player) {
        UUID accountId = player.getAccount().getUuid();
        return inventoryService.executeResponsivePlayerMutation(accountId, () -> {
            AccountModel before = player.getAccount();
            Integer originalLevel = before.getRebirthOriginalLevel();
            if (!isActive(before) || originalLevel == null) {
                throw new RebirthRejectedException(RebirthRejectionReason.NOT_ACTIVE);
            }
            InventoryService.InventoryStateSnapshot inventoryBefore = inventoryService.snapshotState(accountId);
            if (inventoryBefore == null) {
                throw new RebirthRejectedException(RebirthRejectionReason.PLAYER_STATE_UNAVAILABLE);
            }
            if (!inventoryService.consumeCurrency(
                accountId,
                ItemService.ASTRALD_CURRENCY_ITEM_ID,
                EARLY_END_ASTRALD_COST
            )) {
                throw new RebirthRejectedException(RebirthRejectionReason.INSUFFICIENT_ASTRALD);
            }
            AccountModel updated;
            try {
                updated = accountService.endRebirthCached(before, player.getUser().getUuid());
                player.setAccount(updated);
            } catch (RuntimeException failure) {
                inventoryService.restoreState(inventoryBefore);
                accountService.restoreCachedProgress(before, player.getUser().getUuid());
                player.setAccount(before);
                throw failure;
            }
            return new InventorySaveCoordinator.CriticalMutation<>(
                new RebirthOperationResult(updated, originalLevel, EARLY_END_ASTRALD_COST),
                () -> { }
            );
        });
    }
}
