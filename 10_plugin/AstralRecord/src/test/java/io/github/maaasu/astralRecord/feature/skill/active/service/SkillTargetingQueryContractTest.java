package io.github.maaasu.astralRecord.feature.skill.active.service;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.mob.model.MobInstance;
import io.github.maaasu.astralRecord.feature.mob.model.MobState;
import io.github.maaasu.astralRecord.feature.mob.model.MobTemplate;
import io.github.maaasu.astralRecord.feature.mob.service.MobService;
import io.github.maaasu.astralRecord.support.DesignTestFixtures;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** サーバーを起動せず、共通対象検索の順序・距離定義・再評価の不変条件を検証します。 */
class SkillTargetingQueryContractTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-サービス.md
     * 章・見出し: # 13_3-サービス > ## 9. active skill 共通支援
     * 検証契約: 線分・円柱・球体の同距離候補は入力順によらずinstance UUID順で対象数上限まで選ぶ。
     */
    @Test
    void equallyDistantCandidatesUseUuidOrderBeforeApplyingLimit() {
        Fixture fixture = new Fixture();
        MobInstance first = fixture.mob(1L, 0.0D, 4.0D);
        MobInstance second = fixture.mob(2L, 0.0D, 4.0D);
        when(fixture.mobs.getInstances()).thenReturn(List.of(second, first));
        List<UUID> expected = List.of(first.instanceId());

        assertEquals(expected, ids(fixture.service.inLineBeforeBlock(
                fixture.player, fixture.origin, new Vector(0.0D, 0.0D, 1.0D), 10.0D, 0.0D, 1)));
        assertEquals(expected, ids(fixture.service.inRadius(fixture.player, fixture.origin, 10.0D, 3.0D, 1, false)));
        assertEquals(expected, ids(fixture.service.inSphere(fixture.player, fixture.origin, 10.0D, 1, false)));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-サービス.md
     * 章・見出し: # 13_3-サービス > ## 9. active skill 共通支援
     * 検証契約: 対象検索間で移動したMobは同じtickでも新しいbody boundsから範囲内外を再評価する。
     */
    @Test
    void subsequentQueriesObserveMovementWithoutWaitingForNextTick() {
        Fixture fixture = new Fixture();
        MobInstance mob = fixture.mob(1L, 0.0D, 4.0D);
        when(fixture.mobs.getInstances()).thenReturn(List.of(mob));
        assertEquals(List.of(mob.instanceId()), ids(fixture.service.inSphere(
                fixture.player, fixture.origin, 5.0D, 8, false)));

        when(mob.currentLocation()).thenReturn(new Location(fixture.world, 0.0D, 0.0D, 20.0D));
        assertTrue(fixture.service.inSphere(fixture.player, fixture.origin, 5.0D, 8, false).isEmpty());
        assertTrue(fixture.service.inRadius(fixture.player, fixture.origin, 5.0D, 3.0D, 8, false).isEmpty());
        assertTrue(fixture.service.inLineBeforeBlock(fixture.player, fixture.origin,
                new Vector(0.0D, 0.0D, 1.0D), 5.0D, 0.0D, 8).isEmpty());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-サービス.md
     * 章・見出し: # 13_3-サービス > ## 9. active skill 共通支援
     * 検証契約: 円柱は水平中心距離、球体はbody boundsまでの最短距離を順位の正本にする。
     */
    @Test
    void cylinderAndSphereKeepTheirDifferentDistanceDefinitions() {
        Fixture fixture = new Fixture();
        MobInstance elevated = fixture.mob(1L, 0.0D, 1.0D);
        when(elevated.currentLocation()).thenReturn(new Location(fixture.world, 0.0D, 4.0D, 1.0D));
        MobInstance level = fixture.mob(2L, 0.0D, 2.0D);
        when(fixture.mobs.getInstances()).thenReturn(List.of(level, elevated));

        assertEquals(List.of(elevated.instanceId()), ids(fixture.service.inRadius(
                fixture.player, fixture.origin, 10.0D, 10.0D, 1, false)));
        assertEquals(List.of(level.instanceId()), ids(fixture.service.inSphere(
                fixture.player, fixture.origin, 10.0D, 1, false)));
    }

    private static List<UUID> ids(List<AstEntity> targets) {
        return targets.stream().map(AstEntity::id).toList();
    }

    private static final class Fixture {
        private final World world = mock(World.class);
        private final Player player = mock(Player.class);
        private final MobService mobs = mock(MobService.class);
        private final MobTemplate template = DesignTestFixtures.mobInstance(100.0D, 0.0D, 0.0D).template();
        private final SkillTargetingService service = new SkillTargetingService(mobs);
        private final Location origin = new Location(world, 0.0D, 1.0D, 0.0D);

        private Fixture() {
            when(player.getWorld()).thenReturn(world);
        }

        private MobInstance mob(long id, double x, double z) {
            MobInstance mob = mock(MobInstance.class);
            when(mob.state()).thenReturn(MobState.IDLE);
            when(mob.template()).thenReturn(template);
            when(mob.instanceId()).thenReturn(new UUID(0L, id));
            when(mob.bukkitEntityId()).thenReturn(null);
            when(mob.currentLocation()).thenReturn(new Location(world, x, 0.0D, z));
            return mob;
        }
    }
}
