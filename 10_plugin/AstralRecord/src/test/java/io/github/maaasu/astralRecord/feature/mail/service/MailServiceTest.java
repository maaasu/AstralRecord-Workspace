package io.github.maaasu.astralRecord.feature.mail.service;

import io.github.maaasu.astralRecord.feature.account.model.AccountModel;
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryInstanceType;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.item.model.EquipmentInstance;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.item.service.ItemService;
import io.github.maaasu.astralRecord.feature.mail.model.MailEntry;
import io.github.maaasu.astralRecord.feature.mail.model.MailFilter;
import io.github.maaasu.astralRecord.feature.mail.model.MailReward;
import io.github.maaasu.astralRecord.feature.mail.repository.MailRepository;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.feature.user.model.UserModel;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.MockedStatic;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MailServiceTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/18-mail/18_4-統合フロー.md
     * 章・見出し: # 18_4-統合フロー > ## 2. 未読メールの報酬受取
     * 検証契約: 追跡可能な報酬receiptをinventoryへ反映してからAPI既読化し、全state snapshot方式を使わない。
     */
    @Test
    void addsTrackedRewardBeforeMarkingMailRead() {
        TestContext context = new TestContext();
        InventoryService.InventoryGrantReceipt receipt = context.receipt(3L);
        when(context.inventoryService.addPreparedRewardsToNormalInventory(eq(context.astPlayer), any()))
            .thenReturn(receipt);
        when(context.repository.markRead(context.userId, context.mail.id()))
            .thenReturn(context.readMail());
        AtomicReference<MailService.ReadAndReceiveResult> result = new AtomicReference<>();
        AtomicInteger receivedEvents = new AtomicInteger();
        context.service.setMailReceivedListener((ignoredPlayer, ignoredMailId) -> receivedEvents.incrementAndGet());

        context.runWithPlayerServices(() ->
            context.service.readAndReceive(context.astPlayer, context.mail, result::set)
        );

        assertTrue(result.get().success());
        assertTrue(result.get().rewardReceived());
        assertEquals(1, receivedEvents.get());
        InOrder order = inOrder(context.inventoryService, context.repository);
        order.verify(context.inventoryService).addPreparedRewardsToNormalInventory(
            eq(context.astPlayer),
            any()
        );
        order.verify(context.repository).markRead(context.userId, context.mail.id());
        verify(context.inventoryService, never()).snapshotState(any());
        verify(context.inventoryService, never()).restoreState(any());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/18-mail/18_4-統合フロー.md
     * 章・見出し: # 18_4-統合フロー > ## 2. 未読メールの報酬受取
     * 検証契約: 既読化失敗時は当該claimのreceiptだけをrollbackし、並行して追加されたmutationを残す。
     */
    @Test
    void markReadFailureCompensatesOnlyClaimMutationsAndKeepsConcurrentMutation() {
        TestContext context = new TestContext();
        InventoryService.InventoryGrantReceipt receipt = context.receipt(3L);
        AtomicInteger claimReward = new AtomicInteger();
        AtomicInteger concurrentReward = new AtomicInteger();
        when(context.inventoryService.addPreparedRewardsToNormalInventory(eq(context.astPlayer), any()))
            .thenAnswer(invocation -> {
                claimReward.addAndGet(3);
                return receipt;
            });
        when(context.repository.markRead(context.userId, context.mail.id()))
            .thenAnswer(invocation -> {
                concurrentReward.incrementAndGet();
                return null;
            });
        when(context.inventoryService.rollbackPreparedRewards(receipt))
            .thenAnswer(invocation -> {
                claimReward.addAndGet(-3);
                return true;
            });
        AtomicReference<MailService.ReadAndReceiveResult> result = new AtomicReference<>();

        context.runWithPlayerServices(() ->
            context.service.readAndReceive(context.astPlayer, context.mail, result::set)
        );

        assertFalse(result.get().success());
        assertEquals(0, claimReward.get());
        assertEquals(1, concurrentReward.get());
        verify(context.inventoryService).rollbackPreparedRewards(receipt);
        verify(context.inventoryService, never()).restoreState(any());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/18-mail/18_4-統合フロー.md
     * 章・見出し: # 18_4-統合フロー > ## 2. 未読メールの報酬受取
     * 検証契約: 報酬なしメールはinventory snapshot・付与・復元を行わず既読化だけで成功する。
     */
    @Test
    void rewardlessMailDoesNotSnapshotOrMutateInventory() {
        TestContext context = new TestContext();
        MailEntry rewardless = TestContext.mail(false, List.of());
        when(context.repository.markRead(context.userId, rewardless.id()))
            .thenReturn(context.readMail(rewardless));
        AtomicReference<MailService.ReadAndReceiveResult> result = new AtomicReference<>();
        AtomicInteger receivedEvents = new AtomicInteger();
        context.service.setMailReceivedListener((ignoredPlayer, ignoredMailId) -> receivedEvents.incrementAndGet());

        context.runWithPlayerServices(() ->
            context.service.readAndReceive(context.astPlayer, rewardless, result::set)
        );

        assertTrue(result.get().success());
        assertFalse(result.get().rewardReceived());
        assertEquals(0, receivedEvents.get());
        verify(context.inventoryService, never()).addPreparedRewardsToNormalInventory(any(), any());
        verify(context.inventoryService, never()).snapshotState(any());
        verify(context.inventoryService, never()).restoreState(any());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/18-mail/18_0-概要.md
     * 章・見出し: # 18_0-概要 > ## 整合性原則
     * 検証契約: 一覧再取得が stale な未読メールを返しても、完了済みclaimの排他を解除して二重付与しない。
     */
    @Test
    void staleListRefreshDoesNotUnlockCompletedClaim() {
        TestContext context = new TestContext();
        InventoryService.InventoryGrantReceipt receipt = context.receipt(3L);
        when(context.inventoryService.addPreparedRewardsToNormalInventory(eq(context.astPlayer), any()))
            .thenReturn(receipt);
        when(context.repository.markRead(context.userId, context.mail.id()))
            .thenReturn(context.readMail());

        context.runWithPlayerServices(() ->
            context.service.readAndReceive(context.astPlayer, context.mail, ignored -> { })
        );

        when(context.repository.findAvailable(context.userId, MailFilter.ALL))
            .thenReturn(List.of(context.mail));
        context.service.listAsync(context.userId, MailFilter.ALL, ignored -> { }, () -> { });

        AtomicReference<MailService.ReadAndReceiveResult> duplicateResult = new AtomicReference<>();
        context.runWithPlayerServices(() ->
            context.service.readAndReceive(context.astPlayer, context.mail, duplicateResult::set)
        );

        assertTrue(duplicateResult.get().success());
        assertFalse(duplicateResult.get().rewardReceived());
        verify(context.inventoryService, times(1)).addPreparedRewardsToNormalInventory(
            eq(context.astPlayer),
            any()
        );
        verify(context.repository, times(1)).markRead(context.userId, context.mail.id());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/18-mail/18_4-統合フロー.md
     * 章・見出し: # 18_4-統合フロー > ## 2. 未読メールの報酬受取
     * 検証契約: equipment数量分のinstanceを事前生成し、prepared instance一覧をmain-thread inventory反映後に既読化する。
     */
    @Test
    void preparesEquipmentInstanceBeforeMainThreadInventoryPublication() {
        TestContext context = new TestContext();
        List<UUID> instanceIds = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        EquipmentInstance firstInstance = mock(EquipmentInstance.class);
        EquipmentInstance secondInstance = mock(EquipmentInstance.class);
        EquipmentInstance thirdInstance = mock(EquipmentInstance.class);
        when(context.itemModel.getCategory()).thenReturn("equipment");
        when(firstInstance.getEquipmentInstanceId()).thenReturn(instanceIds.get(0).toString());
        when(secondInstance.getEquipmentInstanceId()).thenReturn(instanceIds.get(1).toString());
        when(thirdInstance.getEquipmentInstanceId()).thenReturn(instanceIds.get(2).toString());
        when(context.itemService.createEquipmentInstance(
            context.itemModel.getId(),
            context.accountId.toString(),
            "mail",
            context.accountId.toString()
        )).thenReturn(firstInstance, secondInstance, thirdInstance);
        when(context.inventoryService.addPreparedRewardsToNormalInventory(eq(context.astPlayer), any()))
            .thenReturn(context.receipt(1L));
        when(context.repository.markRead(context.userId, context.mail.id()))
            .thenReturn(context.readMail());

        context.runWithPlayerServices(() ->
            context.service.readAndReceive(context.astPlayer, context.mail, ignored -> { })
        );

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<InventoryService.PreparedInventoryReward>> rewardsCaptor =
            ArgumentCaptor.forClass(List.class);
        InOrder order = inOrder(context.itemService, context.inventoryService, context.repository);
        order.verify(context.itemService, times(3)).createEquipmentInstance(
            context.itemModel.getId(),
            context.accountId.toString(),
            "mail",
            context.accountId.toString()
        );
        order.verify(context.inventoryService).addPreparedRewardsToNormalInventory(
            eq(context.astPlayer),
            rewardsCaptor.capture()
        );
        order.verify(context.repository).markRead(context.userId, context.mail.id());
        InventoryService.PreparedInventoryInstance prepared = rewardsCaptor.getValue()
            .get(0)
            .instances()
            .get(0);
        assertNotNull(prepared);
        assertEquals(3, rewardsCaptor.getValue().get(0).instances().size());
        assertEquals(InventoryInstanceType.EQUIPMENT, prepared.instanceType());
        assertEquals(instanceIds.get(0), prepared.instanceId());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/18-mail/18_4-統合フロー.md
     * 章・見出し: # 18_4-統合フロー > ## 3. reconciliation
     * 検証契約: 既読化とrollbackの両方が失敗した間はclaimを維持して重複付与を拒否し、再調停成功後は既読として扱う。
     */
    @Test
    void stateLossKeepsClaimLockedUntilMarkReadReconciliationCompletes() {
        TestContext context = new TestContext();
        InventoryService.InventoryGrantReceipt receipt = context.receipt(3L);
        AtomicReference<Runnable> reconciliation = new AtomicReference<>();
        AtomicInteger receivedEvents = new AtomicInteger();
        context.service.setMailReceivedListener((ignoredPlayer, ignoredMailId) -> receivedEvents.incrementAndGet());
        when(context.inventoryService.addPreparedRewardsToNormalInventory(eq(context.astPlayer), any()))
            .thenReturn(receipt);
        when(context.repository.markRead(context.userId, context.mail.id())).thenReturn(null);
        when(context.inventoryService.rollbackPreparedRewards(receipt)).thenReturn(false);
        doAnswer(invocation -> {
            reconciliation.set(invocation.getArgument(1));
            return mock(BukkitTask.class);
        }).when(context.scheduler).runTaskLaterAsynchronously(
            eq(context.plugin),
            any(Runnable.class),
            anyLong()
        );
        AtomicReference<MailService.ReadAndReceiveResult> firstResult = new AtomicReference<>();
        AtomicReference<MailService.ReadAndReceiveResult> duplicateResult = new AtomicReference<>();

        context.runWithPlayerServices(() -> {
            context.service.readAndReceive(context.astPlayer, context.mail, firstResult::set);
            context.service.readAndReceive(context.astPlayer, context.mail, duplicateResult::set);
        });

        assertFalse(firstResult.get().success());
        assertTrue(firstResult.get().rewardReceived());
        assertFalse(duplicateResult.get().success());
        assertNotNull(reconciliation.get());
        verify(context.inventoryService, times(1)).addPreparedRewardsToNormalInventory(
            eq(context.astPlayer),
            any()
        );
        verify(context.repository, times(1)).markRead(context.userId, context.mail.id());

        when(context.repository.markRead(context.userId, context.mail.id())).thenReturn(context.readMail());
        AtomicReference<MailService.ReadAndReceiveResult> staleResult = new AtomicReference<>();
        context.runWithPlayerServices(() -> {
            reconciliation.get().run();
            context.service.readAndReceive(context.astPlayer, context.mail, staleResult::set);
        });

        assertTrue(staleResult.get().success());
        assertFalse(staleResult.get().rewardReceived());
        assertEquals(1, receivedEvents.get());
        verify(context.inventoryService, times(1)).addPreparedRewardsToNormalInventory(
            eq(context.astPlayer),
            any()
        );
        verify(context.repository, times(2)).markRead(context.userId, context.mail.id());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/18-mail/18_5-例外・ログ・運用.md
     * 章・見出し: # 18_5-例外・ログ・運用 > ## 運用
     * 検証契約: reconciliation 完了時にプレイヤーが offline なら通知を保留し、再ログイン後に一度だけ通知する。
     */
    @Test
    void reconciliationDefersReceivedNotificationUntilPlayerReady() {
        TestContext context = new TestContext();
        InventoryService.InventoryGrantReceipt receipt = context.receipt(3L);
        AtomicReference<Runnable> reconciliation = new AtomicReference<>();
        AtomicInteger receivedEvents = new AtomicInteger();
        context.service.setMailReceivedListener((ignoredPlayer, ignoredMailId) -> receivedEvents.incrementAndGet());
        when(context.inventoryService.addPreparedRewardsToNormalInventory(eq(context.astPlayer), any()))
            .thenReturn(receipt);
        when(context.repository.markRead(context.userId, context.mail.id())).thenReturn(null);
        when(context.inventoryService.rollbackPreparedRewards(receipt)).thenReturn(false);
        doAnswer(invocation -> {
            reconciliation.set(invocation.getArgument(1));
            return mock(BukkitTask.class);
        }).when(context.scheduler).runTaskLaterAsynchronously(
            eq(context.plugin),
            any(Runnable.class),
            anyLong()
        );
        when(context.player.isOnline()).thenReturn(true, true, false, true);
        when(context.repository.markRead(context.userId, context.mail.id())).thenReturn(null);

        context.runWithPlayerServices(() ->
            context.service.readAndReceive(context.astPlayer, context.mail, ignored -> { })
        );

        when(context.repository.markRead(context.userId, context.mail.id())).thenReturn(context.readMail());
        context.runWithPlayerServices(() -> reconciliation.get().run());

        assertEquals(0, receivedEvents.get());
        context.runWithPlayerServices(() -> context.service.notifyPendingMailReceived(context.astPlayer));
        assertEquals(1, receivedEvents.get());
        context.runWithPlayerServices(() -> context.service.notifyPendingMailReceived(context.astPlayer));
        assertEquals(1, receivedEvents.get());
    }

    private static final class TestContext {
        private final UUID playerId = UUID.randomUUID();
        private final UUID userId = UUID.randomUUID();
        private final UUID accountId = UUID.randomUUID();
        private final Plugin plugin = mock(Plugin.class);
        private final Server server = mock(Server.class);
        private final BukkitScheduler scheduler = mock(BukkitScheduler.class);
        private final MailRepository repository = mock(MailRepository.class);
        private final ItemService itemService = mock(ItemService.class);
        private final InventoryService inventoryService = mock(InventoryService.class);
        private final PlayerMessageService messageService = mock(PlayerMessageService.class);
        private final Player player = mock(Player.class);
        private final AstPlayer astPlayer = mock(AstPlayer.class);
        private final UserModel user = mock(UserModel.class);
        private final AccountModel account = mock(AccountModel.class);
        private final ItemModel itemModel = mock(ItemModel.class);
        private final MailEntry mail = mail(
            true,
            List.of(new MailReward("reward-item", "material", 3))
        );
        private final MailService service;

        private TestContext() {
            when(plugin.getServer()).thenReturn(server);
            when(server.getScheduler()).thenReturn(scheduler);
            when(server.getPlayer(playerId)).thenReturn(player);
            when(player.getUniqueId()).thenReturn(playerId);
            when(player.isOnline()).thenReturn(true);
            when(astPlayer.getBukkit()).thenReturn(player);
            when(astPlayer.getUser()).thenReturn(user);
            when(astPlayer.getAccount()).thenReturn(account);
            when(user.getUuid()).thenReturn(userId);
            when(account.getUuid()).thenReturn(accountId);
            when(itemModel.getId()).thenReturn("reward-item");
            when(itemModel.getCategory()).thenReturn("material");
            when(itemService.findLoadedById("reward-item")).thenReturn(itemModel);
            doAnswer(invocation -> {
                invocation.<Runnable>getArgument(1).run();
                return mock(BukkitTask.class);
            }).when(scheduler).runTaskAsynchronously(eq(plugin), any(Runnable.class));
            doAnswer(invocation -> {
                invocation.<Runnable>getArgument(1).run();
                return mock(BukkitTask.class);
            }).when(scheduler).runTask(eq(plugin), any(Runnable.class));
            service = new MailService(plugin, repository, itemService, inventoryService);
        }

        private void runWithPlayerServices(Runnable action) {
            try (MockedStatic<AstPlayerCache> cache = mockStatic(AstPlayerCache.class);
                 MockedStatic<PlayerMessageService> messages = mockStatic(PlayerMessageService.class)) {
                cache.when(() -> AstPlayerCache.get(player)).thenReturn(astPlayer);
                messages.when(PlayerMessageService::getInstance).thenReturn(messageService);
                action.run();
            }
        }

        private InventoryService.InventoryGrantReceipt receipt(long quantity) {
            return new InventoryService.InventoryGrantReceipt(
                accountId,
                List.of(new InventoryService.InventoryGrantMutation(
                    UUID.randomUUID(),
                    null,
                    quantity
                ))
            );
        }

        private MailEntry readMail() {
            return readMail(mail);
        }

        private MailEntry readMail(MailEntry source) {
            return new MailEntry(
                source.id(),
                source.icon(),
                source.title(),
                source.body(),
                source.publishFrom(),
                source.publishTo(),
                source.receiveOnRead(),
                source.rewards(),
                true,
                LocalDateTime.now()
            );
        }

        private static MailEntry mail(boolean receiveOnRead, List<MailReward> rewards) {
            return new MailEntry(
                "mail-1",
                "CHEST",
                "報酬メール",
                "本文",
                LocalDateTime.now().minusMinutes(1),
                null,
                receiveOnRead,
                rewards,
                false,
                null
            );
        }
    }
}
