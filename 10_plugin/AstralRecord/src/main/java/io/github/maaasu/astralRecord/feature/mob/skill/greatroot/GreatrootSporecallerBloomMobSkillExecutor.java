package io.github.maaasu.astralRecord.feature.mob.skill.greatroot;

import io.github.maaasu.astralRecord.feature.combat.model.AttackType;
import io.github.maaasu.astralRecord.feature.mob.model.MobSkillTiming;
import io.github.maaasu.astralRecord.feature.mob.skill.MobSkillContext;
import io.github.maaasu.astralRecord.feature.mob.skill.MobSkillExecutor;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * {@code mob_greatroot_sporecaller_bloom}: 発動時の対象足元に半径1.8mの種円を35tick予告し、魔法・無属性65%を与えます。
 * その後同じ足元に半径2.2mの胞子円を60tick残し、20tick間隔で3回、12%を与えます。
 * 複数の円が重なっても対象1体につき20tickに1回まで命中し、状態異常を付与しません。
 * paramsは受け付けません。発動距離9m、150tick再使用、詠唱0tickが既定です。
 */
public final class GreatrootSporecallerBloomMobSkillExecutor implements MobSkillExecutor {
    public static final String SKILL_ID = "mob_greatroot_sporecaller_bloom";
    private final GreatrootSkillSupport support;

    /** @param support 大樹根元の技に共通する同期処理と当たり判定の基盤 */
    public GreatrootSporecallerBloomMobSkillExecutor(@NotNull GreatrootSkillSupport support) { this.support = support; }
    /** @return Mob masterから参照するID */
    @Override public @NotNull String id() { return SKILL_ID; }
    /** @return 予告と詠唱表示で使用する技名 */
    @Override public @NotNull String displayName() { return "胞子種の開花"; }
    /** @return 発動距離9m、150tick再使用、詠唱0tick */
    @Override public @NotNull MobSkillTiming defaultTiming() { return new MobSkillTiming(9, 150, 0); }

    /** @param context 発動開始時の主対象 @return 対象足元に固定した種円の予告を開始できた場合true */
    @Override public boolean cast(@NotNull MobSkillContext context) {
        Entity caster = support.activeCaster(context, false);
        if (caster == null || caster.getLocation().distanceSquared(context.target().getLocation()) > 81
                || Math.abs(caster.getLocation().getY() - context.target().getLocation().getY()) > 1.25
                || !GreatrootSkillSupport.clearPath(caster.getLocation(), context.target())) return false;
        Location center = context.target().getLocation();
        List<Location> seed = GreatrootSkillSupport.circle(center, 1.8, 24);
        List<Location> cloud = GreatrootSkillSupport.circle(center, 2.2, 12);
        center.getWorld().playSound(center, Sound.BLOCK_FUNGUS_PLACE, 0.7F, 0.7F);
        support.begin(context, false, 5, elapsed -> {
            if (elapsed < 35) {
                support.draw(context, center, seed, SharedParticleDefinitions.MOB_ERIVA_SPORE_WARNING, true);
            } else if (elapsed == 35) {
                support.draw(context, center, seed, SharedParticleDefinitions.MOB_ERIVA_SPORE_IMPACT, false);
                support.hit(context, center, 1.8, AttackType.MAGIC, 0.65, target -> true, true);
                support.draw(context, center, cloud, SharedParticleDefinitions.MOB_ERIVA_SPORE_WARNING, true);
            } else {
                if ((elapsed - 35) % 10 == 0) {
                    support.draw(context, center, cloud, SharedParticleDefinitions.MOB_ERIVA_SPORE_WARNING, true);
                }
                if ((elapsed - 35) % 20 == 0) {
                    support.hit(context, center, 2.2, AttackType.MAGIC, 0.12, target -> true, true);
                }
            }
            return elapsed < 95;
        });
        return true;
    }
}
