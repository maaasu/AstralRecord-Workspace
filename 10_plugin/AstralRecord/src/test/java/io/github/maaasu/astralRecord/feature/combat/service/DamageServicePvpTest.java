package io.github.maaasu.astralRecord.feature.combat.service;

import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.combat.model.AttackType;
import io.github.maaasu.astralRecord.feature.combat.model.DamageResult;
import io.github.maaasu.astralRecord.feature.mob.service.MobCombatService;
import io.github.maaasu.astralRecord.feature.mob.service.MobKnockbackService;
import io.github.maaasu.astralRecord.feature.mob.service.MobService;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.playersetting.service.PlayerSettingService;
import io.github.maaasu.astralRecord.feature.status.model.StatusType;
import io.github.maaasu.astralRecord.feature.status.service.StatusService;
import io.github.maaasu.astralRecord.shared.display.DisplayTextService;
import io.github.maaasu.astralRecord.shared.effect.ParticleDisplayService;
import io.github.maaasu.astralRecord.support.DesignTestFixtures;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;

class DamageServicePvpTest extends MockBukkitTestBase {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/14-combat/3-メソッド仕様/14_3-サービス.md
     * 章・見出し: # 14_3-サービス > ## 1. damage 計算
     * 検証契約: PvP フラグはセッション生成時に無効で、未許可のプレイヤー間ダメージは適用しない。
     */
    @Test
    void pvpIsDisabledByDefault() {
        AstPlayer attacker = player();
        AstPlayer victim = player();
        DamageService service = damageService();

        DamageResult result = service.applyDamage(
                AstEntity.player(attacker),
                AstEntity.player(victim),
                10.0D,
                AttackType.MELEE
        );

        assertFalse(attacker.isPvpEnabled());
        assertFalse(victim.isPvpEnabled());
        assertEquals(0.0D, result.finalDamage(), 0.0001D);
        assertEquals(100.0D, victim.getStatusSnapshot().getCurrentHp(), 0.0001D);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/14-combat/3-メソッド仕様/14_3-サービス.md
     * 章・見出し: # 14_3-サービス > ## 1. damage 計算
     * 検証契約: プレイヤー間ダメージは攻撃者・被弾者の両方が PvP 有効の場合だけ適用する。
     */
    @Test
    void pvpDamageRequiresBothFlags() {
        AstPlayer attacker = player();
        AstPlayer victim = player();
        DamageService service = damageService();

        attacker.setPvpEnabled(true);
        DamageResult denied = service.applyDamage(
                AstEntity.player(attacker),
                AstEntity.player(victim),
                10.0D,
                AttackType.MELEE
        );

        victim.setPvpEnabled(true);
        DamageResult allowed = service.applyDamage(
                AstEntity.player(attacker),
                AstEntity.player(victim),
                10.0D,
                AttackType.MELEE
        );

        assertEquals(0.0D, denied.finalDamage(), 0.0001D);
        assertTrue(allowed.finalDamage() > 0.0D);
        assertTrue(victim.getStatusSnapshot().getCurrentHp() < 100.0D);
    }

    private AstPlayer player() {
        PlayerMock bukkitPlayer = spy(server().addPlayer());
        doNothing().when(bukkitPlayer).playHurtAnimation(ArgumentMatchers.anyFloat());
        AstPlayer player = DesignTestFixtures.astPlayer(bukkitPlayer, AccountMode.PLAYER);
        player.setStatusSnapshot(DesignTestFixtures.statusSnapshot(Map.of(
                StatusType.MAX_HEALTH, 100.0D,
                StatusType.ATTACK, 10.0D,
                StatusType.ACCURACY, 100.0D,
                StatusType.EVASION, 0.0D,
                StatusType.FINAL_DAMAGE_MULTIPLIER, 100.0D
        ), 100.0D, 0.0D, 0.0D));
        return player;
    }

    private DamageService damageService() {
        return new DamageService(
                new StatusService(),
                mock(MobService.class),
                mock(MobCombatService.class),
                mock(MobKnockbackService.class),
                mock(DisplayTextService.class),
                mock(PlayerSettingService.class),
                mock(ParticleDisplayService.class)
        );
    }
}
