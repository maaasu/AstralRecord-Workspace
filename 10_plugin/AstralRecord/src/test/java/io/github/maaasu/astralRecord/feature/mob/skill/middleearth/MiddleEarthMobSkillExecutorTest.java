package io.github.maaasu.astralRecord.feature.mob.skill.middleearth;

import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.feature.account.model.AccountModel;
import io.github.maaasu.astralRecord.feature.combat.service.DamageService;
import io.github.maaasu.astralRecord.feature.condition.service.ConditionService;
import io.github.maaasu.astralRecord.feature.mob.model.MobSkillBinding;
import io.github.maaasu.astralRecord.feature.mob.service.MobProjectileService;
import io.github.maaasu.astralRecord.feature.mob.service.MobService;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.shared.effect.ParticleDisplayService;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

class MiddleEarthMobSkillExecutorTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/12-mob/12_6-Mobスキル追加ガイド.md
     * 章・見出し: # 12_6-Mobスキル追加ガイド > ## 4. ミドルアースの遺跡の専用契約
     * 検証契約: ミドルアースの専用Mobスキルは、マスタで公開した数値パラメーターだけを受け付ける。
     */
    @Test
    void acceptsOnlyDeclaredMiddleEarthParameters() {
        MiddleEarthPiglinRushMobSkillExecutor piglin = new MiddleEarthPiglinRushMobSkillExecutor(
                mock(MobService.class), mock(DamageService.class)
        );
        AllThingsElIceSphereMobSkillExecutor iceSphere = new AllThingsElIceSphereMobSkillExecutor(
                mock(DamageService.class), mock(ConditionService.class), mock(MobProjectileService.class)
        );
        AllThingsElChargeMobSkillExecutor charge = new AllThingsElChargeMobSkillExecutor(
                mock(MobService.class), mock(DamageService.class)
        );

        assertDoesNotThrow(() -> piglin.validate(binding(piglin.id(), Map.of("speed", 1.10D, "damageRatio", 0.90D))));
        assertDoesNotThrow(() -> iceSphere.validate(binding(iceSphere.id(), Map.of(
                "speed", 0.75D, "damageRatio", 0.70D, "hitRadius", 0.20D,
                "frozenDurationTicks", 20.0D, "spreadDegrees", 24.0D
        ))));
        assertDoesNotThrow(() -> charge.validate(binding(charge.id(), Map.of("speed", 1.40D, "damageRatio", 1.0D, "holdTicks", 20.0D))));
        assertThrows(IllegalArgumentException.class, () -> charge.validate(binding(charge.id(), Map.of("unexpected", 1.0D))));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/12-mob/12_6-Mobスキル追加ガイド.md
     * 章・見出し: # 12_6-Mobスキル追加ガイド > ## 4. ミドルアースの遺跡の専用契約
     * 検証契約: 万物のエルの突進回数は残HP割合の一の位を切り捨てて求める。
     */
    @Test
    void resolvesChargeCountFromHealthDecile() {
        assertEquals(2, AllThingsElChargeMobSkillExecutor.resolveChargeCount(80.0D, 100.0D));
        assertEquals(2, AllThingsElChargeMobSkillExecutor.resolveChargeCount(89.9D, 100.0D));
        assertEquals(9, AllThingsElChargeMobSkillExecutor.resolveChargeCount(15.0D, 100.0D));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/12-mob/12_6-Mobスキル追加ガイド.md
     * 章・見出し: # 12_6-Mobスキル追加ガイド > ## 6. 万象の聖域の専用契約
     * 検証契約: 万象の聖域の専用Mobスキルは、設計で公開した有限範囲の数値パラメーターだけを受け付ける。
     */
    @Test
    void acceptsOnlyDeclaredIluvatarSanctumParameters() {
        MobService mobService = mock(MobService.class);
        DamageService damageService = mock(DamageService.class);
        AinuaBiteRushMobSkillExecutor bite = new AinuaBiteRushMobSkillExecutor(mobService, damageService);
        AinurindaleFangWaveMobSkillExecutor fangs = new AinurindaleFangWaveMobSkillExecutor(
                mobService, damageService, mock(ParticleDisplayService.class)
        );
        IluvatarFireSphereMobSkillExecutor sphere = new IluvatarFireSphereMobSkillExecutor(
                damageService, mock(ConditionService.class), mock(MobProjectileService.class)
        );
        IluvatarFlameChargeMobSkillExecutor charge = new IluvatarFlameChargeMobSkillExecutor(mobService, damageService);

        assertDoesNotThrow(() -> bite.validate(binding(bite.id(), Map.of(
                "damageRatio", 0.28D, "biteRange", 2.2D, "stepDistance", 0.9D, "biteIntervalTicks", 6.0D
        ))));
        assertDoesNotThrow(() -> fangs.validate(binding(fangs.id(), Map.of(
                "damageRatio", 0.45D, "hitRadius", 1.25D, "laneSpacing", 1.8D, "waveIntervalTicks", 8.0D
        ))));
        assertDoesNotThrow(() -> sphere.validate(binding(sphere.id(), Map.of(
                "speed", 0.8D, "damageRatio", 0.65D, "hitRadius", 0.2D,
                "burningDurationTicks", 60.0D, "spreadDegrees", 22.0D
        ))));
        assertDoesNotThrow(() -> charge.validate(binding(charge.id(), Map.of(
                "speed", 1.45D, "damageRatio", 0.85D, "holdTicks", 16.0D
        ))));
        assertThrows(IllegalArgumentException.class, () -> fangs.validate(binding(fangs.id(), Map.of("unexpected", 1.0D))));
        assertThrows(IllegalArgumentException.class, () -> sphere.validate(binding(sphere.id(), Map.of("damageRatio", Double.NaN))));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/12-mob/12_6-Mobスキル追加ガイド.md
     * 章・見出し: # 12_6-Mobスキル追加ガイド > ## 6. 万象の聖域の専用契約
     * 検証契約: アイヌアは固定5回、アイヌリンダレは固定3波・各波3地点として攻撃列を構成する。
     */
    @Test
    void keepsFixedBiteAndFangWaveShapes() {
        assertEquals(5, AinuaBiteRushMobSkillExecutor.BITE_COUNT);
        assertEquals(3, AinurindaleFangWaveMobSkillExecutor.WAVE_COUNT);

        var centers = AinurindaleFangWaveMobSkillExecutor.waveCenters(
                new Location(null, 0.0D, 64.0D, 0.0D),
                new Vector(0.0D, 0.0D, 1.0D),
                2,
                1.8D
        );
        assertEquals(3, centers.size());
        assertEquals(8.0D, centers.get(1).getZ(), 0.0001D);
        assertEquals(-1.8D, centers.get(0).getX(), 0.0001D);
        assertEquals(1.8D, centers.get(2).getX(), 0.0001D);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/12-mob/12_6-Mobスキル追加ガイド.md
     * 章・見出し: # 12_6-Mobスキル追加ガイド > ## 6. 万象の聖域の専用契約
     * 検証契約: イルーヴァタールの反射火球は三次元照準を許可し、炎突進は万物のエルと同じ残HP帯の回数式を使う。
     */
    @Test
    void mirrorsAllThingsElTargetingAndChargeScaling() {
        IluvatarFireSphereMobSkillExecutor sphere = new IluvatarFireSphereMobSkillExecutor(
                mock(DamageService.class), mock(ConditionService.class), mock(MobProjectileService.class)
        );

        assertTrue(sphere.allowsVerticalTargeting());
        assertEquals(2, AllThingsElChargeMobSkillExecutor.resolveChargeCount(80.0D, 100.0D));
        assertEquals(9, AllThingsElChargeMobSkillExecutor.resolveChargeCount(15.0D, 100.0D));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/12-mob/12_6-Mobスキル追加ガイド.md
     * 章・見出し: # 12_6-Mobスキル追加ガイド > ## 6. 万象の聖域の専用契約
     * 検証契約: 連続炎突進は対象がoffline、死亡、world不一致になった時点で中断する。
     */
    @Test
    void rejectsUnavailableChargeTarget() {
        World casterWorld = mock(World.class);
        World otherWorld = mock(World.class);
        Entity entity = mock(Entity.class);
        Player target = mock(Player.class);
        Location targetSnapshot = new Location(casterWorld, 4.0D, 64.0D, 0.0D);
        UUID targetId = UUID.randomUUID();
        when(entity.getWorld()).thenReturn(casterWorld);
        when(target.getUniqueId()).thenReturn(targetId);
        when(target.isOnline()).thenReturn(true);
        when(target.isDead()).thenReturn(false);
        when(target.getWorld()).thenReturn(casterWorld);
        AstPlayer astPlayer = mock(AstPlayer.class);
        AccountModel account = mock(AccountModel.class);
        when(astPlayer.getAccount()).thenReturn(account);
        when(account.getMode()).thenReturn(AccountMode.PLAYER);

        try (var cache = mockStatic(AstPlayerCache.class)) {
            cache.when(() -> AstPlayerCache.get(target)).thenReturn(astPlayer);
            assertTrue(MiddleEarthRushMotion.isTargetAvailable(entity, target, targetSnapshot));

            when(target.getWorld()).thenReturn(otherWorld);
            assertFalse(MiddleEarthRushMotion.isTargetAvailable(entity, target, targetSnapshot));
            when(target.getWorld()).thenReturn(casterWorld);
            when(target.isOnline()).thenReturn(false);
            assertFalse(MiddleEarthRushMotion.isTargetAvailable(entity, target, targetSnapshot));
            when(target.isOnline()).thenReturn(true);
            when(target.isDead()).thenReturn(true);
            assertFalse(MiddleEarthRushMotion.isTargetAvailable(entity, target, targetSnapshot));
        }
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/12-mob/12_6-Mobスキル追加ガイド.md
     * 章・見出し: # 12_6-Mobスキル追加ガイド > ## 6. 万象の聖域の専用契約
     * 検証契約: アイヌリンダレの地走り魔法は各地点の三次元半径内だけに命中する。
     */
    @Test
    void fangWaveDoesNotHitAnotherFloor() {
        World world = mock(World.class);
        Location casterFeet = new Location(world, 0.0D, 64.0D, 0.0D);
        Location center = AinurindaleFangWaveMobSkillExecutor.waveCenters(
                casterFeet, new Vector(0.0D, 0.0D, 1.0D), 0, 1.8D
        ).get(1);

        assertTrue(AinurindaleFangWaveMobSkillExecutor.isWithinHitRadius(
                new Location(world, 0.0D, 64.0D, 3.0D), center, 1.25D
        ));
        assertFalse(AinurindaleFangWaveMobSkillExecutor.isWithinHitRadius(
                new Location(world, 0.0D, 67.0D, 3.0D), center, 1.25D
        ));
    }

    private MobSkillBinding binding(String id, Map<String, Double> params) {
        return new MobSkillBinding(id, null, null, null, params);
    }
}
