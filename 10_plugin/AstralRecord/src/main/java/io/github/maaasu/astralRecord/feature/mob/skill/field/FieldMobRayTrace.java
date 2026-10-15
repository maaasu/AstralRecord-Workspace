package io.github.maaasu.astralRecord.feature.mob.skill.field;

import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.Nullable;

/** フィールド技の射線を、読込済みchunkだけで判定します。 */
final class FieldMobRayTrace {
    private FieldMobRayTrace() { }

    /** Bukkitの3引数版と同じ液体・通過可能block契約で判定します。 */
    static @Nullable RayTraceResult trace(World world, Location start, Vector direction, double distance) {
        return trace(world, start, direction, distance, FluidCollisionMode.NEVER, false);
    }

    /** 未読込候補があれば始点を遮蔽物として返し、block APIを呼びません。 */
    static @Nullable RayTraceResult trace(World world, Location start, Vector direction, double distance,
            FluidCollisionMode fluids, boolean ignorePassable) {
        if (world == null || start.getWorld() != world || !Double.isFinite(distance) || distance < 0
                || distance > 32 || !Double.isFinite(direction.lengthSquared()) || direction.lengthSquared() == 0) {
            return new RayTraceResult(start.toVector());
        }
        Location end = start.clone().add(direction.clone().normalize().multiply(distance));
        // 全交差chunkを含む保守的な矩形。技の有限射程では最大数chunkだけです。
        for (int x = Math.min(start.getBlockX(), end.getBlockX()) >> 4;
                x <= (Math.max(start.getBlockX(), end.getBlockX()) >> 4); x++) {
            for (int z = Math.min(start.getBlockZ(), end.getBlockZ()) >> 4;
                    z <= (Math.max(start.getBlockZ(), end.getBlockZ()) >> 4); z++) {
                if (!world.isChunkLoaded(x, z)) return new RayTraceResult(start.toVector());
            }
        }
        return world.rayTraceBlocks(start, direction, distance, fluids, ignorePassable);
    }
}
