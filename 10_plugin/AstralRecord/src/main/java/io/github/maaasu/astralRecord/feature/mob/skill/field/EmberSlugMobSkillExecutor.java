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
import org.bukkit.Bukkit;
import org.bukkit.Location;
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
 * ヒダネナメクジの火の道。固定長5m/幅1.3mの帯を30tick予告し、80tick燃焼させます。
 * 主対象だけへ発火時/20/40/60tickに魔法炎35%を判定し、全個体の重複も対象毎20tickに制限。
 * paramsなし、射程0.5〜5m、CD240tick以上、castTimeTicks=0。地形/発火/燃焼状態は変更しません。
 * 中央destroy listenerとonDisableからhandleMobDestroyed/stopを呼びます。
 */
public final class EmberSlugMobSkillExecutor implements MobSkillExecutor {
    public static final String SKILL_ID = "mob_field_ember_slug_ember_trail";
    private static final double LENGTH = 5.0D;
    private static final double HALF_WIDTH = 0.65D;
    private static final int WARNING_TICKS = 30;
    private static final int DURATION_TICKS = 80;
    private static final int HIT_INTERVAL = 20;
    private final MobService mobs;
    private final DamageService damage;
    private final ConditionService conditions;
    private final ParticleDisplayService particles;
    private final Map<UUID, FireCast> casts = new HashMap<>();
    // Executorを中央で一度だけ登録し、全Slugの重複判定を同じ窓へまとめます。
    private final Map<UUID, Integer> lastHitTicks = new HashMap<>();

    /**
     * @param mobs 管理個体と同期Schedulerの取得先
     * @param damage 主対象と共通ダメージの判定先
     * @param conditions 行動/攻撃/詠唱中断の判定先
     * @param particles 一括帯状予告と炎の描画先
     */
    public EmberSlugMobSkillExecutor(@NotNull MobService mobs, @NotNull DamageService damage,
            @NotNull ConditionService conditions, @NotNull ParticleDisplayService particles) {
        this.mobs = mobs; this.damage = damage; this.conditions = conditions; this.particles = particles;
    }
    /** @return 火の道のMob専用ID */
    @Override public @NotNull String id() { return SKILL_ID; }
    /** @return 頭上表示の日本語名 */
    @Override public @NotNull String displayName() { return "火の道"; }
    /** @return 射程5m、再使用12秒、内部予告のみ */
    @Override public @NotNull MobSkillTiming defaultTiming() { return new MobSkillTiming(LENGTH, 240L, 0L); }
    /** @param binding マスターの紐付け @throws IllegalArgumentException params/射程/CD/詠唱が契約外の場合 */
    @Override public void validate(@NotNull MobSkillBinding binding) { MesaFireAreaSupport.validate(this, binding); }

    /**
     * 開始位置と向きを固定し、平坦な読込済み足場だけへ予告を出します。
     * @param context 固定された発動文脈
     * @return 予告を開始した場合true
     */
    @Override public boolean cast(@NotNull MobSkillContext context) {
        pruneHits();
        Entity caster = MesaFireAreaSupport.activeCaster(context, mobs, damage, conditions);
        if (caster == null || casts.containsKey(context.mob().instanceId()) || context.mob().scriptedAction()) return false;
        Location origin = MesaFireAreaSupport.ground(caster.getLocation());
        Location feet = context.target().getLocation();
        if (origin == null || !MesaFireAreaSupport.inRange(origin, feet, context.timing().activationRange())
                || !MesaFireAreaSupport.clearPath(origin, feet)) return false;
        Vector direction = feet.toVector().subtract(origin.toVector()).setY(0.0D);
        if (direction.lengthSquared() < 0.01D) return false;
        FireCast cast = new FireCast(context, origin, direction.normalize());
        if (!cast.areaClear()) return false;
        casts.put(context.mob().instanceId(), cast);
        context.mob().scriptedAction(true);
        try {
            cast.render(false);
            origin.getWorld().playSound(origin, Sound.BLOCK_FIRE_AMBIENT, 0.7F, 0.6F);
            cast.task = cast.runTaskTimer(mobs.plugin(), 5L, 5L);
            return true;
        } catch (RuntimeException failure) {
            handleMobDestroyed(context.mob().instanceId()); throw failure;
        }
    }

