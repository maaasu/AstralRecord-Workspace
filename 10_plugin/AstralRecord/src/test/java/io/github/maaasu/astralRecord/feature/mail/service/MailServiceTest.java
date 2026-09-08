package io.github.maaasu.astralRecord.feature.mail.service;

import com.google.gson.JsonObject;
import io.github.maaasu.astralRecord.feature.account.model.AccountModel;
import io.github.maaasu.astralRecord.feature.inventory.service.InventorySaveCoordinator;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryType;
import io.github.maaasu.astralRecord.feature.inventory.state.InventoryPersistence;
import io.github.maaasu.astralRecord.feature.item.model.EquipmentInstance;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.item.service.ItemService;
import io.github.maaasu.astralRecord.feature.mail.model.MailEntry;
import io.github.maaasu.astralRecord.feature.mail.model.MailReward;
import io.github.maaasu.astralRecord.feature.mail.repository.MailRepository;
import io.github.maaasu.astralRecord.feature.mutation.model.PlayerStateSection;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
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
import org.mockito.MockedStatic;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MailServiceTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/18-mail/18_4-統合フロー.md
     * 章・見出し: # 18_4-統合フロー > ## 2. 未読メールの報酬受取
     * 検証契約: 未読化と報酬entryはmailClaim sectionを含むcritical snapshotで確定し、旧markRead APIを呼ばない。
     */
    @Test
    void claimsRewardOnlyAfterCriticalSnapshotAcknowledgement() {
        TestContext context = new TestContext();
        InventoryService.InventoryGrantReceipt receipt = context.receipt(3L);
        when(context.inventoryService.snapshotState(context.accountId)).thenReturn(context.rollbackSnapshot());
        when(context.inventoryService.addPreparedRewardsToNormalInventoryStateOnly(eq(context.astPlayer), any()))
            .thenReturn(receipt);
        context.completeCriticalMutation();
        AtomicReference<MailService.ReadAndReceiveResult> result = new AtomicReference<>();

        context.runWithPlayerServices(() ->
            context.service.readAndReceive(context.astPlayer, context.mail, result::set)
        );

        assertTrue(result.get().success());
        assertTrue(result.get().rewardReceived());
        verify(context.inventoryService).addPreparedRewardsToNormalInventoryStateOnly(eq(context.astPlayer), any());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/18-mail/18_4-統合フロー.md
     * 章・見出し: # 18_4-統合フロー > ## 2. 未読メールの報酬受取
     * 検証契約: critical snapshotが失敗した場合は成功通知・既読完了を返さず、旧別通信によるreconciliationを開始しない。
     */
    @Test
    void criticalSnapshotFailureReturnsFailureWithoutMarkReadReconciliation() {
        TestContext context = new TestContext();
        when(context.inventoryService.executeCriticalPlayerMutation(eq(context.accountId), any()))
            .thenReturn(CompletableFuture.failedFuture(new CompletionException("save failed", null)));
        AtomicReference<MailService.ReadAndReceiveResult> result = new AtomicReference<>();

        context.runWithPlayerServices(() ->
            context.service.readAndReceive(context.astPlayer, context.mail, result::set)
        );

        assertFalse(result.get().success());
        assertFalse(result.get().rewardReceived());
        verify(context.messageService).send(context.astPlayer, PlayerMsgId.P_5625);
        verify(context.inventoryService, never()).rollbackPreparedRewards(any());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/18-mail/18_4-統合フロー.md
     * 章・見出し: # 18_4-統合フロー > ## 2. 未読メールの報酬受取
     * 検証契約: equipment instanceのUUIDと乱数はcritical mutation内でローカル生成し、先行API個体生成を行わない。
     */
    @Test
    void equipmentRewardUsesLocalCreationInsideCriticalSnapshot() {
        TestContext context = new TestContext();
        EquipmentInstance instance = mock(EquipmentInstance.class);
        UUID instanceId = UUID.randomUUID();
        when(context.itemModel.getCategory()).thenReturn("equipment");
        when(instance.getEquipmentInstanceId()).thenReturn(instanceId.toString());
        when(context.itemService.createLocalEquipmentInstance(context.itemModel, context.accountId)).thenReturn(instance);
        when(context.inventoryService.snapshotState(context.accountId)).thenReturn(context.rollbackSnapshot());
        when(context.inventoryService.addPreparedRewardsToNormalInventoryStateOnly(eq(context.astPlayer), any()))
            .thenReturn(context.receipt(1L));
        context.completeCriticalMutation();

        context.runWithPlayerServices(() ->
            context.service.readAndReceive(context.astPlayer, context.mail, ignored -> { })
        );

        verify(context.itemService).createLocalEquipmentInstance(context.itemModel, context.accountId);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/18-mail/18_4-統合フロー.md
     * 章・見出し: # 18_4-統合フロー > ## 2. 未読メールの報酬受取
     * 検証契約: snapshotはaccountId・clientRevision・mailIdを送り、ACKのID照合後にだけ保留claimを解除する。
     */
    @Test
    void mailClaimSectionRequiresMatchingAcknowledgement() {
        TestContext context = new TestContext();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Function<UUID, PlayerStateSection>> participant = ArgumentCaptor.forClass(Function.class);
        verify(context.persistence).registerStateParticipant(participant.capture());

        assertNull(participant.getValue().apply(context.accountId));
        context.runCriticalMutationWithoutCompleting();
        PlayerStateSection section = participant.getValue().apply(context.accountId);
        assertNotNull(section);
        JsonObject payload = section.payload().getAsJsonObject();
        assertEquals(context.accountId.toString(), payload.get("accountId").getAsString());
        assertEquals(context.mail.id(), payload.get("mailId").getAsString());

        JsonObject acknowledgement = new JsonObject();
        acknowledgement.addProperty("clientRevision", payload.get("clientRevision").getAsString());
        acknowledgement.addProperty("mailId", context.mail.id());
        acknowledgement.addProperty("version", 1);
        acknowledgement.addProperty("readAt", LocalDateTime.now().toString());
        section.acknowledge().accept(acknowledgement);
        assertNull(participant.getValue().apply(context.accountId));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/18-mail/18_4-統合フロー.md
     * 章・見出し: # 18_4-統合フロー > ## 2. 未読メールの報酬受取
     * 検証契約: 削除は個別PUTを使わずmailDelete sectionとして保存し、対応するSQL ACKで保留を解除する。
     */
    @Test
    void mailDeleteUsesCriticalSnapshotSection() {
        TestContext context = new TestContext();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Function<UUID, PlayerStateSection>> participant = ArgumentCaptor.forClass(Function.class);
        verify(context.persistence).registerStateParticipant(participant.capture());
        context.runDeleteWithoutCompleting();

        PlayerStateSection section = participant.getValue().apply(context.accountId);
        assertNotNull(section);
        assertEquals("mailDelete", section.name());
        JsonObject payload = section.payload().getAsJsonObject();
        assertEquals(context.accountId.toString(), payload.get("accountId").getAsString());
        assertEquals(context.mail.id(), payload.get("mailId").getAsString());

        JsonObject acknowledgement = new JsonObject();
        acknowledgement.addProperty("clientRevision", payload.get("clientRevision").getAsString());
        acknowledgement.addProperty("mailId", context.mail.id());
        acknowledgement.addProperty("version", 2);
        acknowledgement.addProperty("deletedAt", LocalDateTime.now().toString());
        section.acknowledge().accept(acknowledgement);
        assertNull(participant.getValue().apply(context.accountId));
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
        private final InventoryPersistence persistence = mock(InventoryPersistence.class);
        private final PlayerMessageService messageService = mock(PlayerMessageService.class);
        private final Player player = mock(Player.class);
        private final AstPlayer astPlayer = mock(AstPlayer.class);
        private final UserModel user = mock(UserModel.class);
        private final AccountModel account = mock(AccountModel.class);
        private final ItemModel itemModel = mock(ItemModel.class);
        private final MailEntry mail = new MailEntry(
            "mail-1", "CHEST", "報酬メール", "本文", LocalDateTime.now().minusMinutes(1), null,
            true, List.of(new MailReward("reward-item", "material", 1)), false, null
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
            when(inventoryService.getPersistence()).thenReturn(persistence);
            when(itemService.captureEquipmentStateRollback(accountId)).thenReturn(() -> { });
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

        private void completeCriticalMutation() {
            when(inventoryService.executeCriticalPlayerMutation(eq(accountId), any())).thenAnswer(invocation -> {
                @SuppressWarnings("unchecked")
                Supplier<InventorySaveCoordinator.CriticalMutation<Object>> mutation =
                    invocation.getArgument(1);
                return CompletableFuture.completedFuture(mutation.get().result());
            });
        }

        private void runDeleteWithoutCompleting() {
            when(inventoryService.executeCriticalPlayerMutation(eq(accountId), any())).thenAnswer(invocation -> {
                @SuppressWarnings("unchecked")
                Supplier<InventorySaveCoordinator.CriticalMutation<Boolean>> mutation = invocation.getArgument(1);
                mutation.get();
                return new CompletableFuture<Boolean>();
            });
            runWithPlayerServices(() -> service.delete(astPlayer, mail.id(), ignored -> { }));
        }

        private void runCriticalMutationWithoutCompleting() {
            when(inventoryService.snapshotState(accountId)).thenReturn(rollbackSnapshot());
            when(inventoryService.addPreparedRewardsToNormalInventoryStateOnly(eq(astPlayer), any()))
                .thenReturn(receipt(0L));
            when(inventoryService.executeCriticalPlayerMutation(eq(accountId), any())).thenAnswer(invocation -> {
                @SuppressWarnings("unchecked")
                Supplier<InventorySaveCoordinator.CriticalMutation<InventoryService.InventoryGrantReceipt>> mutation =
                    invocation.getArgument(1);
                mutation.get();
                return new CompletableFuture<InventoryService.InventoryGrantReceipt>();
            });
            runWithPlayerServices(() -> service.readAndReceive(astPlayer, mail, ignored -> { }));
        }

        private InventoryService.InventoryGrantReceipt receipt(long quantity) {
            return new InventoryService.InventoryGrantReceipt(accountId, List.of(
                new InventoryService.InventoryGrantMutation(UUID.randomUUID(), null, quantity)
            ));
        }

        private InventoryService.InventoryStateSnapshot rollbackSnapshot() {
            return new InventoryService.InventoryStateSnapshot(accountId, Map.of(), InventoryType.BAG, false);
        }

        private void runWithPlayerServices(Runnable action) {
            try (MockedStatic<AstPlayerCache> cache = mockStatic(AstPlayerCache.class);
                 MockedStatic<PlayerMessageService> messages = mockStatic(PlayerMessageService.class)) {
                cache.when(() -> AstPlayerCache.get(player)).thenReturn(astPlayer);
                messages.when(PlayerMessageService::getInstance).thenReturn(messageService);
                action.run();
            }
        }
    }
}
