package io.github.maaasu.astralRecord.feature.mob.skill.field;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.combat.service.DamageService;
import io.github.maaasu.astralRecord.feature.condition.service.ConditionService;
import io.github.maaasu.astralRecord.feature.mob.model.MobSkillBinding;
import io.github.maaasu.astralRecord.feature.mob.model.MobSkillTiming;
import io.github.maaasu.astralRecord.feature.mob.model.MobState;
import io.github.maaasu.astralRecord.feature.mob.service.MobService;
import io.github.maaasu.astralRecord.feature.mob.skill.MobSkillContext;
import io.github.maaasu.astralRecord.feature.mob.skill.MobSkillExecutor;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.entity.Entity;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.Nullable;

/** B09だけが使う、読込済み地形・主対象・中断条件の境界です。地形を変更しません。 */
final class MesaFireAreaSupport {
    private MesaFireAreaSupport() { }

    /** paramsなし、射程は既定以下、CDは既定以上、Executor内部予告だけを許可します。 */
    static void validate(MobSkillExecutor executor, MobSkillBinding binding) {
        MobSkillTiming timing = executor.resolveTiming(binding);
        if (!binding.params().isEmpty() || !Double.isFinite(timing.activationRange())
                || timing.activationRange() < 0.5D || timing.activationRange() > executor.defaultTiming().activationRange()
                || timing.cooldownTicks() < executor.defaultTiming().cooldownTicks() || timing.castTimeTicks() != 0L) {
            throw new IllegalArgumentException("Invalid binding for " + executor.id());
        }
    }

    /** 生存・管理個体・COMBAT・主対象・同一world・行動可能を再検査します。 */
    static @Nullable Entity activeCaster(MobSkillContext context, MobService mobs, DamageService damage,
                                         ConditionService conditions) {
        var mob = context.mob();
        AstEntity actor = AstEntity.mob(mob);
        if (!mobs.plugin().isEnabled() || mobs.getInstance(mob.instanceId()) != mob
                || mob.currentHealth() <= 0.0D || mob.state() != MobState.COMBAT
                || !context.target().getUniqueId().equals(mob.targetId())
                || !damage.isMobCombatTarget(context.target()) || !conditions.canRunAi(actor)
                || !conditions.canCastSkill(actor) || !conditions.canAttack(actor)) return null;
        Entity caster = mobs.entityController().getEntity(mob);
        return caster != null && caster.isValid() && !caster.isDead()
                && caster.getWorld() == context.origin().getWorld() && caster.getWorld() == context.target().getWorld()
                && loadedInside(caster.getLocation()) && loadedInside(context.target().getLocation()) ? caster : null;
    }

    /** 高低差1.25mと水平距離で地上戦の射程を判定します。 */
    static boolean inRange(Location from, Location to, double range) {
        return from.getWorld() == to.getWorld() && Math.abs(from.getY() - to.getY()) <= 1.25D
                && horizontalSquared(from, to) <= range * range;
    }

    /** ワールド高さ・境界・読込済みchunkを検査し、chunkをロードしません。 */
    static boolean loadedInside(Location point) {
        World world = point.getWorld();
        return world != null && point.getY() >= world.getMinHeight() && point.getY() < world.getMaxHeight()
                && world.isChunkLoaded(point.getBlockX() >> 4, point.getBlockZ() >> 4)
                && world.getWorldBorder().isInside(point);
    }

    /** 足元の衝突面を最大3m下まで探します。液体や未読込chunkは足場にしません。 */
    static @Nullable Location ground(Location feet) {
        if (!loadedInside(feet)) return null;
        World world = feet.getWorld();
        for (int y = feet.getBlockY(); y >= Math.max(world.getMinHeight(), feet.getBlockY() - 3); y--) {
            var block = world.getBlockAt(feet.getBlockX(), y, feet.getBlockZ());
            if (containsLiquid(block)) return null;
            if (!block.isPassable()) {
                Location result = new Location(world, feet.getX(), block.getBoundingBox().getMaxY(), feet.getZ());
                return loadedInside(result) && world.getBlockAt(result.getBlockX(), result.getBlockY(), result.getBlockZ()).isPassable()
                        ? result : null;
            }
        }
        return null;
    }

