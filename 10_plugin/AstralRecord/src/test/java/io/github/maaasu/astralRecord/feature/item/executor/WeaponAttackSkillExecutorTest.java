package io.github.maaasu.astralRecord.feature.item.executor;

import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.combat.service.DamageService;
import io.github.maaasu.astralRecord.feature.mob.model.MobInstance;
import io.github.maaasu.astralRecord.shared.effect.ParticleDisplayService;
import io.github.maaasu.astralRecord.support.DesignTestFixtures;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WeaponAttackSkillExecutorTest extends MockBukkitTestBase {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/13_2-ユースケース.md
     * 章・見出し: # 13_2-ユースケース > ## 5. Mob がスキルを発動する
     * 検証契約: projectile着弾範囲は最初の命中対象を保持し、追加対象を距離順にmaxTargetsまで選ぶ。
     */
    @Test
    void projectileImpactKeepsPrimaryAndCapsNearestAdditionalTargets() {
        DamageService damageService = mock(DamageService.class);
        WeaponAttackSkillExecutor executor = new WeaponAttackSkillExecutor(
            mock(ParticleDisplayService.class),
            damageService
        );
        World world = mock(World.class);
        Location center = new Location(world, 0.0D, 64.0D, 0.0D);
        AstEntity attacker = AstEntity.player(DesignTestFixtures.astPlayer(
            server().addPlayer(),
            AccountMode.PLAYER
        ));
        MobInstance primaryMob = mobAt(world, 0.5D);
        MobInstance nearMob = mobAt(world, 1.0D);
        MobInstance farMob = mobAt(world, 3.0D);
        AstEntity primary = AstEntity.mob(primaryMob);
        AstEntity near = AstEntity.mob(nearMob);
        AstEntity far = AstEntity.mob(farMob);
        Entity nearEntity = mock(Entity.class);
        Entity farEntity = mock(Entity.class);
        when(world.getNearbyEntities(center, 4.0D, 4.0D, 4.0D)).thenReturn(List.of(farEntity, nearEntity));
        when(damageService.resolveEntity(nearEntity)).thenReturn(near);
        when(damageService.resolveEntity(farEntity)).thenReturn(far);

        List<AstEntity> targets = executor.findProjectileImpactTargets(
            center,
            4.0D,
            2,
            attacker,
            primary
        );

        assertEquals(2, targets.size());
        assertSame(primary, targets.getFirst());
        assertSame(near, targets.get(1));
    }

    private MobInstance mobAt(World world, double x) {
        MobInstance mob = DesignTestFixtures.mobInstance(100.0D, 0.0D, 0.0D);
        mob.currentLocation(new Location(world, x, 64.0D, 0.0D));
        return mob;
    }
}
