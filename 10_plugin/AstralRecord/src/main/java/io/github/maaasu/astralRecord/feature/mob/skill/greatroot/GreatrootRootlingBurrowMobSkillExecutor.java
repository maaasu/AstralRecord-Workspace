package io.github.maaasu.astralRecord.feature.mob.skill.greatroot;

import io.github.maaasu.astralRecord.feature.combat.model.AttackType;
import io.github.maaasu.astralRecord.feature.mob.model.MobSkillTiming;
import io.github.maaasu.astralRecord.feature.mob.skill.MobSkillContext;
import io.github.maaasu.astralRecord.feature.mob.skill.MobSkillExecutor;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * {@code mob_greatroot_rootling_burrow}: 対象足元の半径1.6mを30tick予告し、固定した地点まで地表を掘進して近接・無属性65%を与えます。
 * 最大6m、2tickごとに最大0.5m、追尾なし。全区間の壁・足場・余白・未読込chunkを事前と移動直前に確認します。
 * 安全な進路を確保できなければ、現在足元の半径1.25mを同じ30tick予告して攻撃します。
 * その足元も危険な場合や移動途中の進路変更では中断し、狭所へ長距離teleportしません。
 * paramsは受け付けません。発動距離6m、130tick再使用、詠唱0tickが既定です。
 */
public final class GreatrootRootlingBurrowMobSkillExecutor implements MobSkillExecutor {
    public static final String SKILL_ID = "mob_greatroot_rootling_burrow";
    private final GreatrootSkillSupport support;

    /** @param support 大樹根元の技に共通する同期処理と当たり判定の基盤 */
    public GreatrootRootlingBurrowMobSkillExecutor(@NotNull GreatrootSkillSupport support) { this.support = support; }
    /** @return Mob masterから参照するID */
    @Override public @NotNull String id() { return SKILL_ID; }
    /** @return 予告と詠唱表示で使用する技名 */
    @Override public @NotNull String displayName() { return "根潜りの地表掘進"; }
    /** @return 発動距離6m、130tick再使用、詠唱0tick */
    @Override public @NotNull MobSkillTiming defaultTiming() { return new MobSkillTiming(6, 130, 0); }

    /** @param context 発動開始時の主対象 @return 安全な進路または現在足元の予告を開始できた場合true */
    @Override public boolean cast(@NotNull MobSkillContext context) {
        Entity caster = support.activeCaster(context, true);
        if (caster == null) return false;
        Location start = caster.getLocation();
        Location target = context.target().getLocation();
        Vector delta = target.toVector().subtract(start.toVector()).setY(0);
        Location destination = start.clone().add(delta);
        boolean canBurrow = delta.lengthSquared() <= 36 && Math.abs(target.getY() - start.getY()) <= 0.35
                && GreatrootSkillSupport.safeStep(caster, start, destination);
        if (!canBurrow && !GreatrootSkillSupport.safeStep(caster, start, start)) return false;
        Location center = canBurrow ? destination : start;
        double radius = canBurrow ? 1.6 : 1.25;
        List<Location> points = GreatrootSkillSupport.circle(center, radius, 24);
        support.begin(context, true, 2, new java.util.function.IntPredicate() {
            private Location position = start.clone();
            /**
             * 固定予告後に地表を小刻みに進み、安全な着地時だけ固定円を攻撃します。
             * @param elapsed 発動からの経過tick
             * @return 予告または移動を続ける場合true
             */
            @Override public boolean test(int elapsed) {
                Entity active = support.activeCaster(context, true);
                if (active == null) return false;
                if (elapsed < 30) {
                    if (elapsed % 6 == 0) support.draw(context, center, points,
                            SharedParticleDefinitions.MOB_ERIVA_ROOT_WARNING, true);
                    return true;
                }
                // 被ノックバック等で予告位置から外れた実体を、古い位置へ戻してはいけません。
                if (active.getLocation().distanceSquared(position) > 0.16) return false;
                if ((elapsed - 30) % 6 == 0) support.draw(context, center, points,
                        SharedParticleDefinitions.MOB_ERIVA_ROOT_WARNING, true);
                Vector remaining = center.toVector().subtract(position.toVector());
                if (canBurrow && remaining.lengthSquared() > 0.0001) {
                    double length = remaining.length();
                    Location next = position.clone().add(remaining.multiply(Math.min(0.5, length) / length));
                    if (!GreatrootSkillSupport.safeStep(active, position, next) || !active.teleport(next)) return false;
                    context.mob().currentLocation(next);
                    position = next;
                    if ((elapsed - 30) % 6 == 0) support.draw(context, position, List.of(position.clone().add(0, 0.1, 0)),
                            SharedParticleDefinitions.MOB_ERIVA_ROOT_IMPACT, false);
                    if (position.distanceSquared(center) > 0.0001) return true;
                }
                if (!GreatrootSkillSupport.safeStep(active, position, position)) return false;
                support.draw(context, center, points, SharedParticleDefinitions.MOB_ERIVA_ROOT_IMPACT, false);
                support.hit(context, center, radius, AttackType.MELEE, 0.65, candidate -> true, false);
                return false;
            }
        });
        return true;
    }
}
