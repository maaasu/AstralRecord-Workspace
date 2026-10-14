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
import io.github.maaasu.astralRecord.feature.mob.model.MobState;
import io.github.maaasu.astralRecord.feature.mob.service.MobService;
import io.github.maaasu.astralRecord.feature.mob.skill.MobSkillContext;
import io.github.maaasu.astralRecord.feature.mob.skill.MobSkillExecutor;
import io.github.maaasu.astralRecord.shared.effect.ParticleDisplayService;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * コオリガの「氷粉」。主対象の足元へ半径1.8mの六方模様を固定し、32tick後に
 * 主対象だけへ魔法・氷属性65%を一度適用します。円は追尾せず、円外移動と遮蔽物で回避できます。
 * 凍結・減速・DoTや追加の強制移動を付けず、雪原三種が重なっても状態異常の拘束を連鎖させません。
 * paramsなし、射程0.5〜7m、CD100tick以上、castTimeTicks=0のみ。メインスレッド専用です。
 * 破棄listenerとPlugin停止からcleanupを呼び出してください。
 */
public final class IceMothMobSkillExecutor implements MobSkillExecutor {
    public static final String SKILL_ID = "mob_field_ice_moth_ice_powder";
    private static final int WARNING_TICKS = 32;
    private static final double RADIUS = 1.8D;
    private final MobService mobService;
    private final DamageService damageService;
    private final ConditionService conditionService;
    private final ParticleDisplayService particles;
    private final Map<UUID, PowderCast> casts = new HashMap<>();

    /**
     * @param mobService 実体・管理個体・同期Schedulerの取得先
     * @param damageService 対象境界と独自ダメージの適用先
     * @param conditionService 行動不能による中断の判定先
     * @param particles 共通の氷粒子を表示するサービス
     */
    public IceMothMobSkillExecutor(@NotNull MobService mobService, @NotNull DamageService damageService,
            @NotNull ConditionService conditionService, @NotNull ParticleDisplayService particles) {
        this.mobService = mobService;
        this.damageService = damageService;
        this.conditionService = conditionService;
        this.particles = particles;
    }

    /** @return マスターから参照する専用ID */
    @Override public @NotNull String id() { return SKILL_ID; }

    /** @return 足元固定の技名 */
    @Override public @NotNull String displayName() { return "氷粉"; }

    /** @return 発動7m、再使用180tick、Executor内予告のため詠唱0tick */
    @Override public @NotNull MobSkillTiming defaultTiming() { return new MobSkillTiming(7.0D, 180L, 0L); }

    /**
     * @param binding マスターの紐付け
     * @throws IllegalArgumentException params、二重予告、射程外値、短すぎるCDを指定した場合
     */
    @Override public void validate(@NotNull MobSkillBinding binding) {
        MobSkillExecutor.super.validate(binding);
        MobSkillTiming timing = resolveTiming(binding);
        if (!Double.isFinite(timing.activationRange()) || timing.activationRange() < 0.5D
                || timing.activationRange() > 7.0D || timing.cooldownTicks() < 100L
                || timing.castTimeTicks() != 0L) {
            throw new IllegalArgumentException("Invalid timing for " + SKILL_ID);
        }
    }

    /**
     * 地面に固定する予告円を開始します。同一個体の実行中は再開始しません。
     * @param context 対象・射程・発動者を含む文脈
     * @return 有効なplayer/petと読込済み・境界内の地面を確認できた場合true
     */
    @Override public boolean cast(@NotNull MobSkillContext context) {
        if (casts.containsKey(context.mob().instanceId())) return false;
        Entity caster = activeCaster(context);
        Location center = groundAt(context.target().getLocation());
        if (caster == null || center == null || !validArea(center)
                || !inRange(caster.getLocation(), context.target().getLocation(), context.timing().activationRange())
                || !clearPath(caster.getLocation(), center)) return false;
        PowderCast cast = new PowderCast(context, center, caster.getLocation());
        casts.put(context.mob().instanceId(), cast);
        context.mob().scriptedAction(true);
        try {
            cast.warn();
            caster.getWorld().playSound(caster.getLocation(), Sound.ENTITY_BAT_TAKEOFF, 0.7F, 1.5F);
            cast.task = cast.runTaskTimer(mobService.plugin(), 1L, 1L);
            return true;
        } catch (RuntimeException exception) {
            handleMobDestroyed(context.mob().instanceId());
            throw exception;
        }
    }

