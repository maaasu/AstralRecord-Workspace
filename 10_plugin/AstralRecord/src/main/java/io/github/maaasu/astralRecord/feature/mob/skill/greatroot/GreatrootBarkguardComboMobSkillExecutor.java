package io.github.maaasu.astralRecord.feature.mob.skill.greatroot;

import io.github.maaasu.astralRecord.feature.combat.model.AttackType;
import io.github.maaasu.astralRecord.feature.mob.model.MobSkillTiming;
import io.github.maaasu.astralRecord.feature.mob.skill.MobSkillContext;
import io.github.maaasu.astralRecord.feature.mob.skill.MobSkillExecutor;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code mob_greatroot_barkguard_combo}: 固定した前方90度・4mを35tick予告して近接・無属性60%を与えます。
 * 続けて同じ中心・方向の長さ4.5m、幅1.1mの根線を新たに20tick予告して45%を与えます。
 * 背後と横へ回避でき、壁越し・高低差1.25m超には命中しません。
 * paramsは受け付けません。発動距離4m、140tick再使用、詠唱0tickが既定です。
 */
public final class GreatrootBarkguardComboMobSkillExecutor implements MobSkillExecutor {
    public static final String SKILL_ID = "mob_greatroot_barkguard_combo";
    private final GreatrootSkillSupport support;

    /** @param support 大樹根元の技に共通する同期処理と当たり判定の基盤 */
    public GreatrootBarkguardComboMobSkillExecutor(@NotNull GreatrootSkillSupport support) { this.support = support; }
    /** @return Mob masterから参照するID */
    @Override public @NotNull String id() { return SKILL_ID; }
    /** @return 予告と詠唱表示で使用する技名 */
    @Override public @NotNull String displayName() { return "樹皮の二連根打ち"; }
    /** @return 発動距離4m、140tick再使用、詠唱0tick */
    @Override public @NotNull MobSkillTiming defaultTiming() { return new MobSkillTiming(4, 140, 0); }

    /** @param context 発動開始時の方向と主対象 @return 固定した二段予告を開始できた場合true */
    @Override public boolean cast(@NotNull MobSkillContext context) {
        Entity caster = support.activeCaster(context, false);
        Vector direction = context.direction().clone().setY(0);
        if (caster == null || direction.lengthSquared() < 0.01) return false;
        direction.normalize();
        Location center = caster.getLocation();
        Vector sideways = new Vector(-direction.getZ(), 0, direction.getX());
        List<Location> fan = new ArrayList<>(), line = new ArrayList<>();
        double facing = Math.atan2(direction.getZ(), direction.getX());
        for (int row = 1; row <= 3; row++) {
            for (int column = 0; column < 8; column++) {
                double angle = facing - Math.PI / 4 + Math.PI / 2 * column / 7;
                fan.add(center.clone().add(Math.cos(angle) * 4 * row / 3, 0.1, Math.sin(angle) * 4 * row / 3));
            }
        }
        for (int row = 1; row <= 8; row++) {
            for (int edge = -1; edge <= 1; edge++) {
                line.add(center.clone().add(direction.clone().multiply(4.5 * row / 8))
                        .add(sideways.clone().multiply(edge * 0.55)).add(0, 0.1, 0));
            }
        }
        support.begin(context, false, 5, elapsed -> {
            if (elapsed < 35) {
                support.draw(context, center, fan, SharedParticleDefinitions.MOB_ERIVA_ROOT_WARNING, true);
            } else if (elapsed == 35) {
                support.draw(context, center, fan, SharedParticleDefinitions.MOB_ERIVA_ROOT_IMPACT, false);
                center.getWorld().playSound(center, Sound.ENTITY_IRON_GOLEM_ATTACK, 0.8F, 0.7F);
                support.hit(context, center, 4, AttackType.MELEE, 0.6, target -> {
                    Vector offset = target.getLocation().toVector().subtract(center.toVector()).setY(0);
                    return offset.lengthSquared() < 0.0001
                            || direction.dot(offset.normalize()) >= Math.cos(Math.PI / 4);
                }, false);
                support.draw(context, center, line, SharedParticleDefinitions.MOB_ERIVA_ROOT_WARNING, true);
            } else if (elapsed < 55) {
                support.draw(context, center, line, SharedParticleDefinitions.MOB_ERIVA_ROOT_WARNING, true);
            } else {
                support.draw(context, center, line, SharedParticleDefinitions.MOB_ERIVA_ROOT_IMPACT, false);
                support.hit(context, center, Math.hypot(4.5, 0.55), AttackType.MELEE, 0.45, target -> {
                    Vector offset = target.getLocation().toVector().subtract(center.toVector()).setY(0);
                    double forward = direction.dot(offset);
                    return forward >= 0 && forward <= 4.5 && Math.abs(sideways.dot(offset)) <= 0.55;
                }, false);
                return false;
            }
            return true;
        });
        return true;
    }
}
