package io.github.maaasu.astralRecord.feature.mob.skill.caldera;

import io.github.maaasu.astralRecord.feature.mob.model.MobSkillBinding;
import io.github.maaasu.astralRecord.feature.mob.model.MobSkillTiming;
import io.github.maaasu.astralRecord.feature.mob.skill.MobSkillContext;
import io.github.maaasu.astralRecord.feature.mob.skill.MobSkillExecutor;
import org.bukkit.Location;
import org.bukkit.entity.BlockDisplay;
import org.jetbrains.annotations.NotNull;
import java.util.*;

/**
 * {@code mob_caldera_meteor}: 詠唱完了時の主対象足元を固定し、赤橙予告後に岩塊を落とします。
 * 任意params: damageRatio=0.9（0.01～2）、radius=3.5m（0.5～5）、
 * fallTicks=20（4～40の偶数）、height=12m（2～16）、warningTicks=20（0～60の偶数）。
 * warningTicksは通常詠唱と独立し、その後fallTicksで着弾。水平円・高低差1.25m・壁遮蔽で一対象一命中。
 * 低天井で高さを制限し、岩塊1個・最大50更新・予告24点。地形は変更しません。
 */
public final class CalderaMeteorMobSkillExecutor implements MobSkillExecutor {
    public static final String SKILL_ID = "mob_caldera_meteor";
    private static final Set<String> KEYS = Set.of("damageRatio", "radius", "fallTicks", "height", "warningTicks");
    private final CalderaSkillSupport support;

    /** @param support 同期effect管理・地形・戦闘・描画の依存先 */
    public CalderaMeteorMobSkillExecutor(CalderaSkillSupport support) { this.support = support; }
    /** @return Mob用ID */
    @Override public @NotNull String id() { return SKILL_ID; }
    /** @return 頭上詠唱名 */
    @Override public @NotNull String displayName() { return "降り注ぐ火山弾"; }
    /** @return 射程12m・再使用160tick・通常詠唱20tick */
    @Override public @NotNull MobSkillTiming defaultTiming() { return new MobSkillTiming(12, 160, 20); }
    /** @param binding 数値params @throws IllegalArgumentException 未知キー・範囲外・非偶数tick */
    @Override public void validate(@NotNull MobSkillBinding binding) {
        var p = binding.params();
        if (!KEYS.containsAll(p.keySet())) throw new IllegalArgumentException("Unsupported parameter for " + SKILL_ID);
        CalderaSkillSupport.parameter(p, "damageRatio", .9, .01, 2, false);
        CalderaSkillSupport.parameter(p, "radius", 3.5, .5, 5, false);
        CalderaSkillSupport.parameter(p, "fallTicks", 20, 4, 40, true);
        CalderaSkillSupport.parameter(p, "height", 12, 2, 16, false);
        CalderaSkillSupport.parameter(p, "warningTicks", 20, 0, 60, true);
    }
    /** @param context 詠唱完了時の対象 @return 固定AoEを開始した場合true。表示生成失敗時も回収します。 */
    @Override public boolean cast(@NotNull MobSkillContext context) {
        if (!support.active(context)) return false;
        Location center = support.ground(context.target().getLocation());
        if (center == null || center.getWorld() != context.origin().getWorld()) return false;
        var p = context.binding().params();
        double radius = p.getOrDefault("radius", 3.5), ratio = p.getOrDefault("damageRatio", .9);
        int warning = p.getOrDefault("warningTicks", 20D).intValue(), fall = p.getOrDefault("fallTicks", 20D).intValue();
        double height = Math.min(p.getOrDefault("height", 12D), support.clearance(center, p.getOrDefault("height", 12D)));
        if (height < 1.1D) return false;
        List<Location> points = new ArrayList<>(24);
        for (int i = 0; i < 24; i++) {
            double angle = Math.PI * 2 * i / 24;
            points.add(center.clone().add(Math.cos(angle) * radius, .08D, Math.sin(angle) * radius));
        }
        List<BlockDisplay> displays = new ArrayList<>(1);
        try {
            if (!support.begin(context, displays, elapsed -> {
                if (elapsed >= warning + fall) {
                    support.explosion(center);
                    for (var target : support.targets(center, radius)) {
                        double dx = target.getLocation().getX() - center.getX(), dz = target.getLocation().getZ() - center.getZ();
                        if (dx * dx + dz * dz <= radius * radius) support.hit(context, center, target, ratio);
                    }
                    return false;
                }
                if (elapsed % 4 == 0) support.warning(center, points);
                if (elapsed >= warning) {
                    // 補間2tick先を描画し、着地とダメージtickを一致させる。
                    double progress = Math.min(1D, (double) (elapsed - warning + 2) / fall);
                    Location position = center.clone().add(0, height * (1 - progress), 0);
                    if (displays.isEmpty()) support.display(position, 1.0F, displays);
                    else displays.getFirst().teleport(position);
                }
                return true;
            })) return false;
            support.warning(center, points);
            if (warning == 0) support.display(center.clone().add(0, height, 0), 1.0F, displays);
            return true;
        } catch (RuntimeException exception) {
            support.clearCasterState(context.mob().instanceId());
            throw exception;
        }
    }
}
