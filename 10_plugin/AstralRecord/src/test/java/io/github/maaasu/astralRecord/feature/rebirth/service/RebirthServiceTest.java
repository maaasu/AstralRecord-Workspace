package io.github.maaasu.astralRecord.feature.rebirth.service;

import io.github.maaasu.astralRecord.feature.account.model.AccountExperienceResult;
import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.feature.account.model.AccountModel;
import io.github.maaasu.astralRecord.feature.account.service.AccountService;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryType;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.item.service.ItemService;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.user.model.UserModel;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RebirthServiceTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/34-rebirth/3-メソッド仕様/34_3-サービス.md
     * 章・見出し: # 34_3-サービス > ## RebirthService 原子操作 > ### EXPポイント付与
     * 検証契約: 転生前レベルへの自然到達時だけ、完成player-stateへブラギのオーブを1個追加する。
     */
    @Test
    void naturalCompletionGrantsOneBragiOrbInTheSameLocalMutation() {
        UUID accountId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        AccountModel previous = account(accountId, userId, 9, 10);
        AccountModel updated = account(accountId, userId, 10, null);
        AccountExperienceResult experience = new AccountExperienceResult(previous, updated, 100, 1, 0);

        AccountService accountService = mock(AccountService.class);
        InventoryService inventoryService = mock(InventoryService.class);
        ItemService itemService = mock(ItemService.class);
        Plugin plugin = immediatePlugin();
        AstPlayer player = mock(AstPlayer.class);
        UserModel user = mock(UserModel.class);
        Player bukkitPlayer = mock(Player.class);
        ItemModel bragiOrb = mock(ItemModel.class);
        InventoryService.InventoryStateSnapshot inventoryBefore =
            new InventoryService.InventoryStateSnapshot(accountId, Map.of(), InventoryType.BAG, false);

        when(player.getAccount()).thenReturn(previous);
        when(player.getUser()).thenReturn(user);
        when(player.getBukkit()).thenReturn(bukkitPlayer);
        when(user.getUuid()).thenReturn(userId);
        when(bukkitPlayer.isOnline()).thenReturn(false);
        when(accountService.grantExperienceCached(previous, 100, userId)).thenReturn(experience);
        when(inventoryService.snapshotState(accountId)).thenReturn(inventoryBefore);
        when(itemService.findLoadedById(RebirthService.REBIRTH_COMPLETION_ORB_ITEM_ID)).thenReturn(bragiOrb);
        when(inventoryService.addItemToStorageIfPresentOtherwiseNormalInventoryStateOnly(
            player, bragiOrb, 1, "rebirth_completion"))
            .thenReturn(new InventoryService.StorageFallbackGrantResult(1, 1, false));
        when(inventoryService.executeLocalPlayerMutation(eq(accountId), any())).thenAnswer(invocation -> {
            Supplier<?> mutation = invocation.getArgument(1);
            return mutation.get();
        });

        RebirthService service = new RebirthService(
            plugin, accountService, inventoryService, itemService);

        assertSame(experience, service.grantExperience(player, 100));
        verify(inventoryService).addItemToStorageIfPresentOtherwiseNormalInventoryStateOnly(
            player, bragiOrb, 1, "rebirth_completion");
        verify(inventoryService, never()).restoreState(inventoryBefore);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/34-rebirth/3-メソッド仕様/34_3-サービス.md
     * 章・見出し: # 34_3-サービス > ## RebirthService 原子操作 > ### EXPポイント付与
     * 検証契約: 完了報酬を全量付与できない場合は、EXPポイントを含むinventoryとaccount進行を操作前へ戻す。
     */
    @Test
    void completionRewardShortfallRestoresInventoryAndAccountProgress() {
        UUID accountId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        AccountModel previous = account(accountId, userId, 9, 10);
        AccountModel updated = account(accountId, userId, 10, null);
        AccountExperienceResult experience = new AccountExperienceResult(previous, updated, 100, 1, 5);

        AccountService accountService = mock(AccountService.class);
        InventoryService inventoryService = mock(InventoryService.class);
        ItemService itemService = mock(ItemService.class);
        AstPlayer player = mock(AstPlayer.class);
        UserModel user = mock(UserModel.class);
        ItemModel bragiOrb = mock(ItemModel.class);
        InventoryService.InventoryStateSnapshot inventoryBefore =
            new InventoryService.InventoryStateSnapshot(accountId, Map.of(), InventoryType.BAG, false);

        when(player.getAccount()).thenReturn(previous);
        when(player.getUser()).thenReturn(user);
        when(user.getUuid()).thenReturn(userId);
        when(accountService.grantExperienceCached(previous, 100, userId)).thenReturn(experience);
        when(inventoryService.snapshotState(accountId)).thenReturn(inventoryBefore);
        when(inventoryService.addCurrencyStateOnly(
            accountId, RebirthService.EXP_POINT_CURRENCY_ITEM_ID, 5)).thenReturn(true);
        when(itemService.findLoadedById(RebirthService.REBIRTH_COMPLETION_ORB_ITEM_ID)).thenReturn(bragiOrb);
        when(inventoryService.addItemToStorageIfPresentOtherwiseNormalInventoryStateOnly(
            player, bragiOrb, 1, "rebirth_completion"))
            .thenReturn(new InventoryService.StorageFallbackGrantResult(1, 0, false));
        when(inventoryService.executeLocalPlayerMutation(eq(accountId), any())).thenAnswer(invocation -> {
            Supplier<?> mutation = invocation.getArgument(1);
            return mutation.get();
        });

        RebirthService service = new RebirthService(
            mock(Plugin.class), accountService, inventoryService, itemService);

        assertThrows(IllegalStateException.class, () -> service.grantExperience(player, 100));
        verify(inventoryService).addCurrencyStateOnly(
            accountId, RebirthService.EXP_POINT_CURRENCY_ITEM_ID, 5);
        verify(inventoryService).restoreState(inventoryBefore);
        verify(accountService).restoreCachedProgress(previous, userId);
    }

    private static Plugin immediatePlugin() {
        Plugin plugin = mock(Plugin.class);
        Server server = mock(Server.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getScheduler()).thenReturn(scheduler);
        when(scheduler.runTask(eq(plugin), any(Runnable.class))).thenAnswer(invocation -> {
            invocation.<Runnable>getArgument(1).run();
            return null;
        });
        return plugin;
    }

    private static AccountModel account(
        UUID accountId,
        UUID userId,
        int level,
        Integer rebirthOriginalLevel
    ) {
        LocalDateTime now = LocalDateTime.now();
        return new AccountModel(
            accountId,
            userId,
            "rebirth-test",
            0,
            true,
            AccountMode.PLAYER,
            "{}",
            now,
            now,
            userId,
            userId,
            false,
            level,
            0L,
            "adventurer",
            1,
            0L,
            java.util.List.of(),
            0,
            rebirthOriginalLevel == null ? level : rebirthOriginalLevel,
            rebirthOriginalLevel,
            0
        );
    }
}
