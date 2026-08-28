package io.github.maaasu.astralRecord.feature.skill.executor.active.mage;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.combat.model.AttackType;
import io.github.maaasu.astralRecord.feature.combat.model.DamageElement;
import io.github.maaasu.astralRecord.feature.condition.model.ConditionType;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.skill.active.model.ActiveSkillCondition;
import io.github.maaasu.astralRecord.feature.skill.active.model.SkillEffectLineSegment;
import io.github.maaasu.astralRecord.feature.skill.active.model.SkillLineTargetHit;
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
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MageSparkingExecutorTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/13_6-発動スキル追加ガイド.md
     * 章・見出し: # 13_6-発動スキル追加ガイド > ## 23. メイジ スパーキングの実装契約
     * 検証契約: 雷弾は指定個数を水平360度へ等間隔で生成する。
     */
    @Test
    void createsConfiguredHorizontalRadialProjectiles() {
        List<MageSparkingExecutor.SparkState> states = MageSparkingExecutor.radialStates(
                new Location(null, 1.0D, 2.0D, 3.0D), 5, 0.0F
        );

        assertEquals(5, states.size());
        assertTrue(states.stream().allMatch(state -> Math.abs(state.direction().getY()) < 1.0E-9D));
        assertTrue(states.stream().allMatch(state -> Math.abs(state.direction().length() - 1.0D) < 1.0E-9D));
        assertEquals(Math.PI * 2.0D / 5.0D,
                states.getFirst().direction().angle(states.get(1).direction()), 1.0E-6D);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/13_6-発動スキル追加ガイド.md
     * 章・見出し: # 13_6-発動スキル追加ガイド > ## 23. メイジ スパーキングの実装契約
     * 検証契約: Block面法線による鏡面反射で壁へ向かう成分だけを反転する。
     */
    @Test
    void reflectsDirectionAgainstBlockFaceNormal() {
        Vector reflected = MageSparkingExecutor.reflect(
                new Vector(1.0D, 0.0D, 1.0D), new Vector(-1.0D, 0.0D, 0.0D)
        );

        assertEquals(-Math.sqrt(0.5D), reflected.getX(), 1.0E-9D);
        assertEquals(0.0D, reflected.getY(), 1.0E-9D);
        assertEquals(Math.sqrt(0.5D), reflected.getZ(), 1.0E-9D);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/13_6-発動スキル追加ガイド.md
     * 章・見出し: # 13_6-発動スキル追加ガイド > ## 23. メイジ スパーキングの実装契約
     * 検証契約: 同じ発動で命中済みの手前Mobへ再接触した雷弾は、奥のMobへ貫通せず消滅し、100%雷魔法と25%・100tick感電を1回だけ適用する。
     */
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void hitsEachTargetOnlyOncePerCastWithShockChance() {
        SkillTargetingService targeting = mock(SkillTargetingService.class);
        SkillCombatService combat = mock(SkillCombatService.class);
        SkillEffectService effects = mock(SkillEffectService.class);
        SkillTaskService tasks = mock(SkillTaskService.class);
        ActiveSkillServices services = new ActiveSkillServices(
                targeting, combat, effects, mock(SkillProjectileService.class),
                mock(SkillMovementService.class), mock(TemporarySkillEffectService.class), tasks
        );
        World world = mock(World.class);
        Player player = mock(Player.class);
        UUID playerId = UUID.randomUUID();
        Location playerLocation = new Location(world, 0.0D, 64.0D, 0.0D);
        when(player.getUniqueId()).thenReturn(playerId);
        when(player.getLocation()).thenReturn(playerLocation);
        AstPlayer astPlayer = mock(AstPlayer.class);
        when(astPlayer.getBukkit()).thenReturn(player);
        AstEntity target = mock(AstEntity.class);
        UUID targetId = UUID.randomUUID();
        when(target.id()).thenReturn(targetId);
        AstEntity rearTarget = mock(AstEntity.class);
        when(rearTarget.id()).thenReturn(UUID.randomUUID());
        SkillTargetingService.LineTargetSnapshot snapshot = mock(SkillTargetingService.LineTargetSnapshot.class);
        when(targeting.captureLineTargetSnapshot(player)).thenReturn(snapshot);
        when(targeting.lineTargetHits(
                same(player), same(snapshot), any(), any(), anyDouble(), eq(0.45D), anyInt(), eq(true)
        )).thenAnswer(invocation -> {
            Location origin = invocation.getArgument(2, Location.class);
            Vector direction = invocation.getArgument(3, Vector.class);
            return List.of(
                    new SkillLineTargetHit(
                            target, origin.clone().add(direction.clone().multiply(0.2D)), 0.2D
                    ),
                    new SkillLineTargetHit(
                            rearTarget, origin.clone().add(direction.clone().multiply(0.4D)), 0.4D
                    )
            );
        });

        MageSparkingExecutor executor = new MageSparkingExecutor(services);
        assertTrue(executor.cast(new SkillCastContext(
                definition(), new PlayerSkillCaster(astPlayer), null, List.of(), playerLocation,
                StatusSnapshot.empty(), SkillCastTrigger.PLAYER_COMMAND, Instant.EPOCH
        )).success());

        ArgumentCaptor<IntConsumer> tick = ArgumentCaptor.forClass(IntConsumer.class);
        verify(tasks).repeat(eq(playerId), any(), eq(0L), eq(1L), eq(50), tick.capture());
        tick.getValue().accept(0);

        ArgumentCaptor<ActiveSkillCondition[]> conditions = ArgumentCaptor.forClass(ActiveSkillCondition[].class);
        verify(combat, times(1)).hit(
                any(AstEntity.class), same(target), eq(AttackType.MAGIC), eq(DamageElement.LIGHTNING),
                eq(1.0D), conditions.capture()
        );
        assertEquals(1, conditions.getValue().length);
        assertEquals(ConditionType.SHOCKED, conditions.getValue()[0].type());
        assertEquals(25.0D, conditions.getValue()[0].chance());
        assertEquals(100L, conditions.getValue()[0].durationTicks());
        verify(combat, never()).hit(
                any(AstEntity.class), same(rearTarget), any(), any(), anyDouble(), any(ActiveSkillCondition[].class)
        );
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/13_6-発動スキル追加ガイド.md
     * 章・見出し: # 13_6-発動スキル追加ガイド > ## 23. メイジ スパーキングの実装契約 > ### 23.1 数値・弾道・対象
     * 検証契約: tick途中で壁へ衝突した雷弾は、反射後も残りの移動距離を消費し、合計0.65m進む。
     */
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void continuesRemainingMovementAfterWallReflection() {
        SkillTargetingService targeting = mock(SkillTargetingService.class);
        SkillEffectService effects = mock(SkillEffectService.class);
        SkillTaskService tasks = mock(SkillTaskService.class);
        ActiveSkillServices services = new ActiveSkillServices(
                targeting, mock(SkillCombatService.class), effects, mock(SkillProjectileService.class),
                mock(SkillMovementService.class), mock(TemporarySkillEffectService.class), tasks
        );
        World world = mock(World.class);
        Player player = mock(Player.class);
        UUID playerId = UUID.randomUUID();
        Location playerLocation = new Location(world, 0.0D, 64.0D, 0.0D);
        when(player.getUniqueId()).thenReturn(playerId);
        when(player.getLocation()).thenReturn(playerLocation);
        when(player.getYaw()).thenReturn(-90.0F);
        AstPlayer astPlayer = mock(AstPlayer.class);
        when(astPlayer.getBukkit()).thenReturn(player);
        SkillTargetingService.LineTargetSnapshot snapshot = mock(SkillTargetingService.LineTargetSnapshot.class);
        when(targeting.captureLineTargetSnapshot(player)).thenReturn(snapshot);
        SkillTargetingService.BlockHit wall = new SkillTargetingService.BlockHit(
                new Location(world, 0.2D, 64.75D, 0.0D), new Vector(-1.0D, 0.0D, 0.0D)
        );
        when(targeting.blockHit(any(), any(), anyDouble())).thenReturn(wall, (SkillTargetingService.BlockHit) null);
        when(targeting.lineTargetHits(
                same(player), same(snapshot), any(), any(), anyDouble(), eq(0.45D), eq(1), anyBoolean()
        )).thenReturn(List.of());

        MageSparkingExecutor executor = new MageSparkingExecutor(services);
        assertTrue(executor.cast(new SkillCastContext(
                definition(1), new PlayerSkillCaster(astPlayer), null, List.of(), playerLocation,
                StatusSnapshot.empty(), SkillCastTrigger.PLAYER_COMMAND, Instant.EPOCH
        )).success());

        ArgumentCaptor<IntConsumer> tick = ArgumentCaptor.forClass(IntConsumer.class);
        verify(tasks).repeat(eq(playerId), any(), eq(0L), eq(1L), eq(50), tick.capture());
        tick.getValue().accept(0);

        ArgumentCaptor<List<SkillEffectLineSegment>> segments = ArgumentCaptor.forClass(List.class);
        verify(effects).lines(any(), segments.capture(), eq(0.32D), any());
        assertEquals(2, segments.getValue().size());
        assertEquals(-0.25D, segments.getValue().get(1).end().getX(), 1.0E-9D);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/13_6-発動スキル追加ガイド.md
     * 章・見出し: # 13_6-発動スキル追加ガイド > ## 23. メイジ スパーキングの実装契約 > ### 23.2 バランス・入手・演出・テスト契約
     * 検証契約: 本番マスタはLv.1〜5で雷弾数5 / 7 / 9 / 11 / 13となり、node 1208と交換ショップが同じskillを参照する。
     */
    @Test
    void filebaseDefinesLevelGrowthAndAcquisitionReferences() throws Exception {
        Path root = repositoryRoot();
        YamlConfiguration skill = YamlConfiguration.loadConfiguration(
                root.resolve("40_filebase/30.features.skill/v1.mage_sparking.yml").toFile()
        );
        int count = skill.getInt("params.projectileCount");
        List<Integer> counts = new ArrayList<>(List.of(count));
        for (Map<?, ?> level : skill.getMapList("levels")) {
            Map<?, ?> deltas = (Map<?, ?>) level.get("paramDeltas");
            count += ((Number) deltas.get("projectileCount")).intValue();
            counts.add(count);
        }
        assertEquals(List.of(5, 7, 9, 11, 13), counts);

        String node = Files.readString(root.resolve("40_filebase/35.features.skilltree/nodes/1208.json"));
        assertTrue(node.contains("\"skillId\": \"mage_sparking\""));
        assertTrue(node.contains("\"classId\": \"mage\""));
        YamlConfiguration shop = YamlConfiguration.loadConfiguration(
                root.resolve("40_filebase/45.features.shop/v1.skill_gem_exchange.yml").toFile()
        );
        assertTrue(shop.getMapList("items").stream().anyMatch(item -> {
            Object itemId = item.get("itemId");
            return itemId instanceof Map<?, ?> ref
                    && "item:00_skill_gem_mage_sparking".equals(ref.get("ref"))
                    && Integer.valueOf(1).equals(item.get("page"))
                    && Integer.valueOf(19).equals(item.get("slot"));
        }));
    }

    private static SkillDefinition definition() {
        return definition(5);
    }

    private static SkillDefinition definition(int projectileCount) {
        return new SkillDefinition(
                MageSparkingExecutor.ID,
                MageSparkingExecutor.ID,
                "スパーキング",
                null,
                "LIGHTNING_ROD",
                List.of(),
                160L,
                0.0D,
                4L,
                1,
                null,
                Map.of(
                        "damageRatio", 1.0D,
                        "projectileCount", projectileCount,
                        "projectileSpeed", 0.65D,
                        "projectileHitRadius", 0.45D,
                        "durationTicks", 50,
                        "shockChance", 25.0D,
                        "shockDurationTicks", 100
                ),
                List.of("active", "magic", "lightning"),
                SkillKind.ACTIVE,
                true,
                SkillResourceType.MANA,
                14.0D
        );
    }

    private static Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isDirectory(current.resolve("40_filebase"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new AssertionError("repository root was not found from the test directory");
    }
}
