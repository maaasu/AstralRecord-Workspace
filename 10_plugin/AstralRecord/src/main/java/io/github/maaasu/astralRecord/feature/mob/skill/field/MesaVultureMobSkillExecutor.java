package io.github.maaasu.astralRecord.feature.mob.skill.field;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.combat.model.AttackType;
import io.github.maaasu.astralRecord.feature.combat.model.DamageComponent;
import io.github.maaasu.astralRecord.feature.combat.model.DamageElement;
import io.github.maaasu.astralRecord.feature.combat.model.DamageSource;
import io.github.maaasu.astralRecord.feature.combat.service.DamageService;
import io.github.maaasu.astralRecord.feature.condition.service.ConditionService;
import io.github.maaasu.astralRecord.feature.mob.model.MobSkillBinding;
import io.github.maaasu.astralRecord.feature.mob.model.MobSkillTiming;
import io.github.maaasu.astralRecord.feature.mob.service.MobService;
import io.github.maaasu.astralRecord.feature.mob.skill.MobSkillContext;
import io.github.maaasu.astralRecord.feature.mob.skill.MobSkillExecutor;
import io.github.maaasu.astralRecord.shared.effect.ParticleDisplayService;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * メサハゲワシの落石。対象足元を固定し、半径1.6mの円を30tick予告、10tick下降後に
 * 主対象だけへ遠隔無属性120%を一回適用します。地形/落下エンティティを作りません。
 * paramsなし、射程0.5〜10m、CD180tick以上、castTimeTicks=0。
 * 中央destroy listenerとonDisableからhandleMobDestroyed/stopを呼びます。
 */
public final class MesaVultureMobSkillExecutor implements MobSkillExecutor {
    public static final String SKILL_ID = "mob_field_mesa_vulture_stone_drop";
    private static final double RADIUS = 1.6D;
    private final MobService mobs;
    private final DamageService damage;
    private final ConditionService conditions;
    private final ParticleDisplayService particles;
    private final Map<UUID, StoneCast> casts = new HashMap<>();

    /**
     * @param mobs 管理個体と同期Schedulerの取得先
     * @param damage 主対象と共通ダメージの判定先
     * @param conditions 行動/攻撃/詠唱中断の判定先
     * @param particles 一括予告と下降/着弾粒子の描画先
     */
    public MesaVultureMobSkillExecutor(@NotNull MobService mobs, @NotNull DamageService damage,
            @NotNull ConditionService conditions, @NotNull ParticleDisplayService particles) {
        this.mobs = mobs; this.damage = damage; this.conditions = conditions; this.particles = particles;
    }

    /** @return 落石のMob専用ID */
    @Override public @NotNull String id() { return SKILL_ID; }
    /** @return 頭上表示の日本語名 */
    @Override public @NotNull String displayName() { return "落石"; }
    /** @return 射程10m、再使用9秒、内部予告のみ */
    @Override public @NotNull MobSkillTiming defaultTiming() { return new MobSkillTiming(10.0D, 180L, 0L); }
    /** @param binding マスターの紐付け @throws IllegalArgumentException params/射程/CD/詠唱が契約外の場合 */
    @Override public void validate(@NotNull MobSkillBinding binding) { MesaFireAreaSupport.validate(this, binding); }

    /**
     * 固定地点と下降経路を検査し、独立した有限予告を開始します。
     * @param context 固定された発動文脈
     * @return 予告を開始した場合true
     */
    @Override public boolean cast(@NotNull MobSkillContext context) {
        Entity caster = MesaFireAreaSupport.activeCaster(context, mobs, damage, conditions);
        if (caster == null || casts.containsKey(context.mob().instanceId()) || context.mob().scriptedAction()) return false;
        Location center = MesaFireAreaSupport.ground(context.target().getLocation());
        if (center == null || !MesaFireAreaSupport.inRange(caster.getLocation(), center, context.timing().activationRange())
                || !MesaFireAreaSupport.clearPath(caster.getLocation(), center)) return false;
        StoneCast cast = new StoneCast(context, caster.getLocation(), center);
        if (!cast.areaClear()) return false;
        casts.put(context.mob().instanceId(), cast);
        context.mob().scriptedAction(true);
        try {
            cast.warn();
            center.getWorld().playSound(center, Sound.BLOCK_STONE_HIT, 0.8F, 0.6F);
            cast.task = cast.runTaskTimer(mobs.plugin(), 5L, 5L);
            return true;
        } catch (RuntimeException failure) {
            handleMobDestroyed(context.mob().instanceId());
            throw failure;
        }
    }

