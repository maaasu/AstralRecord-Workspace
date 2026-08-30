package io.github.maaasu.astralRecord.feature.skill.executor.active.adventurer;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.combat.model.AttackType;
import io.github.maaasu.astralRecord.feature.combat.model.DamageElement;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.skill.active.service.ActiveSkillServices;
import io.github.maaasu.astralRecord.feature.skill.active.service.SkillCombatService;
import io.github.maaasu.astralRecord.feature.skill.active.service.SkillEffectService;
import io.github.maaasu.astralRecord.feature.skill.active.service.SkillMovementService;
import io.github.maaasu.astralRecord.feature.skill.active.service.SkillProjectileService;
import io.github.maaasu.astralRecord.feature.skill.active.service.SkillTargetingService;
import io.github.maaasu.astralRecord.feature.skill.active.service.SkillTaskService;
import io.github.maaasu.astralRecord.feature.skill.active.service.TemporarySkillEffectService;
import io.github.maaasu.astralRecord.feature.skill.model.PlayerSkillCaster;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastContext;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastResult;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastTrigger;
import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.feature.skill.model.SkillKind;
import io.github.maaasu.astralRecord.feature.skill.model.SkillResourceType;
import io.github.maaasu.astralRecord.feature.status.model.StatusSnapshot;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class AdventurerManaBurstExecutorTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/13_6-発動スキル追加ガイド.md
     * 章・見出し: # 13_6-発動スキル追加ガイド > ## 9. 冒険者マナバーストの実装契約 > ### 9.1 数値と対象形状
     * 検証契約: 発動時に射程7m・全角60度・最大6体・遮蔽判定ありの前方扇形を一度だけ検索する。
     */
    @Test
    void selectsForwardConeWithExistingTargetingContract() {
        SkillTargetingService targeting = mock(SkillTargetingService.class);
        ActiveSkillServices services = services(targeting, mock(SkillCombatService.class), mock(SkillEffectService.class));
        Player player = mock(Player.class);
        World world = mock(World.class);
        Location eye = new Location(world, 2.0D, 64.0D, 3.0D);
        when(player.getEyeLocation()).thenReturn(eye);
        AstPlayer astPlayer = mock(AstPlayer.class);
        when(astPlayer.getBukkit()).thenReturn(player);
        when(targeting.clippedEnd(any(Location.class), any(Vector.class), eq(7.0D)))
                .thenReturn(new Location(world, 2.0D, 64.0D, 10.0D));
        when(targeting.inCone(same(player), eq(7.0D), eq(60.0D), eq(6), eq(true)))
                .thenReturn(List.of());

        SkillCastResult result = new AdventurerManaBurstExecutor(services).cast(new SkillCastContext(
                manaBurstDefinition(), new PlayerSkillCaster(astPlayer), null, List.of(), eye,
                StatusSnapshot.empty(), SkillCastTrigger.PLAYER_COMMAND, Instant.EPOCH
        ));

        assertTrue(result.success());
        verify(targeting).inCone(same(player), eq(7.0D), eq(60.0D), eq(6), eq(true));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/13_6-発動スキル追加ガイド.md
     * 章・見出し: # 13_6-発動スキル追加ガイド > ## 9. 冒険者マナバーストの実装契約 > ### 9.2 演出
     * 検証契約: 選択した各MobへMAGIC/NONEを1回だけ適用し、状態異常を追加せず、魔力波と命中粒子を表示する。
     */
    @Test
    void appliesPureMagicOncePerTargetAndRendersImpact() {
        SkillTargetingService targeting = mock(SkillTargetingService.class);
        SkillCombatService combat = mock(SkillCombatService.class);
        SkillEffectService effects = mock(SkillEffectService.class);
        ActiveSkillServices services = services(targeting, combat, effects);
        Player player = mock(Player.class);
        World world = mock(World.class);
        Location eye = new Location(world, 2.0D, 64.0D, 3.0D);
        when(player.getEyeLocation()).thenReturn(eye);
        AstPlayer astPlayer = mock(AstPlayer.class);
        when(astPlayer.getBukkit()).thenReturn(player);
        AstEntity first = mock(AstEntity.class);
        AstEntity second = mock(AstEntity.class);
        when(first.location()).thenReturn(new Location(world, 2.0D, 64.0D, 5.0D));
        when(second.location()).thenReturn(new Location(world, 3.0D, 64.0D, 5.5D));
        when(targeting.clippedEnd(any(Location.class), any(Vector.class), eq(7.0D)))
                .thenReturn(new Location(world, 2.0D, 64.0D, 10.0D));
        when(targeting.inCone(same(player), eq(7.0D), eq(60.0D), eq(6), eq(true)))
                .thenReturn(List.of(first, second));

        SkillCastResult result = new AdventurerManaBurstExecutor(services).cast(new SkillCastContext(
                manaBurstDefinition(), new PlayerSkillCaster(astPlayer), null, List.of(), eye,
                StatusSnapshot.empty(), SkillCastTrigger.PLAYER_COMMAND, Instant.EPOCH
        ));

        assertTrue(result.success());
        verify(combat, times(2)).hit(any(AstEntity.class), any(AstEntity.class), eq(AttackType.MAGIC), eq(DamageElement.NONE), eq(1.32D));
        ArgumentCaptor<Location> waveOriginCaptor = ArgumentCaptor.forClass(Location.class);
        verify(effects, times(3)).viewArcSegment(
                waveOriginCaptor.capture(), any(Vector.class), anyDouble(), anyDouble(), anyDouble(),
                eq(12), same(SharedParticleDefinitions.MAGIC_PROJECTILE_CORE_DUST)
        );
        assertEquals(eye.getY() - 0.25D, waveOriginCaptor.getAllValues().get(0).getY(), 1.0E-9D);
        assertEquals(eye.getZ() + 0.35D, waveOriginCaptor.getAllValues().get(0).getZ(), 1.0E-9D);
        verify(effects, times(1)).viewArcSegment(
                any(Location.class), any(Vector.class), anyDouble(), eq(-30.0D), eq(30.0D),
                eq(12), same(SharedParticleDefinitions.MAGIC_IMPACT_ENCHANT)
        );
        verify(effects, times(2)).point(any(Location.class), same(SharedParticleDefinitions.MAGIC_IMPACT_ENCHANT));
        verify(effects, times(2)).point(any(Location.class), same(SharedParticleDefinitions.MAGIC_IMPACT_DUST));
        verify(effects).sound(any(Location.class), eq(Sound.BLOCK_AMETHYST_BLOCK_CHIME), eq(0.9F), eq(1.35F));
        verifyNoMoreInteractions(combat);
    }

    private static ActiveSkillServices services(
            SkillTargetingService targeting,
            SkillCombatService combat,
            SkillEffectService effects
    ) {
        return new ActiveSkillServices(
                targeting,
                combat,
                effects,
                mock(SkillProjectileService.class),
                mock(SkillMovementService.class),
                mock(TemporarySkillEffectService.class),
                mock(SkillTaskService.class)
        );
    }

    private static SkillDefinition manaBurstDefinition() {
        return new SkillDefinition(
                AdventurerManaBurstExecutor.ID,
                AdventurerManaBurstExecutor.ID,
                "マナバースト",
                null,
                "AMETHYST_SHARD",
                List.of(),
                60L,
                13.0D,
                2L,
                1,
                null,
                Map.of("range", 7.0D, "angle", 60.0D, "damageRatio", 1.32D, "maxTargets", 6),
                List.of("active", "magic", "adventurer"),
                SkillKind.ACTIVE,
                true,
                SkillResourceType.MANA,
                13.0D
        );
    }
}
