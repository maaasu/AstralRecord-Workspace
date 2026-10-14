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
 * コノハフクロウの「木の葉旋風」。主対象の地上位置へ半径2.6mの葉の円を36tick予告し、
 * 同じ位置に10tick間隔で3回、無属性・間接攻撃力55%を主対象だけへ与えます。
 * 円外、高低差1.25m超、遮蔽物で各段を回避できます。追尾・吸引・拘束・飛行は行いません。
 * paramsなし、発動距離0.5〜8m、CD200tick以上、外部詠唱0tick。
 * メインスレッド専用。破棄listenerとPlugin停止からcleanupを接続してください。
 */
public final class CanopyOwlMobSkillExecutor implements MobSkillExecutor {
    public static final String SKILL_ID = "mob_field_canopy_owl_leaf_vortex";
    private static final int WARNING_TICKS = 36;
    private static final int PULSE_INTERVAL = 10;
    private static final int PULSES = 3;
    private static final double RADIUS = 2.6D;
    private final MobService mobs;
    private final DamageService damage;
    private final ConditionService conditions;
    private final ParticleDisplayService particles;
    private final Map<UUID, VortexCast> casts = new HashMap<>();

    /**
     * @param mobs 管理個体と同期Schedulerの取得先
     * @param damage 主対象の有効性と間接攻撃の計算先
     * @param conditions 行動不能・攻撃不能・詠唱不能の確認先
     * @param particles 葉の輪と螺旋の一括描画先
     */
    public CanopyOwlMobSkillExecutor(@NotNull MobService mobs, @NotNull DamageService damage,
            @NotNull ConditionService conditions, @NotNull ParticleDisplayService particles) {
        this.mobs = mobs;
        this.damage = damage;
        this.conditions = conditions;
        this.particles = particles;
    }

    /** @return マスターから参照する専用ID */
    @Override public @NotNull String id() { return SKILL_ID; }

    /** @return 頭上予告バーの技名 */
    @Override public @NotNull String displayName() { return "木の葉旋風"; }

    /** @return 射程8m、再使用10秒、予告はExecutor内で管理 */
    @Override public @NotNull MobSkillTiming defaultTiming() { return new MobSkillTiming(8.0D, 200L, 0L); }

    /**
     * @param binding マスターの紐付け
     * @throws IllegalArgumentException params・短いCD・二重詠唱・範囲外射程の場合
     */
    @Override public void validate(@NotNull MobSkillBinding binding) {
        MobSkillExecutor.super.validate(binding);
        MobSkillTiming timing = resolveTiming(binding);
        if (!Double.isFinite(timing.activationRange()) || timing.activationRange() < 0.5D
                || timing.activationRange() > 8.0D || timing.cooldownTicks() < 200L || timing.castTimeTicks() != 0L) {
            throw new IllegalArgumentException("Invalid timing for " + SKILL_ID);
        }
    }

    /**
     * 主対象の足元を固定し、読込済みの地上に葉の予告を開始します。
     * @param context 発動者・主対象・実効timing。Bukkitメインスレッド専用
     * @return 有効な同一worldの地上対象に対して開始できた場合true
     */
    @Override public boolean cast(@NotNull MobSkillContext context) {
        validate(context.binding());
        if (casts.containsKey(context.mob().instanceId()) || context.mob().scriptedAction()) return false;
        Entity caster = activeCaster(context);
        if (caster == null) return false;
        Location origin = caster.getLocation().clone();
        Location center = context.target().getLocation().clone();
        if (!inCylinder(origin, center, context.timing().activationRange()) || !supported(center)
                || !clearPath(origin, center)) return false;
        VortexCast cast = new VortexCast(context, origin, center);
        if (cast.outline.stream().anyMatch(p -> !loadedInside(p))) return false;
        casts.put(context.mob().instanceId(), cast);
        context.mob().scriptedAction(true);
        context.mob().startSkillCasting(displayName(), WARNING_TICKS);
        try {
            mobs.stopPathfinding(context.mob());
            cast.warn();
            origin.getWorld().playSound(origin, Sound.ENTITY_PARROT_AMBIENT, 0.7F, 0.65F);
            cast.task = cast.runTaskTimer(mobs.plugin(), 1L, 1L);
            return true;
        } catch (RuntimeException exception) {
            handleMobDestroyed(context.mob().instanceId());
            throw exception;
        }
    }

