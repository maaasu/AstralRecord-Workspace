package io.github.maaasu.astralRecord.feature.guide.service;

import io.github.maaasu.astralRecord.feature.inventory.state.PlayerInventoryState;
import io.github.maaasu.astralRecord.feature.menu.service.TrashService;
import com.google.gson.JsonObject;
import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.account.model.AccountModel;
import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.feature.guide.model.GuideCondition;
import io.github.maaasu.astralRecord.feature.guide.model.GuideConditionType;
import io.github.maaasu.astralRecord.feature.guide.model.GuideEntry;
import io.github.maaasu.astralRecord.feature.guide.model.GuideStep;
import io.github.maaasu.astralRecord.feature.guide.model.GuideStepKey;
import io.github.maaasu.astralRecord.feature.guide.repository.GuideProgressRepository;
import io.github.maaasu.astralRecord.feature.guide.repository.GuideRepository;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.item.model.ItemReference;
import io.github.maaasu.astralRecord.feature.item.service.ItemService;
import io.github.maaasu.astralRecord.feature.mail.repository.MailRepository;
import io.github.maaasu.astralRecord.feature.mail.repository.MailRepository.WelcomeTutorialStatus;
import io.github.maaasu.astralRecord.feature.mail.service.MailService;
import io.github.maaasu.astralRecord.feature.mutation.model.PlayerStateSection;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.feature.playersetting.service.PlayerSettingService;
import org.bukkit.entity.Player;
import io.github.maaasu.astralRecord.feature.playerclass.PlayerClassService;
import io.github.maaasu.astralRecord.feature.world.service.WorldService;
import org.bukkit.Server;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class GuideServiceTest {

    /**
     * ガイド進行の読み込みFutureを共有し、正常取得して現世代へ反映した場合だけtrueで完了する。
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
     * ガイド進行の読み込みに失敗した場合、Futureはfalseで完了してtitle表示可否の成功判定へ進めない。
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
     * 進行読み込み中に世代を解放した場合、古い結果をキャッシュへ反映せずFutureをfalseで完了する。
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
     * ガイド達成はローカルへ即時反映し、logout後もSQL ACKまでは完成状態を保持する。
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
     * 初回破損の記録はロード前の同時イベントでも一度だけ通知し、アカウント保存後の再ログインでも繰り返さない。
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
     * ロード待ち破損→即ログアウト後も、次回ログインの保存済み耐久 0 から補完し、二重通知しない。
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
     * 通常ログインで読み込んだ孵化済みペットはガイドへ補完し、魔法の卵だけは対象にしない。
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
     * 破損前の部分修理は修理手順にならず、初回破損後にシンドリ操作を案内する。
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
     * クリア直後は次の未完了ガイドを示し、全完了後は後日追加されたガイドだけを未完了として扱う。
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
     * 未ロードやガイドなしを全達成と誤認せず、未破損の修理ガイドを自動の次案内にしない。
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
        verify(fixture.messages, never()).send(eq(player),
            eq(PlayerMsgId.P_5185), any());
        assertTrue(fixture.service.hasIncompleteGuide(accountId));
        assertFalse(fixture.service.areAllGuidesCompleted(accountId));
    }

    /**
     * 全完了・空マスター・通知OFFでは定期通知を止め、未完了進行と通知ONが揃うと次手順を1件案内する。
     */
    @Test
    void reminderStopsAtCompletionAndResumesForAddedGuide() {
        Fixture fixture = new Fixture();
        UUID accountId = UUID.randomUUID();
        AstPlayer player = fixture.player(accountId);
        Player bukkit = player.getBukkit();
        UUID userId = UUID.randomUUID();
        when(bukkit.getUniqueId()).thenReturn(userId);
        PlayerSettingService settings = mock(PlayerSettingService.class);
        when(settings.isGuideReminderEnabled(userId)).thenReturn(true);
        GuideReminderTask reminder = new GuideReminderTask(fixture.messages, fixture.service, settings);
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
            when(settings.isGuideReminderEnabled(userId)).thenReturn(false);
            reminder.sendReminder();
            when(settings.isGuideReminderEnabled(userId)).thenReturn(true);
            reminder.sendReminder();
        }
        verify(fixture.messages, times(3)).sendClickable(bukkit, PlayerMsgId.P_5600, "/menu guide");
        verify(fixture.messages).send(player, PlayerMsgId.P_5186, "Start");
        verify(fixture.messages, times(2)).send(player, PlayerMsgId.P_5186, "Later action");
    }

    /**
     * ロード後に予約した旧セッションのイベントは再ログイン先へ適用せず、現セッションの破損補完だけが案内する。
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

    /** 旧最終手順を達成済みでも、旧手順が一つでも欠けていれば新設手順を補完しません。 */
    @Test
    void incompleteLegacyProgressKeepsOnlyEarnedTutorialSteps() {
        Fixture fixture = new Fixture();
        UUID accountId = UUID.randomUUID();
        Set<GuideStepKey> legacy = legacyTutorialProgress();
        legacy.remove(new GuideStepKey(GuideService.INITIAL_GUIDE_ID, "buy_starter_armor_chest"));
        fixture.service.replaceEntrySnapshot(List.of(tutorialGuide()));
        when(fixture.progressRepository.findByAccountId(accountId)).thenReturn(legacy);

        assertNull(fixture.service.tutorialStep(accountId));
        CompletableFuture<Boolean> loaded = fixture.service.loadProgressAsync(accountId);
        fixture.asyncTasks.getFirst().run();

        assertTrue(loaded.join());
        assertEquals("open_menu", fixture.service.tutorialStep(accountId));
        assertTrue(fixture.service.isStepCompleted(accountId, GuideService.INITIAL_GUIDE_ID, "defeat_grassboar"));
        assertFalse(fixture.service.isStepCompleted(accountId, GuideService.INITIAL_GUIDE_ID, "equip_starter_weapon"));
        assertFalse(fixture.service.areAllGuidesCompleted(accountId));
        assertNull(fixture.service.snapshotPlayerState(accountId));
        verify(fixture.inventoryService, never()).queueLocalPlayerSave(accountId);
    }

    /** 完了済みの旧進行は公開時に補完済みで、ロード成功後に通常の保存へ一度だけ渡します。 */
    @Test
    void completedLegacyProgressMigratesBeforePublicationAndSavesAfterLoadCompletion() {
        Fixture fixture = new Fixture();
        UUID accountId = UUID.randomUUID();
        AstPlayer player = fixture.player(accountId);
        List<AstPlayer> notifications = new ArrayList<>();
        fixture.service.setTutorialCompletedListener(notifications::add);
        fixture.service.replaceEntrySnapshot(List.of(legacyTutorialGuide()));
        Set<GuideStepKey> legacy = legacyTutorialProgress();
        when(fixture.progressRepository.findByAccountId(accountId)).thenReturn(legacy);

        CompletableFuture<Boolean> loaded = fixture.service.loadProgressAsync(accountId);
        CompletableFuture<Void> atLoadCompletion = loaded.thenAccept(success -> {
            assertTrue(success);
            assertNull(fixture.service.tutorialStep(accountId));
            assertTrue(fixture.service.areAllGuidesCompleted(accountId));
            assertNull(fixture.service.snapshotPlayerState(accountId));
            verify(fixture.inventoryService, never()).queueLocalPlayerSave(accountId);
        });

        fixture.asyncTasks.getFirst().run();
        assertFalse(loaded.isDone());
        assertFalse(fixture.service.isProgressLoaded(accountId));
        fixture.syncTasks.getFirst().run();
        atLoadCompletion.join();
        fixture.service.replaceEntrySnapshot(List.of(tutorialGuide()));
        assertNull(fixture.service.tutorialStep(accountId));
        assertTrue(fixture.service.areAllGuidesCompleted(accountId));

        PlayerStateSection snapshot = fixture.service.snapshotPlayerState(accountId);
        assertNotNull(snapshot);
        JsonObject payload = snapshot.payload().getAsJsonObject();
        assertEquals(1L, payload.get("clientRevision").getAsLong());
        assertEquals(23, payload.getAsJsonArray("completedStepKeys").size());
        fixture.service.releaseProgress(accountId);
        snapshot.acknowledge().accept(payload);

        legacy.add(new GuideStepKey(GuideService.INITIAL_GUIDE_ID, "open_menu"));
        legacy.add(new GuideStepKey(GuideService.INITIAL_GUIDE_ID, "equip_starter_weapon"));
        CompletableFuture<Boolean> reloaded = fixture.service.loadProgressAsync(accountId);
        fixture.asyncTasks.get(1).run();
        assertTrue(reloaded.join());
        fixture.service.recordTutorialMenuOpened(player);

        assertNull(fixture.service.tutorialStep(accountId));
        assertNull(fixture.service.snapshotPlayerState(accountId));
        assertTrue(notifications.isEmpty());
        verify(fixture.inventoryService, times(1)).queueLocalPlayerSave(accountId);
    }

    /** 手順は順不同で記録し、導入の完了通知と全ガイド完了報酬を各達成時に一度だけ発行します。 */
    @Test
    void tutorialCompletesOutOfOrderWithoutAdvancingAllGuidesReward() {
        Fixture fixture = new Fixture();
        UUID accountId = UUID.randomUUID();
        AstPlayer player = fixture.player(accountId);
        ItemModel weapon = mock(ItemModel.class);
        when(weapon.getId()).thenReturn("20a00002", "20a00001");
        when(fixture.inventoryService.getOwnedItemModelAtBukkitSlot(player, 8)).thenReturn(weapon);
        when(fixture.progressRepository.findByAccountId(accountId)).thenReturn(Set.of());
        List<AstPlayer> tutorialCompletions = new ArrayList<>();
        List<AstPlayer> allCompletions = new ArrayList<>();
        fixture.service.setTutorialCompletedListener(tutorialCompletions::add);
        fixture.service.setAllGuidesCompletedListener(allCompletions::add);
        fixture.service.replaceEntrySnapshot(List.of(tutorialGuide(),
            guide("skills", "Skills", "Unlock a skill", GuideConditionType.SKILLTREE_NODE_UNLOCKED, 2)));
        fixture.service.loadProgressAsync(accountId);
        fixture.asyncTasks.getFirst().run();

        assertEquals(0, fixture.service.tutorialCompletedStepCount(accountId));
        fixture.service.recordCondition(player, GuideConditionType.MOB_DEFEATED, "midgard_grassboar");
        assertEquals(1, fixture.service.tutorialCompletedStepCount(accountId));
        assertEquals("open_menu", fixture.service.tutorialStep(accountId));
        fixture.service.recordCondition(player, GuideConditionType.SHOP_PURCHASED, "nox_weapon");
        when(player.getAccount().getMode()).thenReturn(AccountMode.ADMIN);
        fixture.service.recordTutorialHeldWeapon(player, 8);
        when(player.getAccount().getMode()).thenReturn(AccountMode.PLAYER);
        fixture.service.recordTutorialHeldWeapon(player, -1);
        fixture.service.recordTutorialHeldWeapon(player, 9);
        fixture.service.recordTutorialHeldWeapon(player, 8);
        assertFalse(fixture.service.isStepCompleted(accountId, GuideService.INITIAL_GUIDE_ID, "equip_starter_weapon"));
        fixture.service.recordTutorialHeldWeapon(player, 8);
        assertTrue(fixture.service.isStepCompleted(accountId, GuideService.INITIAL_GUIDE_ID, "equip_starter_weapon"));
        fixture.service.recordTutorialHeldWeapon(player, 8);
        verify(fixture.inventoryService, times(2)).getOwnedItemModelAtBukkitSlot(
            eq(player), anyInt());
        fixture.service.recordCondition(player, GuideConditionType.BUNDLE_OPENED, "50a00001");
        fixture.service.recordCondition(player, GuideConditionType.MAIL_RECEIVED, "welcome_mail");

        assertEquals("open_menu", fixture.service.tutorialStep(accountId));
        assertTrue(tutorialCompletions.isEmpty());
        assertTrue(allCompletions.isEmpty());

        fixture.service.recordTutorialMenuOpened(player);
        fixture.service.recordTutorialMenuOpened(player);
        assertNull(fixture.service.tutorialStep(accountId));
        assertEquals(6, fixture.service.tutorialCompletedStepCount(accountId));
        assertEquals(List.of(player), tutorialCompletions);
        assertFalse(fixture.service.areAllGuidesCompleted(accountId));
        assertTrue(allCompletions.isEmpty());
        verify(fixture.messages, never()).send(player, PlayerMsgId.P_5186, "Unlock a skill");

        fixture.service.recordCondition(player, GuideConditionType.SKILLTREE_NODE_UNLOCKED, "1047");
        fixture.service.recordCondition(player, GuideConditionType.SKILLTREE_NODE_UNLOCKED, "1047");
        assertTrue(fixture.service.areAllGuidesCompleted(accountId));
        assertEquals(List.of(player), allCompletions);
        assertEquals(List.of(player), tutorialCompletions);
    }

    @Test
    void onlyProductionMasterEnablesTutorialUiAndCompletion() {
        Fixture fixture = new Fixture();
        UUID accountId = UUID.randomUUID();
        AstPlayer player = fixture.player(accountId);
        List<AstPlayer> completions = new ArrayList<>();
        fixture.service.setTutorialCompletedListener(completions::add);
        fixture.service.loadProgressAsync(accountId);
        fixture.asyncTasks.getFirst().run();

        GuideEntry legacy = legacyTutorialGuide();
        fixture.service.replaceEntrySnapshot(List.of(legacy));
        assertEquals(List.of(legacy), fixture.service.getAll());
        assertNull(fixture.service.tutorialStep(accountId));

        List<GuideStep> steps = new ArrayList<>(tutorialGuide().steps());
        steps.removeLast();
        fixture.service.replaceEntrySnapshot(List.of(tutorialGuide(steps)));
        assertNull(fixture.service.tutorialStep(accountId));

        steps.add(tutorialStep("defeat_grassboar", GuideConditionType.MOB_DEFEATED, "other_mob"));
        GuideEntry broken = tutorialGuide(steps);
        fixture.service.replaceEntrySnapshot(List.of(broken));
        assertNull(fixture.service.tutorialStep(accountId));
        for (GuideStep step : broken.steps()) {
            fixture.service.recordCondition(player, step.condition().type(), step.condition().targetId());
        }
        assertTrue(fixture.service.isGuideCompleted(accountId, broken));
        assertTrue(completions.isEmpty());
    }

    @Test
    void loginRestoresStarterWeaponSilentlyOnlyWhenHeld() {
        Fixture fixture = new Fixture();
        UUID accountId = UUID.randomUUID();
        AstPlayer player = fixture.player(accountId);
        when(player.getAccount().getMode()).thenReturn(AccountMode.PLAYER);
        GuideEntry tutorial = tutorialGuide();
        Set<GuideStepKey> saved = new HashSet<>();
        for (GuideStep step : tutorial.steps()) {
            if (!"equip_starter_weapon".equals(step.id())) {
                saved.add(new GuideStepKey(tutorial.id(), step.id()));
            }
        }
        when(fixture.progressRepository.findByAccountId(accountId)).thenReturn(saved);
        fixture.service.replaceEntrySnapshot(List.of(tutorial));
        List<AstPlayer> notifications = new ArrayList<>();
        fixture.service.setTutorialCompletedListener(notifications::add);
        fixture.service.setSoundPlayers(notifications::add, notifications::add);

        when(fixture.inventoryService.getItemReferenceInHand(player, EquipmentSlot.HAND))
            .thenReturn(new ItemReference("20a00001", "instance", "item"));
        fixture.service.reconcileLoadedAccount(player, false);
        assertTrue(fixture.asyncTasks.isEmpty());

        fixture.service.loadProgressAsync(accountId);
        fixture.asyncTasks.getFirst().run();
        when(fixture.inventoryService.getItemReferenceInHand(player, EquipmentSlot.HAND)).thenReturn(null);
        fixture.service.reconcileLoadedAccount(player, false);
        assertEquals("equip_starter_weapon", fixture.service.tutorialStep(accountId));
        verify(fixture.inventoryService, never()).queueLocalPlayerSave(accountId);

        fixture.service.releaseProgress(accountId);
        AstPlayer restored = fixture.player(accountId);
        when(restored.getAccount().getMode()).thenReturn(AccountMode.PLAYER);
        when(fixture.inventoryService.getItemReferenceInHand(restored, EquipmentSlot.HAND))
            .thenReturn(new ItemReference("20a00001", "instance", "item"));
        fixture.service.loadProgressAsync(accountId);
        fixture.asyncTasks.getLast().run();
        fixture.service.reconcileLoadedAccount(restored, false);
        fixture.service.reconcileLoadedAccount(restored, false);

        assertTrue(fixture.service.isStepCompleted(accountId, tutorial.id(), "equip_starter_weapon"));
        assertNull(fixture.service.tutorialStep(accountId));
        assertTrue(notifications.isEmpty());
        verifyNoInteractions(fixture.messages);
        verify(fixture.inventoryService).getItemReferenceInHand(restored, EquipmentSlot.HAND);
        verify(fixture.inventoryService).queueLocalPlayerSave(accountId);
    }

    @Test
    void unavailableWelcomeMailWithoutBundleRestoresOnlyInitialStepsAndSavesOnce() {
        WelcomeMailFixture welcome = new WelcomeMailFixture(WelcomeTutorialStatus.UNAVAILABLE);
        GuideEntry other = guide("other", "Other", "Receive", GuideConditionType.MAIL_RECEIVED, 2);
        List<GuideStep> steps = new ArrayList<>(other.steps());
        steps.add(tutorialStep("other_bundle", GuideConditionType.BUNDLE_OPENED, "50a00001"));
        other = new GuideEntry(4, other.id(), "beginner", 2, "Other", null, null, steps);
        welcome.fixture.service.replaceEntrySnapshot(List.of(tutorialGuide(), other));
        List<AstPlayer> notifications = new ArrayList<>();
        welcome.fixture.service.setTutorialCompletedListener(notifications::add);
        welcome.fixture.service.setSoundPlayers(notifications::add, notifications::add);

        try (MockedStatic<AstPlayerCache> cache = welcome.mockCurrentPlayer()) {
            welcome.start();
            welcome.start();
            welcome.finish();
            welcome.finish();
            welcome.start();
            assertTrue(welcome.fixture.asyncTasks.isEmpty());
        }

        assertTrue(welcome.completed("receive_welcome_mail"));
        assertTrue(welcome.completed("open_initial_bundle"));
        assertFalse(welcome.completed("open_menu"));
        assertFalse(welcome.fixture.service.isStepCompleted(welcome.accountId, other.id(), "step"));
        assertFalse(welcome.fixture.service.isStepCompleted(welcome.accountId, other.id(), "other_bundle"));
        PlayerStateSection snapshot = welcome.fixture.service.snapshotPlayerState(welcome.accountId);
        assertNotNull(snapshot);
        JsonObject payload = snapshot.payload().getAsJsonObject();
        assertEquals(1L, payload.get("clientRevision").getAsLong());
        assertEquals(2, payload.getAsJsonArray("completedStepKeys").size());
        verify(welcome.fixture.inventoryService, times(1)).queueLocalPlayerSave(welcome.accountId);
        verifyNoInteractions(welcome.fixture.messages);
        assertTrue(notifications.isEmpty());
    }

    @Test
    void receivedWelcomeMailKeepsBundleStepWhenBundleExistsBeforeOrAfterLookup() {
        for (String location : List.of("bag", "storage", "trash", "beforeLookup")) {
            WelcomeMailFixture welcome = new WelcomeMailFixture(WelcomeTutorialStatus.RECEIVED,
                Set.of(new GuideStepKey(GuideService.INITIAL_GUIDE_ID, "open_menu")));
            try (MockedStatic<AstPlayerCache> cache = welcome.mockCurrentPlayer()) {
                var inventory = welcome.fixture.inventoryService;
                if (location.equals("beforeLookup")) {
                    when(inventory.getNormalItemAmount(welcome.accountId, "50a00001"))
                        // 最初の照会中だけ一時的に消え、次回の照会では再びバッグにある。
                        .thenReturn(1L, 0L, 1L);
                }
                welcome.start();
                welcome.fixture.asyncTasks.removeFirst().run();
                switch (location) {
                    case "bag" -> when(inventory.getNormalItemAmount(welcome.accountId, "50a00001"))
                        .thenReturn(1L);
                    case "storage" -> when(inventory.getStoredNormalItemAmount(welcome.accountId, "50a00001"))
                        .thenReturn(1L);
                    case "trash" -> {
                        UUID playerId = UUID.randomUUID();
                        when(welcome.bukkit.getUniqueId()).thenReturn(playerId);
                        TrashService trash = mock(TrashService.class);
                        when(welcome.fixture.plugin.getTrashService()).thenReturn(trash);
                        when(trash.containsPendingItem(playerId, "50a00001")).thenReturn(true);
                    }
                    case "beforeLookup" -> { }
                    default -> throw new AssertionError(location);
                }
                welcome.fixture.syncTasks.removeFirst().run();
                welcome.start();
                welcome.finish();
            }

            assertTrue(welcome.completed("receive_welcome_mail"));
            assertFalse(welcome.completed("open_initial_bundle"), location);
            assertEquals("open_initial_bundle", welcome.fixture.service.tutorialStep(welcome.accountId));
            verify(welcome.fixture.inventoryService, times(1)).queueLocalPlayerSave(welcome.accountId);
        }
    }

    @Test
    void receivedWelcomeMailWithoutBundleUsesOnlyTheAllGuidesCompletionListener() {
        Set<GuideStepKey> saved = new HashSet<>();
        for (GuideStep step : tutorialGuide().steps()) {
            if (!List.of("receive_welcome_mail", "open_initial_bundle").contains(step.id())) {
                saved.add(new GuideStepKey(GuideService.INITIAL_GUIDE_ID, step.id()));
            }
        }
        WelcomeMailFixture welcome = new WelcomeMailFixture(WelcomeTutorialStatus.RECEIVED, saved);
        List<AstPlayer> tutorialCompletions = new ArrayList<>();
        List<AstPlayer> allCompletions = new ArrayList<>();
        welcome.fixture.service.setTutorialCompletedListener(tutorialCompletions::add);
        welcome.fixture.service.setAllGuidesCompletedListener(allCompletions::add);

        try (MockedStatic<AstPlayerCache> cache = welcome.mockCurrentPlayer()) {
            welcome.start();
            welcome.finish();
        }

        assertTrue(welcome.completed("open_initial_bundle"));
        assertTrue(welcome.fixture.service.areAllGuidesCompleted(welcome.accountId));
        assertEquals(List.of(welcome.player), allCompletions);
        assertTrue(tutorialCompletions.isEmpty());
        verifyNoInteractions(welcome.fixture.messages);
        verify(welcome.fixture.inventoryService, times(1)).queueLocalPlayerSave(welcome.accountId);
    }

    @Test
    void availableAndUnknownWelcomeMailDoNotChangeProgress() {
        for (WelcomeTutorialStatus status : List.of(WelcomeTutorialStatus.AVAILABLE, WelcomeTutorialStatus.UNKNOWN)) {
            WelcomeMailFixture welcome = new WelcomeMailFixture(status);
            try (MockedStatic<AstPlayerCache> cache = welcome.mockCurrentPlayer()) {
                welcome.start();
                welcome.fixture.asyncTasks.removeFirst().run();
                assertTrue(welcome.fixture.syncTasks.isEmpty());
            }
            welcome.assertUnchanged();
        }
    }

    @Test
    void welcomeMailResponseIsIgnoredAfterReloginIntoSameAccount() {
        WelcomeMailFixture welcome = new WelcomeMailFixture(WelcomeTutorialStatus.RECEIVED);
        try (MockedStatic<AstPlayerCache> cache = welcome.mockCurrentPlayer()) {
            welcome.start();
            welcome.fixture.asyncTasks.removeFirst().run();
            welcome.fixture.service.releaseProgress(welcome.accountId);
            AstPlayer current = welcome.fixture.player(welcome.accountId);
            when(current.getBukkit().isOnline()).thenReturn(true);
            cache.when(() -> AstPlayerCache.get(welcome.bukkit)).thenReturn(current);
            welcome.loadProgress();
            welcome.fixture.syncTasks.removeFirst().run();

            welcome.assertUnchanged();
        }
    }

    @Test
    void welcomeMailResponseIsIgnoredIfProgressInventoryOrDefinitionIsInvalidated() {
        for (String invalidated : List.of("progress", "inventory", "inventoryGeneration", "definition")) {
            WelcomeMailFixture welcome = new WelcomeMailFixture(WelcomeTutorialStatus.UNAVAILABLE);
            try (MockedStatic<AstPlayerCache> cache = welcome.mockCurrentPlayer()) {
                welcome.start();
                welcome.fixture.asyncTasks.removeFirst().run();
                switch (invalidated) {
                    case "progress" -> welcome.fixture.service.releaseProgress(welcome.accountId);
                    case "inventory", "inventoryGeneration" -> {
                        var registry = welcome.fixture.inventoryService.getStateRegistry();
                        var replacement = invalidated.equals("inventory") ? null : mock(PlayerInventoryState.class);
                        when(registry.get(welcome.accountId)).thenReturn(replacement);
                    }
                    case "definition" -> welcome.fixture.service.replaceEntrySnapshot(List.of(legacyTutorialGuide()));
                    default -> throw new AssertionError(invalidated);
                }
                welcome.fixture.syncTasks.removeFirst().run();
            }
            welcome.assertUnchanged();
        }
    }

    @Test
    void welcomeMailLookupWaitsForValidDefinitionAndLoadedPlayerState() {
        WelcomeMailFixture welcome = new WelcomeMailFixture(WelcomeTutorialStatus.RECEIVED);
        try (MockedStatic<AstPlayerCache> cache = welcome.mockCurrentPlayer()) {
            welcome.fixture.service.replaceEntrySnapshot(List.of(legacyTutorialGuide()));
            welcome.start();
            welcome.fixture.service.replaceEntrySnapshot(List.of(tutorialGuide()));
            welcome.fixture.service.releaseProgress(welcome.accountId);
            welcome.start();
            welcome.loadProgress();
            var registry = welcome.fixture.inventoryService.getStateRegistry();
            when(registry.get(welcome.accountId)).thenReturn(null);
            welcome.start();
        }
        assertTrue(welcome.fixture.asyncTasks.isEmpty());
        verifyNoInteractions(welcome.repository);
        welcome.assertUnchanged();
    }

    private static final class WelcomeMailFixture {
        private final Fixture fixture = new Fixture(mock(InventoryService.class, RETURNS_DEEP_STUBS));
        private final UUID accountId = UUID.randomUUID();
        private final AstPlayer player = fixture.player(accountId);
        private final Player bukkit = player.getBukkit();
        private final MailRepository repository = mock(MailRepository.class);
        private final MailService mailService;

        private WelcomeMailFixture(WelcomeTutorialStatus status) {
            this(status, Set.of());
        }

        private WelcomeMailFixture(WelcomeTutorialStatus status, Set<GuideStepKey> saved) {
            when(fixture.plugin.isEnabled()).thenReturn(true);
            when(bukkit.isOnline()).thenReturn(true);
            when(repository.findWelcomeTutorialStatus(accountId)).thenReturn(status);
            when(fixture.progressRepository.findByAccountId(accountId)).thenReturn(saved);
            fixture.service.replaceEntrySnapshot(List.of(tutorialGuide()));
            mailService = new MailService(fixture.plugin, repository, fixture.itemService, fixture.inventoryService);
            loadProgress();
        }

        private void loadProgress() {
            CompletableFuture<Boolean> loaded = fixture.service.loadProgressAsync(accountId);
            fixture.asyncTasks.removeFirst().run();
            assertTrue(loaded.join());
        }

        private MockedStatic<AstPlayerCache> mockCurrentPlayer() {
            MockedStatic<AstPlayerCache> cache = mockStatic(AstPlayerCache.class);
            cache.when(() -> AstPlayerCache.get(bukkit)).thenReturn(player);
            return cache;
        }

        private void start() {
            mailService.reconcileTutorialMail(player, fixture.service);
        }

        private void finish() {
            fixture.asyncTasks.removeFirst().run();
            fixture.syncTasks.removeFirst().run();
        }

        private boolean completed(String stepId) {
            return fixture.service.isStepCompleted(accountId, GuideService.INITIAL_GUIDE_ID, stepId);
        }

        private void assertUnchanged() {
            assertFalse(completed("receive_welcome_mail"));
            assertFalse(completed("open_initial_bundle"));
            assertNull(fixture.service.snapshotPlayerState(accountId));
            verify(fixture.inventoryService, never()).queueLocalPlayerSave(accountId);
        }
    }

    private static GuideEntry legacyTutorialGuide() {
        return tutorialGuide(legacyTutorialProgress().stream()
            .map(key -> tutorialStep(key.stepId(), GuideConditionType.GUIDE_OPENED, null))
            .toList());
    }

    private static GuideEntry tutorialGuide(List<GuideStep> steps) {
        return new GuideEntry(3, GuideService.INITIAL_GUIDE_ID, "beginner", 1, "Tutorial", null, null,
            List.copyOf(steps));
    }

    private static Set<GuideStepKey> legacyTutorialProgress() {
        Set<GuideStepKey> progress = new HashSet<>();
        for (String id : List.of(
            "login",
            "open_guide",
            "claim_login_bonus",
            "receive_welcome_mail",
            "open_initial_bundle",
            "buy_starter_weapon",
            "buy_starter_armor_helmet",
            "buy_starter_armor_chest",
            "buy_starter_armor_legs",
            "buy_starter_armor_boots",
            "unlock_free_node",
            "learn_astral_edge",
            "learn_smash",
            "enhance_skill",
            "bind_skill_to_action_ring",
            "cast_astral_edge",
            "defeat_grassboar",
            "buy_hoe",
            "accept_gathering_quest",
            "report_gathering_quest",
            "freely_explore_and_change_class"
        )) {
            progress.add(new GuideStepKey(GuideService.INITIAL_GUIDE_ID, id));
        }
        return progress;
    }

    /** v3はHUD、v2/統合版はチャットで案内し、非表示の間も進捗は記録します。 */
    @Test
    void guideDisplayPolicyChoosesOneAutomaticChannelAndPreservesHiddenProgress() {
        Fixture fixture = new Fixture();
        AstPlayer player = fixture.player(UUID.randomUUID());
        UUID account = player.getAccount().getUuid();
        when(fixture.progressRepository.findByAccountId(account)).thenReturn(Set.of());
        fixture.service.replaceEntrySnapshot(List.of(tutorialGuide()));
        fixture.service.loadProgressAsync(account);
        fixture.asyncTasks.getFirst().run();

        fixture.service.setGuideDisplayPolicy(ignored -> true, ignored -> true);
        fixture.service.recordTutorialMenuOpened(player);
        assertFalse(fixture.service.shouldSendGuideReminder(player));
        verify(fixture.messages, never()).send(eq(player), eq(PlayerMsgId.P_5180), any());
        verify(fixture.messages, never()).send(eq(player), eq(PlayerMsgId.P_5186), any());

        fixture.service.setGuideDisplayPolicy(ignored -> false, ignored -> false);
        fixture.service.recordCondition(player, GuideConditionType.MAIL_RECEIVED, "welcome_mail");
        assertEquals(2, fixture.service.tutorialCompletedStepCount(account));
        assertFalse(fixture.service.shouldSendGuideReminder(player));
        verify(fixture.messages, never()).send(eq(player), eq(PlayerMsgId.P_5180), any());

        fixture.service.setGuideDisplayPolicy(ignored -> true, ignored -> false);
        assertTrue(fixture.service.shouldSendGuideReminder(player));
        fixture.service.sendNextStepHint(player);
        verify(fixture.messages).send(player, PlayerMsgId.P_5186, "open_initial_bundle");
    }

    private static GuideEntry tutorialGuide() {
        return new GuideEntry(3, GuideService.INITIAL_GUIDE_ID, "beginner", 1, "Tutorial", null, null,
            List.of(
                tutorialStep("open_menu", GuideConditionType.MAIN_MENU_OPENED, null),
                tutorialStep("receive_welcome_mail", GuideConditionType.MAIL_RECEIVED, "welcome_mail"),
                tutorialStep("open_initial_bundle", GuideConditionType.BUNDLE_OPENED, "50a00001"),
                tutorialStep("buy_starter_weapon", GuideConditionType.SHOP_PURCHASED, "nox_weapon"),
                tutorialStep("equip_starter_weapon", GuideConditionType.ITEM_EQUIPPED, "20a00001"),
                tutorialStep("defeat_grassboar", GuideConditionType.MOB_DEFEATED, "midgard_grassboar")
            ));
    }

    private static GuideStep tutorialStep(String id, GuideConditionType type, String targetId) {
        return new GuideStep(id, id, List.of(), new GuideCondition(type, targetId), null);
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
        private final InventoryService inventoryService;
        private final ItemService itemService = mock(ItemService.class);
        private final PlayerMessageService messages = mock(PlayerMessageService.class);
        private final List<Runnable> asyncTasks = new ArrayList<>();
        private final List<Runnable> syncTasks = new ArrayList<>();
        private final GuideService service;

        private Fixture() {
            this(mock(InventoryService.class));
        }

        private Fixture(InventoryService inventoryService) {
            this.inventoryService = inventoryService;
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