    /** 全線分を0.25m以下で検査してからblock rayを使います。 */
    static boolean clearRay(Location from, Location to) {
        if (from.getWorld() == null || from.getWorld() != to.getWorld()) return false;
        // chunk角を短くかすめる線分も逃さず検査します。各呼出の射程は最大10mです。
        for (int x = Math.min(from.getBlockX(), to.getBlockX()) >> 4;
                x <= (Math.max(from.getBlockX(), to.getBlockX()) >> 4); x++) {
            for (int z = Math.min(from.getBlockZ(), to.getBlockZ()) >> 4;
                    z <= (Math.max(from.getBlockZ(), to.getBlockZ()) >> 4); z++) {
                if (!from.getWorld().isChunkLoaded(x, z)) return false;
            }
        }
        Vector delta = to.toVector().subtract(from.toVector());
        int steps = Math.max(1, (int) Math.ceil(delta.length() / 0.25D));
        for (int i = 0; i <= steps; i++) {
            Location sample = from.clone().add(delta.clone().multiply((double) i / steps));
            if (!loadedInside(sample)) return false;
        }
        // 点間隔より短いブロック角の通過も、voxel traversalで液体を検査します。
        if (!liquidFree(from, to)) return false;
        return delta.lengthSquared() < 0.0001D || from.getWorld().rayTraceBlocks(from, delta, delta.length(),
                FluidCollisionMode.ALWAYS, true) == null;
    }

    /** 線分が通る各ブロックをDDAで順に検査します。最長10mの呼出に閉じた有限走査です。 */
    private static boolean liquidFree(Location from, Location to) {
        int x = from.getBlockX(), y = from.getBlockY(), z = from.getBlockZ();
        int endX = to.getBlockX(), endY = to.getBlockY(), endZ = to.getBlockZ();
        double dx = to.getX() - from.getX(), dy = to.getY() - from.getY(), dz = to.getZ() - from.getZ();
        int sx = (int) Math.signum(dx), sy = (int) Math.signum(dy), sz = (int) Math.signum(dz);
        double tx = dx == 0.0D ? Double.POSITIVE_INFINITY : (x + (sx > 0 ? 1 : 0) - from.getX()) / dx;
        double ty = dy == 0.0D ? Double.POSITIVE_INFINITY : (y + (sy > 0 ? 1 : 0) - from.getY()) / dy;
        double tz = dz == 0.0D ? Double.POSITIVE_INFINITY : (z + (sz > 0 ? 1 : 0) - from.getZ()) / dz;
        double stepX = dx == 0.0D ? Double.POSITIVE_INFINITY : Math.abs(1.0D / dx);
        double stepY = dy == 0.0D ? Double.POSITIVE_INFINITY : Math.abs(1.0D / dy);
        double stepZ = dz == 0.0D ? Double.POSITIVE_INFINITY : Math.abs(1.0D / dz);
        int limit = Math.abs(endX - x) + Math.abs(endY - y) + Math.abs(endZ - z) + 4;
        World world = from.getWorld();
        for (int i = 0; i < limit; i++) {
            if (y < world.getMinHeight() || y >= world.getMaxHeight() || !world.isChunkLoaded(x >> 4, z >> 4)
                    || containsLiquid(world.getBlockAt(x, y, z))) return false;
            if (x == endX && y == endY && z == endZ) return true;
            double next = Math.min(tx, Math.min(ty, tz));
            if (tx <= next) { x += sx; tx += stepX; }
            if (ty <= next) { y += sy; ty += stepY; }
            if (tz <= next) { z += sz; tz += stepZ; }
        }
        return false;
    }

    /** waterloggedの空洞も液体として拒否します。 */
    private static boolean containsLiquid(Block block) {
        return block.isLiquid() || block.getBlockData() instanceof Waterlogged data && data.isWaterlogged();
    }

    /** 胴体の線分を確認し、壁越しの発動/命中を防ぎます。 */
    static boolean clearPath(Location from, Location to) {
        return clearRay(from.clone().add(0.0D, 0.5D, 0.0D), to.clone().add(0.0D, 0.5D, 0.0D));
    }

    /** 水平距離の二乗を返します。同一worldは呼出側で確認します。 */
    static double horizontalSquared(Location from, Location to) {
        double x = from.getX() - to.getX(), z = from.getZ() - to.getZ();
        return x * x + z * z;
    }
}
