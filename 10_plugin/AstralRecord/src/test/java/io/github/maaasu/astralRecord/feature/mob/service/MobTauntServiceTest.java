package io.github.maaasu.astralRecord.feature.mob.service;

import io.github.maaasu.astralRecord.feature.mob.model.MobCategory;
import io.github.maaasu.astralRecord.feature.mob.model.MobEquipmentConfig;
import io.github.maaasu.astralRecord.feature.mob.model.MobIdleConfig;
import io.github.maaasu.astralRecord.feature.mob.model.MobInstance;
import io.github.maaasu.astralRecord.feature.mob.model.MobInteractionsConfig;
import io.github.maaasu.astralRecord.feature.mob.model.MobShieldConfig;
import io.github.maaasu.astralRecord.feature.mob.model.MobTargetingConfig;
import io.github.maaasu.astralRecord.feature.mob.model.MobTemplate;
import io.github.maaasu.astralRecord.feature.mob.model.TargetStrategy;
import org.bukkit.Location;
import org.bukkit.entity.EntityType;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MobTauntServiceTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/12-mob/3-メソッド仕様/12_3-サービス.md
     * 章・見出し: # 12_3-サービス > ## 3. MobAiService メソッド仕様 > ### 一時挑発と追跡対象解決
     * 検証契約: 同一Mobへの後発挑発が先発を上書きし、期限切れで固定対象を解除する。
     */
    @Test
    void latestTauntWinsAndExpiryClearsForcedTarget() {
        AtomicLong tick = new AtomicLong(100L);
        MobTauntService service = new MobTauntService(tick::get);
        Fixture fixture = fixture();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        assertTrue(service.apply(fixture.mob, first, 20L));
        assertEquals(first, service.activeTaunter(fixture.mob));

        tick.set(105L);
        assertTrue(service.apply(fixture.mob, second, 21L));
        assertEquals(second, service.activeTaunter(fixture.mob));
        assertEquals(second, fixture.mob.targetId());

        tick.set(126L);
        assertNull(service.activeTaunter(fixture.mob));
        assertNull(fixture.mob.targetId());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/12-mob/3-メソッド仕様/12_3-サービス.md
     * 章・見出し: # 12_3-サービス > ## 3. MobAiService メソッド仕様 > ### 一時挑発と追跡対象解決
     * 検証契約: 挑発者単位のlifecycle解除では別プレイヤーの挑発を残し、対象者の固定だけを解除する。
     */
    @Test
    void clearByTaunterOnlyRemovesMatchingTaunts() {
        MobTauntService service = new MobTauntService(() -> 0L);
        Fixture firstMob = fixture();
        Fixture secondMob = fixture();
        UUID cleared = UUID.randomUUID();
        UUID retained = UUID.randomUUID();
        service.apply(firstMob.mob, cleared, 100L);
        service.apply(secondMob.mob, retained, 100L);

        service.clearByTaunter(cleared);

        assertNull(service.activeTaunter(firstMob.mob));
        assertNull(firstMob.mob.targetId());
        assertEquals(retained, service.activeTaunter(secondMob.mob));
        assertEquals(retained, secondMob.mob.targetId());
    }

    private static Fixture fixture() {
        MobInstance mob = new MobInstance(UUID.randomUUID(), template(), new Location(null, 0.0D, 64.0D, 0.0D));
        return new Fixture(mob);
    }

    private static MobTemplate template() {
        return new MobTemplate(
                1,
                "enemy:taunt_test",
                MobCategory.ENEMY,
                "Taunt Test",
                null,
                1,
                EntityType.ARMOR_STAND,
                true,
                null,
                List.of(),
                List.of(),
                null,
                MobEquipmentConfig.EMPTY,
                List.of(),
                MobShieldConfig.EMPTY,
                MobIdleConfig.defaults(),
                false,
                MobInteractionsConfig.EMPTY,
                new MobTargetingConfig(TargetStrategy.HIGHEST_THREAT, 12.0D, 20.0D, 30.0D, false),
                null,
                null
        );
    }

    private record Fixture(MobInstance mob) {
    }
}
