package io.github.maaasu.astralRecord.feature.skill.executor.active.adventurer;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.combat.model.AttackType;
import io.github.maaasu.astralRecord.feature.combat.model.DamageElement;
import io.github.maaasu.astralRecord.feature.combat.model.DamageResult;
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
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdventurerSmashExecutorTest extends MockBukkitTestBase {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/13_6-発動スキル追加ガイド.md
     * 章・見出し: # 13_6-発動スキル追加ガイド > ## 6. レビュー・テストチェック
     * 検証契約: スマッシュは半径4m内から主対象を1体選び、遅延後に3.60倍、主対象を除く半径内の最大8体へ0.576倍の近接攻撃を適用する。
     */
    @Test
    void appliesPrimaryAndIndependentSecondaryDamageAfterImpactDelay() {
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
        Location playerLocation = new Location(world, 0.0D, 64.0D, 0.0D);
        Location eye = new Location(world, 0.0D, 65.6D, 0.0D, 0.0F, 0.0F);
        when(player.getUniqueId()).thenReturn(playerId);
        when(player.getWorld()).thenReturn(world);
        when(player.isOnline()).thenReturn(true);
        when(player.getLocation()).thenReturn(playerLocation);
        when(player.getEyeLocation()).thenReturn(eye);
        Block floorBlock = mock(Block.class);
        when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenReturn(floorBlock);
        when(world.getBlockAt(any(Location.class))).thenReturn(floorBlock);
        when(floorBlock.getBlockData()).thenReturn(mock(BlockData.class));

        AstPlayer astPlayer = mock(AstPlayer.class);
        when(astPlayer.getBukkit()).thenReturn(player);
        AstEntity primary = entity(world, 4.0D);
        List<AstEntity> secondaries = IntStream.range(0, 9)
                .mapToObj(index -> entity(world, 4.5D + index * 0.1D))
                .toList();
        // executor側の8体上限も固定するため、mockは要求数より1体多く返す。
        List<AstEntity> radiusTargets = new ArrayList<>();
        radiusTargets.add(primary);
        radiusTargets.addAll(secondaries);
        when(targeting.inRadius(
                same(player), any(Location.class), eq(4.0D), eq(4.0D), eq(Integer.MAX_VALUE), eq(true)
        )).thenReturn(List.of(primary));
        when(targeting.inRadius(
                same(player), any(Location.class), eq(2.0D), eq(2.0D), eq(9), eq(true)
        )).thenReturn(radiusTargets);
        when(combat.hit(any(AstEntity.class), any(AstEntity.class), eq(AttackType.MELEE),
                eq(DamageElement.NONE), eq(3.60D))).thenReturn(new DamageResult(20.0D));
        when(combat.hit(any(AstEntity.class), any(AstEntity.class), eq(AttackType.MELEE),
                eq(DamageElement.NONE), eq(0.576D))).thenReturn(new DamageResult(20.0D));

        SkillCastContext context = new SkillCastContext(
                definition(),
                new PlayerSkillCaster(astPlayer),
                null,
                List.of(),
                eye,
                StatusSnapshot.empty(),
                SkillCastTrigger.PLAYER_COMMAND,
                Instant.EPOCH
        );
        ArgumentCaptor<Runnable> impactCaptor = ArgumentCaptor.forClass(Runnable.class);
        ArgumentCaptor<Location> primaryCenterCaptor = ArgumentCaptor.forClass(Location.class);

        assertTrue(new AdventurerSmashExecutor(services).cast(context).success());
        verify(tasks).later(eq(playerId), eq(AdventurerSmashExecutor.ID), eq(8L), impactCaptor.capture());

        impactCaptor.getValue().run();

        verify(combat).hit(any(AstEntity.class), same(primary), eq(AttackType.MELEE),
                eq(DamageElement.NONE), eq(3.60D));
        for (AstEntity secondary : secondaries.subList(0, 8)) {
            verify(combat).hit(any(AstEntity.class), same(secondary), eq(AttackType.MELEE),
                    eq(DamageElement.NONE), eq(0.576D));
            verify(combat).knockback(same(secondary), any(Location.class), eq(1.0D), eq(0.25D));
        }
        AstEntity overLimit = secondaries.get(8);
        verify(combat, never()).hit(any(AstEntity.class), same(overLimit), eq(AttackType.MELEE),
                eq(DamageElement.NONE), eq(0.576D));
        verify(combat, never()).knockback(same(overLimit), any(Location.class), eq(1.0D), eq(0.25D));
        verify(combat, never()).hit(any(AstEntity.class), same(primary), eq(AttackType.MELEE),
                eq(DamageElement.NONE), eq(0.576D));
        verify(targeting).inRadius(same(player), any(Location.class), eq(2.0D), eq(2.0D), eq(9), eq(true));
        verify(targeting).inRadius(
                same(player), primaryCenterCaptor.capture(), eq(4.0D), eq(4.0D), eq(Integer.MAX_VALUE), eq(true)
        );
        Location primaryCenter = primaryCenterCaptor.getValue();
        assertEquals(eye.getX(), primaryCenter.getX());
        assertEquals(eye.getY(), primaryCenter.getY());
        assertEquals(eye.getZ(), primaryCenter.getZ());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/13_6-発動スキル追加ガイド.md
     * 章・見出し: # 13_6-発動スキル追加ガイド > ## 6. レビュー・テストチェック
     * 検証契約: 半径4m内の複数候補では、視点ラインに触れる候補を優先し、その中で最も近いMobだけへ主攻撃を適用する。
     */
    @Test
    void prioritizesNearestTargetOnViewLineAmongPrimaryRadiusCandidates() {
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
        Location playerLocation = new Location(world, 0.0D, 64.0D, 0.0D);
        Location eye = new Location(world, 0.0D, 65.6D, 0.0D, 0.0F, 0.0F);
        when(player.getUniqueId()).thenReturn(playerId);
        when(player.getWorld()).thenReturn(world);
        when(player.isOnline()).thenReturn(true);
        when(player.getLocation()).thenReturn(playerLocation);
        when(player.getEyeLocation()).thenReturn(eye);
        Block floorBlock = mock(Block.class);
        when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenReturn(floorBlock);
        when(world.getBlockAt(any(Location.class))).thenReturn(floorBlock);
        when(floorBlock.getBlockData()).thenReturn(mock(BlockData.class));

        AstPlayer astPlayer = mock(AstPlayer.class);
        when(astPlayer.getBukkit()).thenReturn(player);
        AstEntity nearestOffLine = entity(world, 1.0D);
        AstEntity nearestOnLine = entity(world, 2.0D);
        AstEntity fartherOnLine = entity(world, 3.0D);
        when(targeting.inRadius(
                same(player), any(Location.class), eq(4.0D), eq(4.0D), eq(Integer.MAX_VALUE), eq(true)
        )).thenReturn(List.of(nearestOffLine, nearestOnLine, fartherOnLine));
        when(targeting.inLine(
                same(player), any(Location.class), any(Vector.class), eq(4.0D), eq(0.0D), eq(Integer.MAX_VALUE)
        )).thenReturn(List.of(nearestOnLine, fartherOnLine));
        when(targeting.inRadius(
                same(player), any(Location.class), eq(2.0D), eq(2.0D), eq(9), eq(true)
        )).thenReturn(List.of());
        when(combat.hit(any(AstEntity.class), any(AstEntity.class), eq(AttackType.MELEE),
                eq(DamageElement.NONE), eq(3.60D))).thenReturn(new DamageResult(20.0D));

        SkillCastContext context = new SkillCastContext(
                definition(),
                new PlayerSkillCaster(astPlayer),
                null,
                List.of(),
                eye,
                StatusSnapshot.empty(),
                SkillCastTrigger.PLAYER_COMMAND,
                Instant.EPOCH
        );
        ArgumentCaptor<Runnable> impactCaptor = ArgumentCaptor.forClass(Runnable.class);

        assertTrue(new AdventurerSmashExecutor(services).cast(context).success());
        verify(tasks).later(eq(playerId), eq(AdventurerSmashExecutor.ID), eq(8L), impactCaptor.capture());

        impactCaptor.getValue().run();

        verify(combat).hit(any(AstEntity.class), same(nearestOnLine), eq(AttackType.MELEE),
                eq(DamageElement.NONE), eq(3.60D));
        verify(combat, never()).hit(any(AstEntity.class), same(nearestOffLine), eq(AttackType.MELEE),
                eq(DamageElement.NONE), eq(3.60D));
        verify(combat, never()).hit(any(AstEntity.class), same(fartherOnLine), eq(AttackType.MELEE),
                eq(DamageElement.NONE), eq(3.60D));
        verify(targeting).inLine(
                same(player), any(Location.class), any(Vector.class), eq(4.0D), eq(0.0D), eq(Integer.MAX_VALUE)
        );
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/13_6-発動スキル追加ガイド.md
     * 章・見出し: # 13_6-発動スキル追加ガイド > ## 6. レビュー・テストチェック
     * 検証契約: 視点ライン候補がない場合は、半径4m内で最も近いMob 1体だけへ主攻撃を適用する。
     */
    @Test
    void fallsBackToNearestPrimaryRadiusTargetWhenViewLineHasNoTarget() {
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
        Location playerLocation = new Location(world, 0.0D, 64.0D, 0.0D);
        Location eye = new Location(world, 0.0D, 65.6D, 0.0D, 0.0F, 0.0F);
        when(player.getUniqueId()).thenReturn(playerId);
        when(player.getWorld()).thenReturn(world);
        when(player.isOnline()).thenReturn(true);
        when(player.getLocation()).thenReturn(playerLocation);
        when(player.getEyeLocation()).thenReturn(eye);
        Block floorBlock = mock(Block.class);
        when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenReturn(floorBlock);
        when(world.getBlockAt(any(Location.class))).thenReturn(floorBlock);
        when(floorBlock.getBlockData()).thenReturn(mock(BlockData.class));

        AstPlayer astPlayer = mock(AstPlayer.class);
        when(astPlayer.getBukkit()).thenReturn(player);
        AstEntity nearest = entity(world, 1.0D);
        AstEntity farther = entity(world, 3.0D);
        when(targeting.inRadius(
                same(player), any(Location.class), eq(4.0D), eq(4.0D), eq(Integer.MAX_VALUE), eq(true)
        )).thenReturn(List.of(nearest, farther));
        when(targeting.inLine(
                same(player), any(Location.class), any(Vector.class), eq(4.0D), eq(0.0D), eq(Integer.MAX_VALUE)
        )).thenReturn(List.of());
        when(targeting.inRadius(
                same(player), any(Location.class), eq(2.0D), eq(2.0D), eq(9), eq(true)
        )).thenReturn(List.of());
        when(combat.hit(any(AstEntity.class), any(AstEntity.class), eq(AttackType.MELEE),
                eq(DamageElement.NONE), eq(3.60D))).thenReturn(new DamageResult(20.0D));

        SkillCastContext context = new SkillCastContext(
                definition(),
                new PlayerSkillCaster(astPlayer),
                null,
                List.of(),
                eye,
                StatusSnapshot.empty(),
                SkillCastTrigger.PLAYER_COMMAND,
                Instant.EPOCH
        );
        ArgumentCaptor<Runnable> impactCaptor = ArgumentCaptor.forClass(Runnable.class);

        assertTrue(new AdventurerSmashExecutor(services).cast(context).success());
        verify(tasks).later(eq(playerId), eq(AdventurerSmashExecutor.ID), eq(8L), impactCaptor.capture());

        impactCaptor.getValue().run();

        verify(combat).hit(any(AstEntity.class), same(nearest), eq(AttackType.MELEE),
                eq(DamageElement.NONE), eq(3.60D));
        verify(combat, never()).hit(any(AstEntity.class), same(farther), eq(AttackType.MELEE),
                eq(DamageElement.NONE), eq(3.60D));
    }

    private static AstEntity entity(World world, double z) {
        AstEntity entity = mock(AstEntity.class);
        when(entity.id()).thenReturn(UUID.randomUUID());
        when(entity.location()).thenReturn(new Location(world, 0.0D, 64.0D, z));
        return entity;
    }

    private static SkillDefinition definition() {
        return new SkillDefinition(
                AdventurerSmashExecutor.ID,
                AdventurerSmashExecutor.ID,
                "スマッシュ",
                null,
                "IRON_AXE",
                List.of(),
                150L,
                0.0D,
                12L,
                1,
                null,
                Map.of(
                        "primaryRadius", 4.0D,
                        "impactRadius", 2.0D,
                        "damageRatio", 3.60D,
                        "secondaryRatio", 0.576D,
                        "secondaryKnockback", 1.0D,
                        "impactDelayTicks", 8,
                        "maxSecondaryTargets", 8
                ),
                List.of("active", "melee", "adventurer"),
                SkillKind.ACTIVE,
                true,
                SkillResourceType.ENERGY,
                30.0D
        );
    }
}
