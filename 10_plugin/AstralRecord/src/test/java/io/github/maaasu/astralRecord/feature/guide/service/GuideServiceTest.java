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
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.feature.playerclass.PlayerClassService;
import io.github.maaasu.astralRecord.feature.world.service.WorldService;
import org.bukkit.Server;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.Test;

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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
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

    private static final class Fixture {
        private final AstralRecord plugin = mock(AstralRecord.class);
        private final Server server = mock(Server.class);
        private final BukkitScheduler scheduler = mock(BukkitScheduler.class);
        private final GuideProgressRepository progressRepository = mock(GuideProgressRepository.class);
        private final InventoryService inventoryService = mock(InventoryService.class);
        private final List<Runnable> asyncTasks = new ArrayList<>();
        private final GuideService service;

        private Fixture() {
            when(plugin.getServer()).thenReturn(server);
            when(server.getScheduler()).thenReturn(scheduler);
            doAnswer(invocation -> {
                asyncTasks.add(invocation.getArgument(1));
                return mock(BukkitTask.class);
            }).when(scheduler).runTaskAsynchronously(eq(plugin), any(Runnable.class));
            service = new GuideService(
                plugin,
                mock(GuideRepository.class),
                progressRepository,
                mock(ItemService.class),
                mock(PlayerClassService.class),
                mock(WorldService.class),
                mock(PlayerMessageService.class),
                inventoryService
            );
        }
    }
}