    /** @param mobInstanceId 破棄個体。予告/炎taskとAI停止を冪等に回収します。 */
    public void handleMobDestroyed(@NotNull UUID mobInstanceId) {
        FireCast cast = casts.remove(mobInstanceId);
        if (cast != null) cast.finish();
        pruneHits();
        // 残存する別個体の炎の窓は消さず、最後の炎終了時だけ残留Mapを解放します。
        if (casts.isEmpty()) lastHitTicks.clear();
    }
    /** Plugin停止時に全task/AI停止/重複判定を回収します。同期スレッド専用です。 */
    public void stop() {
        for (UUID id : List.copyOf(casts.keySet())) handleMobDestroyed(id);
        lastHitTicks.clear();
    }
    private void pruneHits() {
        int now = Bukkit.getCurrentTick();
        lastHitTicks.entrySet().removeIf(entry -> now - entry.getValue() >= HIT_INTERVAL);
    }

    private final class FireCast extends BukkitRunnable {
        private final MobSkillContext context;
        private final Location origin;
        private final Vector direction;
        private final List<Location> footprint = new ArrayList<>(33);
        private final List<Location> flame = new ArrayList<>(11);
        private BukkitTask task;
        private int elapsed;
        private boolean finished;
        private boolean ownsAiPause = true;

        private FireCast(MobSkillContext context, Location origin, Vector direction) {
            this.context = context; this.origin = origin.clone(); this.direction = direction.clone();
            Vector side = new Vector(-direction.getZ(), 0.0D, direction.getX());
            for (int i = 0; i <= 10; i++) {
                Location center = origin.clone().add(direction.clone().multiply(i * 0.5D)).add(0.0D, 0.08D, 0.0D);
                flame.add(center);
                for (double width : new double[]{-HALF_WIDTH, 0.0D, HALF_WIDTH}) footprint.add(center.clone().add(side.clone().multiply(width)));
            }
        }
        private boolean areaClear() {
            for (Location point : footprint) {
                Location ground = MesaFireAreaSupport.ground(point);
                if (ground == null || Math.abs(ground.getY() - origin.getY()) > 0.05D) return false;
            }
            return MesaFireAreaSupport.clearPath(origin, origin.clone().add(direction.clone().multiply(LENGTH)));
        }

        @Override public void run() {
            try {
                pruneHits();
                Entity caster = MesaFireAreaSupport.activeCaster(context, mobs, damage, conditions);
                if (finished || caster == null || !areaClear()
                        || (ownsAiPause && caster.getLocation().distanceSquared(origin) > 0.5625D)) {
                    handleMobDestroyed(context.mob().instanceId()); return;
                }
                elapsed += 5;
                if (elapsed < WARNING_TICKS) {
                    caster.setVelocity(new Vector()); render(false); return;
                }
                int age = elapsed - WARNING_TICKS;
                if (age >= DURATION_TICKS) { handleMobDestroyed(context.mob().instanceId()); return; }
                if (ownsAiPause) {
                    ownsAiPause = false;
                    context.mob().scriptedAction(false);
                    origin.getWorld().playSound(origin, Sound.ITEM_FIRECHARGE_USE, 0.8F, 0.8F);
                }
                render(true);
                if (age % HIT_INTERVAL == 0) hit();
            } catch (RuntimeException failure) {
                handleMobDestroyed(context.mob().instanceId()); throw failure;
            }
        }
        private void hit() {
            Location feet = context.target().getLocation();
            double dx = feet.getX() - origin.getX(), dz = feet.getZ() - origin.getZ();
            double forward = dx * direction.getX() + dz * direction.getZ();
            double side = -dx * direction.getZ() + dz * direction.getX();
            if (Math.abs(feet.getY() - origin.getY()) > 1.25D || forward < 0.0D || forward > LENGTH
                    || Math.abs(side) > HALF_WIDTH || !MesaFireAreaSupport.clearPath(origin, feet)) return;
            UUID targetId = context.target().getUniqueId();
            int now = Bukkit.getCurrentTick();
            Integer previous = lastHitTicks.get(targetId);
            if (previous != null && now - previous < HIT_INTERVAL) return;
            // 回避/無敵/Shieldも含めて一窓一試行。再入より先に窓を予約します。
            lastHitTicks.put(targetId, now);
            damage.attack(AstEntity.mob(context.mob()), damage.resolveEntity(context.target()), AttackType.MAGIC,
                    List.of(new DamageComponent(DamageElement.FIRE, 0.35D)), DamageSource.SKILL);
        }
        private void render(boolean burning) {
            particles.spawnForNearbyViewers(origin, footprint, SharedParticleDefinitions.KNOCKER_WARNING);
            if (burning) particles.spawnForNearbyViewers(origin, flame, SharedParticleDefinitions.CONDITION_BURNING_FLAME);
        }
        private void finish() {
            if (finished) return;
            finished = true;
            if (task != null) task.cancel();
            if (ownsAiPause) { ownsAiPause = false; context.mob().scriptedAction(false); }
        }
    }
}
