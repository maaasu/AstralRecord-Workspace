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

    private static BuffType buff(String id, String stackGroup, StatusType status, double value) {
        return new BuffType(
            id,
            "BUFF",
            id,
            1_200,
            false,
            stackGroup,
            List.of(new BuffModifier(status, BuffModifierType.FLAT, value))
        );
    }
}
