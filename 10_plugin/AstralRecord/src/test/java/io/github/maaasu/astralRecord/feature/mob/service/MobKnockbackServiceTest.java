package io.github.maaasu.astralRecord.feature.mob.service;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.mob.model.MobInstance;
import io.github.maaasu.astralRecord.feature.status.model.StatusType;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

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

        MobKnockbackService service = new MobKnockbackService(mobService);
        service.setAdditionalKnockbackMultiplier(ignored -> 0.5D);
        service.apply(source, target, 1.0D);

        ArgumentCaptor<Vector> velocity = ArgumentCaptor.forClass(Vector.class);
        verify(controller).addVelocity(org.mockito.ArgumentMatchers.same(mob), velocity.capture());
        assertEquals(0.0D, velocity.getValue().getX(), 1.0E-9D);
        assertEquals(0.2D, velocity.getValue().getY(), 1.0E-9D);
        assertEquals(0.2D, velocity.getValue().getZ(), 1.0E-9D);
    }
}
