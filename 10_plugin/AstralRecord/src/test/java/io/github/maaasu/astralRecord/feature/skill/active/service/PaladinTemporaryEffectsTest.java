package io.github.maaasu.astralRecord.feature.skill.active.service;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.combat.model.DamageResult;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PaladinTemporaryEffectsTest {
    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-パラディン.md
     * 章・見出し: # 13_3-パラディン > ## 2. 防御低下
     * 検証契約: 防御低下は最強だけ適用し、強い効果が失効した時点で残存する弱い効果へ戻る。
     */
    @Test
    void strongestReductionHasItsOwnExpiry() {
        AtomicLong clock = new AtomicLong();
        var service = new TemporarySkillEffectService(clock::get);
        UUID target = UUID.randomUUID();
        service.reduceDefense(target, 15, 100);
        service.reduceDefense(target, 25, 20);
        assertEquals(.75, service.defenseMultiplier(target), 1e-9);
        clock.set(1000);
        assertEquals(.85, service.defenseMultiplier(target), 1e-9);
        clock.set(5000);
        assertEquals(1, service.defenseMultiplier(target), 1e-9);
    }
    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-パラディン.md
     * 章・見出し: # 13_3-パラディン > ## 3. 時限シールドと聖歌
     * 検証契約: 通常シールド不要でHPを吸収し、超過分と固定成分の残量を保つ。
     */
    @Test
    void absorbsHealthAndFixedDamageWithoutNativeShield() {
        var service = new TemporarySkillEffectService();
        UUID target = UUID.randomUUID();
        service.grantWard(target, 30, 100);
        DamageResult result = service.absorbWard(target, new DamageResult(20).withAddedFixedHealthDamage(20));
        assertEquals(10, result.finalDamage(), 1e-9);
        assertEquals(10, result.fixedHealthDamage(), 1e-9);
        assertEquals(30, result.shieldDamage(), 1e-9);
        assertFalse(result.shieldBroken());
        assertEquals(0, service.wardAmount(target));
        assertEquals(12, service.absorbWard(target, new DamageResult(12)).finalDamage());
    }
    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-パラディン.md
     * 章・見出し: # 13_3-パラディン > ## 3. 時限シールドと聖歌
     * 検証契約: 弱い付与で残量や期限を延ばさず、失効時に防護と聖歌を除去する。
     */
    @Test
    void weakReplacementCannotRefreshWardOrSong() {
        AtomicLong clock = new AtomicLong();
        var service = new TemporarySkillEffectService(clock::get);
        UUID id = UUID.randomUUID();
        AstEntity player = mock(AstEntity.class);
        when(player.id()).thenReturn(id);
        service.grantWard(id, 100, 20);
        service.empowerWard(id, 1.2, 200);
        clock.set(500);
        assertFalse(service.grantWard(id, 50, 200));
        assertEquals(1.2, service.outgoingMultiplier(player), 1e-9);
        clock.set(1000);
        service.pruneExpired();
        assertEquals(0, service.wardAmount(id));
        assertEquals(1, service.outgoingMultiplier(player), 1e-9);
    }
    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-パラディン.md
     * 章・見出し: # 13_3-パラディン > ## 3. 時限シールドと聖歌
     * 検証契約: 防護を全消費して再付与しても古い聖歌は復活しない。
     */
    @Test
    void consumptionAndReplacementEndCovenant() {
        var service = new TemporarySkillEffectService();
        UUID id = UUID.randomUUID();
        AstEntity player = mock(AstEntity.class);
        when(player.id()).thenReturn(id);
        service.grantWard(id, 100, 100);
        assertTrue(service.empowerWard(id, 1.2, 100));
        assertEquals(100, service.consumeWard(id));
        service.grantWard(id, 200, 100);
        assertEquals(1, service.outgoingMultiplier(player), 1e-9);
        service.empowerWard(id, 1.2, 100);
        service.grantWard(id, 250, 100);
        assertEquals(1, service.outgoingMultiplier(player), 1e-9);
    }
    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-パラディン.md
     * 章・見出し: # 13_3-パラディン > ## 3. 時限シールドと聖歌
     * 検証契約: 回避・通常シールドだけの被弾では時限シールドを消費せず、lifecycle clearで消去する。
     */
    @Test
    void evasionAndNativeShieldDoNotConsumeWardAndLifecycleClears() {
        var service = new TemporarySkillEffectService();
        UUID id = UUID.randomUUID();
        service.grantWard(id, 100, 100);
        service.absorbWard(id, DamageResult.evaded(50, 100, 50));
        service.absorbWard(id, DamageResult.shield(10, false));
        assertEquals(100, service.wardAmount(id));
        service.reduceDefense(id, 25, 100);
        service.clear(id);
        assertEquals(0, service.wardAmount(id));
        assertEquals(1, service.defenseMultiplier(id));
    }
    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-パラディン.md
     * 章・見出し: # 13_3-パラディン > ## 3. 時限シールドと聖歌
     * 検証契約: 無効な防護量・持続で状態を作らず、聖歌だけを発生させない。
     */
    @Test
    void rejectsInvalidWardInput() {
        var service = new TemporarySkillEffectService();
        UUID id = UUID.randomUUID();
        assertFalse(service.grantWard(id, Double.NaN, 100));
        assertFalse(service.grantWard(id, Double.POSITIVE_INFINITY, 100));
        assertFalse(service.grantWard(id, 100, 0));
        assertFalse(service.empowerWard(id, 1.2, 100));
    }
}
