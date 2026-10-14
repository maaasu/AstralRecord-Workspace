package io.github.maaasu.astralRecord.feature.mob.skill.field;

import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

/** B03専用の一回限り診断。Plugin test sourceや恒久JUnitには登録しません。 */
public final class B03GeometryCheck {
    /** 実Executorの純粋な当たり判定を呼び、横回避・背後・端点・入力保全を検査します。 */
    public static void main(String[] args) {
        Vector origin = new Vector(0, 0.7, 0);
        Vector forward = new Vector(1, 0, 0);
        BoundingBox front = body(3.2, 0);
        check(PlainRamMobSkillExecutor.hitPosition(front, origin, forward) != null, "ram front");
        check(PlainRamMobSkillExecutor.hitPosition(body(3, 0.9), origin, forward) == null, "ram sidestep");
        check(PlainRamMobSkillExecutor.hitPosition(body(-1.5, 0), origin, forward) == null, "ram behind");
        check(PlainRamMobSkillExecutor.hitPosition(body(4.4, 0), origin, forward) == null, "ram beyond reach");
        check(PlainRamMobSkillExecutor.hitPosition(body(0, 0), origin, forward).equals(origin), "ram overlapping");
        check(PlainRamMobSkillExecutor.hitPosition(new BoundingBox(2, 2, -.3, 2.6, 4, .3), origin,
                forward) == null, "ram above");
        check(front.equals(body(3.2, 0)) && origin.equals(new Vector(0, .7, 0))
                && forward.equals(new Vector(1, 0, 0)), "ram inputs preserved");
        Vector center = new Vector(0, 9, 0);
        check(BladeMantisMobSkillExecutor.intersectsSweep(body(3, 0), center, forward), "mantis front");
        check(BladeMantisMobSkillExecutor.intersectsSweep(body(2, 2), center, forward), "mantis diagonal");
        check(!BladeMantisMobSkillExecutor.intersectsSweep(body(0, 3), center, forward), "mantis side");
        check(!BladeMantisMobSkillExecutor.intersectsSweep(body(-2, 0), center, forward), "mantis behind");
        check(!BladeMantisMobSkillExecutor.intersectsSweep(body(4.3, 0), center, forward), "mantis far");
        check(BladeMantisMobSkillExecutor.intersectsSweep(body(3.8, 0), center, forward), "mantis near hitbox edge");
        check(BladeMantisMobSkillExecutor.intersectsSweep(body(0, 0), center, forward), "mantis overlap");
        check(BladeMantisMobSkillExecutor.intersectsSweep(body(-3, 0), center, new Vector(-1, 0, 0)),
                "mantis opposite aim");
        check(center.equals(new Vector(0, 9, 0)) && forward.equals(new Vector(1, 0, 0)), "mantis inputs preserved");
        System.out.println("PASS: B03 real Executor geometry, 16 checks; no server or master fixture loaded.");
    }

    /** 標準幅0.6mの水平hitboxを診断fixtureとして返します。 */
    private static BoundingBox body(double x, double z) {
        return new BoundingBox(x - .3, 0, z - .3, x + .3, 1.8, z + .3);
    }

    /** 失敗時に診断名を例外で返します。 */
    private static void check(boolean ok, String name) {
        if (!ok) throw new AssertionError(name);
    }
}
