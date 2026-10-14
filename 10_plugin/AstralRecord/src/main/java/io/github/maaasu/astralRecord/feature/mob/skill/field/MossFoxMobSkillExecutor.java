package io.github.maaasu.astralRecord.feature.mob.skill.field;

import io.github.maaasu.astralRecord.feature.boss.service.BossParticleEffect;
import io.github.maaasu.astralRecord.feature.combat.model.*;
import io.github.maaasu.astralRecord.feature.combat.service.DamageService;
import io.github.maaasu.astralRecord.feature.condition.service.ConditionService;
import io.github.maaasu.astralRecord.feature.mob.model.MobSkillTiming;
import io.github.maaasu.astralRecord.feature.mob.model.MobState;
import io.github.maaasu.astralRecord.feature.mob.service.MobService;
import io.github.maaasu.astralRecord.feature.mob.skill.MobSkillContext;
import io.github.maaasu.astralRecord.feature.mob.skill.MobSkillExecutor;
import io.github.maaasu.astralRecord.shared.effect.ParticleDisplayService;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * コケ煙。20tickの頭上詠唱後、自身の足元へ半径2.75mの円を20tick予告して固定します。
 * 煙は60tick持続し、開始・20・40tickに魔法・無属性40%をそれぞれ1回与えます。
 * 対象は同じ高さ1.25m以内かつ壁に遮られていないプレイヤーと召喚ペットだけです。
 * 煙中も境界を描画し、足止め・残留バフは付与しません。paramsは受け付けません。
 */
public final class MossFoxMobSkillExecutor implements MobSkillExecutor {
    public static final String SKILL_ID = "mob_field_moss_fox_moss_smoke";
    private static final double RADIUS = 2.75D;
    private final MobService mobService;
    private final DamageService damageService;
    private final ConditionService conditionService;
    private final ParticleDisplayService particles;
    private final Map<UUID, Smoke> clouds = new HashMap<>();

    /**
     * @param mobService Mob実体と同期Schedulerの取得先
     * @param damageService 味方陣営の対象判定と統合ダメージ適用先
     * @param conditionService 行動不能・詠唱不能の判定先
     * @param particles 固定範囲の予告と煙の描画先
     */
    public MossFoxMobSkillExecutor(@NotNull MobService mobService, @NotNull DamageService damageService,
                                  @NotNull ConditionService conditionService, @NotNull ParticleDisplayService particles) {
        this.mobService = mobService;
        this.damageService = damageService;
        this.conditionService = conditionService;
        this.particles = particles;
    }

    /** @return 専用MobスキルID */
    @Override public @NotNull String id() { return SKILL_ID; }
    /** @return 頭上に表示する技名 */
    @Override public @NotNull String displayName() { return "コケ煙"; }
    /** @return 2.6m発動・160tick再使用・20tick詠唱 */
    @Override public @NotNull MobSkillTiming defaultTiming() { return new MobSkillTiming(2.6D, 160L, 20L); }

    /** @param context 詠唱開始時の主対象 @return 足元の煙予告を開始できた場合true */
    @Override public boolean cast(@NotNull MobSkillContext context) {
        Entity caster = activeCaster(context);
        if (caster == null || clouds.containsKey(context.mob().instanceId())) return false;
        Smoke smoke = new Smoke(context, caster.getLocation());
        if (!smoke.loaded()) return false;
        clouds.put(context.mob().instanceId(), smoke);
        context.mob().scriptedAction(true);
        mobService.stopPathfinding(context.mob());
        try {
            smoke.warn();
            smoke.center.getWorld().playSound(smoke.center, Sound.ENTITY_FOX_SNIFF, 0.8F, 0.75F);
            smoke.runTaskTimer(mobService.plugin(), 5L, 5L);
            smoke.scheduled = true;
            return true;
        } catch (RuntimeException failure) { smoke.finish(); throw failure; }
    }

    /** @param mobInstanceId 破棄されたMob。自分の煙・予告taskとAI停止状態を即時解除します。 */
    public void clearCasterState(@NotNull UUID mobInstanceId) {
        Smoke smoke = clouds.get(mobInstanceId);
        if (smoke != null) smoke.finish();
    }

    /** Plugin停止時、Schedulerの一括cancelより先に同期スレッドで呼び出します。 */
    public void stop() {
        for (Smoke smoke : List.copyOf(clouds.values())) smoke.finish();
    }

    /** @param context 元の発動文脈 @return 同じ戦闘を継続し行動可能な実体。中断条件成立時はnull */
    private @Nullable Entity activeCaster(MobSkillContext context) {
        var mob = context.mob();
        if (!mobService.plugin().isEnabled() || mobService.getInstance(mob.instanceId()) != mob
                || mob.state() != MobState.COMBAT || !context.target().getUniqueId().equals(mob.targetId())
                || !damageService.isMobCombatTarget(context.target())) return null;
        Entity entity = mobService.entityController().getEntity(mob);
        AstEntity source = AstEntity.mob(mob);
        return entity != null && entity.isValid() && !entity.isDead()
                && entity.getWorld() == context.origin().getWorld() && entity.getWorld() == context.target().getWorld()
                && conditionService.canRunAi(source) && conditionService.canAttack(source)
                && conditionService.canCastSkill(source) ? entity : null;
    }

