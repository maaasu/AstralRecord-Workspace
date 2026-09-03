package io.github.maaasu.astralRecord.feature.skill.executor.active.swordsman;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.combat.model.AttackType;
import io.github.maaasu.astralRecord.feature.combat.model.DamageElement;
import io.github.maaasu.astralRecord.feature.combat.model.DamageResult;
import io.github.maaasu.astralRecord.feature.item.service.ItemService;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.skill.active.service.ActiveSkillServices;
import io.github.maaasu.astralRecord.feature.skill.active.service.SkillCombatService;
import io.github.maaasu.astralRecord.feature.skill.active.service.SkillEffectService;
import io.github.maaasu.astralRecord.feature.skill.active.service.SkillMovementService;
import io.github.maaasu.astralRecord.feature.skill.active.service.SkillProjectileService;
import io.github.maaasu.astralRecord.feature.skill.active.service.SkillTargetingService;
import io.github.maaasu.astralRecord.feature.skill.active.service.SkillTaskService;
import io.github.maaasu.astralRecord.feature.skill.active.service.TemporarySkillEffectService;
import io.github.maaasu.astralRecord.feature.skill.model.LearnedSkillInstance;
import io.github.maaasu.astralRecord.feature.skill.model.PlayerSkillCaster;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastContext;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastTrigger;
import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.feature.skill.model.SkillKind;
import io.github.maaasu.astralRecord.feature.skill.model.SkillLevelDefinition;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParamReader;
import io.github.maaasu.astralRecord.feature.skill.model.SkillResourceType;
import io.github.maaasu.astralRecord.feature.skill.service.LearnedSkillResolver;
import io.github.maaasu.astralRecord.feature.status.model.StatusSnapshot;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import net.kyori.adventure.title.Title;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.IntConsumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings("deprecation")
class SwordsmanExeptStampExecutorTest extends MockBukkitTestBase {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/13_6-発動スキル追加ガイド.md
     * 章・見出し: # 13_6-発動スキル追加ガイド > ## 28. ソードマン エクゼプトスタンプの実装契約 > ### 28.1 数値・発動条件
     * 検証契約: 解決済みレベル1〜5で半径を1〜5m、ダメージを110〜150%、移動速度減少を10〜50へ展開する。
     */
    @Test
    void resolvedLevelsFollowRadiusDamageAndMovementReductionFormula() {
        LearnedSkillResolver resolver = new LearnedSkillResolver(new ItemService());
        for (int level = 1; level <= 5; level++) {
            LearnedSkillInstance learned = new LearnedSkillInstance(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    SwordsmanExeptStampExecutor.ID,
                    level,
                    List.of(),
                    0,
                    null,
                    null
            );
            SkillDefinition resolved = resolver.resolve(definitionWithLevels(), learned).definition();
            SkillParamReader params = new SkillParamReader(resolved.getId(), resolved.getParams());

            assertEquals(level, params.getDouble("radius", -1.0D), 0.0001D);
            assertEquals(1.0D + level * 0.10D, params.getDouble("damageRatio", -1.0D), 0.0001D);
            assertEquals(level * 10.0D, params.getDouble("movementSpeedReduction", -1.0D), 0.0001D);
        }
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/13_6-発動スキル追加ガイド.md
     * 章・見出し: # 13_6-発動スキル追加ガイド > ## 28. ソードマン エクゼプトスタンプの実装契約 > ### 28.1 数値・発動条件
     * 検証契約: 空中でスニークを開始すると下方向velocityを設定し、着地時だけ範囲攻撃・実ダメージ対象へのデバフ・2種の演出を行う。
     */
    @Test
    void airborneSneakDivesAndAttacksOnlyOnLanding() {
        Fixture fixture = fixture();
        ArgumentCaptor<IntConsumer> flightCaptor = ArgumentCaptor.forClass(IntConsumer.class);

        assertTrue(fixture.executor().cast(fixture.context()).success());
        verify(fixture.tasks()).repeat(
                eq(fixture.player().getUniqueId()),
                eq(SwordsmanExeptStampExecutor.ID + ":flight"),
                eq(1L),
                eq(1L),
                eq(100),
                flightCaptor.capture(),
                any(Runnable.class)
        );

        IntConsumer tick = flightCaptor.getValue();
        fixture.onGround()[0] = false;
        tick.accept(0);

        fixture.sneaking()[0] = true;
        tick.accept(1);

        fixture.onGround()[0] = true;
        tick.accept(2);

        ArgumentCaptor<Vector> velocityCaptor = ArgumentCaptor.forClass(Vector.class);
        verify(fixture.movement(), times(2)).velocity(
                same(fixture.player()),
                any(AstEntity.class),
                velocityCaptor.capture()
        );
        assertEquals(new Vector(0.0D, 1.05D, 0.65D), velocityCaptor.getAllValues().get(0));
        assertEquals(new Vector(0.0D, -2.40D, 0.0D), velocityCaptor.getAllValues().get(1));
        verify(fixture.combat()).hit(
                any(AstEntity.class),
                same(fixture.target()),
                eq(AttackType.MELEE),
                eq(DamageElement.NONE),
                eq(1.30D)
        );
        verify(fixture.combat()).applyTemporaryMovementSpeedReduction(
                same(fixture.target()), eq(30.0D), eq(100L)
        );
        verify(fixture.effects(), times(2)).sound(any(Location.class), any(), anyFloat(), anyFloat());
        verify(fixture.effects(), times(8)).blockDust(any(Location.class), same(fixture.blockData()));
        verify(fixture.effects()).point(
                any(Location.class), eq(SharedParticleDefinitions.SWORDSMAN_EXEPT_STAMP_CRIT)
        );
        verify(fixture.effects()).ring(
                any(Location.class), eq(3.0D), eq(24), eq(SharedParticleDefinitions.SWORDSMAN_EXEPT_STAMP_DUST)
        );
        verify(fixture.player()).showTitle(any(Title.class));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/13_6-発動スキル追加ガイド.md
     * 章・見出し: # 13_6-発動スキル追加ガイド > ## 28. ソードマン エクゼプトスタンプの実装契約 > ### 28.1 数値・発動条件
     * 検証契約: 空中スニークなしで着地した場合は対象探索・攻撃・デバフを行わない。
     */
    @Test
    void landingWithoutAirborneSneakDoesNotAttack() {
        Fixture fixture = fixture();
        ArgumentCaptor<IntConsumer> flightCaptor = ArgumentCaptor.forClass(IntConsumer.class);

        assertTrue(fixture.executor().cast(fixture.context()).success());
        verify(fixture.tasks()).repeat(
                any(UUID.class),
                eq(SwordsmanExeptStampExecutor.ID + ":flight"),
                eq(1L),
                eq(1L),
                eq(100),
                flightCaptor.capture(),
                any(Runnable.class)
        );

        IntConsumer tick = flightCaptor.getValue();
        fixture.onGround()[0] = false;
        tick.accept(0);
        fixture.onGround()[0] = true;
        tick.accept(1);

        verify(fixture.combat(), never()).hit(
                any(AstEntity.class), any(AstEntity.class), any(AttackType.class), any(DamageElement.class), anyDouble()
        );
        verify(fixture.targeting(), never()).inRadius(
                any(Player.class), any(Location.class), anyDouble(), anyDouble(), anyInt(), anyBoolean()
        );
        verify(fixture.combat(), never()).applyTemporaryMovementSpeedReduction(
                any(AstEntity.class), anyDouble(), anyLong()
        );
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/13_6-発動スキル追加ガイド.md
     * 章・見出し: # 13_6-発動スキル追加ガイド > ## 28. ソードマン エクゼプトスタンプの実装契約 > ### 28.1 数値・発動条件
     * 検証契約: 着地したtickで初めてスニークを開始しても、空中入力ではないため急降下・攻撃を行わない。
     */
    @Test
    void groundedSneakStartDoesNotTriggerDiveOrAttack() {
        Fixture fixture = fixture();
        ArgumentCaptor<IntConsumer> flightCaptor = ArgumentCaptor.forClass(IntConsumer.class);

        assertTrue(fixture.executor().cast(fixture.context()).success());
        verify(fixture.tasks()).repeat(
                any(UUID.class),
                eq(SwordsmanExeptStampExecutor.ID + ":flight"),
                eq(1L),
                eq(1L),
                eq(100),
                flightCaptor.capture(),
                any(Runnable.class)
        );

        IntConsumer tick = flightCaptor.getValue();
        fixture.onGround()[0] = false;
        tick.accept(0);
        fixture.sneaking()[0] = true;
        fixture.onGround()[0] = true;
        tick.accept(1);

        verify(fixture.movement(), times(1)).velocity(
                same(fixture.player()), any(AstEntity.class), any(Vector.class)
        );
        verify(fixture.combat(), never()).hit(
                any(AstEntity.class), any(AstEntity.class), any(AttackType.class), any(DamageElement.class), anyDouble()
        );
        verify(fixture.combat(), never()).applyTemporaryMovementSpeedReduction(
                any(AstEntity.class), anyDouble(), anyLong()
        );
    }

    private static Fixture fixture() {
        SkillTargetingService targeting = mock(SkillTargetingService.class);
        SkillCombatService combat = mock(SkillCombatService.class);
        SkillEffectService effects = mock(SkillEffectService.class);
        SkillMovementService movement = mock(SkillMovementService.class);
        SkillTaskService tasks = mock(SkillTaskService.class);
        ActiveSkillServices services = new ActiveSkillServices(
                targeting,
                combat,
                effects,
                mock(SkillProjectileService.class),
                movement,
                mock(TemporarySkillEffectService.class),
                tasks
        );
        Player player = mock(Player.class);
        World world = mock(World.class);
        Block block = mock(Block.class);
        BlockData blockData = mock(BlockData.class);
        UUID playerId = UUID.randomUUID();
        Location playerLocation = new Location(world, 0.0D, 64.0D, 0.0D, 0.0F, 0.0F);
        Location eyeLocation = new Location(world, 0.0D, 65.62D, 0.0D, 0.0F, 0.0F);
        Location impact = new Location(world, 0.0D, 64.05D, 0.0D);
        boolean[] onGround = {true};
        boolean[] sneaking = {false};
        when(player.getUniqueId()).thenReturn(playerId);
        when(player.getWorld()).thenReturn(world);
        when(player.getLocation()).thenReturn(playerLocation);
        when(player.getEyeLocation()).thenReturn(eyeLocation);
        when(player.isOnline()).thenReturn(true);
        when(player.isDead()).thenReturn(false);
        when(player.isOnGround()).thenAnswer(ignored -> onGround[0]);
        when(player.isSneaking()).thenAnswer(ignored -> sneaking[0]);
        when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenReturn(block);
        when(world.getBlockAt(any(Location.class))).thenReturn(block);
        when(block.getBlockData()).thenReturn(blockData);

        AstPlayer astPlayer = mock(AstPlayer.class);
        when(astPlayer.getBukkit()).thenReturn(player);
        AstEntity target = mock(AstEntity.class);
        when(targeting.groundAt(any(Location.class), eq(2), eq(2))).thenReturn(impact);
        when(targeting.inRadius(
                same(player), eq(impact), eq(3.0D), eq(3.0D), eq(Integer.MAX_VALUE), eq(true)
        )).thenReturn(List.of(target));
        when(movement.velocity(same(player), any(AstEntity.class), any(Vector.class)))
                .thenAnswer(invocation -> ((Vector) invocation.getArgument(2)).clone());
        when(combat.hit(
                any(AstEntity.class), same(target), eq(AttackType.MELEE), eq(DamageElement.NONE), eq(1.30D)
        )).thenReturn(new DamageResult(20.0D));

        LearnedSkillInstance learned = new LearnedSkillInstance(
                UUID.randomUUID(),
                UUID.randomUUID(),
                SwordsmanExeptStampExecutor.ID,
                3,
                List.of(),
                0,
                null,
                null
        );
        SkillDefinition resolvedDefinition = new LearnedSkillResolver(new ItemService())
                .resolve(definitionWithLevels(), learned)
                .definition();
        SkillCastContext context = new SkillCastContext(
                resolvedDefinition,
                new PlayerSkillCaster(astPlayer),
                null,
                List.of(),
                eyeLocation,
                mock(StatusSnapshot.class),
                SkillCastTrigger.PLAYER_COMMAND,
                Instant.EPOCH,
                learned
        );
        return new Fixture(
                player,
                target,
                targeting,
                combat,
                effects,
                movement,
                tasks,
                blockData,
                onGround,
                sneaking,
                new SwordsmanExeptStampExecutor(services),
                context
        );
    }

    private static SkillDefinition definitionWithLevels() {
        return new SkillDefinition(
                SwordsmanExeptStampExecutor.ID,
                SwordsmanExeptStampExecutor.ID,
                "エクゼプトスタンプ",
                null,
                "ANVIL",
                List.of(),
                160L,
                0.0D,
                0L,
                1,
                null,
                Map.of(
                        "radius", 1.0D,
                        "damageRatio", 1.10D,
                        "movementSpeedReduction", 10.0D,
                        "movementSpeedDebuffDurationTicks", 100,
                        "launchHorizontalVelocity", 0.65D,
                        "launchVerticalVelocity", 1.05D,
                        "diveVelocity", 2.40D,
                        "maxFlightTicks", 100
                ),
                List.of("active", "melee", "mobility", "debuff"),
                SkillKind.ACTIVE,
                true,
                SkillResourceType.ENERGY,
                20.0D,
                null,
                5,
                List.of(
                        new SkillLevelDefinition(2, 0L, 0.0D, 0L, Map.of(
                                "radius", 1.0D,
                                "damageRatio", 0.10D,
                                "movementSpeedReduction", 10.0D
                        ), List.of()),
                        new SkillLevelDefinition(3, 0L, 0.0D, 0L, Map.of(
                                "radius", 1.0D,
                                "damageRatio", 0.10D,
                                "movementSpeedReduction", 10.0D
                        ), List.of()),
                        new SkillLevelDefinition(4, 0L, 0.0D, 0L, Map.of(
                                "radius", 1.0D,
                                "damageRatio", 0.10D,
                                "movementSpeedReduction", 10.0D
                        ), List.of()),
                        new SkillLevelDefinition(5, 0L, 0.0D, 0L, Map.of(
                                "radius", 1.0D,
                                "damageRatio", 0.10D,
                                "movementSpeedReduction", 10.0D
                        ), List.of())
                ),
                List.of(),
                List.of()
        );
    }

    private record Fixture(
            Player player,
            AstEntity target,
            SkillTargetingService targeting,
            SkillCombatService combat,
            SkillEffectService effects,
            SkillMovementService movement,
            SkillTaskService tasks,
            BlockData blockData,
            boolean[] onGround,
            boolean[] sneaking,
            SwordsmanExeptStampExecutor executor,
            SkillCastContext context
    ) {
    }

}