    /**
     * 破棄・死亡・despawn時にtask、詠唱表示、AI停止を回収します。
     * @param mobInstanceId 管理個体ID。メインスレッド専用
     */
    public void handleMobDestroyed(@NotNull UUID mobInstanceId) {
        VortexCast cast = casts.remove(mobInstanceId);
        if (cast != null) cast.finish();
    }

    /** Plugin停止時に全taskと実行状態を冪等に回収します。メインスレッド専用です。 */
    public void stop() {
        for (UUID id : List.copyOf(casts.keySet())) handleMobDestroyed(id);
    }

    /** 対象変更・帰還・world変更・死亡・詠唱不能を中断条件として確認します。 */
    private @Nullable Entity activeCaster(MobSkillContext context) {
        var mob = context.mob();
        AstEntity actor = AstEntity.mob(mob);
        if (!mobs.plugin().isEnabled() || mobs.getInstance(mob.instanceId()) != mob
                || mob.state() != MobState.COMBAT || mob.currentHealth() <= 0.0D
                || !context.target().getUniqueId().equals(mob.targetId())
                || !damage.isMobCombatTarget(context.target()) || !conditions.canRunAi(actor)
                || !conditions.canCastSkill(actor) || !conditions.canAttack(actor)) return null;
        Entity caster = mobs.entityController().getEntity(mob);
        return caster != null && caster.isValid() && !caster.isDead()
                && caster.getWorld() == context.origin().getWorld() && caster.getWorld() == context.target().getWorld()
                && loadedInside(caster.getLocation()) && loadedInside(context.target().getLocation()) ? caster : null;
    }

    /** 足元の高さを分離し、別階層に水平距離だけで命中しない円柱判定です。 */
    private boolean inCylinder(Location center, Location at, double radius) {
        double dx = at.getX() - center.getX(), dz = at.getZ() - center.getZ();
        return center.getWorld() == at.getWorld() && Math.abs(at.getY() - center.getY()) <= 1.25D
                && dx * dx + dz * dz <= radius * radius;
    }

    /** 予告の中心に足場があることを確認し、空中・崖下への設置を防ぎます。 */
    private boolean supported(Location feet) {
        if (!loadedInside(feet) || !loadedInside(feet.clone().add(0, -0.2D, 0))) return false;
        RayTraceResult ground = feet.getWorld().rayTraceBlocks(feet.clone().add(0, 0.15D, 0),
                new Vector(0, -1, 0), 0.35D, FluidCollisionMode.NEVER, true);
        return ground != null;
    }

    /** world高さ・境界・読込chunkに限定し、判定や描画で新しいchunkを読み込みません。 */
    private boolean loadedInside(Location at) {
        World world = at.getWorld();
        return world != null && at.getY() >= world.getMinHeight() && at.getY() < world.getMaxHeight()
                && world.isChunkLoaded(at.getBlockX() >> 4, at.getBlockZ() >> 4) && world.getWorldBorder().isInside(at);
    }

    /** 読込済み区間だけを視線検査し、壁・床越しに旋風を設置または命中させません。 */
    private boolean clearPath(Location from, Location to) {
        if (from.getWorld() == null || from.getWorld() != to.getWorld()) return false;
        // 射線がchunk角を短く横切る場合も、block rayの前に読込状態を確認します。
        for (int x = Math.min(from.getBlockX(), to.getBlockX()) >> 4;
                x <= (Math.max(from.getBlockX(), to.getBlockX()) >> 4); x++) {
            for (int z = Math.min(from.getBlockZ(), to.getBlockZ()) >> 4;
                    z <= (Math.max(from.getBlockZ(), to.getBlockZ()) >> 4); z++) {
                if (!from.getWorld().isChunkLoaded(x, z)) return false;
            }
        }
        Location start = from.clone().add(0, 0.45D, 0);
        Vector delta = to.clone().add(0, 0.45D, 0).toVector().subtract(start.toVector());
        int samples = Math.max(1, (int) Math.ceil(delta.length() / 0.25D));
        for (int i = 0; i <= samples; i++) {
            if (!loadedInside(start.clone().add(delta.clone().multiply((double) i / samples)))) return false;
        }
        return delta.lengthSquared() < 0.0001D || start.getWorld().rayTraceBlocks(start, delta,
                delta.length(), FluidCollisionMode.NEVER, true) == null;
    }

    /** 最大56tickだけ維持する、対象を追尾しない固定円の三段攻撃です。 */
    private final class VortexCast extends BukkitRunnable {
        private final MobSkillContext context;
        private final Location origin;
        private final Location center;
        private final List<Location> outline = new ArrayList<>(24);
        private @Nullable BukkitTask task;
        private int elapsed;
        private int pulses;
        private boolean finished;

