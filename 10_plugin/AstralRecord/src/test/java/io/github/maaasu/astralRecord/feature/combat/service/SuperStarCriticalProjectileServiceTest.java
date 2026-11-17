package io.github.maaasu.astralRecord.feature.combat.service;

import io.github.maaasu.astralRecord.feature.mob.model.MobInstance;
import io.github.maaasu.astralRecord.feature.mob.model.MobTemplate;
import io.github.maaasu.astralRecord.feature.mob.service.MobService;
import io.github.maaasu.astralRecord.shared.effect.ParticleDisplayService;
import io.github.maaasu.astralRecord.support.DesignTestFixtures;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SuperStarCriticalProjectileServiceTest {

    /**
     * 抽選した水平方位と指定仰角から初速方向を算出する。
     */
    @Test
    void initialDirectionUsesRequestedElevationAndAzimuth() {
        double elevation = Math.toRadians(30.0D);
        Vector direction = SuperStarCriticalProjectileService.initialDirection(0.0D, elevation);

        assertEquals(1.0D, direction.length(), 0.0001D);
        assertEquals(Math.cos(elevation), direction.getX(), 0.0001D);
        assertEquals(Math.sin(elevation), direction.getY(), 0.0001D);
        assertEquals(0.0D, direction.getZ(), 0.0001D);
    }

    /**
     * 曲率軸は向きの入力によらず進行方向に直交し、入力に応じて方向が変わる。
     */
    @Test
    void curvatureAxisUsesRequestedRandomDirectionAndStaysPerpendicular() {
        Vector direction = new Vector(1.0D, 1.0D, 0.0D).normalize();
        Vector first = SuperStarCriticalProjectileService.curvatureAxis(direction, 0.0D);
        Vector second = SuperStarCriticalProjectileService.curvatureAxis(direction, Math.PI / 2.0D);

        assertEquals(1.0D, first.length(), 0.0001D);
        assertEquals(0.0D, first.dot(direction), 0.0001D);
        assertEquals(0.0D, second.dot(direction), 0.0001D);
        assertNotEquals(first, second);
    }

    /**
     * 初期飛行の旋回は速度の大きさを変えない。
     */
    @Test
    void initialMovementCurvesWithoutChangingSpeed() {
        Vector velocity = new Vector(0.2D, 0.0D, 0.0D);
        Vector curved = SuperStarCriticalProjectileService.curvedVelocity(
                velocity,
                new Vector(0.0D, 1.0D, 0.0D),
                SuperStarCriticalProjectileService.INITIAL_CURVE_RADIANS_PER_TICK
        );

        assertEquals(velocity.length(), curved.length(), 0.0001D);
        assertTrue(curved.getZ() < 0.0D);
        assertNotEquals(velocity, curved);
    }

    /**
     * 追尾移動は設定された速度を保ち、目標方向への進行を続ける。
     */
    @Test
    void homingMovementMaintainsSpeedWhileMovingTowardTarget() {
        Vector targetOffset = new Vector(10.0D, 0.0D, 0.0D);
        Vector movement = SuperStarCriticalProjectileService.curvedHomingMovement(
                new Vector(0.0D, 0.0D, 0.2D),
                targetOffset,
                new Vector(0.0D, 1.0D, 0.0D),
                Math.PI / 2.0D
        );

        assertEquals(SuperStarCriticalProjectileService.HOMING_SPEED_PER_TICK, movement.length(), 0.0001D);
        assertTrue(movement.dot(targetOffset) > 0.0D);
    }

    /**
     * 逆向きと至近距離のどちらでも旋回上限を守り、残距離を超えて進まない。
     */
    @Test
    void homingMovementRespectsTurnAndTravelLimits() {
        assertHomingStep(new Vector(-10.0D, 0.0D, 0.0D), -Math.PI / 2.0D,
                SuperStarCriticalProjectileService.HOMING_SPEED_PER_TICK);
        Vector nearbyTarget = new Vector(0.1D, 0.1D, 0.0D);
        assertHomingStep(nearbyTarget, 0.0D, nearbyTarget.length());
    }

    private static void assertHomingStep(Vector targetOffset, double phase, double expectedDistance) {
        Vector current = new Vector(1.0D, 0.0D, 0.0D);
        Vector movement = SuperStarCriticalProjectileService.curvedHomingMovement(
                current, targetOffset, new Vector(0.0D, 1.0D, 0.0D), phase
        );

        double turn = Math.acos(current.dot(movement.clone().normalize()));

        assertEquals(expectedDistance, movement.length(), 0.0001D);
        assertEquals(SuperStarCriticalProjectileService.HOMING_MAX_TURN_RADIANS_PER_TICK, turn, 0.0001D);
    }

    /**
     * 正反対へ向いた追尾弾も、半径24 block内の目標へ100 tickの生存期間内に接触する。
     */
    @Test
    void homingMovementConvergesFromOppositeDirectionWithinLifetime() {
        World world = mock(World.class);
        MobInstance target = mobAt(world, -SuperStarCriticalProjectileService.TARGET_RADIUS,
                "00000000-0000-0000-0000-000000000001");
        SuperStarCriticalProjectileService service = serviceWithMobs(List.of(target));
        Location position = new Location(world, 0.0D, 0.9D, 0.0D);
        Vector targetCenter = new Vector(-SuperStarCriticalProjectileService.TARGET_RADIUS, 0.9D, 0.0D);
        Vector movement = new Vector(SuperStarCriticalProjectileService.HOMING_SPEED_PER_TICK, 0.0D, 0.0D);
        double phase = -Math.PI / 2.0D;
        boolean hit = false;

        for (int tick = SuperStarCriticalProjectileService.MAX_INITIAL_TICKS;
             tick < SuperStarCriticalProjectileService.LIFETIME_TICKS;
             tick++) {
            Vector targetOffset = targetCenter.clone().subtract(position.toVector());
            movement = SuperStarCriticalProjectileService.curvedHomingMovement(
                    movement,
                    targetOffset,
                    new Vector(0.0D, 1.0D, 0.0D),
                    phase
            );
            Location next = position.clone().add(movement);
            if (service.firstCollision(position, next, null) == target) {
                hit = true;
                break;
            }
            position = next;
            phase += SuperStarCriticalProjectileService.MIN_CURVE_PHASE_STEP;
        }

        assertTrue(hit);
    }

    /**
     * 生成元Mobの当たり判定から出る区間は除外し、同じ移動線分で新たに入ったMobを命中対象とする。
     */
    @Test
    void initialFlightCollisionSelectsEnteredMobButNotSpawnOverlap() {
        World world = mock(World.class);
        MobInstance spawnMob = mobAt(world, 0.0D, "00000000-0000-0000-0000-000000000001");
        MobInstance enteredMob = mobAt(world, 0.8D, "00000000-0000-0000-0000-000000000002");
        SuperStarCriticalProjectileService service = serviceWithMobs(List.of(spawnMob, enteredMob));

        MobInstance collision = service.firstCollision(
                new Location(world, 0.0D, 0.9D, 0.0D),
                new Location(world, 0.2D, 0.9D, 0.0D),
                spawnMob.instanceId()
        );

        assertSame(enteredMob, collision);
    }

    /**
     * 現在の追尾対象より手前で移動線分が接触した攻撃可能Mobを命中対象とする。
     */
    @Test
    void collisionSelectsMobBeforeCurrentSteeringTarget() {
        World world = mock(World.class);
        MobInstance steeringTarget = mobAt(world, 0.95D, "00000000-0000-0000-0000-000000000002");
        MobInstance firstContact = mobAt(world, 0.8D, "00000000-0000-0000-0000-000000000001");
        SuperStarCriticalProjectileService service = serviceWithMobs(List.of(steeringTarget, firstContact));

        MobInstance collision = service.firstCollision(
                new Location(world, 0.0D, 0.9D, 0.0D),
                new Location(world, SuperStarCriticalProjectileService.HOMING_SPEED_PER_TICK, 0.9D, 0.0D),
                null
        );

        assertSame(firstContact, collision);
    }

    /**
     * 生成元UUIDだけを接触除外し、同じ位置に重なる別UUIDの攻撃可能Mobは命中対象とする。
     */
    @Test
    void originExitExclusionDoesNotHideAnotherOverlappingMob() {
        World world = mock(World.class);
        MobInstance originVictim = mobAt(world, 0.0D, "00000000-0000-0000-0000-000000000001");
        MobInstance overlappingMob = mobAt(world, 0.0D, "00000000-0000-0000-0000-000000000002");
        SuperStarCriticalProjectileService service = serviceWithMobs(List.of(originVictim, overlappingMob));

        MobInstance collision = service.firstCollision(
                new Location(world, 0.0D, 0.9D, 0.0D),
                new Location(world, 0.2D, 0.9D, 0.0D),
                originVictim.instanceId()
        );

        assertSame(overlappingMob, collision);
    }

    /**
     * 生成元除外の終了後は、移動線分の開始点が攻撃可能Mob内なら即時に命中対象とする。
     */
    @Test
    void startingInsideMobAfterOriginExitIsImmediateCollision() {
        World world = mock(World.class);
        MobInstance overlappingMob = mobAt(world, 0.0D, "00000000-0000-0000-0000-000000000001");
        SuperStarCriticalProjectileService service = serviceWithMobs(List.of(overlappingMob));

        MobInstance collision = service.firstCollision(
                new Location(world, 0.0D, 0.9D, 0.0D),
                new Location(world, 0.2D, 0.9D, 0.0D),
                null
        );

        assertSame(overlappingMob, collision);
    }

    private static SuperStarCriticalProjectileService serviceWithMobs(List<MobInstance> mobs) {
        MobService mobService = mock(MobService.class);
        when(mobService.getInstances()).thenReturn(mobs);
        return new SuperStarCriticalProjectileService(
                mock(Plugin.class),
                mobService,
                mock(ParticleDisplayService.class)
        );
    }

    private static MobInstance mobAt(World world, double x, String instanceId) {
        MobTemplate template = DesignTestFixtures.mobInstance(100.0D, 0.0D, 0.0D).template();
        return new MobInstance(
                UUID.fromString(instanceId),
                template,
                new Location(world, x, 0.0D, 0.0D)
        );
    }
}
