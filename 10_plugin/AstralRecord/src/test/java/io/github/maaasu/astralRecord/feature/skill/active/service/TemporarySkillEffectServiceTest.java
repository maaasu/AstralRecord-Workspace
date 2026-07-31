package io.github.maaasu.astralRecord.feature.skill.active.service;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TemporarySkillEffectServiceTest {

    private static final double DELTA = 0.0001D;

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-サービス.md
     * 章・見出し: # 13_3-サービス > ## 9. active skill 共通支援
     * 検証契約: 異なるtemporary modifierを合成し同じeffect keyの再適用は置換する。
     */
    @Test
    void composesDifferentEffectsAndReplacesTheSameEffect() {
        TemporarySkillEffectService service = new TemporarySkillEffectService();
        AstEntity entity = entity(UUID.randomUUID());

        service.apply(entity.id(), "guard", 200L, 0.5D, 1.2D, 0.8D);
        service.apply(entity.id(), "guard", 200L, 0.8D, 1.1D, 0.5D);
        service.apply(entity.id(), "stance", 200L, 0.5D, 1.5D, 0.5D);

        assertEquals(0.4D, service.incomingMultiplier(entity), DELTA);
        assertEquals(1.65D, service.outgoingMultiplier(entity), DELTA);
        assertEquals(0.25D, service.knockbackMultiplier(entity), DELTA);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-サービス.md
     * 章・見出し: # 13_3-サービス > ## 9. active skill 共通支援
     * 検証契約: 期限切れtemporary effectを読取時に除去して倍率へ含めない。
     */
    @Test
    void removesExpiredEffectsWhenTheyAreRead() {
        AtomicLong currentTimeMillis = new AtomicLong(1_000L);
        TemporarySkillEffectService service = new TemporarySkillEffectService(currentTimeMillis::get);
        AstEntity entity = entity(UUID.randomUUID());
        service.apply(entity.id(), "short_guard", 1L, 0.25D, 2.0D, 0.5D);

        currentTimeMillis.addAndGet(49L);

        assertEquals(0.25D, service.incomingMultiplier(entity), DELTA);
        assertEquals(2.0D, service.outgoingMultiplier(entity), DELTA);
        assertEquals(0.5D, service.knockbackMultiplier(entity), DELTA);

        currentTimeMillis.incrementAndGet();

        assertEquals(1.0D, service.incomingMultiplier(entity), DELTA);
        assertEquals(1.0D, service.outgoingMultiplier(entity), DELTA);
        assertEquals(1.0D, service.knockbackMultiplier(entity), DELTA);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-サービス.md
     * 章・見出し: # 13_3-サービス > ## 9. active skill 共通支援
     * 検証契約: 1 entityのeffectだけをclearでき、全clearも他stateを残さず行える。
     */
    @Test
    void clearsOneEntityWithoutAffectingOthersAndCanClearAll() {
        TemporarySkillEffectService service = new TemporarySkillEffectService();
        AstEntity first = entity(UUID.randomUUID());
        AstEntity second = entity(UUID.randomUUID());
        service.apply(first.id(), "first_guard", 200L, 0.5D, 1.0D, 0.5D);
        service.apply(second.id(), "second_guard", 200L, 0.75D, 1.0D, 0.75D);

        service.clear(first.id());

        assertEquals(1.0D, service.incomingMultiplier(first), DELTA);
        assertEquals(0.75D, service.incomingMultiplier(second), DELTA);

        service.clearAll();

        assertEquals(1.0D, service.incomingMultiplier(second), DELTA);
    }

    private static AstEntity entity(UUID id) {
        AstEntity entity = mock(AstEntity.class);
        when(entity.id()).thenReturn(id);
        return entity;
    }
}
