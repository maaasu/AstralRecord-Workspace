package io.github.maaasu.astralRecord.feature.buff.service;

import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.feature.buff.model.ActiveBuff;
import io.github.maaasu.astralRecord.feature.buff.model.BuffModifier;
import io.github.maaasu.astralRecord.feature.buff.model.BuffModifierType;
import io.github.maaasu.astralRecord.feature.buff.model.BuffType;
import io.github.maaasu.astralRecord.feature.buff.repository.BuffRepository;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.status.model.StatusType;
import io.github.maaasu.astralRecord.support.DesignTestFixtures;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BuffServiceStackGroupTest extends MockBukkitTestBase {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/05-buff/05_2-ユースケース.md
     * 章・見出し: # 05_2-ユースケース > ## 1. プレイヤーへバフを付与する
     * 検証契約: 同じstackGroupの後発バフは先発バフを置換し、異なるstackGroupのバフは共存する。
     */
    @Test
    void laterBuffInTheSameStackGroupReplacesEarlierBuff() {
        PlayerMock bukkitPlayer = server().addPlayer();
        AstPlayer player = DesignTestFixtures.astPlayer(bukkitPlayer, AccountMode.PLAYER);
        BuffRepository repository = mock(BuffRepository.class);
        when(repository.findById("attack_small")).thenReturn(
            buff("attack_small", "attack_power", StatusType.ATTACK, 10.0D)
        );
        when(repository.findById("attack_large")).thenReturn(
            buff("attack_large", "attack_power", StatusType.ATTACK, 20.0D)
        );
        when(repository.findById("defense_small")).thenReturn(
            buff("defense_small", "defense_power", StatusType.DEFENSE, 5.0D)
        );

        BuffService service = new BuffService(repository);
        assertTrue(service.apply(player, "attack_small"));
        assertTrue(service.apply(player, "attack_large"));
        assertTrue(service.apply(player, "defense_small"));

        List<ActiveBuff> activeBuffs = service.getActiveBuffs(player);
        assertEquals(2, activeBuffs.size());
        assertEquals("attack_large", activeBuffs.get(0).getType().getId());
        assertEquals("defense_small", activeBuffs.get(1).getType().getId());
        assertEquals(20.0D, service.getTotalBonus(player, StatusType.ATTACK, 0.0D), 0.0001D);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/05-buff/3-メソッド仕様/05_3-サービス.md
     * 章・見出し: # 05_3-サービス > ## 1. BuffService メソッド仕様 > ### 挑戦開始時バフ解除
     * 検証契約: 挑戦時はフラグ付きバフだけを解除し、通常バフと管理者一時バフは保持する。
     */
    @Test
    void challengeResetKeepsUnmarkedAndAdministratorBuffs() {
        AstPlayer player = DesignTestFixtures.astPlayer(server().addPlayer(), AccountMode.PLAYER);
        BuffService service = new BuffService(mock(BuffRepository.class));
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        ActiveBuff marked = new ActiveBuff(new BuffType("marked", "BUFF", "marked", 600,
            false, true, null, List.of()), now, now.plusMinutes(1));
        ActiveBuff retained = new ActiveBuff(buff("retained", null, StatusType.ATTACK, 2), now, now.plusMinutes(1));
        player.getActiveBuffs().add(marked);
        player.getActiveBuffs().add(retained);
        ActiveBuff administrator = service.applyTemporaryFlat(player, StatusType.DEFENSE, 3, 60);
        service.applyTemporaryFlat(player, "field_fixture", "field", StatusType.ATTACK, 5, 60);
        assertTrue(service.removeChallengeResettableBuffs(player));
        assertEquals(List.of(retained, administrator), service.getActiveBuffs(player));
        org.junit.jupiter.api.Assertions.assertFalse(service.removeChallengeResettableBuffs(player));
        player.getActiveBuffs().add(new ActiveBuff(marked.getType(), now.minusMinutes(2), now.minusMinutes(1)));
        assertTrue(service.removeChallengeResettableBuffs(player));
        assertEquals(List.of(retained, administrator), service.getActiveBuffs(player));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/05-buff/3-メソッド仕様/05_3-サービス.md
     * 章・見出し: # 05_3-サービス > ## 1. BuffService メソッド仕様 > ### バフ持続時間消費
     * 検証契約: 同値でも別個体は消費せず、現在の個体だけを指定tick分短縮して古い個体を再消費しない。
     */
    @Test
    void durationConsumptionRequiresExactCurrentInstance() {
        AstPlayer player = DesignTestFixtures.astPlayer(server().addPlayer(), AccountMode.PLAYER);
        BuffService service = new BuffService(mock(BuffRepository.class));
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        ActiveBuff current = new ActiveBuff(buff("fixture", null, StatusType.ATTACK, 2), now, now.plusMinutes(1));
        ActiveBuff equalCopy = new ActiveBuff(current.getType(), current.getStartedAt(), current.getExpiresAt());
        player.getActiveBuffs().add(current);
        org.junit.jupiter.api.Assertions.assertFalse(service.consumeDuration(player, equalCopy, 20));
        assertTrue(service.consumeDuration(player, current, 20));
        assertEquals(now.plusSeconds(59), service.getActiveBuffs(player).getFirst().getExpiresAt());
        org.junit.jupiter.api.Assertions.assertFalse(service.consumeDuration(player, current, 20));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/05-buff/3-メソッド仕様/05_3-サービス.md
     * 章・見出し: # 05_3-サービス > ## 1. BuffService メソッド仕様 > ### バフ持続時間消費
     * 検証契約: 残り時間未満でも一回の消費は成立してバフを解除し、消費済み個体の再消費は拒否する。
     */
    @Test
    void durationConsumptionExhaustsShortRemainingLifetime() {
        AstPlayer player = DesignTestFixtures.astPlayer(server().addPlayer(), AccountMode.PLAYER);
        BuffService service = new BuffService(mock(BuffRepository.class));
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        ActiveBuff current = new ActiveBuff(buff("fixture", null, StatusType.ATTACK, 2), now, now.plusSeconds(5));
        player.getActiveBuffs().add(current);
        assertTrue(service.consumeDuration(player, current, 600));
        assertTrue(service.getActiveBuffs(player).isEmpty());
        org.junit.jupiter.api.Assertions.assertFalse(service.consumeDuration(player, current, 600));
    }

    private static BuffType buff(String id, String stackGroup, StatusType status, double value) {
        return new BuffType(
            id,
            "BUFF",
            id,
            1_200,
            false,
            false,
            stackGroup,
            List.of(new BuffModifier(status, BuffModifierType.FLAT, value))
        );
    }
}
