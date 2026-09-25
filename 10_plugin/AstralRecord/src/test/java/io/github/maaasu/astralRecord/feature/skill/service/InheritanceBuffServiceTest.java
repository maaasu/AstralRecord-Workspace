package io.github.maaasu.astralRecord.feature.skill.service;

import io.github.maaasu.astralRecord.feature.buff.model.ActiveBuff;
import io.github.maaasu.astralRecord.feature.buff.model.BuffType;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.skill.active.service.ActiveSkillServices;
import io.github.maaasu.astralRecord.feature.skill.active.service.SkillTaskService;
import io.github.maaasu.astralRecord.feature.skill.executor.SharpshooterInheritanceMasterySkillExecutor;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillContext;
import io.github.maaasu.astralRecord.feature.skill.model.PlayerSkillCaster;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastContext;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastTrigger;
import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.feature.status.model.StatusSnapshot;
import io.github.maaasu.astralRecord.feature.status.service.StatusService;
import io.github.maaasu.astralRecord.feature.skill.registry.SkillRegistry;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InheritanceBuffServiceTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-サービス.md
     * 章・見出し: # 13_3-サービス > ## 6.7 継承バフ
     * 検証契約: 元スキル資源を再消費しない継承バフは、最初の通常攻撃着弾でバフ時間だけを一度消費し、同じ攻撃の後続着弾では再実行しない。
     */
    @Test
    void statusOnlyInheritanceConsumesBuffOnlyOnFirstImpact() {
        SkillService skillService = mock(SkillService.class);
        PassiveSkillService passiveSkillService = mock(PassiveSkillService.class);
        StatusService statusService = mock(StatusService.class);
        SkillRegistry registry = mock(SkillRegistry.class);
        SkillDefinition mastery = mock(SkillDefinition.class);
        SkillDefinition quickShot = mock(SkillDefinition.class);
        PlayerSkillCaster caster = mock(PlayerSkillCaster.class);
        AstPlayer player = mock(AstPlayer.class);
        Player bukkitPlayer = mock(Player.class);
        World world = mock(World.class);
        StatusSnapshot statusSnapshot = mock(StatusSnapshot.class);
        SkillTaskService taskService = mock(SkillTaskService.class);
        ActiveBuff activeBuff = mock(ActiveBuff.class);
        BuffType buffType = mock(BuffType.class);

        String buffId = "adventurer_quick_shot_inheritance_critical";
        UUID casterId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        Map<String, Object> inheritanceDefinition = Map.of(
                "buffId", "buff:" + buffId,
                "durationConsumptionTicks", 600L,
                "sourceSkillId", "skill:adventurer_quick_shot",
                "consumeSourceSkillResources", false
        );

        when(skillService.registry()).thenReturn(registry);
        when(registry.getDefinition(SharpshooterInheritanceMasterySkillExecutor.ID)).thenReturn(mastery);
        when(mastery.getParams()).thenReturn(Map.of(
                "inheritedSkillDamageMultiplier", 0.5D,
                "inheritanceBuffs", List.of(inheritanceDefinition)
        ));
        when(quickShot.getId()).thenReturn("adventurer_quick_shot");
        when(caster.player()).thenReturn(player);
        when(caster.casterId()).thenReturn(casterId);
        when(player.getBukkit()).thenReturn(bukkitPlayer);
        when(player.getStatusSnapshot()).thenReturn(statusSnapshot);
        when(statusSnapshot.getCurrentHp()).thenReturn(100.0D);
        when(bukkitPlayer.isOnline()).thenReturn(true);
        when(bukkitPlayer.isDead()).thenReturn(false);
        when(bukkitPlayer.getWorld()).thenReturn(world);
        ActiveSkillServices activeServices = new ActiveSkillServices(
                null, null, null, null, null, null, taskService, null, null
        );
        when(activeBuff.getType()).thenReturn(buffType);
        when(buffType.getId()).thenReturn(buffId);
        when(statusService.getActiveBuffs(player)).thenReturn(List.of(activeBuff));
        when(statusService.consumeBuffDuration(player, activeBuff, 600L)).thenReturn(true);
        when(passiveSkillService.isPassiveSkillActive(
                player, SharpshooterInheritanceMasterySkillExecutor.ID)).thenReturn(true);

        Location castLocation = new Location(world, 0.0D, 0.0D, 0.0D);
        SkillCastContext source = new SkillCastContext(
                quickShot,
                caster,
                null,
                List.of(),
                castLocation,
                statusSnapshot,
                SkillCastTrigger.PLAYER_COMMAND,
                Instant.parse("2026-01-01T00:00:00Z")
        );
        PlayerActiveSkillContext sourceContext = new PlayerActiveSkillContext(
                source, caster, activeServices
        );

        InheritanceBuffService service = new InheritanceBuffService(
                skillService, passiveSkillService, statusService
        );
        AtomicInteger effectCount = new AtomicInteger();
        service.grant(sourceContext, (impact, damageMultiplier) -> effectCount.incrementAndGet());

        SkillCastContext attack = new SkillCastContext(
                quickShot,
                caster,
                null,
                List.of(),
                castLocation,
                statusSnapshot,
                SkillCastTrigger.AUTO_ATTACK,
                Instant.parse("2026-01-01T00:00:01Z")
        );
        var impact = new InheritanceBuffService.InheritanceImpact(
                new Location(world, 0.0D, 0.0D, 0.0D), null
        );
        var inheritanceHit = service.prepareAttack(attack);

        inheritanceHit.accept(impact);
        inheritanceHit.accept(impact);

        verify(skillService, never()).tryConsumeEffectResources(
                any(PlayerSkillCaster.class),
                any(SkillDefinition.class),
                any(StatusSnapshot.class),
                any(java.util.function.BooleanSupplier.class)
        );
        verify(statusService).consumeBuffDuration(player, activeBuff, 600L);
        assertEquals(1, effectCount.get());
    }
}
