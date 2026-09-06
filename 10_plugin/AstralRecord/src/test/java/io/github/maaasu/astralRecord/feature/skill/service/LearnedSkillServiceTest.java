package io.github.maaasu.astralRecord.feature.skill.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.mutation.model.PlayerStateSection;
import io.github.maaasu.astralRecord.feature.skill.model.LearnedSkillInstance;
import io.github.maaasu.astralRecord.feature.skill.model.LearnedSkillSigil;
import io.github.maaasu.astralRecord.feature.skill.repository.LearnedSkillRepository;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LearnedSkillServiceTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-サービス.md
     * 章・見出し: # 13_3-サービス > ## 習得済みスキル個体
     * 検証契約: 素材不要の習得はAPIを呼ばずローカルキャッシュへ個体を追加し、player-state保存を予約する。
     */
    @Test
    void learnWithoutPaymentConfirmsLocallyAndQueuesSave() {
        UUID accountId = UUID.randomUUID();
        InventoryService inventory = committingInventory(accountId);
        LearnedSkillRepository repository = mock(LearnedSkillRepository.class);
        AtomicReference<LearnedSkillInstance> succeeded = new AtomicReference<>();
        LearnedSkillService service = service(accountId, inventory, repository, List.of());

        boolean accepted = service.learnFromManagerAsync(
            accountId, "adventurer_smash", accountId, List.of(),
            succeeded::set, failure -> { throw new AssertionError(failure); }
        );

        assertTrue(accepted);
        assertNotNull(succeeded.get());
        assertEquals("adventurer_smash", succeeded.get().getSkillId());
        assertEquals(1, succeeded.get().getLevel());
        assertSame(succeeded.get(), service.findInstance(accountId, succeeded.get().getLearnedSkillId()));
        JsonObject snapshotSkill = onlySkill(service.snapshotPlayerState(accountId));
        assertTrue(snapshotSkill.get("expectedVersion").isJsonNull());
        assertEquals(1, snapshotSkill.get("targetVersion").getAsInt());
        verify(inventory).queueLocalPlayerSave(accountId);
        verify(repository, never()).learn(any(), any(), any(), any());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-サービス.md
     * 章・見出し: # 13_3-サービス > ## 習得済みスキル個体
     * 検証契約: 同じ素材entryを複数口使う習得は数量を集約し、予約・消費・個体追加を同じaccount state lock内で確定する。
     */
    @Test
    void learnAggregatesRepeatedPaymentEntriesInsideOneLocalMutation() {
        UUID accountId = UUID.randomUUID();
        UUID materialEntryId = UUID.randomUUID();
        InventoryService inventory = committingInventory(accountId);
        LearnedSkillService service = service(accountId, inventory, List.of());

        assertTrue(service.learnFromManagerAsync(
            accountId, "adventurer_smash", accountId,
            List.of(materialEntryId, materialEntryId), ignored -> { },
            failure -> { throw new AssertionError(failure); }
        ));

        ArgumentCaptor<UUID> operationId = ArgumentCaptor.forClass(UUID.class);
        verify(inventory).reserveLocalMutationPayment(
            eq(accountId), operationId.capture(), eq(Map.of(materialEntryId, 2L))
        );
        verify(inventory).commitLocalOrbOperationPayment(
            eq(accountId), eq(operationId.getValue()), any(Runnable.class)
        );
        assertEquals(1, service.getLearnedSkills(accountId).size());
        verify(inventory).queueLocalPlayerSave(accountId);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-サービス.md
     * 章・見出し: # 13_3-サービス > ## 習得済みスキル個体
     * 検証契約: 習得素材を予約できない場合は個体を追加せず、仮予約を解放して失敗を通知する。
     */
    @Test
    void learnRejectsInsufficientPaymentAndReleasesReservation() {
        UUID accountId = UUID.randomUUID();
        UUID materialEntryId = UUID.randomUUID();
        InventoryService inventory = mutationInventory(accountId);
        when(inventory.reserveLocalMutationPayment(eq(accountId), any(), any())).thenReturn(false);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        LearnedSkillService service = service(accountId, inventory, List.of());

        boolean accepted = service.learnFromManagerAsync(
            accountId, "adventurer_smash", accountId, List.of(materialEntryId),
            ignored -> { throw new AssertionError("insufficient payment must not learn"); }, failure::set
        );

        assertFalse(accepted);
        assertNotNull(failure.get());
        assertTrue(service.getLearnedSkills(accountId).isEmpty());
        ArgumentCaptor<UUID> operationId = ArgumentCaptor.forClass(UUID.class);
        verify(inventory).reserveLocalMutationPayment(eq(accountId), operationId.capture(), any());
        verify(inventory).releaseOrbOperationPayment(accountId, operationId.getValue());
        verify(inventory, never()).commitLocalOrbOperationPayment(any(), any(), any());
        verify(inventory, never()).queueLocalPlayerSave(accountId);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-サービス.md
     * 章・見出し: # 13_3-サービス > ## 習得済みスキル個体
     * 検証契約: レベルアップは指定素材の消費と同じaccount state lock内でlevel・versionを進め、保存を予約する。
     */
    @Test
    void levelUpConfirmsLevelAndVersionWithPayment() {
        UUID accountId = UUID.randomUUID();
        UUID materialEntryId = UUID.randomUUID();
        LearnedSkillInstance learned = learned(accountId, 2, 4, List.of());
        InventoryService inventory = committingInventory(accountId);
        AtomicReference<LearnedSkillInstance> succeeded = new AtomicReference<>();
        LearnedSkillService service = service(accountId, inventory, List.of(learned));

        assertTrue(service.levelUpFromManagerWithPaymentsAsync(
            accountId, learned.getLearnedSkillId(), accountId, Map.of(materialEntryId, 3L),
            succeeded::set, failure -> { throw new AssertionError(failure); }, () -> { }
        ));

        assertEquals(3, succeeded.get().getLevel());
        assertEquals(5, succeeded.get().getVersion());
        assertSame(succeeded.get(), service.findInstance(accountId, learned.getLearnedSkillId()));
        verify(inventory).reserveLocalMutationPayment(eq(accountId), any(), eq(Map.of(materialEntryId, 3L)));
        verify(inventory).queueLocalPlayerSave(accountId);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-サービス.md
     * 章・見出し: # 13_3-サービス > ## 習得済みスキル個体
     * 検証契約: 支払いcommitが不成立ならレベルとversionを変更せず、操作予約を解放する。
     */
    @Test
    void levelUpKeepsSkillUnchangedWhenPaymentCommitFails() {
        UUID accountId = UUID.randomUUID();
        UUID materialEntryId = UUID.randomUUID();
        LearnedSkillInstance learned = learned(accountId, 2, 4, List.of());
        InventoryService inventory = mutationInventory(accountId);
        when(inventory.reserveLocalMutationPayment(eq(accountId), any(), any())).thenReturn(true);
        when(inventory.commitLocalOrbOperationPayment(eq(accountId), any(), any())).thenReturn(false);
        LearnedSkillService service = service(accountId, inventory, List.of(learned));

        assertFalse(service.levelUpFromManagerWithPaymentsAsync(
            accountId, learned.getLearnedSkillId(), accountId, Map.of(materialEntryId, 1L),
            ignored -> { throw new AssertionError("failed commit must not level up"); }, ignored -> { }, () -> { }
        ));

        LearnedSkillInstance unchanged = service.findInstance(accountId, learned.getLearnedSkillId());
        assertNotNull(unchanged);
        assertEquals(2, unchanged.getLevel());
        assertEquals(4, unchanged.getVersion());
        verify(inventory).releaseOrbOperationPayment(eq(accountId), any());
        verify(inventory, never()).queueLocalPlayerSave(accountId);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-サービス.md
     * 章・見出し: # 13_3-サービス > ## 習得済みスキル個体
     * 検証契約: 支払い予約後に対象個体が見つからないレベルアップは予約を解放し、保存を予約しない。
     */
    @Test
    void levelUpReleasesReservationWhenTargetIsMissing() {
        UUID accountId = UUID.randomUUID();
        InventoryService inventory = committingInventory(accountId);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        LearnedSkillService service = service(accountId, inventory, List.of());

        assertFalse(service.levelUpFromManagerWithPaymentsAsync(
            accountId, UUID.randomUUID(), accountId, Map.of(UUID.randomUUID(), 1L),
            ignored -> { }, failure::set, () -> { }
        ));

        assertNotNull(failure.get());
        verify(inventory).releaseOrbOperationPayment(eq(accountId), any());
        verify(inventory, never()).queueLocalPlayerSave(accountId);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-サービス.md
     * 章・見出し: # 13_3-サービス > ## 習得済みスキル個体
     * 検証契約: シジル装着はオーブとシジルを各1個消費し、同じaccount state lock内で個体へ装着してversionを進める。
     */
    @Test
    void attachSigilConsumesBothPaymentsAndUpdatesSkill() {
        UUID accountId = UUID.randomUUID();
        UUID orbEntryId = UUID.randomUUID();
        UUID sigilEntryId = UUID.randomUUID();
        LearnedSkillInstance learned = learned(accountId, 1, 4, List.of());
        LearnedSkillSigil sigil = sigil("sigil_power", "power", 0);
        InventoryService inventory = committingInventory(accountId);
        LearnedSkillService service = service(accountId, inventory, List.of(learned));

        assertTrue(service.attachSigilLocally(
            accountId, learned.getLearnedSkillId(), orbEntryId, sigil, sigilEntryId,
            ignored -> { }, failure -> { throw new AssertionError(failure); }
        ));

        LearnedSkillInstance updated = service.findInstance(accountId, learned.getLearnedSkillId());
        assertNotNull(updated);
        assertEquals(List.of(sigil), updated.getSigils());
        assertEquals(5, updated.getVersion());
        verify(inventory).reserveLocalMutationPayment(
            eq(accountId), any(), eq(Map.of(orbEntryId, 1L, sigilEntryId, 1L))
        );
        verify(inventory).queueLocalPlayerSave(accountId);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-サービス.md
     * 章・見出し: # 13_3-サービス > ## 習得済みスキル個体
     * 検証契約: 同じequipGroupIdのシジルを装着しようとした場合は個体を変更せず、支払い予約を解放する。
     */
    @Test
    void attachSigilRejectsDuplicateEquipGroupAndReleasesReservation() {
        UUID accountId = UUID.randomUUID();
        LearnedSkillSigil existing = sigil("sigil_power_small", "power", 0);
        LearnedSkillInstance learned = learned(accountId, 1, 4, List.of(existing));
        InventoryService inventory = committingInventory(accountId);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        LearnedSkillService service = service(accountId, inventory, List.of(learned));

        assertFalse(service.attachSigilLocally(
            accountId, learned.getLearnedSkillId(), UUID.randomUUID(),
            sigil("sigil_power_large", "POWER", 1), UUID.randomUUID(),
            ignored -> { }, failure::set
        ));

        assertNotNull(failure.get());
        assertEquals(List.of(existing), service.findInstance(accountId, learned.getLearnedSkillId()).getSigils());
        verify(inventory).releaseOrbOperationPayment(eq(accountId), any());
        verify(inventory, never()).queueLocalPlayerSave(accountId);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-サービス.md
     * 章・見出し: # 13_3-サービス > ## 習得済みスキル個体
     * 検証契約: シジル脱着はオーブ消費、シジル返却、個体更新を同じaccount state lock内で確定する。
     */
    @Test
    void detachSigilReturnsItemAndUpdatesSkillInsidePaymentCommit() {
        UUID accountId = UUID.randomUUID();
        LearnedSkillSigil attached = sigil("sigil_power", "power", 0);
        LearnedSkillInstance learned = learned(accountId, 1, 4, List.of(attached));
        InventoryService inventory = committingInventory(accountId);
        AtomicBoolean returned = new AtomicBoolean();
        LearnedSkillService service = service(accountId, inventory, List.of(learned));

        assertTrue(service.detachSigilLocally(
            accountId, learned.getLearnedSkillId(), UUID.randomUUID(),
            attached.getLearnedSkillSigilId(), () -> returned.set(true),
            ignored -> { }, failure -> { throw new AssertionError(failure); }
        ));

        assertTrue(returned.get());
        LearnedSkillInstance updated = service.findInstance(accountId, learned.getLearnedSkillId());
        assertNotNull(updated);
        assertTrue(updated.getSigils().isEmpty());
        assertEquals(5, updated.getVersion());
        verify(inventory).queueLocalPlayerSave(accountId);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-サービス.md
     * 章・見出し: # 13_3-サービス > ## 習得済みスキル個体
     * 検証契約: 指定装着行が存在しない脱着はシジルを返却せず、支払い予約を解放する。
     */
    @Test
    void detachSigilRejectsMissingAttachmentWithoutReturningItem() {
        UUID accountId = UUID.randomUUID();
        LearnedSkillInstance learned = learned(accountId, 1, 4, List.of());
        InventoryService inventory = committingInventory(accountId);
        AtomicInteger returns = new AtomicInteger();
        LearnedSkillService service = service(accountId, inventory, List.of(learned));

        assertFalse(service.detachSigilLocally(
            accountId, learned.getLearnedSkillId(), UUID.randomUUID(), UUID.randomUUID(),
            returns::incrementAndGet, ignored -> { }, ignored -> { }
        ));

        assertEquals(0, returns.get());
        verify(inventory).releaseOrbOperationPayment(eq(accountId), any());
        verify(inventory, never()).queueLocalPlayerSave(accountId);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-サービス.md
     * 章・見出し: # 13_3-サービス > ## 習得済みスキル個体
     * 検証契約: 忘却はAPIを呼ばずキャッシュから個体を即時削除し、削除expectedVersionを含む保存を予約する。
     */
    @Test
    void forgetRemovesSkillLocallyAndQueuesVersionedDeletion() {
        UUID accountId = UUID.randomUUID();
        LearnedSkillInstance learned = learned(accountId, 1, 4, List.of());
        InventoryService inventory = committingInventory(accountId);
        LearnedSkillRepository repository = mock(LearnedSkillRepository.class);
        AtomicReference<LearnedSkillInstance> removed = new AtomicReference<>();
        LearnedSkillService service = service(accountId, inventory, repository, List.of(learned));

        assertTrue(service.forgetAsync(
            accountId, learned.getLearnedSkillId(), accountId,
            removed::set, failure -> { throw new AssertionError(failure); }
        ));

        assertSame(learned, removed.get());
        assertNull(service.findInstance(accountId, learned.getLearnedSkillId()));
        JsonObject deletion = onlyDeletedSkill(service.snapshotPlayerState(accountId));
        assertEquals(learned.getLearnedSkillId().toString(), deletion.get("learnedSkillId").getAsString());
        assertEquals(4, deletion.get("expectedVersion").getAsInt());
        verify(inventory).queueLocalPlayerSave(accountId);
        verify(repository, never()).forget(any(), any(), any(), any());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-サービス.md
     * 章・見出し: # 13_3-サービス > ## 習得済みスキル個体
     * 検証契約: 未所持個体の忘却はキャッシュを変更せず、player-state保存を予約しない。
     */
    @Test
    void forgetRejectsMissingSkillWithoutQueuingSave() {
        UUID accountId = UUID.randomUUID();
        InventoryService inventory = committingInventory(accountId);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        LearnedSkillService service = service(accountId, inventory, List.of());

        assertFalse(service.forgetAsync(
            accountId, UUID.randomUUID(), accountId, ignored -> { }, failure::set
        ));

        assertNotNull(failure.get());
        assertNull(service.snapshotPlayerState(accountId));
        verify(inventory, never()).queueLocalPlayerSave(accountId);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-サービス.md
     * 章・見出し: # 13_3-サービス > ## 習得済みスキル個体
     * 検証契約: learnedSkills snapshotは全現存個体のversionとシジル識別情報を送り、同一revisionの完全ACKだけでdirtyを解除する。
     */
    @Test
    void matchingCompleteAcknowledgementClearsDirtySnapshot() {
        UUID accountId = UUID.randomUUID();
        LearnedSkillSigil sigil = sigil("sigil_power", "power", 0);
        LearnedSkillInstance learned = learned(accountId, 1, 4, List.of(sigil));
        LearnedSkillService service = service(accountId, committingInventory(accountId), List.of(learned));
        levelUpWithoutPayment(service, accountId, learned.getLearnedSkillId());

        PlayerStateSection snapshot = service.snapshotPlayerState(accountId);
        JsonObject skill = onlySkill(snapshot);
        assertEquals(4, skill.get("expectedVersion").getAsInt());
        assertEquals(5, skill.get("targetVersion").getAsInt());
        JsonObject serializedSigil = skill.getAsJsonArray("sigils").get(0).getAsJsonObject();
        assertEquals(sigil.getLearnedSkillSigilId().toString(), serializedSigil.get("learnedSkillSigilId").getAsString());
        assertEquals("power", serializedSigil.get("equipGroupId").getAsString());
        assertEquals(0, serializedSigil.get("slotIndex").getAsInt());

        snapshot.acknowledge().accept(acknowledgement(snapshot, Map.of(learned.getLearnedSkillId(), 5), List.of()));

        assertNull(service.snapshotPlayerState(accountId));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-サービス.md
     * 章・見出し: # 13_3-サービス > ## 習得済みスキル個体
     * 検証契約: 連続更新中に到着した旧revisionの完全ACKは新しいdirtyを解除せず、返却versionだけを後続expectedVersionへ反映する。
     */
    @Test
    void staleAcknowledgementDuringBurstKeepsLatestRevisionDirty() {
        UUID accountId = UUID.randomUUID();
        LearnedSkillInstance learned = learned(accountId, 1, 4, List.of());
        LearnedSkillService service = service(accountId, committingInventory(accountId), List.of(learned));

        levelUpWithoutPayment(service, accountId, learned.getLearnedSkillId());
        PlayerStateSection first = service.snapshotPlayerState(accountId);
        levelUpWithoutPayment(service, accountId, learned.getLearnedSkillId());
        first.acknowledge().accept(acknowledgement(first, Map.of(learned.getLearnedSkillId(), 9), List.of()));

        PlayerStateSection latest = service.snapshotPlayerState(accountId);
        assertNotNull(latest);
        assertEquals(2L, latest.payload().getAsJsonObject().get("clientRevision").getAsLong());
        assertEquals(9, onlySkill(latest).get("expectedVersion").getAsInt());
        assertEquals(10, onlySkill(latest).get("targetVersion").getAsInt());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-サービス.md
     * 章・見出し: # 13_3-サービス > ## 習得済みスキル個体
     * 検証契約: ACKが全現存個体を含まない場合は同一revisionでもdirtyを解除せず、version基準も更新しない。
     */
    @Test
    void incompleteAcknowledgementKeepsSnapshotDirtyAndVersionsUnchanged() {
        UUID accountId = UUID.randomUUID();
        LearnedSkillInstance firstSkill = learned(accountId, 1, 4, List.of());
        LearnedSkillInstance secondSkill = learned(accountId, 1, 7, List.of());
        LearnedSkillService service = service(
            accountId, committingInventory(accountId), List.of(firstSkill, secondSkill)
        );
        levelUpWithoutPayment(service, accountId, firstSkill.getLearnedSkillId());
        PlayerStateSection snapshot = service.snapshotPlayerState(accountId);

        snapshot.acknowledge().accept(acknowledgement(
            snapshot, Map.of(firstSkill.getLearnedSkillId(), 20), List.of()
        ));

        PlayerStateSection stillDirty = service.snapshotPlayerState(accountId);
        assertNotNull(stillDirty);
        assertEquals(4, skillById(stillDirty, firstSkill.getLearnedSkillId()).get("expectedVersion").getAsInt());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-サービス.md
     * 章・見出し: # 13_3-サービス > ## 習得済みスキル個体
     * 検証契約: 送信後に忘却した個体は旧ACKのversionを削除expectedVersionへ引き継ぎ、完全な削除ACKでdirtyを解除する。
     */
    @Test
    void oldAcknowledgementAdvancesDeletionVersionBeforeDeleteAck() {
        UUID accountId = UUID.randomUUID();
        LearnedSkillInstance learned = learned(accountId, 1, 4, List.of());
        LearnedSkillService service = service(accountId, committingInventory(accountId), List.of(learned));
        levelUpWithoutPayment(service, accountId, learned.getLearnedSkillId());
        PlayerStateSection updateSnapshot = service.snapshotPlayerState(accountId);
        service.forgetAsync(accountId, learned.getLearnedSkillId(), accountId, ignored -> { }, ignored -> { });

        updateSnapshot.acknowledge().accept(acknowledgement(
            updateSnapshot, Map.of(learned.getLearnedSkillId(), 9), List.of()
        ));

        PlayerStateSection deleteSnapshot = service.snapshotPlayerState(accountId);
        assertEquals(9, onlyDeletedSkill(deleteSnapshot).get("expectedVersion").getAsInt());
        deleteSnapshot.acknowledge().accept(acknowledgement(
            deleteSnapshot, Map.of(), List.of(learned.getLearnedSkillId())
        ));
        assertNull(service.snapshotPlayerState(accountId));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-サービス.md
     * 章・見出し: # 13_3-サービス > ## 習得済みスキル個体
     * 検証契約: 習得とシジル装着直後の退出でもsnapshot捕捉前の変更を保持し、完全ACK後に退出済みcacheを破棄する。
     */
    @Test
    void quitBeforeSnapshotRetainsLearnAndSigilUntilCompleteAck() {
        UUID accountId = UUID.randomUUID();
        LearnedSkillService service = service(accountId, committingInventory(accountId), List.of());
        assertTrue(service.learnFromManagerAsync(accountId, "adventurer_smash", accountId,
            List.of(UUID.randomUUID()), ignored -> { }, failure -> { throw new AssertionError(failure); }));
        UUID skillId = service.getLearnedSkills(accountId).getFirst().getLearnedSkillId();
        LearnedSkillSigil attached = sigil("sigil_power", "power", 0);
        assertTrue(service.attachSigilLocally(accountId, skillId, UUID.randomUUID(), attached,
            UUID.randomUUID(), ignored -> { }, failure -> { throw new AssertionError(failure); }));

        service.invalidate(accountId);
        PlayerStateSection snapshot = service.snapshotPlayerState(accountId);

        assertEquals(1, onlySkill(snapshot).getAsJsonArray("sigils").size());
        snapshot.acknowledge().accept(new JsonObject());
        assertTrue(service.hasLoadedSkills(accountId));
        snapshot.acknowledge().accept(acknowledgement(snapshot, Map.of(skillId, 2), List.of()));
        assertFalse(service.hasLoadedSkills(accountId));
        assertNull(service.snapshotPlayerState(accountId));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-サービス.md
     * 章・見出し: # 13_3-サービス > ## 習得済みスキル個体
     * 検証契約: 退出後の再参加は未保存個体を優先し、ロードとapplyの間にACKが届いても旧値へ戻さず次回versionへ反映する。
     */
    @Test
    void rejoinRetainsLocalSkillAcrossAckBetweenLoadAndApply() {
        UUID accountId = UUID.randomUUID();
        LearnedSkillInstance initial = learned(accountId, 1, 4, List.of());
        LearnedSkillRepository repository = mock(LearnedSkillRepository.class);
        LearnedSkillService service = service(accountId, committingInventory(accountId), repository, List.of(initial));
        levelUpWithoutPayment(service, accountId, initial.getLearnedSkillId());
        PlayerStateSection sent = service.snapshotPlayerState(accountId);
        service.invalidate(accountId);

        List<LearnedSkillInstance> loaded = service.loadInitialSkills(accountId);
        assertEquals(2, loaded.getFirst().getLevel());
        sent.acknowledge().accept(acknowledgement(sent, Map.of(initial.getLearnedSkillId(), 9), List.of()));
        service.applyInitialSkills(accountId, List.of(initial));
        levelUpWithoutPayment(service, accountId, initial.getLearnedSkillId());

        JsonObject next = onlySkill(service.snapshotPlayerState(accountId));
        assertEquals(3, next.get("level").getAsInt());
        assertEquals(9, next.get("expectedVersion").getAsInt());
        assertEquals(10, next.get("targetVersion").getAsInt());
        verify(repository, never()).findByAccountId(accountId);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-サービス.md
     * 章・見出し: # 13_3-サービス > ## 習得済みスキル個体
     * 検証契約: 再参加のapplyへ渡された旧API値は未保存忘却を復活させず、削除ACK後も再参加中のcacheは維持する。
     */
    @Test
    void rejoinDoesNotResurrectPendingDeletion() {
        UUID accountId = UUID.randomUUID();
        LearnedSkillInstance initial = learned(accountId, 1, 4, List.of());
        LearnedSkillService service = service(accountId, committingInventory(accountId), List.of(initial));
        assertTrue(service.forgetAsync(accountId, initial.getLearnedSkillId(), accountId,
            ignored -> { }, failure -> { throw new AssertionError(failure); }));
        service.invalidate(accountId);
        service.applyInitialSkills(accountId, List.of(initial));

        PlayerStateSection snapshot = service.snapshotPlayerState(accountId);
        assertNull(service.findInstance(accountId, initial.getLearnedSkillId()));
        assertEquals(4, onlyDeletedSkill(snapshot).get("expectedVersion").getAsInt());
        snapshot.acknowledge().accept(acknowledgement(snapshot, Map.of(), List.of(initial.getLearnedSkillId())));
        assertTrue(service.hasLoadedSkills(accountId));
        assertNull(service.snapshotPlayerState(accountId));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-サービス.md
     * 章・見出し: # 13_3-サービス > ## 習得済みスキル個体
     * 検証契約: 旧ACKの重複到着は後続変更のversionを二重加算せず、再ロード後の保存世代にも影響しない。
     */
    @Test
    void duplicateAckCannotChangePendingVersionOrNewSession() {
        UUID accountId = UUID.randomUUID();
        LearnedSkillInstance initial = learned(accountId, 1, 4, List.of());
        LearnedSkillService service = service(accountId, committingInventory(accountId), List.of(initial));
        levelUpWithoutPayment(service, accountId, initial.getLearnedSkillId());
        PlayerStateSection first = service.snapshotPlayerState(accountId);
        levelUpWithoutPayment(service, accountId, initial.getLearnedSkillId());
        JsonObject ack = acknowledgement(first, Map.of(initial.getLearnedSkillId(), 9), List.of());
        first.acknowledge().accept(ack);
        first.acknowledge().accept(ack);
        assertEquals(10, onlySkill(service.snapshotPlayerState(accountId)).get("targetVersion").getAsInt());
        PlayerStateSection latest = service.snapshotPlayerState(accountId);
        latest.acknowledge().accept(acknowledgement(latest, Map.of(initial.getLearnedSkillId(), 10), List.of()));
        service.invalidate(accountId);
        service.applyInitialSkills(accountId, List.of(initial));
        levelUpWithoutPayment(service, accountId, initial.getLearnedSkillId());
        first.acknowledge().accept(ack);
        assertEquals(4, onlySkill(service.snapshotPlayerState(accountId)).get("expectedVersion").getAsInt());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-サービス.md
     * 章・見出し: # 13_3-サービス > ## 習得済みスキル個体
     * 検証契約: 未ACKの新規learnを忘却して退出した場合も、先行learn ACKのversionを後続削除へ引き継ぎ個体を復活させない。
     */
    @Test
    void forgetOfUnacknowledgedNewLearnRetainsDeletionAfterLearnAck() {
        UUID accountId = UUID.randomUUID();
        LearnedSkillService service = service(accountId, committingInventory(accountId), List.of());
        assertTrue(service.learnFromManagerAsync(accountId, "adventurer_smash", accountId,
            List.of(UUID.randomUUID()), ignored -> { }, failure -> { throw new AssertionError(failure); }));
        UUID skillId = service.getLearnedSkills(accountId).getFirst().getLearnedSkillId();
        PlayerStateSection learnSnapshot = service.snapshotPlayerState(accountId);
        assertTrue(onlySkill(learnSnapshot).get("expectedVersion").isJsonNull());
        assertTrue(service.forgetAsync(accountId, skillId, accountId,
            ignored -> { }, failure -> { throw new AssertionError(failure); }));
        assertTrue(service.snapshotPlayerState(accountId).payload().getAsJsonObject().getAsJsonArray("deletedSkills").isEmpty());
        service.invalidate(accountId);

        learnSnapshot.acknowledge().accept(acknowledgement(learnSnapshot, Map.of(skillId, 9), List.of()));

        PlayerStateSection deletion = service.snapshotPlayerState(accountId);
        assertNotNull(deletion);
        assertEquals(0, deletion.payload().getAsJsonObject().getAsJsonArray("skills").size());
        assertEquals(skillId.toString(), onlyDeletedSkill(deletion).get("learnedSkillId").getAsString());
        assertEquals(9, onlyDeletedSkill(deletion).get("expectedVersion").getAsInt());
        deletion.acknowledge().accept(acknowledgement(deletion, Map.of(), List.of(skillId)));
        assertFalse(service.hasLoadedSkills(accountId));
        assertNull(service.snapshotPlayerState(accountId));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-サービス.md
     * 章・見出し: # 13_3-サービス > ## 習得済みスキル個体
     * 検証契約: captureだけで未送信の新規習得をforgetしても存在しない個体の削除予定を作らない。
     */
    @Test
    void forgettingCapturedButNeverSentLearnDoesNotCreatePhantomDeletion() {
        UUID accountId = UUID.randomUUID();
        LearnedSkillService service = service(accountId, committingInventory(accountId), List.of());
        assertTrue(service.learnFromManagerAsync(accountId, "adventurer_smash", accountId,
            List.of(UUID.randomUUID()), ignored -> { }, failure -> { throw new AssertionError(failure); }));
        UUID skillId = service.getLearnedSkills(accountId).getFirst().getLearnedSkillId();
        assertNotNull(service.snapshotPlayerState(accountId));
        assertNotNull(service.snapshotPlayerState(accountId));
        assertTrue(service.forgetAsync(accountId, skillId, accountId,
            ignored -> { }, failure -> { throw new AssertionError(failure); }));
        PlayerStateSection initialSend = service.snapshotPlayerState(accountId);
        assertTrue(initialSend.payload().getAsJsonObject().getAsJsonArray("skills").isEmpty());
        assertTrue(initialSend.payload().getAsJsonObject().getAsJsonArray("deletedSkills").isEmpty());
        initialSend.acknowledge().accept(acknowledgement(initialSend, Map.of(), List.of()));
        assertNull(service.snapshotPlayerState(accountId));
    }

    private static LearnedSkillService service(
        UUID accountId,
        InventoryService inventory,
        List<LearnedSkillInstance> skills
    ) {
        return service(accountId, inventory, mock(LearnedSkillRepository.class), skills);
    }

    private static LearnedSkillService service(
        UUID accountId,
        InventoryService inventory,
        LearnedSkillRepository repository,
        List<LearnedSkillInstance> skills
    ) {
        LearnedSkillService service = new LearnedSkillService(mock(Plugin.class), repository, inventory);
        service.applyInitialSkills(accountId, skills);
        return service;
    }

    private static LearnedSkillInstance learned(
        UUID accountId,
        int level,
        int version,
        List<LearnedSkillSigil> sigils
    ) {
        return new LearnedSkillInstance(
            UUID.randomUUID(), accountId, "adventurer_smash", level, sigils, version, null, null
        );
    }

    private static LearnedSkillSigil sigil(String sigilId, String equipGroupId, int slotIndex) {
        return new LearnedSkillSigil(UUID.randomUUID(), sigilId, equipGroupId, slotIndex);
    }

    private static InventoryService mutationInventory(UUID accountId) {
        InventoryService inventory = mock(InventoryService.class);
        doAnswer(invocation -> invocation.<java.util.function.Supplier<?>>getArgument(1).get())
            .when(inventory).executeLocalPlayerMutation(eq(accountId), any());
        return inventory;
    }

    private static InventoryService committingInventory(UUID accountId) {
        InventoryService inventory = mutationInventory(accountId);
        when(inventory.reserveLocalMutationPayment(eq(accountId), any(), any())).thenReturn(true);
        doAnswer(invocation -> {
            invocation.<Runnable>getArgument(2).run();
            return true;
        }).when(inventory).commitLocalOrbOperationPayment(eq(accountId), any(), any());
        return inventory;
    }

    private static void levelUpWithoutPayment(
        LearnedSkillService service,
        UUID accountId,
        UUID learnedSkillId
    ) {
        assertTrue(service.levelUpFromManagerWithPaymentsAsync(
            accountId, learnedSkillId, accountId, Map.of(), ignored -> { },
            failure -> { throw new AssertionError(failure); }, () -> { }
        ));
    }

    private static JsonObject onlySkill(PlayerStateSection snapshot) {
        assertNotNull(snapshot);
        JsonArray skills = snapshot.payload().getAsJsonObject().getAsJsonArray("skills");
        assertEquals(1, skills.size());
        return skills.get(0).getAsJsonObject();
    }

    private static JsonObject skillById(PlayerStateSection snapshot, UUID learnedSkillId) {
        for (var element : snapshot.payload().getAsJsonObject().getAsJsonArray("skills")) {
            JsonObject skill = element.getAsJsonObject();
            if (learnedSkillId.toString().equals(skill.get("learnedSkillId").getAsString())) {
                return skill;
            }
        }
        throw new AssertionError("skill is missing from snapshot: " + learnedSkillId);
    }

    private static JsonObject onlyDeletedSkill(PlayerStateSection snapshot) {
        assertNotNull(snapshot);
        JsonArray deletedSkills = snapshot.payload().getAsJsonObject().getAsJsonArray("deletedSkills");
        assertEquals(1, deletedSkills.size());
        return deletedSkills.get(0).getAsJsonObject();
    }

    private static JsonObject acknowledgement(
        PlayerStateSection snapshot,
        Map<UUID, Integer> versions,
        List<UUID> deletedIds
    ) {
        JsonObject acknowledgement = new JsonObject();
        acknowledgement.addProperty(
            "clientRevision", snapshot.payload().getAsJsonObject().get("clientRevision").getAsLong()
        );
        JsonArray entries = new JsonArray();
        versions.forEach((learnedSkillId, version) -> {
            JsonObject entry = new JsonObject();
            entry.addProperty("learnedSkillId", learnedSkillId.toString());
            entry.addProperty("version", version);
            entry.add("updatedAt", JsonNull.INSTANCE);
            entries.add(entry);
        });
        acknowledgement.add("entries", entries);
        JsonArray deleted = new JsonArray();
        deletedIds.forEach(id -> deleted.add(id.toString()));
        acknowledgement.add("deletedIds", deleted);
        return acknowledgement;
    }
}
