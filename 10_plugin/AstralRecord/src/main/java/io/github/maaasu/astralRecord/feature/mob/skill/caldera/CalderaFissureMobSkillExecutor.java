package io.github.maaasu.astralRecord.feature.mob.skill.caldera;

import io.github.maaasu.astralRecord.feature.mob.model.MobSkillBinding;
import io.github.maaasu.astralRecord.feature.mob.model.MobSkillTiming;
import io.github.maaasu.astralRecord.feature.mob.skill.MobSkillContext;
import io.github.maaasu.astralRecord.feature.mob.skill.MobSkillExecutor;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import java.util.*;

/**
 * {@code mob_caldera_fissure}: 固定した前方矩形を赤橙予告後、一斉噴火します。
 * 任意params: damageRatio=0.65（0.01～2）、length=9m（2～12）、width=2m全幅（0.5～3）、
 * warningTicks=20（2～60の偶数）。既定通常詠唱20tickの後に独立した床予告を行います。
 * 壁で予告と判定の長さを同時制限し、高低差1.25m以内の有効対象へ一回だけ魔法・炎ダメージ。
 * 最大13表示・64予告点・34更新・噴火表示8tick。地形を破壊しません。
 */
public final class CalderaFissureMobSkillExecutor implements MobSkillExecutor {
    public static final String SKILL_ID = "mob_caldera_fissure";
    private static final Set<String> KEYS = Set.of("damageRatio", "length", "width", "warningTicks");
    private final CalderaSkillSupport support;
    /** @param support 同期管理・地形・ダメージ・描画の依存先 */
    public CalderaFissureMobSkillExecutor(CalderaSkillSupport support) { this.support = support; }
    /** @return Mob用ID */
    @Override public @NotNull String id() { return SKILL_ID; }
    /** @return 頭上詠唱名 */
    @Override public @NotNull String displayName() { return "溶岩の裂け目"; }
    /** @return 射程9m・再使用120tick・通常詠唱20tick */
    @Override public @NotNull MobSkillTiming defaultTiming() { return new MobSkillTiming(9, 120, 20); }
    /** @param binding 数値params @throws IllegalArgumentException 未知キー・範囲外・非偶数tick */
    @Override public void validate(@NotNull MobSkillBinding binding) {
        var p = binding.params();
        if (!KEYS.containsAll(p.keySet())) throw new IllegalArgumentException("Unsupported parameter for " + SKILL_ID);
        CalderaSkillSupport.parameter(p, "damageRatio", .65, .01, 2, false);
        CalderaSkillSupport.parameter(p, "length", 9, 2, 12, false);
        CalderaSkillSupport.parameter(p, "width", 2, .5, 3, false);
        CalderaSkillSupport.parameter(p, "warningTicks", 20, 2, 60, true);
    }
    /** @param context 固定原点と方向 @return 安全な地上矩形を開始した場合true */
    @Override public boolean cast(@NotNull MobSkillContext context) {
        if (!support.active(context)) return false;
        Location origin = support.ground(context.origin());
        Vector forward = context.direction().clone().setY(0);
        if (origin == null || forward.lengthSquared() < .001) return false;
        forward.normalize();
        Vector side = new Vector(-forward.getZ(), 0, forward.getX());
        var p = context.binding().params();
        double requested = p.getOrDefault("length", 9D), halfWidth = p.getOrDefault("width", 2D) / 2;
        for (int i = 0; i <= Math.ceil(requested); i++) {
            for (double offset : new double[]{-halfWidth, 0, halfWidth}) {
                Location check = origin.clone().add(forward.clone().multiply(i)).add(side.clone().multiply(offset));
                if (!origin.getWorld().isChunkLoaded(check.getBlockX() >> 4, check.getBlockZ() >> 4)) return false;
            }
        }
        double resolved = requested;
        for (double offset : new double[]{-halfWidth, 0, halfWidth}) {
            Location start = origin.clone().add(side.clone().multiply(offset)).add(0, .5, 0);
            var hit = origin.getWorld().rayTraceBlocks(start, forward, requested, FluidCollisionMode.NEVER, true);
            if (hit != null) resolved = Math.min(resolved, start.toVector().distance(hit.getHitPosition()));
        }
        final double length = Math.max(0, resolved < requested ? resolved - .1D : resolved);
        if (length < 1) return false;
        List<Location> centers = new ArrayList<>(13);
        List<Location> points = new ArrayList<>(64);
        int steps = (int) Math.ceil(length);
        for (int i = 0; i <= steps; i++) {
            Location center = origin.clone().add(forward.clone().multiply(length * i / steps));
            Location ground = support.ground(center);
            if (ground == null || Math.abs(ground.getY() - origin.getY()) > .25D) return false;
            centers.add(center);
            points.add(center.clone().add(side.clone().multiply(halfWidth)).add(0, .08, 0));
            points.add(center.clone().add(side.clone().multiply(-halfWidth)).add(0, .08, 0));
        }
        for (int i = 1; i < 4; i++) {
            double offset = halfWidth * (i / 2D - 1D);
            points.add(origin.clone().add(side.clone().multiply(offset)).add(0, .08, 0));
            points.add(origin.clone().add(forward.clone().multiply(length)).add(side.clone().multiply(offset)).add(0, .08, 0));
        }
        int warning = p.getOrDefault("warningTicks", 20D).intValue();
        double ratio = p.getOrDefault("damageRatio", .65);
        List<BlockDisplay> displays = new ArrayList<>(13);
        Map<BlockDisplay, Location> eruptionTops = new LinkedHashMap<>();
        try {
            if (!support.begin(context, displays, elapsed -> {
                if (elapsed < warning) { if (elapsed % 4 == 0) support.warning(origin, points); return true; }
                if (elapsed == warning) {
                    for (Location center : centers) {
                        double clearance = support.clearance(center, 1.5D);
                        float scale = (float) Math.min(Math.min(.8D, halfWidth * 2), clearance);
                        if (scale >= .2F) {
                            BlockDisplay display = support.display(center.clone().add(0, .05D, 0), scale, displays);
                            eruptionTops.put(display, center.clone().add(0, Math.min(1D, clearance), 0));
                        }
                    }
                    support.explosion(origin.clone().add(forward.clone().multiply(length / 2)));
                    Location middle = origin.clone().add(forward.clone().multiply(length / 2));
                    for (var target : support.targets(middle, length / 2 + halfWidth)) {
                        Vector delta = target.getLocation().toVector().subtract(origin.toVector());
                        double along = delta.dot(forward), across = delta.dot(side);
                        if (along >= 0 && along <= length && Math.abs(across) <= halfWidth)
                            support.hit(context, origin, target, ratio);
                    }
                }
                if (elapsed > warning && elapsed < warning + 8) {
                    for (var entry : eruptionTops.entrySet()) {
                        Location top = entry.getValue();
                        double rise = top.getY() - origin.getY();
                        entry.getKey().teleport(top.clone().subtract(0, rise * (1D - (elapsed - warning + 2) / 8D), 0));
                    }
                }
                return elapsed < warning + 8;
            })) return false;
            support.warning(origin, points);
            return true;
        } catch (RuntimeException exception) { support.clearCasterState(context.mob().instanceId()); throw exception; }
    }
}
