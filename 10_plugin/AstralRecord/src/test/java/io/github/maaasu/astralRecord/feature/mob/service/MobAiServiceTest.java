package io.github.maaasu.astralRecord.feature.mob.service;

import io.github.maaasu.astralRecord.feature.mob.model.CombatStyle;
import io.github.maaasu.astralRecord.feature.mob.model.IdleBehavior;
import io.github.maaasu.astralRecord.feature.mob.model.MobBaseStat;
import io.github.maaasu.astralRecord.feature.mob.model.MobCategory;
import io.github.maaasu.astralRecord.feature.mob.model.MobCombatConfig;
import io.github.maaasu.astralRecord.feature.mob.model.MobIdleConfig;
import io.github.maaasu.astralRecord.feature.mob.model.MobInstance;
import io.github.maaasu.astralRecord.feature.mob.model.MobShieldConfig;
import io.github.maaasu.astralRecord.feature.mob.model.MobTargetingConfig;
import io.github.maaasu.astralRecord.feature.mob.model.MobTemplate;
import io.github.maaasu.astralRecord.feature.mob.model.TargetStrategy;
import io.github.maaasu.astralRecord.feature.skill.model.MobSkillCaster;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastResult;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastTrigger;
import io.github.maaasu.astralRecord.feature.skill.service.SkillService;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import org.bukkit.Location;
import org.bukkit.entity.EntityType;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MobAiServiceTest extends MockBukkitTestBase {

    @Test
    void stationaryNpcLooksAtNearestPlayer() throws Exception {
        var world = server().addSimpleWorld("npc_world");
        PlayerMock nearPlayer = server().addPlayer();
        nearPlayer.teleport(new Location(world, 2.0D, 64.0D, 0.0D));
        PlayerMock farPlayer = server().addPlayer();
        farPlayer.teleport(new Location(world, 6.0D, 64.0D, 0.0D));

        MobService mobService = mock(MobService.class);
        MobAiService service = new MobAiService(mobService, mock(MobCombatService.class), mock(SkillService.class));
        MobInstance instance = new MobInstance(
                UUID.randomUUID(),
                new MobTemplate(
                        1,
                        "npc_shopkeeper",
                        MobCategory.NPC,
                        "Shopkeeper",
                        null,
                        1,
                        EntityType.VILLAGER,
                        false,
                        null,
                        List.of(),
                        List.of(),
                        null,
                        null,
                        List.of(),
                        MobShieldConfig.EMPTY,
                        new MobIdleConfig(IdleBehavior.STATIONARY, 0.0D, 1.0D),
                        true,
                        null,
                        null,
                        null,
                        null
                ),
                new Location(world, 0.0D, 64.0D, 0.0D)
        );

        Method tickIdle = MobAiService.class.getDeclaredMethod("tickIdle", MobInstance.class);
        tickIdle.setAccessible(true);
        tickIdle.invoke(service, instance);

        verify(mobService).stopPathfinding(instance);
        verify(mobService).holdPosition(eq(instance), argThat(location ->
                location.getWorld() == world
                        && Math.abs(location.getX()) < 0.0001D
                        && Math.abs(location.getY() - 64.0D) < 0.0001D
                        && Math.abs(location.getZ()) < 0.0001D
        ));
        verify(mobService).lookAt(eq(instance), argThat(location ->
                location.getWorld() == nearPlayer.getWorld()
                        && Math.abs(location.getX() - nearPlayer.getEyeLocation().getX()) < 0.0001D
                        && Math.abs(location.getY() - nearPlayer.getEyeLocation().getY()) < 0.0001D
                        && Math.abs(location.getZ() - nearPlayer.getEyeLocation().getZ()) < 0.0001D
        ));
    }

    @Test
    void combatMobCastsConfiguredSkillAtTarget() throws Exception {
        var world = server().addSimpleWorld("combat_world");
        PlayerMock target = server().addPlayer();
        target.teleport(new Location(world, 1.2D, 64.0D, 0.0D));

        MobService mobService = mock(MobService.class);
        SkillService skillService = mock(SkillService.class);
        when(skillService.castSkill(
                any(MobSkillCaster.class),
                eq("mob_test_slash"),
                eq(SkillCastTrigger.MOB_AI),
                any(Location.class),
                eq(target),
                argThat(targets -> targets != null && targets.size() == 1 && targets.contains(target))
        )).thenReturn(SkillCastResult.success(0.0D, 20L));
        MobAiService service = new MobAiService(mobService, mock(MobCombatService.class), skillService);
        MobInstance instance = new MobInstance(
                UUID.randomUUID(),
                combatTemplate(),
                new Location(world, 0.0D, 64.0D, 0.0D)
        );
        instance.targetId(target.getUniqueId());

        Method tickCombatHold = MobAiService.class.getDeclaredMethod("tickCombatHold", MobInstance.class);
        tickCombatHold.setAccessible(true);
        tickCombatHold.invoke(service, instance);

        verify(skillService).castSkill(
                any(MobSkillCaster.class),
                eq("mob_test_slash"),
                eq(SkillCastTrigger.MOB_AI),
                any(Location.class),
                eq(target),
                argThat(targets -> targets != null && targets.size() == 1 && targets.contains(target))
        );
    }

    @Test
    void selectTargetIgnoresDeadPlayers() throws Exception {
        var world = server().addSimpleWorld("dead_target_world");
        PlayerMock deadPlayer = server().addPlayer();
        deadPlayer.teleport(new Location(world, 1.0D, 64.0D, 0.0D));
        deadPlayer.setHealth(0.0D);
        PlayerMock livingPlayer = server().addPlayer();
        livingPlayer.teleport(new Location(world, 3.0D, 64.0D, 0.0D));

        MobService mobService = mock(MobService.class);
        MobAiService service = new MobAiService(mobService, mock(MobCombatService.class), mock(SkillService.class));
        MobInstance instance = new MobInstance(
                UUID.randomUUID(),
                combatTemplate(),
                new Location(world, 0.0D, 64.0D, 0.0D)
        );

        Method selectTarget = MobAiService.class.getDeclaredMethod("selectTarget", MobInstance.class);
        selectTarget.setAccessible(true);
        Object selected = selectTarget.invoke(service, instance);

        assertEquals(livingPlayer, selected);
        assertEquals(livingPlayer.getUniqueId(), instance.targetId());
    }

    private MobTemplate combatTemplate() {
        return new MobTemplate(
                1,
                "mob_skill_test",
                MobCategory.ENEMY,
                "Mob Skill Test",
                null,
                1,
                EntityType.ZOMBIE,
                false,
                null,
                List.of(),
                List.of(),
                null,
                null,
                List.of(
                        new MobBaseStat("MAX_HEALTH", 100.0D),
                        new MobBaseStat("ATTACK", 10.0D)
                ),
                MobShieldConfig.EMPTY,
                new MobIdleConfig(IdleBehavior.STATIONARY, 0.0D, 1.0D),
                false,
                null,
                new MobTargetingConfig(TargetStrategy.NEAREST, 10.0D, 20.0D, 30.0D),
                new MobCombatConfig(CombatStyle.MELEE, 1.5D, 0L, List.of("mob_test_slash")),
                null
        );
    }
}
