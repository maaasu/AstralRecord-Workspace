package io.github.maaasu.astralRecord.feature.mob.service;

import io.github.maaasu.astralRecord.feature.mob.model.IdleBehavior;
import io.github.maaasu.astralRecord.feature.mob.model.MobBaseStat;
import io.github.maaasu.astralRecord.feature.mob.model.MobCategory;
import io.github.maaasu.astralRecord.feature.mob.model.MobEquipmentConfig;
import io.github.maaasu.astralRecord.feature.mob.model.MobIdleConfig;
import io.github.maaasu.astralRecord.feature.mob.model.MobInstance;
import io.github.maaasu.astralRecord.feature.mob.model.MobInteractionsConfig;
import io.github.maaasu.astralRecord.feature.mob.model.MobShieldConfig;
import io.github.maaasu.astralRecord.feature.mob.model.MobTemplate;
import io.github.maaasu.astralRecord.feature.mob.model.MobVariantConfig;
import io.github.maaasu.astralRecord.feature.skill.service.SkillService;
import org.bukkit.Location;
import org.bukkit.entity.EntityType;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MobAiServiceTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/12-mob/3-メソッド仕様/12_3-サービス.md
     * 章・見出し: # 12_3-サービス > ## 3. MobAiService メソッド仕様 > ### AI tick 本体
     * 検証契約: NPCのWANDERが30秒周期で配置アンカーへの経路を設定し、テレポートを実行しない。
     */
    @Test
    void wanderingNpcPeriodicallyRoutesToPlacementAnchorWithoutTeleporting() throws ReflectiveOperationException {
        MobService mobService = mock(MobService.class);
        MobInstance instance = new MobInstance(
                UUID.randomUUID(),
                wanderingNpcTemplate(),
                new Location(null, 0.0D, 64.0D, 0.0D)
        );
        instance.currentLocation(new Location(null, 20.0D, 64.0D, 20.0D));
        when(mobService.getInstances()).thenReturn(List.of(instance));
        when(mobService.syncLocation(instance)).thenReturn(true);

        MobAiService aiService = new MobAiService(
                mobService,
                mock(MobCombatService.class),
                mock(SkillService.class)
        );
        long routeTick = Math.floorMod(-(long) instance.instanceId().hashCode(), 20L * 30L);
        if (routeTick == 0L) {
            routeTick = 20L * 30L;
        }
        setInternalTick(aiService, routeTick - 1L);

        aiService.tick();

        verify(mobService, never()).resetPosition(any(MobInstance.class), any(Location.class));
        verify(mobService).moveToward(
                same(instance),
                argThat(anchor -> anchor.getX() == 0.0D
                        && anchor.getY() == 64.0D
                        && anchor.getZ() == 0.0D),
                eq(0.75D),
                eq(routeTick)
        );
        assertEquals(0.0D, instance.wanderTarget().getX());
        assertEquals(0.0D, instance.wanderTarget().getZ());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/12-mob/3-メソッド仕様/12_3-サービス.md
     * 章・見出し: # 12_3-サービス > ## 3. MobAiService メソッド仕様 > ### AI tick 本体
     * 検証契約: WANDER目的地への水平移動が10秒間ないNPCを緊急テレポートで配置アンカーへ戻す。
     */
    @Test
    void stalledWanderingNpcIsResetAfterNoHorizontalProgress() throws ReflectiveOperationException {
        MobService mobService = mock(MobService.class);
        MobInstance instance = new MobInstance(
                UUID.randomUUID(),
                wanderingNpcTemplate(),
                new Location(null, 0.0D, 64.0D, 0.0D)
        );
        instance.wanderTarget(new Location(null, 8.0D, 64.0D, 0.0D));
        when(mobService.getInstances()).thenReturn(List.of(instance));
        when(mobService.syncLocation(instance)).thenReturn(true);

        MobAiService aiService = new MobAiService(
                mobService,
                mock(MobCombatService.class),
                mock(SkillService.class)
        );
        long firstDecisionTick = Math.floorMod(-(long) instance.instanceId().hashCode(), 10L);
        if (firstDecisionTick == 0L) {
            firstDecisionTick = 10L;
        }

        for (long decisionTick = firstDecisionTick;
             decisionTick <= firstDecisionTick + 20L * 10L;
             decisionTick += 10L) {
            setInternalTick(aiService, decisionTick - 1L);
            aiService.tick();
        }

        verify(mobService, never()).resetPosition(any(MobInstance.class), any(Location.class));

        setInternalTick(aiService, firstDecisionTick + 20L * 10L + 9L);
        aiService.tick();

        verify(mobService).resetPosition(
                same(instance),
                argThat(anchor -> anchor.getX() == 0.0D
                        && anchor.getY() == 64.0D
                        && anchor.getZ() == 0.0D)
        );
        assertNull(instance.wanderTarget());
        assertEquals(firstDecisionTick + 20L * 10L + 10L, instance.lastWanderTeleportTick());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/12-mob/3-メソッド仕様/12_3-サービス.md
     * 章・見出し: # 12_3-サービス > ## 3. MobAiService メソッド仕様 > ### AI tick 本体
     * 検証契約: 緊急テレポートから3分以内に再び10秒間停止しても、アンカーへの経路設定だけを行う。
     */
    @Test
    void stalledWanderingNpcUsesAnchorRouteDuringTeleportCooldown() throws ReflectiveOperationException {
        MobService mobService = mock(MobService.class);
        MobInstance instance = new MobInstance(
                UUID.randomUUID(),
                wanderingNpcTemplate(),
                new Location(null, 0.0D, 64.0D, 0.0D)
        );
        instance.currentLocation(new Location(null, 20.0D, 64.0D, 20.0D));
        instance.wanderTarget(new Location(null, 8.0D, 64.0D, 0.0D));
        instance.navBlockedSinceTick(0L);
        instance.navLastObservedLocation(instance.currentLocation());
        instance.lastWanderTeleportTick(0L);
        when(mobService.getInstances()).thenReturn(List.of(instance));
        when(mobService.syncLocation(instance)).thenReturn(true);

        MobAiService aiService = new MobAiService(
                mobService,
                mock(MobCombatService.class),
                mock(SkillService.class)
        );
        long decisionTick = 20L * 10L;
        while (Math.floorMod(instance.instanceId().hashCode() + decisionTick, 10L) != 0L) {
            decisionTick++;
        }
        setInternalTick(aiService, decisionTick - 1L);

        aiService.tick();

        verify(mobService, never()).resetPosition(any(MobInstance.class), any(Location.class));
        verify(mobService).moveToward(
                same(instance),
                argThat(anchor -> anchor.getX() == 0.0D
                        && anchor.getY() == 64.0D
                        && anchor.getZ() == 0.0D),
                eq(0.75D),
                eq(decisionTick)
        );
        assertEquals(0.0D, instance.wanderTarget().getX());
        assertEquals(0.0D, instance.wanderTarget().getZ());
    }

    private static MobTemplate wanderingNpcTemplate() {
        return new MobTemplate(
                1,
                "npc:test_wanderer",
                MobCategory.NPC,
                "Test Wanderer",
                null,
                1,
                EntityType.VILLAGER,
                false,
                null,
                List.of(),
                List.of(),
                null,
                MobVariantConfig.DEFAULT,
                MobEquipmentConfig.EMPTY,
                List.of(new MobBaseStat("MAX_HEALTH", 100.0D)),
                MobShieldConfig.EMPTY,
                new MobIdleConfig(IdleBehavior.WANDER, 8.0D, 0.75D),
                true,
                MobInteractionsConfig.EMPTY,
                null,
                null,
                null
        );
    }

    private static void setInternalTick(MobAiService aiService, long tick) throws ReflectiveOperationException {
        Field field = MobAiService.class.getDeclaredField("internalTick");
        field.setAccessible(true);
        field.setLong(aiService, tick);
    }
}
