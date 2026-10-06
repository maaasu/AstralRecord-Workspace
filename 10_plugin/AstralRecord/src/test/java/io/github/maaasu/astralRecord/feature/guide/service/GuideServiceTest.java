package io.github.maaasu.astralRecord.feature.guide.service;

import com.google.gson.JsonObject;
import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.account.model.AccountModel;
import io.github.maaasu.astralRecord.feature.guide.model.GuideCondition;
import io.github.maaasu.astralRecord.feature.guide.model.GuideConditionType;
import io.github.maaasu.astralRecord.feature.guide.model.GuideEntry;
import io.github.maaasu.astralRecord.feature.guide.model.GuideStep;
import io.github.maaasu.astralRecord.feature.guide.model.GuideStepKey;
import io.github.maaasu.astralRecord.feature.guide.repository.GuideProgressRepository;
import io.github.maaasu.astralRecord.feature.guide.repository.GuideRepository;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.item.service.ItemService;
import io.github.maaasu.astralRecord.feature.mutation.model.PlayerStateSection;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import org.bukkit.entity.Player;
import io.github.maaasu.astralRecord.feature.playerclass.PlayerClassService;
import io.github.maaasu.astralRecord.feature.world.service.WorldService;
import org.bukkit.Server;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GuideServiceTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/03-player/3-メソッド仕様/03_3-イベント.md
     * 章・見出し: # 03_3-イベント > ## 1. event メソッド仕様 > ### プレイヤー参加イベント受付
     * 検証契約: ガイド進行の読み込みFutureを共有し、正常取得して現世代へ反映した場合だけtrueで完了する。
     */
    @Test
    void loadProgressSharesFutureAndCompletesTrueAfterSuccessfulLoad() {
        Fixture fixture = new Fixture();
        UUID accountId = UUID.randomUUID();
        GuideStepKey completedStep = new GuideStepKey("beginner_onboarding", "open_guide");
        when(fixture.progressRepository.findByAccountId(accountId)).thenReturn(Set.of(completedStep));

        CompletableFuture<Boolean> first = fixture.service.loadProgressAsync(accountId);
        CompletableFuture<Boolean> second = fixture.service.loadProgressAsync(accountId);

        assertSame(first, second);
        assertFalse(first.isDone());
        fixture.asyncTasks.getFirst().run();

        assertTrue(first.join());
        assertTrue(fixture.service.isStepCompleted(accountId, completedStep.guideId(), completedStep.stepId()));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/03-player/3-メソッド仕様/03_3-イベント.md
     * 章・見出し: # 03_3-イベント > ## 1. event メソッド仕様 > ### プレイヤー参加イベント受付
     * 検証契約: ガイド進行の読み込みに失敗した場合、Futureはfalseで完了してtitle表示可否の成功判定へ進めない。
     */
    @Test
    void loadProgressCompletesFalseAfterRepositoryFailure() {
        Fixture fixture = new Fixture();
        UUID accountId = UUID.randomUUID();
        when(fixture.progressRepository.findByAccountId(accountId))
            .thenThrow(new IllegalStateException("progress load failed"));

        CompletableFuture<Boolean> future = fixture.service.loadProgressAsync(accountId);
        fixture.asyncTasks.getFirst().run();

        assertFalse(future.join());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/03-player/3-メソッド仕様/03_3-イベント.md
     * 章・見出し: # 03_3-イベント > ## 1. event メソッド仕様 > ### プレイヤー退出イベント受付
     * 検証契約: 進行読み込み中に世代を解放した場合、古い結果をキャッシュへ反映せずFutureをfalseで完了する。
     */
    @Test
    void loadProgressCompletesFalseWhenAccountGenerationIsReleased() {
        Fixture fixture = new Fixture();
        UUID accountId = UUID.randomUUID();
        GuideStepKey completedStep = new GuideStepKey("beginner_onboarding", "open_guide");
        when(fixture.progressRepository.findByAccountId(accountId)).thenReturn(Set.of(completedStep));

        CompletableFuture<Boolean> future = fixture.service.loadProgressAsync(accountId);
        fixture.service.releaseProgress(accountId);
        fixture.asyncTasks.getFirst().run();

        assertFalse(future.join());
        assertFalse(fixture.service.isStepCompleted(accountId, completedStep.guideId(), completedStep.stepId()));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/03-player/3-メソッド仕様/03_3-保存.md
     * 章・見出し: # 03_3-保存 > ## 通常変更の統合スナップショット
     * 検証契約: ガイド達成はローカルへ即時反映し、logout後もSQL ACKまでは完成状態を保持する。
     */
    @Test
    void completedStepUsesSnapshotSaveAndSurvivesReleaseUntilAcknowledged() {
        Fixture fixture = new Fixture();
        UUID accountId = UUID.randomUUID();
        when(fixture.progressRepository.findByAccountId(accountId)).thenReturn(Set.of());
        fixture.service.replaceEntrySnapshot(List.of(new GuideEntry(
            3,
            GuideService.INITIAL_GUIDE_ID,
            "beginner",
            1,
            "Beginner",
            null,
            null,
            List.of(new GuideStep(
                GuideService.INITIAL_GUIDE_OPEN_STEP_ID,
                "Open",
                List.of(),
                new GuideCondition(GuideConditionType.GUIDE_OPENED, null),
                null
            ))
        )));
        AstPlayer player = mock(AstPlayer.class);
        AccountModel account = mock(AccountModel.class);
        when(player.getAccount()).thenReturn(account);
        when(account.getUuid()).thenReturn(accountId);

        fixture.service.loadProgressAsync(accountId);
        fixture.asyncTasks.getFirst().run();
        fixture.service.recordConditionSilently(player, GuideConditionType.GUIDE_OPENED, null);

        verify(fixture.inventoryService).queueLocalPlayerSave(accountId);
        PlayerStateSection section = fixture.service.snapshotPlayerState(accountId);
        assertNotNull(section);
        JsonObject payload = section.payload().getAsJsonObject();
        assertTrue(payload.get("isFullSnapshot").getAsBoolean());
        assertTrue(fixture.service.isStepCompleted(
            accountId, GuideService.INITIAL_GUIDE_ID, GuideService.INITIAL_GUIDE_OPEN_STEP_ID));

        fixture.service.releaseProgress(accountId);
        assertNotNull(fixture.service.snapshotPlayerState(accountId));
        section.acknowledge().accept(payload);

        assertNull(fixture.service.snapshotPlayerState(accountId));
        assertFalse(fixture.service.isStepCompleted(
            accountId, GuideService.INITIAL_GUIDE_ID, GuideService.INITIAL_GUIDE_OPEN_STEP_ID));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/09-menu/09_4-統合フロー.md
     * 章・見出し: # 09_4-統合フロー > ## 4. ガイド進行と通知
     * 検証契約: 初回破損の記録はロード前の同時イベントでも一度だけ通知し、アカウント保存後の再ログインでも繰り返さない。
     */
    @Test
    void firstBreakIsPersistedOnceAcrossPendingEventsAndRelogin() {
        Fixture fixture = new Fixture();
        UUID accountId = UUID.randomUUID();
        AstPlayer player = fixture.player(accountId);
        GuideStepKey brokenKey = new GuideStepKey("first_equipment_repair", "notice_broken_equipment");
        fixture.service.replaceEntrySnapshot(List.of(repairGuide()));
        when(fixture.progressRepository.findByAccountId(accountId)).thenReturn(Set.of());

        fixture.service.recordCondition(player, GuideConditionType.EQUIPMENT_BROKEN, "weapon");
        fixture.service.recordCondition(player, GuideConditionType.EQUIPMENT_BROKEN, "helmet");
        fixture.asyncTasks.getFirst().run();
        when(player.getBukkit().isOnline()).thenReturn(true);
        try (MockedStatic<AstPlayerCache> cache = mockStatic(AstPlayerCache.class)) {
            cache.when(() -> AstPlayerCache.get(player.getBukkit())).thenReturn(player);
            fixture.syncTasks.getFirst().run();
        }

        assertTrue(fixture.service.isStepCompleted(accountId, brokenKey.guideId(), brokenKey.stepId()));
        verify(fixture.messages, times(1)).send(player, PlayerMsgId.P_5180, "Broken");
        verify(fixture.messages, times(1)).send(player, PlayerMsgId.P_5186, "Use Sindri");
        verify(fixture.inventoryService, times(1)).queueLocalPlayerSave(accountId);
        PlayerStateSection snapshot = fixture.service.snapshotPlayerState(accountId);
        assertNotNull(snapshot);
        assertTrue(snapshot.payload().getAsJsonObject().getAsJsonArray("completedStepKeys").toString()
            .contains("notice_broken_equipment"));

        fixture.service.releaseProgress(accountId);
        snapshot.acknowledge().accept(snapshot.payload());
        when(fixture.progressRepository.findByAccountId(accountId)).thenReturn(Set.of(brokenKey));
        fixture.service.loadProgressAsync(accountId);
        fixture.asyncTasks.get(1).run();
        fixture.service.recordCondition(player, GuideConditionType.EQUIPMENT_BROKEN, "boots");
        verify(fixture.messages, times(1)).send(player, PlayerMsgId.P_5180, "Broken");
        verify(fixture.inventoryService, times(1)).queueLocalPlayerSave(accountId);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/09-menu/09_4-統合フロー.md
     * 章・見出し: # 09_4-統合フロー > ## 4. ガイド進行と通知
     * 検証契約: ロード待ち破損→即ログアウト後も、次回ログインの保存済み耐久 0 から補完し、二重通知しない。
     */
    @Test
    void brokenEquipmentIsRecoveredAfterLogoutDuringProgressLoad() {
        Fixture fixture = new Fixture();
        UUID accountId = UUID.randomUUID();
        AstPlayer player = fixture.player(accountId);
        GuideStepKey brokenKey = new GuideStepKey("first_equipment_repair", "notice_broken_equipment");
        fixture.service.replaceEntrySnapshot(List.of(repairGuide()));
        when(fixture.progressRepository.findByAccountId(accountId)).thenReturn(Set.of());
        when(fixture.itemService.hasLoadedBrokenEquipment(accountId)).thenReturn(true);

        fixture.service.recordCondition(player, GuideConditionType.EQUIPMENT_BROKEN, "weapon");
        fixture.service.releaseProgress(accountId);
        fixture.asyncTasks.getFirst().run();
        assertFalse(fixture.service.isStepCompleted(accountId, brokenKey.guideId(), brokenKey.stepId()));

        fixture.service.loadProgressAsync(accountId);
        fixture.asyncTasks.get(1).run();
        fixture.service.reconcileLoadedAccount(player, false);
        assertTrue(fixture.service.isStepCompleted(accountId, brokenKey.guideId(), brokenKey.stepId()));
        verify(fixture.messages, times(1)).send(player, PlayerMsgId.P_5186, "Use Sindri");
        PlayerStateSection snapshot = fixture.service.snapshotPlayerState(accountId);
        assertNotNull(snapshot);
        fixture.service.releaseProgress(accountId);
        snapshot.acknowledge().accept(snapshot.payload());

        when(fixture.progressRepository.findByAccountId(accountId)).thenReturn(Set.of(brokenKey));
        fixture.service.loadProgressAsync(accountId);
        fixture.asyncTasks.get(2).run();
        fixture.service.reconcileLoadedAccount(player, false);
        verify(fixture.messages, times(1)).send(player, PlayerMsgId.P_5180, "Broken");
        verify(fixture.inventoryService, times(1)).queueLocalPlayerSave(accountId);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/09-menu/09_4-統合フロー.md
     * 章・見出し: # 09_4-統合フロー > ## 4. ガイド進行と通知
     * 検証契約: 通常ログインで読み込んだ孵化済みペットはガイドへ補完し、魔法の卵だけは対象にしない。
     */
    @Test
    void loadedHatchedPetIsReconciledOnce() {
        Fixture fixture = new Fixture();
        UUID accountId = UUID.randomUUID();
        AstPlayer player = fixture.player(accountId);
        fixture.service.replaceEntrySnapshot(List.of(
            guide("first_pet_hatching", "Pets", "Hatch", GuideConditionType.PET_HATCHED, 1)
        ));
        when(fixture.progressRepository.findByAccountId(accountId)).thenReturn(Set.of());
        fixture.service.loadProgressAsync(accountId);
        fixture.asyncTasks.getFirst().run();

        fixture.service.reconcileLoadedAccount(player, false);
        assertFalse(fixture.service.isStepCompleted(accountId, "first_pet_hatching", "step"));
        fixture.service.reconcileLoadedAccount(player, true);
        fixture.service.reconcileLoadedAccount(player, true);
        assertTrue(fixture.service.isStepCompleted(accountId, "first_pet_hatching", "step"));
        verify(fixture.inventoryService, times(1)).queueLocalPlayerSave(accountId);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/09-menu/09_4-統合フロー.md
     * 章・見出し: # 09_4-統合フロー > ## 4. ガイド進行と通知
     * 検証契約: 破損前の部分修理は修理手順にならず、初回破損後にシンドリ操作を案内する。
     */
    @Test
    void sindriUseBeforeFirstBreakDoesNotCompleteRepairStep() {
        Fixture fixture = new Fixture();
        UUID accountId = UUID.randomUUID();
        AstPlayer player = fixture.player(accountId);
        fixture.service.replaceEntrySnapshot(List.of(repairGuide()));
        when(fixture.progressRepository.findByAccountId(accountId)).thenReturn(Set.of());
        fixture.service.loadProgressAsync(accountId);
        fixture.asyncTasks.getFirst().run();

        fixture.service.recordCondition(player, GuideConditionType.ORB_USED, "40a00011");
        assertFalse(fixture.service.isStepCompleted(accountId, "first_equipment_repair", "repair_with_sindri"));
        fixture.service.recordCondition(player, GuideConditionType.EQUIPMENT_BROKEN, "weapon");
        assertFalse(fixture.service.areAllGuidesCompleted(accountId));
        verify(fixture.messages).send(player, PlayerMsgId.P_5186, "Use Sindri");
        verify(fixture.messages, never()).send(eq(player), eq(PlayerMsgId.P_5187), any());

        fixture.service.recordCondition(player, GuideConditionType.ORB_USED, "40a00011");
        assertTrue(fixture.service.areAllGuidesCompleted(accountId));
        verify(fixture.inventoryService, times(2)).queueLocalPlayerSave(accountId);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/09-menu/09_4-統合フロー.md
     * 章・見出し: # 09_4-統合フロー > ## 4. ガイド進行と通知
     * 検証契約: クリア直後は次の未完了ガイドを示し、全完了後は後日追加されたガイドだけを未完了として扱う。
     */
    @Test
    void completionShowsNextGuideAndLaterGuideAdditionReopensProgress() {
        Fixture fixture = new Fixture();
        UUID accountId = UUID.randomUUID();
        AstPlayer player = fixture.player(accountId);
        GuideEntry first = guide("first", "First", "Start", GuideConditionType.SHOP_PURCHASED, 1);
        GuideEntry second = guide("second", "Second", "Next action", GuideConditionType.QUEST_ACCEPTED, 2);
        GuideEntry later = guide("later", "Later", "Later action", GuideConditionType.QUEST_COMPLETED, 3);
        fixture.service.replaceEntrySnapshot(List.of(first, second));
        when(fixture.progressRepository.findByAccountId(accountId)).thenReturn(Set.of());
        fixture.service.loadProgressAsync(accountId);
        fixture.asyncTasks.getFirst().run();

        fixture.service.recordCondition(player, GuideConditionType.SHOP_PURCHASED, null);
        verify(fixture.messages).send(player, PlayerMsgId.P_5185, "Second");
        verify(fixture.messages).send(player, PlayerMsgId.P_5186, "Next action");
        fixture.service.recordCondition(player, GuideConditionType.QUEST_ACCEPTED, null);
        assertTrue(fixture.service.areAllGuidesCompleted(accountId));
        assertFalse(fixture.service.hasIncompleteGuide(accountId));
        fixture.service.recordCondition(player, GuideConditionType.QUEST_ACCEPTED, null);
        verify(fixture.messages, times(1)).send(player, PlayerMsgId.P_5181, "Second");

        fixture.service.replaceEntrySnapshot(List.of(first, second, later));
        assertFalse(fixture.service.areAllGuidesCompleted(accountId));
        assertTrue(fixture.service.hasIncompleteGuide(accountId));
        fixture.service.recordCondition(player, GuideConditionType.QUEST_COMPLETED, null);
        assertTrue(fixture.service.areAllGuidesCompleted(accountId));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/09-menu/09_4-統合フロー.md
     * 章・見出し: # 09_4-統合フロー > ## 4. ガイド進行と通知
     * 検証契約: 未ロードやガイドなしを全達成と誤認せず、未破損の修理ガイドを自動の次案内にしない。
     */
    @Test
    void unloadedAndEmptyMasterAreNotAllCompletedAndRepairStartsOnlyOnBreak() {
        Fixture fixture = new Fixture();
        UUID accountId = UUID.randomUUID();
        AstPlayer player = fixture.player(accountId);
        assertFalse(fixture.service.isProgressLoaded(accountId));
        assertFalse(fixture.service.areAllGuidesCompleted(accountId));
        fixture.service.loadProgressAsync(accountId);
        fixture.asyncTasks.getFirst().run();
        assertTrue(fixture.service.isProgressLoaded(accountId));
        assertFalse(fixture.service.areAllGuidesCompleted(accountId));
        assertFalse(fixture.service.hasIncompleteGuide(accountId));

        fixture.service.replaceEntrySnapshot(List.of(
            guide("starter", "Starter", "Start", GuideConditionType.SHOP_PURCHASED, 1), repairGuide()
        ));
        fixture.service.recordCondition(player, GuideConditionType.SHOP_PURCHASED, null);
        verify(fixture.messages, never()).send(org.mockito.ArgumentMatchers.eq(player),
            org.mockito.ArgumentMatchers.eq(PlayerMsgId.P_5185), any());
        assertTrue(fixture.service.hasIncompleteGuide(accountId));
        assertFalse(fixture.service.areAllGuidesCompleted(accountId));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/09-menu/09_4-統合フロー.md
     * 章・見出し: # 09_4-統合フロー > ## 4. ガイド進行と通知
     * 検証契約: 全完了と空マスターでは定期ガイド促しを止め、後日追加したガイドがある時だけ再開する。
     */
    @Test
    void reminderStopsAtCompletionAndResumesForAddedGuide() {
        Fixture fixture = new Fixture();
        UUID accountId = UUID.randomUUID();
        AstPlayer player = fixture.player(accountId);
        Player bukkit = player.getBukkit();
        GuideReminderTask reminder = new GuideReminderTask(fixture.messages, fixture.service);
        GuideEntry first = guide("first", "First", "Start", GuideConditionType.SHOP_PURCHASED, 1);
        GuideEntry later = guide("later", "Later", "Later action", GuideConditionType.QUEST_COMPLETED, 2);
        when(fixture.progressRepository.findByAccountId(accountId)).thenReturn(Set.of());

        try (MockedStatic<AstPlayerCache> cache = mockStatic(AstPlayerCache.class)) {
            cache.when(AstPlayerCache::getAll).thenReturn(List.of(player));
            reminder.sendReminder();
            fixture.service.loadProgressAsync(accountId);
            fixture.asyncTasks.getFirst().run();
            reminder.sendReminder();
            fixture.service.replaceEntrySnapshot(List.of(first));
            reminder.sendReminder();
            fixture.service.recordCondition(player, GuideConditionType.SHOP_PURCHASED, null);
            reminder.sendReminder();
            fixture.service.replaceEntrySnapshot(List.of(first, later));
            reminder.sendReminder();
        }
        verify(fixture.messages, times(2)).sendClickable(bukkit, PlayerMsgId.P_5600, "/menu guide");
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/09-menu/09_4-統合フロー.md
     * 章・見出し: # 09_4-統合フロー > ## 4. ガイド進行と通知
     * 検証契約: ロード後に予約した旧セッションのイベントは再ログイン先へ適用せず、現セッションの破損補完だけが案内する。
     */
    @Test
    void pendingReplayAfterReloginDoesNotConsumeNewSessionBreakNotice() {
        Fixture fixture = new Fixture();
        UUID accountId = UUID.randomUUID();
        AstPlayer previous = fixture.player(accountId);
        AstPlayer current = fixture.player(accountId);
        fixture.service.replaceEntrySnapshot(List.of(repairGuide()));
        when(fixture.progressRepository.findByAccountId(accountId)).thenReturn(Set.of());
        when(fixture.itemService.hasLoadedBrokenEquipment(accountId)).thenReturn(true);
        when(previous.getBukkit().isOnline()).thenReturn(true);
        when(current.getBukkit().isOnline()).thenReturn(true);
        fixture.service.recordCondition(previous, GuideConditionType.EQUIPMENT_BROKEN, "weapon");
        fixture.asyncTasks.getFirst().run();
        fixture.service.releaseProgress(accountId);
        fixture.service.loadProgressAsync(accountId);
        fixture.asyncTasks.get(1).run();
        try (MockedStatic<AstPlayerCache> cache = mockStatic(AstPlayerCache.class)) {
            cache.when(() -> AstPlayerCache.get(previous.getBukkit())).thenReturn(current);
            fixture.syncTasks.getFirst().run();
        }
        assertFalse(fixture.service.isStepCompleted(accountId, "first_equipment_repair", "notice_broken_equipment"));
        verify(fixture.inventoryService, never()).queueLocalPlayerSave(accountId);
        fixture.service.reconcileLoadedAccount(current, false);
        verify(fixture.messages, never()).send(eq(previous), eq(PlayerMsgId.P_5186), any());
        verify(fixture.messages, times(1)).send(current, PlayerMsgId.P_5186, "Use Sindri");
        verify(fixture.inventoryService, times(1)).queueLocalPlayerSave(accountId);
    }

    private static GuideEntry guide(String id, String title, String text, GuideConditionType condition, int order) {
        return new GuideEntry(3, id, "beginner", order, title, null, null,
            List.of(new GuideStep("step", text, List.of(), new GuideCondition(condition, null), null)));
    }

    private static GuideEntry repairGuide() {
        return new GuideEntry(3, "first_equipment_repair", "equipment", 10, "Repair", null, null,
            List.of(
                new GuideStep("notice_broken_equipment", "Broken", List.of(),
                    new GuideCondition(GuideConditionType.EQUIPMENT_BROKEN, null), null),
                new GuideStep("repair_with_sindri", "Use Sindri", List.of(),
                    new GuideCondition(GuideConditionType.ORB_USED, "40a00011"), null)
            ));
    }

    private static final class Fixture {
        private final AstralRecord plugin = mock(AstralRecord.class);
        private final Server server = mock(Server.class);
        private final BukkitScheduler scheduler = mock(BukkitScheduler.class);
        private final GuideProgressRepository progressRepository = mock(GuideProgressRepository.class);
        private final InventoryService inventoryService = mock(InventoryService.class);
        private final ItemService itemService = mock(ItemService.class);
        private final PlayerMessageService messages = mock(PlayerMessageService.class);
        private final List<Runnable> asyncTasks = new ArrayList<>();
        private final List<Runnable> syncTasks = new ArrayList<>();
        private final GuideService service;

        private Fixture() {
            when(plugin.getServer()).thenReturn(server);
            when(server.getScheduler()).thenReturn(scheduler);
            doAnswer(invocation -> {
                asyncTasks.add(invocation.getArgument(1));
                return mock(BukkitTask.class);
            }).when(scheduler).runTaskAsynchronously(eq(plugin), any(Runnable.class));
            doAnswer(invocation -> {
                syncTasks.add(invocation.getArgument(1));
                return mock(BukkitTask.class);
            }).when(scheduler).runTask(eq(plugin), any(Runnable.class));
            service = new GuideService(
                plugin,
                mock(GuideRepository.class),
                progressRepository,
                itemService,
                mock(PlayerClassService.class),
                mock(WorldService.class),
                messages,
                inventoryService
            );
            service.setSoundPlayers(ignored -> { }, ignored -> { });
        }

        private AstPlayer player(UUID accountId) {
            AstPlayer player = mock(AstPlayer.class);
            AccountModel account = mock(AccountModel.class);
            when(player.getAccount()).thenReturn(account);
            when(account.getUuid()).thenReturn(accountId);
            when(player.getBukkit()).thenReturn(mock(Player.class));
            return player;
        }
    }
}
