package io.github.maaasu.astralRecord.feature.skill.executor.active.swordsman;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
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
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.IntConsumer;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SwordsmanChallengingRoarExecutorTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/13_6-発動スキル追加ガイド.md
     * 章・見出し: # 13_6-発動スキル追加ガイド > ## 11. チャレンジングロアの実装契約
     * 検証契約: Lv.1発動は発動者位置から咆哮音を鳴らし、5tick周期16回の継続処理を開始する。
     */
    @Test
    void castStartsLevelOneAuraAndPlayerOriginRoar() {
        Fixture fixture = fixture();

        assertTrue(fixture.executor.cast(fixture.context).success());

        verify(fixture.effects).sound(
                any(Location.class),
                eq(Sound.ENTITY_RAVAGER_ROAR),
                eq(1.35F),
                eq(0.85F)
        );
        verify(fixture.tasks).repeat(
                eq(fixture.player.getUniqueId()),
                eq(SwordsmanChallengingRoarExecutor.ID),
                eq(0L),
                eq(5L),
                eq(16),
                any(IntConsumer.class)
        );
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/13_6-発動スキル追加ガイド.md
     * 章・見出し: # 13_6-発動スキル追加ガイド > ## 11. チャレンジングロアの実装契約 > ### 11.1 数値・対象・一時挑発
     * 検証契約: 4frameごとのpulseだけが半径8m・上下8m・最大24体・遮蔽ありで検索し、各対象を21tick挑発する。
     */
    @Test
    void pulseTauntsRadiusTargetsWhileIntermediateFrameOnlyRendersSpores() {
        Fixture fixture = fixture();
        ArgumentCaptor<IntConsumer> frames = ArgumentCaptor.forClass(IntConsumer.class);
        fixture.executor.cast(fixture.context);
        verify(fixture.tasks).repeat(any(), any(), anyLong(), anyLong(), anyInt(), frames.capture());
        clearInvocations(fixture.targeting, fixture.combat, fixture.effects);

        frames.getValue().accept(0);

        verify(fixture.effects).point(any(Location.class), eq(SharedParticleDefinitions.CHALLENGING_ROAR_WARPED_SPORE));
        verify(fixture.targeting).inRadius(same(fixture.player), any(Location.class), eq(8.0D), eq(8.0D), eq(24), eq(true));
        verify(fixture.combat).taunt(any(AstEntity.class), same(fixture.target), eq(21L));

        clearInvocations(fixture.targeting, fixture.combat, fixture.effects);
        frames.getValue().accept(1);

        verify(fixture.effects).point(any(Location.class), eq(SharedParticleDefinitions.CHALLENGING_ROAR_WARPED_SPORE));
        verify(fixture.targeting, never()).inRadius(any(), any(), anyDouble(), anyDouble(), anyInt(), eq(true));
        verify(fixture.combat, never()).taunt(any(), any(), anyLong());
    }

    private static Fixture fixture() {
        SkillTargetingService targeting = mock(SkillTargetingService.class);
        SkillCombatService combat = mock(SkillCombatService.class);
        SkillEffectService effects = mock(SkillEffectService.class);
        SkillTaskService tasks = mock(SkillTaskService.class);
        ActiveSkillServices services = new ActiveSkillServices(
                targeting,
                combat,
                effects,
                mock(SkillProjectileService.class),
                mock(SkillMovementService.class),
                mock(TemporarySkillEffectService.class),
                tasks
        );
        Player player = mock(Player.class);
        World world = mock(World.class);
        UUID playerId = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(playerId);
        when(player.getWorld()).thenReturn(world);
        when(player.isOnline()).thenReturn(true);
        when(player.isDead()).thenReturn(false);
        when(player.getLocation()).thenReturn(new Location(world, 1.0D, 64.0D, 2.0D));
        when(player.getEyeLocation()).thenReturn(new Location(world, 1.0D, 65.6D, 2.0D));
        AstPlayer astPlayer = mock(AstPlayer.class);
        when(astPlayer.getBukkit()).thenReturn(player);
        AstEntity target = mock(AstEntity.class);
        when(targeting.inRadius(same(player), any(Location.class), eq(8.0D), eq(8.0D), eq(24), eq(true)))
                .thenReturn(List.of(target));
        SkillCastContext context = new SkillCastContext(
                definition(),
                new PlayerSkillCaster(astPlayer),
                null,
                List.of(),
                player.getEyeLocation(),
                mock(StatusSnapshot.class),
                SkillCastTrigger.PLAYER_COMMAND,
                Instant.EPOCH
        );
        return new Fixture(player, target, targeting, combat, effects, tasks,
                new SwordsmanChallengingRoarExecutor(services), context);
    }

    private static SkillDefinition definition() {
        return new SkillDefinition(
                SwordsmanChallengingRoarExecutor.ID,
                SwordsmanChallengingRoarExecutor.ID,
                "チャレンジングロア",
                null,
                "GOAT_HORN",
                List.of(),
                400L,
                0.0D,
                0L,
                1,
                null,
                Map.of(
                        "radius", 8.0D,
                        "height", 8.0D,
                        "maxTargets", 24,
                        "durationTicks", 80,
                        "visualIntervalTicks", 5,
                        "tauntIntervalTicks", 20,
                        "tauntHoldTicks", 21
                ),
                List.of("active"),
                SkillKind.ACTIVE,
                true,
                SkillResourceType.ENERGY,
                25.0D
        );
    }

    private record Fixture(
            Player player,
            AstEntity target,
            SkillTargetingService targeting,
            SkillCombatService combat,
            SkillEffectService effects,
            SkillTaskService tasks,
            SwordsmanChallengingRoarExecutor executor,
            SkillCastContext context
    ) {
    }
}
