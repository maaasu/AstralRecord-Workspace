package io.github.maaasu.astralRecord.feature.mob.service;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.mob.model.MobInstance;
import io.github.maaasu.astralRecord.feature.status.model.StatusType;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MobKnockbackServiceTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/12-mob/3-メソッド仕様/12_3-戦闘.md
     * 章・見出し: # 12_3-戦闘 > ## 2. MobKnockbackService メソッド仕様 > ### ノックバック適用
     * 検証契約: custom strengthへ(1-resistance/100)を乗算して水平knockbackを減衰する。
     */
    @Test
    void customStrengthIsReducedByKnockbackResistance() {
        MobService mobService = mock(MobService.class);
        MobEntityController controller = mock(MobEntityController.class);
        MobInstance mob = mock(MobInstance.class);
        AstEntity target = mock(AstEntity.class);
        World world = mock(World.class);
        when(mobService.entityController()).thenReturn(controller);
        when(target.location()).thenReturn(new Location(world, 3.0D, 0.0D, 4.0D));
        when(target.statValue(StatusType.KNOCKBACK_RESISTANCE)).thenReturn(50.0D);
        when(target.isMob()).thenReturn(true);
        when(target.mob()).thenReturn(mob);
        when(target.id()).thenReturn(UUID.randomUUID());

        new MobKnockbackService(mobService).applyWithStrength(
                target,
                new Location(world, 0.0D, 0.0D, 0.0D),
                2.0D,
                0.5D
        );

        ArgumentCaptor<Vector> velocity = ArgumentCaptor.forClass(Vector.class);
        verify(controller).addVelocity(org.mockito.ArgumentMatchers.same(mob), velocity.capture());
        assertEquals(0.6D, velocity.getValue().getX(), 1.0E-9D);
        assertEquals(0.25D, velocity.getValue().getY(), 1.0E-9D);
        assertEquals(0.8D, velocity.getValue().getZ(), 1.0E-9D);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/12-mob/3-メソッド仕様/12_3-戦闘.md
     * 章・見出し: # 12_3-戦闘 > ## 2. MobKnockbackService メソッド仕様 > ### ノックバック適用
     * 検証契約: knockback resistance 100%でcustom velocityを発生させない。
     */
    @Test
    void fullKnockbackResistancePreventsCustomVelocity() {
        MobService mobService = mock(MobService.class);
        MobEntityController controller = mock(MobEntityController.class);
        AstEntity target = mock(AstEntity.class);
        when(mobService.entityController()).thenReturn(controller);
        when(target.statValue(StatusType.KNOCKBACK_RESISTANCE)).thenReturn(100.0D);

        new MobKnockbackService(mobService).applyWithStrength(
                target,
                mock(Location.class),
                2.0D,
                0.5D
        );

        verify(controller, never()).addVelocity(
                org.mockito.ArgumentMatchers.any(MobInstance.class),
                org.mockito.ArgumentMatchers.any(Vector.class)
        );
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/13_6-発動スキル追加ガイド.md
     * 章・見出し: # 13_6-発動スキル追加ガイド > ## 24. メイジ フロストブリザードの実装契約 > ### 24.1 数値・移動・対象
     * 検証契約: 任意方向velocityはノックバック耐性50で全成分が半減し、耐性100では適用されない。
     */
    @Test
    void arbitraryVelocityIsLinearlyReducedByKnockbackResistance() {
        MobService mobService = mock(MobService.class);
        MobEntityController controller = mock(MobEntityController.class);
        when(mobService.entityController()).thenReturn(controller);
        MobKnockbackService service = new MobKnockbackService(mobService);

        MobInstance zeroMob = mock(MobInstance.class);
        AstEntity zeroTarget = mock(AstEntity.class);
        when(zeroTarget.id()).thenReturn(UUID.randomUUID());
        when(zeroTarget.statValue(StatusType.KNOCKBACK_RESISTANCE)).thenReturn(0.0D);
        when(zeroTarget.isMob()).thenReturn(true);
        when(zeroTarget.mob()).thenReturn(zeroMob);

        service.applyVelocityWithResistance(zeroTarget, new Vector(0.16D, 0.04D, -0.08D));

        ArgumentCaptor<Vector> zeroVelocity = ArgumentCaptor.forClass(Vector.class);
        verify(controller).addVelocity(org.mockito.ArgumentMatchers.same(zeroMob), zeroVelocity.capture());
        assertEquals(0.16D, zeroVelocity.getValue().getX(), 1.0E-9D);
        assertEquals(0.04D, zeroVelocity.getValue().getY(), 1.0E-9D);
        assertEquals(-0.08D, zeroVelocity.getValue().getZ(), 1.0E-9D);

        MobInstance halfMob = mock(MobInstance.class);
        AstEntity halfTarget = mock(AstEntity.class);
        when(halfTarget.id()).thenReturn(UUID.randomUUID());
        when(halfTarget.statValue(StatusType.KNOCKBACK_RESISTANCE)).thenReturn(50.0D);
        when(halfTarget.isMob()).thenReturn(true);
        when(halfTarget.mob()).thenReturn(halfMob);

        service.applyVelocityWithResistance(halfTarget, new Vector(0.16D, 0.04D, -0.08D));

        ArgumentCaptor<Vector> velocity = ArgumentCaptor.forClass(Vector.class);
        verify(controller).addVelocity(org.mockito.ArgumentMatchers.same(halfMob), velocity.capture());
        assertEquals(0.08D, velocity.getValue().getX(), 1.0E-9D);
        assertEquals(0.02D, velocity.getValue().getY(), 1.0E-9D);
        assertEquals(-0.04D, velocity.getValue().getZ(), 1.0E-9D);

        MobInstance immuneMob = mock(MobInstance.class);
        AstEntity immuneTarget = mock(AstEntity.class);
        when(immuneTarget.id()).thenReturn(UUID.randomUUID());
        when(immuneTarget.statValue(StatusType.KNOCKBACK_RESISTANCE)).thenReturn(100.0D);
        when(immuneTarget.isMob()).thenReturn(true);
        when(immuneTarget.mob()).thenReturn(immuneMob);

        service.applyVelocityWithResistance(immuneTarget, new Vector(0.16D, 0.04D, -0.08D));

        verify(controller, never()).addVelocity(
                org.mockito.ArgumentMatchers.same(immuneMob),
                org.mockito.ArgumentMatchers.any(Vector.class)
        );
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/12-mob/3-メソッド仕様/12_3-戦闘.md
     * 章・見出し: # 12_3-戦闘 > ## 2. MobKnockbackService メソッド仕様 > ### ノックバック適用
     * 検証契約: 追加multiplierでbase knockbackを比例scaleする。
     */
    @Test
    void additionalMultiplierScalesNormalKnockback() {
        MobService mobService = mock(MobService.class);
        MobEntityController controller = mock(MobEntityController.class);
        MobInstance mob = mock(MobInstance.class);
        AstEntity source = mock(AstEntity.class);
        AstEntity target = mock(AstEntity.class);
        World world = mock(World.class);
        when(mobService.entityController()).thenReturn(controller);
        when(source.location()).thenReturn(new Location(world, 0.0D, 0.0D, 0.0D));
        when(target.location()).thenReturn(new Location(world, 0.0D, 0.0D, 2.0D));
        when(mob.currentLocation()).thenReturn(new Location(world, 0.0D, 0.0D, 2.0D));
        when(target.isMob()).thenReturn(true);
        when(target.mob()).thenReturn(mob);
        when(target.id()).thenReturn(UUID.randomUUID());

        MobKnockbackService service = new MobKnockbackService(mobService);
        service.setAdditionalKnockbackMultiplier(ignored -> 0.5D);
        service.apply(source, target, 1.0D);

        ArgumentCaptor<Vector> velocity = ArgumentCaptor.forClass(Vector.class);
        verify(controller).addVelocity(org.mockito.ArgumentMatchers.same(mob), velocity.capture());
        assertEquals(0.0D, velocity.getValue().getX(), 1.0E-9D);
        assertEquals(0.2D, velocity.getValue().getY(), 1.0E-9D);
        assertEquals(0.2D, velocity.getValue().getZ(), 1.0E-9D);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/12-mob/3-メソッド仕様/12_3-戦闘.md
     * 章・見出し: # 12_3-戦闘 > ## 2. MobKnockbackService メソッド仕様 > ### ノックバック適用
     * 検証契約: 同一対象へのノックバックは4 tickの受付クールダウンで抑制し、通常攻撃と指定強度の経路で共有する。
     */
    @Test
    void sameTargetKnockbackIsSuppressedForCooldownTicks() {
        MobService mobService = mock(MobService.class);
        MobEntityController controller = mock(MobEntityController.class);
        MobInstance mob = mock(MobInstance.class);
        AstEntity source = mock(AstEntity.class);
        AstEntity target = mock(AstEntity.class);
        World world = mock(World.class);
        AtomicLong currentTick = new AtomicLong(100L);
        UUID targetId = UUID.randomUUID();
        when(mobService.entityController()).thenReturn(controller);
        when(source.location()).thenReturn(new Location(world, 0.0D, 0.0D, 0.0D));
        when(target.location()).thenReturn(new Location(world, 0.0D, 0.0D, 2.0D));
        when(mob.currentLocation()).thenReturn(new Location(world, 0.0D, 0.0D, 2.0D));
        when(target.id()).thenReturn(targetId);
        when(target.statValue(StatusType.KNOCKBACK_RESISTANCE)).thenReturn(0.0D);
        when(target.isMob()).thenReturn(true);
        when(target.mob()).thenReturn(mob);

        MobKnockbackService service = new MobKnockbackService(mobService, currentTick::get);
        service.apply(source, target, 1.0D);
        service.applyWithStrength(target, new Location(world, 0.0D, 0.0D, 0.0D), 1.0D, 0.5D);
        verify(controller, org.mockito.Mockito.times(1)).addVelocity(
                org.mockito.ArgumentMatchers.same(mob), org.mockito.ArgumentMatchers.any(Vector.class)
        );

        currentTick.addAndGet(MobKnockbackService.KNOCKBACK_COOLDOWN_TICKS - 1L);
        service.applyWithStrength(target, new Location(world, 0.0D, 0.0D, 0.0D), 1.0D, 0.5D);
        verify(controller, org.mockito.Mockito.times(1)).addVelocity(
                org.mockito.ArgumentMatchers.same(mob), org.mockito.ArgumentMatchers.any(Vector.class)
        );

        currentTick.incrementAndGet();
        service.applyWithStrength(target, new Location(world, 0.0D, 0.0D, 0.0D), 1.0D, 0.5D);
        verify(controller, org.mockito.Mockito.times(2)).addVelocity(
                org.mockito.ArgumentMatchers.same(mob), org.mockito.ArgumentMatchers.any(Vector.class)
        );
    }
}