    /**
     * 予告taskと通常AI停止を冪等に回収します。
     * @param mobInstanceId 破棄・死亡・despawnする管理個体ID
     */
    public void handleMobDestroyed(@NotNull UUID mobInstanceId) {
        PowderCast cast = casts.remove(mobInstanceId);
        if (cast != null) cast.finish();
    }

    /** Plugin停止時に全実行を回収します。メインスレッド専用です。 */
    public void stop() {
        for (UUID id : List.copyOf(casts.keySet())) handleMobDestroyed(id);
    }

    /** 対象変更・帰還・world変更・行動不能・破棄を毎tick検査します。 */
    private @Nullable Entity activeCaster(@NotNull MobSkillContext context) {
        var mob = context.mob();
        AstEntity actor = AstEntity.mob(mob);
        if (!mobService.plugin().isEnabled() || mobService.getInstance(mob.instanceId()) != mob
                || mob.state() != MobState.COMBAT || mob.currentHealth() <= 0.0D
                || !context.target().getUniqueId().equals(mob.targetId())
                || !damageService.isMobCombatTarget(context.target())
                || !conditionService.canRunAi(actor) || !conditionService.canCastSkill(actor)
                || !conditionService.canAttack(actor)) return null;
        Entity entity = mobService.entityController().getEntity(mob);
        return entity != null && entity.isValid() && !entity.isDead()
                && entity.getWorld() == context.origin().getWorld()
                && entity.getWorld() == context.target().getWorld()
                && entity.getWorld().getWorldBorder().isInside(entity.getLocation())
                && entity.getWorld().getWorldBorder().isInside(context.target().getLocation()) ? entity : null;
    }

    /** 水平射程と地上戦の高低差を確認します。 */
    private boolean inRange(@NotNull Location from, @NotNull Location to, double range) {
        double dx = from.getX() - to.getX();
        double dz = from.getZ() - to.getZ();
        return from.getWorld() == to.getWorld() && Math.abs(from.getY() - to.getY()) <= 1.25D
                && dx * dx + dz * dz <= range * range;
    }

    /** rayの全区間を読込済み・world border内で検査し、壁越しの発動・命中を防ぎます。 */
    private boolean clearPath(@NotNull Location from, @NotNull Location to) {
        if (from.getWorld() == null || from.getWorld() != to.getWorld()) return false;
        Location start = from.clone().add(0, 0.4D, 0);
        Vector delta = to.clone().add(0, 0.4D, 0).toVector().subtract(start.toVector());
        int samples = Math.max(1, (int) Math.ceil(delta.length() / 0.25D));
        for (int i = 0; i <= samples; i++) {
            Location point = start.clone().add(delta.clone().multiply((double) i / samples));
            if (!loadedInside(point)) return false;
        }
        return delta.lengthSquared() < 0.0001D || FieldMobRayTrace.trace(start.getWorld(), start, delta,
                delta.length(), FluidCollisionMode.NEVER, true) == null;
    }

    /** 空中1.25m以内にある実衝突面を使い、未読込chunkや水面を地面と扱いません。 */
    private @Nullable Location groundAt(@NotNull Location feet) {
        if (!loadedInside(feet)) return null;
        RayTraceResult hit = feet.getWorld().rayTraceBlocks(feet.clone().add(0, 0.2D, 0),
                new Vector(0, -1, 0), 1.45D, FluidCollisionMode.NEVER, true);
        return hit == null ? null : hit.getHitPosition().toLocation(feet.getWorld());
    }

