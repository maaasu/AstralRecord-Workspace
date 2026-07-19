package io.github.maaasu.astralRecord.feature.mail.service;

import io.github.maaasu.astralRecord.feature.account.model.AccountModel;
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryInstanceType;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.item.model.EquipmentInstance;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.item.service.ItemService;
import io.github.maaasu.astralRecord.feature.mail.model.MailEntry;
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

    @Test
    void addsTrackedRewardBeforeMarkingMailRead() {
        TestContext context = new TestContext();
        InventoryService.InventoryGrantReceipt receipt = context.receipt(3L);
        when(context.inventoryService.addPreparedRewardsToNormalInventory(eq(context.astPlayer), any()))
            .thenReturn(receipt);
        when(context.repository.markRead(context.userId, context.mail.id()))
            .thenReturn(context.readMail());
        AtomicReference<Boolean> result = new AtomicReference<>();

        context.runWithPlayerServices(() ->
            context.service.readAndReceive(context.astPlayer, context.mail, result::set)
        );

        assertTrue(result.get());
        InOrder order = inOrder(context.inventoryService, context.repository);
        order.verify(context.inventoryService).addPreparedRewardsToNormalInventory(
            eq(context.astPlayer),
            any()
        );
        order.verify(context.repository).markRead(context.userId, context.mail.id());
        verify(context.inventoryService, never()).snapshotState(any());
        verify(context.inventoryService, never()).restoreState(any());
    }

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
        AtomicReference<Boolean> result = new AtomicReference<>();

        context.runWithPlayerServices(() ->
            context.service.readAndReceive(context.astPlayer, context.mail, result::set)
        );

        assertFalse(result.get());
        assertEquals(0, claimReward.get());
        assertEquals(1, concurrentReward.get());
        verify(context.inventoryService).rollbackPreparedRewards(receipt);
        verify(context.inventoryService, never()).restoreState(any());
    }

    @Test
    void rewardlessMailDoesNotSnapshotOrMutateInventory() {
        TestContext context = new TestContext();
        MailEntry rewardless = TestContext.mail(false, List.of());
        when(context.repository.markRead(context.userId, rewardless.id()))
            .thenReturn(context.readMail(rewardless));
        AtomicReference<Boolean> result = new AtomicReference<>();

        context.runWithPlayerServices(() ->
            context.service.readAndReceive(context.astPlayer, rewardless, result::set)
        );

        assertTrue(result.get());
        verify(context.inventoryService, never()).addPreparedRewardsToNormalInventory(any(), any());
        verify(context.inventoryService, never()).snapshotState(any());
        verify(context.inventoryService, never()).restoreState(any());
    }

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

    @Test
    void stateLossKeepsClaimLockedUntilMarkReadReconciliationCompletes() {
        TestContext context = new TestContext();
        InventoryService.InventoryGrantReceipt receipt = context.receipt(3L);
        AtomicReference<Runnable> reconciliation = new AtomicReference<>();
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
        AtomicReference<Boolean> firstResult = new AtomicReference<>();
        AtomicReference<Boolean> duplicateResult = new AtomicReference<>();

        context.runWithPlayerServices(() -> {
            context.service.readAndReceive(context.astPlayer, context.mail, firstResult::set);
            context.service.readAndReceive(context.astPlayer, context.mail, duplicateResult::set);
        });

        assertFalse(firstResult.get());
        assertFalse(duplicateResult.get());
        assertNotNull(reconciliation.get());
        verify(context.inventoryService, times(1)).addPreparedRewardsToNormalInventory(
            eq(context.astPlayer),
            any()
        );
        verify(context.repository, times(1)).markRead(context.userId, context.mail.id());

        when(context.repository.markRead(context.userId, context.mail.id())).thenReturn(context.readMail());
        reconciliation.get().run();
        AtomicReference<Boolean> staleResult = new AtomicReference<>();
        context.service.readAndReceive(context.astPlayer, context.mail, staleResult::set);

        assertTrue(staleResult.get());
        verify(context.inventoryService, times(1)).addPreparedRewardsToNormalInventory(
            eq(context.astPlayer),
            any()
        );
        verify(context.repository, times(2)).markRead(context.userId, context.mail.id());
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
