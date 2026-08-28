package io.github.maaasu.astralRecord.feature.item.executor;

import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.combat.service.DamageService;
import io.github.maaasu.astralRecord.feature.item.service.BuiltInWeaponAttackDefinitions;
import io.github.maaasu.astralRecord.feature.mob.model.MobInstance;
import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.shared.effect.ParticleDisplayService;
import io.github.maaasu.astralRecord.support.DesignTestFixtures;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WeaponAttackSkillExecutorTest extends MockBukkitTestBase {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/04_1-モデル定義.md
     * 章・見出し: # 04_1-モデル定義 > ## 4. カテゴリ固有定義 > ### 4.3 `ItemEquipment` > #### 武器通常攻撃
     * 検証契約: 8武器種のbuilt-in定義が固有ID・射程・表示材質・多段数を保持し、executor検証を通過する。
     */
    @Test
    void builtInWeaponAttacksExposeEightValidatedWeaponProfiles() {
        WeaponAttackSkillExecutor executor = new WeaponAttackSkillExecutor(
            mock(ParticleDisplayService.class),
            mock(DamageService.class)
        );
        Map<String, SkillDefinition> definitions = BuiltInWeaponAttackDefinitions.definitions().stream()
            .collect(java.util.stream.Collectors.toMap(SkillDefinition::getId, definition -> definition));

        assertEquals(8, definitions.size());
        BuiltInWeaponAttackDefinitions.definitions().forEach(definition ->
            assertDoesNotThrow(() -> executor.validateParams(definition))
        );
        assertEquals(2.45D, doubleParam(definitions, BuiltInWeaponAttackDefinitions.NORMAL_ATTACK_MELEE, "hitRange"));
        assertEquals(3.0D, doubleParam(definitions, BuiltInWeaponAttackDefinitions.NORMAL_ATTACK_HAMMER, "hitRange"));
        assertEquals(5.5D, doubleParam(definitions, BuiltInWeaponAttackDefinitions.NORMAL_ATTACK_SPEAR, "hitRange"));
        assertEquals(3, intParam(definitions, BuiltInWeaponAttackDefinitions.NORMAL_ATTACK_SPEAR, "hitCount"));
        assertEquals(15.75D, doubleParam(definitions, BuiltInWeaponAttackDefinitions.NORMAL_ATTACK_BOW, "hitRange"));
        assertEquals(10.5D, doubleParam(definitions, BuiltInWeaponAttackDefinitions.NORMAL_ATTACK_SHORTBOW, "hitRange"));
        assertEquals(23.1D, doubleParam(definitions, BuiltInWeaponAttackDefinitions.NORMAL_ATTACK_LONGBOW, "hitRange"));
        assertEquals("ARROW", param(definitions, BuiltInWeaponAttackDefinitions.NORMAL_ATTACK_BOW, "displayMaterial"));
        assertEquals(90.0D, doubleParam(definitions, BuiltInWeaponAttackDefinitions.NORMAL_ATTACK_BOW, "displayModelPitchDegrees"));
        assertEquals(45.0D, doubleParam(definitions, BuiltInWeaponAttackDefinitions.NORMAL_ATTACK_BOW, "displayModelRollDegrees"));
        assertEquals(45.0D, doubleParam(definitions, BuiltInWeaponAttackDefinitions.NORMAL_ATTACK_SHORTBOW, "displayModelRollDegrees"));
        assertEquals(45.0D, doubleParam(definitions, BuiltInWeaponAttackDefinitions.NORMAL_ATTACK_LONGBOW, "displayModelRollDegrees"));
        assertEquals("HORN_CORAL_BLOCK", param(definitions, BuiltInWeaponAttackDefinitions.NORMAL_ATTACK_WAND, "displayMaterial"));
        assertEquals("TUBE_CORAL_BLOCK", param(definitions, BuiltInWeaponAttackDefinitions.NORMAL_ATTACK_MAGIC, "displayMaterial"));
    }

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

    private Object param(Map<String, SkillDefinition> definitions, String skillId, String paramName) {
        SkillDefinition definition = definitions.get(skillId);
        assertNotNull(definition);
        return definition.getParams().get(paramName);
    }

    private double doubleParam(Map<String, SkillDefinition> definitions, String skillId, String paramName) {
        return ((Number) param(definitions, skillId, paramName)).doubleValue();
    }

    private int intParam(Map<String, SkillDefinition> definitions, String skillId, String paramName) {
        return ((Number) param(definitions, skillId, paramName)).intValue();
    }
}