    /** @param center 固定煙中心 @param feet 候補の足元 @return 円内かつ同じ階にある場合true */
    private static boolean insideSmoke(Location center, Location feet) {
        double dx = feet.getX() - center.getX(), dz = feet.getZ() - center.getZ();
        return dx * dx + dz * dz <= RADIUS * RADIUS && Math.abs(feet.getY() - center.getY()) <= 1.25D;
    }

    private final class Smoke extends BukkitRunnable {
        private final MobSkillContext context;
        private final Location center;
        private final List<Location> border = new ArrayList<>(24);
        private final List<Location> haze = new ArrayList<>(9);
        private int elapsed;
        private boolean scheduled;
        private boolean finished;
        private boolean holdingAi = true;

        /** @param context 発動文脈 @param center 発動者の足元。固定中心・境界・煙座標をコピーします */
        private Smoke(MobSkillContext context, Location center) {
            this.context = context;
            this.center = center.clone();
            for (int point = 0; point < 24; point++) {
                double angle = Math.PI * 2 * point / 24;
                border.add(center.clone().add(Math.cos(angle) * RADIUS, 0.12D, Math.sin(angle) * RADIUS));
            }
            haze.add(center.clone().add(0, 0.6D, 0));
            for (int point = 0; point < 8; point++) {
                double angle = Math.PI * 2 * point / 8;
                haze.add(center.clone().add(Math.cos(angle) * 1.65D, 0.6D, Math.sin(angle) * 1.65D));
            }
        }

        /** @return 中心と境界24点のchunkが既に読込済みの場合true。chunkを新規読込しません */
        private boolean loaded() {
            return center.getWorld() != null
                    && center.getWorld().isChunkLoaded(center.getBlockX() >> 4, center.getBlockZ() >> 4)
                    && border.stream().allMatch(point -> center.getWorld().isChunkLoaded(point.getBlockX() >> 4, point.getBlockZ() >> 4));
        }

        /** 煙の発生前・持続中とも固定円の境界を近傍閲覧者へ一括表示します。 */
        private void warn() {
            particles.spawnForNearbyViewers(center, border,
                    BossParticleEffect.attackWarning(context.mob(), SharedParticleDefinitions.MOB_ERIVA_SPORE_WARNING));
        }

        /** 同期Schedulerの5tick更新。20tick予告、60tick煙、3pulseを処理し中断・例外時に回収します。 */
        @Override public void run() {
            if (activeCaster(context) == null || !loaded()) { finish(); return; }
            try {
                elapsed += 5;
                if (elapsed >= 80) { finish(); return; }
                warn();
                if (elapsed < 20) return;
                if (holdingAi) {
                    context.mob().scriptedAction(false);
                    holdingAi = false;
                    center.getWorld().playSound(center, Sound.BLOCK_FUNGUS_BREAK, 0.8F, 0.8F);
                }
                particles.spawnForNearbyViewers(center, haze, SharedParticleDefinitions.BOSS_MECHANIC_SMOKE);
                if ((elapsed - 20) % 20 == 0) pulse();
            } catch (RuntimeException failure) { finish(); throw failure; }
        }

        /** 円・階差・壁をその時点で検査し、有効プレイヤー・召喚ペットへ1pulseだけ適用します。 */
        private void pulse() {
            particles.spawnForNearbyViewers(center.clone().add(0, 0.6D, 0), SharedParticleDefinitions.MOB_ERIVA_SPORE_CLOUD);
            for (LivingEntity target : damageService.mobCombatTargets(center, RADIUS, 1.25D)) {
                Location feet = target.getLocation();
                if (!insideSmoke(center, feet)) continue;
                Location from = center.clone().add(0, 0.5D, 0);
                Vector delta = feet.add(0, Math.min(1.0D, target.getHeight() * 0.5D), 0).toVector().subtract(from.toVector());
                if (delta.lengthSquared() > 0.0001D && from.getWorld().rayTraceBlocks(from, delta, delta.length()) != null) continue;
                damageService.attack(AstEntity.mob(context.mob()), damageService.resolveEntity(target), AttackType.MAGIC,
                        List.of(new DamageComponent(DamageElement.NONE, 0.40D)), DamageSource.SKILL);
            }
        }

        /** 冪等にtask・mapを回収し、自分がまだ保持する予告のAI停止状態だけを解除します。 */
        private void finish() {
            if (finished) return;
            finished = true;
            if (scheduled) cancel();
            clouds.remove(context.mob().instanceId(), this);
            if (holdingAi) context.mob().scriptedAction(false);
        }
    }
}
