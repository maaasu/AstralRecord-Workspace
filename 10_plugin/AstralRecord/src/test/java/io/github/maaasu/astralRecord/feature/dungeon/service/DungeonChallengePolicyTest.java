package io.github.maaasu.astralRecord.feature.dungeon.service;

import io.github.maaasu.astralRecord.feature.dungeon.model.DungeonRewardEntry;
import io.github.maaasu.astralRecord.feature.mob.model.MobDropResultItem;
import io.github.maaasu.astralRecord.shared.challenge.ChallengeStartCountdown;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DungeonChallengePolicyTest {
    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/32-dungeon/32_3-処理契約.md
     * 章・見出し: # 32_3-処理契約 > ## 7. 終了と回収
     * 検証契約: ENDING開始前の入場callbackを失効し、終了世代の帰還callbackだけを索引解放前まで受理する。
     */
    @Test
    void rejectsStaleEntryTransferUntilEndingCleanupFinishes() {
        UUID sessionId = UUID.randomUUID();

        assertTrue(DungeonService.isTransferCallbackCurrent(
                sessionId, sessionId, 1L, 1L, false, false));
        assertFalse(DungeonService.isTransferCallbackCurrent(
                sessionId, sessionId, 1L, 2L, true, false));
        assertTrue(DungeonService.isTransferCallbackCurrent(
                sessionId, sessionId, 2L, 2L, true, true));
        assertFalse(DungeonService.isTransferCallbackCurrent(
                sessionId, null, 2L, 2L, true, true));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/32-dungeon/32_3-処理契約.md
     * 章・見出し: # 32_3-処理契約 > ## 7. 終了と回収
     * 検証契約: 確定離脱時は対象Dungeon sessionが所有する死亡状態だけを一度回収する。
     */
    @Test
    void recoversOnlyDeathStateOwnedByTheDepartedSession() {
        UUID ownerSessionId = UUID.randomUUID();
        UUID otherSessionId = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();
        Map<UUID, UUID> ownership = new HashMap<>(Map.of(playerId, ownerSessionId));
        AtomicInteger recoveries = new AtomicInteger();

        assertFalse(DungeonService.recoverOwnedDungeonDeath(
                ownership, otherSessionId, playerId, ignored -> recoveries.incrementAndGet()));
        assertTrue(DungeonService.recoverOwnedDungeonDeath(
                ownership, ownerSessionId, playerId, ignored -> recoveries.incrementAndGet()));
        assertEquals(1, recoveries.get());
        assertTrue(ownership.isEmpty());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/32-dungeon/32_3-処理契約.md
     * 章・見出し: # 32_3-処理契約 > ## 4. 開始・生成・転送
     * 検証契約: 開始カウントダウンの前半・後半とも、同じ稼働sessionの現在参加者は死亡復帰callbackでSTARTへ戻せる。
     */
    @Test
    void currentParticipantCanRecoverThroughoutStartCountdown() {
        UUID sessionId = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();
        LinkedHashSet<UUID> participants = new LinkedHashSet<>(List.of(playerId));
        ChallengeStartCountdown countdown = new ChallengeStartCountdown();

        ChallengeStartCountdown.Tick firstHalf = countdown.advance();
        assertEquals(10, firstHalf.remainingSeconds());
        assertTrue(DungeonService.canRunDungeonRecoveryCallback(
                sessionId, sessionId, false, participants, playerId));

        for (int tick = 0; tick < 5; tick++) countdown.advance();
        ChallengeStartCountdown.Tick secondHalf = countdown.advance();
        assertEquals(4, secondHalf.remainingSeconds());
        assertTrue(DungeonService.canRunDungeonRecoveryCallback(
                sessionId, sessionId, false, participants, playerId));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/32-dungeon/32_3-処理契約.md
     * 章・見出し: # 32_3-処理契約 > ## 7. 終了と回収
     * 検証契約: logoutまたはgate離脱で現在参加者から外れた後の死亡復帰callbackはDungeonへ再入場させない。
     */
    @Test
    void removedDungeonDeathCallbackCannotReenterInstance() {
        UUID sessionId = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();
        LinkedHashSet<UUID> participants = new LinkedHashSet<>(List.of(playerId));

        assertTrue(DungeonService.canRunDungeonRecoveryCallback(
                sessionId, sessionId, false, participants, playerId));
        participants.remove(playerId);
        assertFalse(DungeonService.canRunDungeonRecoveryCallback(
                sessionId, sessionId, false, participants, playerId));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/32-dungeon/32_3-処理契約.md
     * 章・見出し: # 32_3-処理契約 > ## 7. 終了と回収
     * 検証契約: ENDING移行後またはsession索引解放後の死亡復帰callbackはDungeonへ再入場させない。
     */
    @Test
    void endingDungeonDeathCallbackCannotReenterInstance() {
        UUID sessionId = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();
        LinkedHashSet<UUID> participants = new LinkedHashSet<>(List.of(playerId));

        assertFalse(DungeonService.canRunDungeonRecoveryCallback(
                sessionId, sessionId, true, participants, playerId));
        assertFalse(DungeonService.canRunDungeonRecoveryCallback(
                sessionId, null, false, participants, playerId));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/32-dungeon/32_3-処理契約.md
     * 章・見出し: # 32_3-処理契約 > ## 5. 離脱・再参加・中止
     * 検証契約: 受付時参加者かつ帰還gate自主離脱者だけを再参加可能とし、late joinとlogout失効後を拒否する。
     */
    @Test
    void onlyOriginalGateReturnParticipantCanRejoin() {
        UUID original = UUID.randomUUID();
        UUID lateMember = UUID.randomUUID();
        LinkedHashSet<UUID> originals = new LinkedHashSet<>(List.of(original));
        LinkedHashSet<UUID> eligible = new LinkedHashSet<>(List.of(original));

        assertTrue(DungeonService.canRejoinParticipant(originals, eligible, original));
        assertFalse(DungeonService.canRejoinParticipant(originals, eligible, lateMember));

        eligible.remove(original);
        assertFalse(DungeonService.canRejoinParticipant(originals, eligible, original));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/32-dungeon/32_3-処理契約.md
     * 章・見出し: # 32_3-処理契約 > ## 5. 離脱・再参加・中止
     * 検証契約: 待機ハブから離脱した参加者だけをDungeon Sidebarの対象外とし、再到着して未到着記録が消えた後は再び対象にする。
     */
    @Test
    void excludesWaitingHubDepartedParticipantFromDungeonSidebarUntilRejoin() {
        UUID remainingPlayer = UUID.randomUUID();
        UUID departedPlayer = UUID.randomUUID();
        List<UUID> participants = List.of(remainingPlayer, departedPlayer);

        assertTrue(DungeonService.isSidebarParticipant(participants, List.of(), remainingPlayer));
        assertFalse(DungeonService.isSidebarParticipant(participants, List.of(departedPlayer), departedPlayer));
        assertTrue(DungeonService.isSidebarParticipant(participants, List.of(), departedPlayer));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/32-dungeon/32_3-処理契約.md
     * 章・見出し: # 32_3-処理契約 > ## 6. クリア報酬と30秒回収
     * 検証契約: 当選報酬は設定確率の昇順で個別claim IDを付け、表示後に一覧が変化してもclaim IDで同じ報酬を特定する。
     */
    @Test
    void clearRewardsAreRareFirstAndClaimedByStableId() {
        List<DungeonRewardEntry> rewards = new ArrayList<>(DungeonService.createRewardEntries(List.of(
                new MobDropResultItem("common", 3, 100.0D),
                new MobDropResultItem("rare", 1, 2.5D),
                new MobDropResultItem("uncommon", 2, 25.0D)
        )));

        assertEquals(List.of("rare", "uncommon", "common"),
                rewards.stream().map(DungeonRewardEntry::itemId).toList());
        UUID uncommonClaimId = rewards.get(1).claimId();
        rewards.removeFirst();
        assertEquals(0, DungeonService.findRewardIndex(rewards, uncommonClaimId));
        assertEquals(-1, DungeonService.findRewardIndex(rewards, UUID.randomUUID()));
    }
}