    /** 円の外接正方形を含むchunkとborderを検査し、予告を境界外へ描きません。 */
    private boolean validArea(@NotNull Location center) {
        World world = center.getWorld();
        if (!loadedInside(center)) return false;
        for (double x : new double[]{-RADIUS, RADIUS}) {
            for (double z : new double[]{-RADIUS, RADIUS}) {
                if (!loadedInside(center.clone().add(x, 0, z))) return false;
            }
        }
        for (int x = (int) Math.floor(center.getX() - RADIUS) >> 4;
                x <= (int) Math.floor(center.getX() + RADIUS) >> 4; x++) {
            for (int z = (int) Math.floor(center.getZ() - RADIUS) >> 4;
                    z <= (int) Math.floor(center.getZ() + RADIUS) >> 4; z++) {
                if (!world.isChunkLoaded(x, z)) return false;
            }
        }
        return groundAt(center) != null;
    }

    /** 高さ・chunk・world borderだけを確認し、地形読込を誘発しません。 */
    private boolean loadedInside(@NotNull Location location) {
        World world = location.getWorld();
        return world != null && location.getY() >= world.getMinHeight() && location.getY() < world.getMaxHeight()
                && world.isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4)
                && world.getWorldBorder().isInside(location);
    }

    /** 固定円へ一回だけ氷粉を落とす、32tickで終了する実行です。 */
    private final class PowderCast extends BukkitRunnable {
        private final MobSkillContext context;
        private final Location center;
        private final Location casterStart;
        private @Nullable BukkitTask task;
        private int elapsed;
        private boolean finished;

        /** 固定中心と発動者の開始位置を複製します。 */
        private PowderCast(MobSkillContext context, Location center, Location casterStart) {
            this.context = context;
            this.center = center.clone();
            this.casterStart = casterStart.clone();
        }

        /** 予告中の外部移動・地面破壊を検査し、32tick目に一回だけ命中判定します。 */
        @Override public void run() {
            try {
                Entity caster = activeCaster(context);
                if (caster == null || caster.getLocation().distanceSquared(casterStart) > 0.36D
                        || !validArea(center)) { handleMobDestroyed(context.mob().instanceId()); return; }
                caster.setVelocity(new Vector(0, 0, 0));
                if (++elapsed < WARNING_TICKS) {
                    if (elapsed % 4 == 0) warn();
                    return;
                }
                Location feet = context.target().getLocation();
                boolean hit = inRange(center, feet, RADIUS) && clearPath(center, feet)
                        && clearPath(caster.getLocation(), feet);
                // ダメージによる再入・死亡の前に、taskとAI停止を解放します。
                handleMobDestroyed(context.mob().instanceId());
                particles.spawnForNearbyViewers(center, points(), SharedParticleDefinitions.WIZARD_RIME_SNOW);
                center.getWorld().playSound(center, Sound.BLOCK_POWDER_SNOW_BREAK, 0.8F, 1.3F);
                if (hit) damageService.attack(AstEntity.mob(context.mob()), damageService.resolveEntity(context.target()),
                        AttackType.MAGIC, List.of(new DamageComponent(DamageElement.ICE, 0.65D)), DamageSource.SKILL);
            } catch (RuntimeException exception) {
                handleMobDestroyed(context.mob().instanceId());
                throw exception;
            }
        }

        /** 円周と六本の筋を4tick間隔で、閲覧者解決を一括して表示します。 */
        private void warn() {
            particles.spawnForNearbyViewers(center, points(), SharedParticleDefinitions.WIZARD_RIME_SIGIL);
        }

        /** 円周24点と六方筋12点、合計36点の固定予告です。 */
        private List<Location> points() {
            List<Location> points = new ArrayList<>(36);
            for (int i = 0; i < 24; i++) {
                double angle = Math.PI * 2 * i / 24;
                points.add(center.clone().add(Math.cos(angle) * RADIUS, 0.08D, Math.sin(angle) * RADIUS));
            }
            for (int i = 0; i < 6; i++) {
                double angle = Math.PI * i / 3;
                for (int j = 1; j <= 2; j++) points.add(center.clone().add(
                        Math.cos(angle) * j * 0.6D, 0.08D, Math.sin(angle) * j * 0.6D));
            }
            return points;
        }

        /** 今回のtaskとAI停止を一度だけ返却します。状態異常や設置物は残しません。 */
        private void finish() {
            if (finished) return;
            finished = true;
            if (task != null) task.cancel();
            context.mob().scriptedAction(false);
        }
    }
}