    /** @param mobInstanceId 破棄した個体。taskとAI停止を冪等に解除します。 */
    public void handleMobDestroyed(@NotNull UUID mobInstanceId) {
        StoneCast cast = casts.remove(mobInstanceId);
        if (cast != null) cast.finish();
    }
    /** Plugin停止時に全予告を解除します。同期スレッド専用です。 */
    public void stop() { for (UUID id : List.copyOf(casts.keySet())) handleMobDestroyed(id); }

    private final class StoneCast extends BukkitRunnable {
        private final MobSkillContext context;
        private final Location origin;
        private final Location center;
        private final List<Location> ring = new ArrayList<>(25);
        private BukkitTask task;
        private int elapsed;
        private boolean finished;

        private StoneCast(MobSkillContext context, Location origin, Location center) {
            this.context = context; this.origin = origin.clone(); this.center = center.clone();
            for (int i = 0; i < 24; i++) {
                double angle = 2.0D * Math.PI * i / 24.0D;
                ring.add(center.clone().add(Math.cos(angle) * RADIUS, 0.08D, Math.sin(angle) * RADIUS));
            }
            ring.add(center.clone().add(0.0D, 0.08D, 0.0D));
        }

        /** 半径内の足場と固定した4mの落下経路を再検査します。 */
        private boolean areaClear() {
            if (!MesaFireAreaSupport.clearRay(center.clone().add(0.0D, 0.15D, 0.0D), center.clone().add(0.0D, 4.0D, 0.0D))) return false;
            for (Location point : ring) {
                Location ground = MesaFireAreaSupport.ground(point);
                if (ground == null || Math.abs(ground.getY() - center.getY()) > 0.05D) return false;
            }
            return true;
        }

        @Override public void run() {
            try {
                Entity caster = MesaFireAreaSupport.activeCaster(context, mobs, damage, conditions);
                if (finished || caster == null || caster.getLocation().distanceSquared(origin) > 0.5625D || !areaClear()) {
                    handleMobDestroyed(context.mob().instanceId()); return;
                }
                caster.setVelocity(new Vector());
                elapsed += 5;
                warn();
                if (elapsed < 40) {
                    if (elapsed >= 30) particles.spawnForNearbyViewers(center,
                            List.of(center.clone().add(0.0D, (40 - elapsed) * 0.4D, 0.0D)),
                            SharedParticleDefinitions.mobImpactBlock(Material.RED_SANDSTONE.createBlockData()));
                    return;
                }
                Location feet = context.target().getLocation();
                boolean hit = MesaFireAreaSupport.inRange(center, feet, RADIUS)
                        && MesaFireAreaSupport.clearPath(center, feet);
                // ダメージの再入より先に実行状態とAI停止を解放します。
                handleMobDestroyed(context.mob().instanceId());
                particles.spawnForNearbyViewers(center, SharedParticleDefinitions.mobImpactBlock(Material.RED_SANDSTONE.createBlockData()));
                center.getWorld().playSound(center, Sound.BLOCK_STONE_BREAK, 0.9F, 0.65F);
                if (hit) damage.attack(AstEntity.mob(context.mob()), damage.resolveEntity(context.target()), AttackType.RANGED,
                        List.of(new DamageComponent(DamageElement.NONE, 1.2D)), DamageSource.SKILL);
            } catch (RuntimeException failure) {
                handleMobDestroyed(context.mob().instanceId()); throw failure;
            }
        }

        private void warn() { particles.spawnForNearbyViewers(center, ring, SharedParticleDefinitions.KNOCKER_WARNING); }
        private void finish() {
            if (finished) return;
            finished = true;
            if (task != null) task.cancel();
            context.mob().scriptedAction(false);
        }
    }
}