        /** 発動時の中心・外周を複製し、以降の対象移動から分離します。 */
        private VortexCast(MobSkillContext context, Location origin, Location center) {
            this.context = context;
            this.origin = origin.clone();
            this.center = center.clone();
            for (int i = 0; i < 24; i++) {
                double angle = Math.PI * 2 * i / 24;
                outline.add(center.clone().add(Math.cos(angle) * RADIUS, 0.12D, Math.sin(angle) * RADIUS));
            }
        }

        /** 中断を毎tick確認し、描画は6tick、攻撃は10tick間隔に限定します。 */
        @Override public void run() {
            try {
                Entity caster = activeCaster(context);
                if (finished || caster == null || caster.getLocation().distanceSquared(origin) > 0.36D
                        || outline.stream().anyMatch(p -> !loadedInside(p)) || !supported(center)
                        || !clearPath(origin, center)) {
                    handleMobDestroyed(context.mob().instanceId()); return;
                }
                caster.setVelocity(new Vector(0, caster.getVelocity().getY(), 0));
                ++elapsed;
                context.mob().updateSkillCastingRemaining(Math.max(0L, WARNING_TICKS - elapsed));
                if (elapsed < WARNING_TICKS) {
                    if (elapsed % 6 == 0) warn();
                    return;
                }
                if (elapsed == WARNING_TICKS) context.mob().clearSkillCasting();
                if ((elapsed - WARNING_TICKS) % PULSE_INTERVAL != 0) return;
                Location feet = context.target().getLocation();
                boolean hit = inCylinder(center, feet, RADIUS) && clearPath(center, feet);
                // 攻撃callbackの破棄・stop再入より前に段数を消費します。
                boolean last = ++pulses >= PULSES;
                if (last) handleMobDestroyed(context.mob().instanceId());
                spiral();
                if (hit) damage.attack(AstEntity.mob(context.mob()), damage.resolveEntity(context.target()),
                        AttackType.RANGED, List.of(new DamageComponent(DamageElement.NONE, 0.55D)), DamageSource.SKILL);
            } catch (RuntimeException exception) {
                handleMobDestroyed(context.mob().instanceId());
                throw exception;
            }
        }

        /** 緑の外周と内側の葉8点で危険円を示します。閲覧者走査は形状ごとに一回です。 */
        private void warn() {
            particles.spawnForNearbyViewers(center, outline, SharedParticleDefinitions.MOB_ERIVA_STARHORN_WARNING);
            List<Location> leaves = new ArrayList<>(8);
            for (int i = 0; i < 8; i++) {
                double angle = Math.PI * 2 * i / 8 + elapsed * 0.08D;
                leaves.add(center.clone().add(Math.cos(angle) * 1.6D, 0.3D, Math.sin(angle) * 1.6D));
            }
            particles.spawnForNearbyViewers(center, leaves,
                    SharedParticleDefinitions.SHARPSHOOTER_SPREADING_AMBITION_LEAVES.withCount(1).withOffsets(0, 0, 0));
        }

        /** 同じ円内の上昇螺旋を一括描画します。葉の高度は命中の高さを拡張しません。 */
        private void spiral() {
            List<Location> leaves = new ArrayList<>(24);
            for (int i = 0; i < 24; i++) {
                double angle = Math.PI * 4 * i / 24 + pulses;
                Location at = center.clone().add(Math.cos(angle) * (RADIUS - 0.3D), 0.2D + i * 0.045D,
                        Math.sin(angle) * (RADIUS - 0.3D));
                if (loadedInside(at)) leaves.add(at);
            }
            particles.spawnForNearbyViewers(center, leaves,
                    SharedParticleDefinitions.SHARPSHOOTER_SPREADING_AMBITION_LEAVES.withCount(1).withOffsets(0, 0, 0));
            center.getWorld().playSound(center, Sound.BLOCK_AZALEA_LEAVES_FALL, 0.65F, 0.9F + pulses * 0.15F);
        }

        /** 満了・中断・例外・死亡・Plugin停止でtaskとAI/詠唱表示を一度だけ解放します。 */
        private void finish() {
            if (finished) return;
            finished = true;
            if (task != null) task.cancel();
            context.mob().clearSkillCasting();
            context.mob().scriptedAction(false);
        }
    }
}
