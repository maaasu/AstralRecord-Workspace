package io.github.maaasu.astralRecord.feature.mob.skill.field;

import io.github.maaasu.astralRecord.feature.buff.model.ActiveBuff;
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
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.status.service.StatusService;
import io.github.maaasu.astralRecord.shared.effect.ParticleDisplayService;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Sound;
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
 * ネッコガメの「根の足止め」。主対象の地面に半径1.6mの輪を固定し、24tick後に一度だけ
 * 間接・無属性40%の攻撃を行います。命中したプレイヤーの移動速度を24tickだけ30%下げます。
 * 輪外への移動と遮蔽物で回避でき、攻撃・スキル・ジャンプは封じません。
 * プレイヤー専用の短時間バフを使うため、ペットへの発動は通常攻撃へ委ねます。
 * paramsなし、発動距離0.5〜6m、CD80tick以上、castTimeTicksは0だけを許可します。
 * Bukkitメインスレッド専用。破棄listenerとPlugin停止からcleanupを呼び出してください。
 */
public final class RootTurtleMobSkillExecutor implements MobSkillExecutor {
    public static final String SKILL_ID = "mob_field_root_turtle_root_snare";
    public static final String BUFF_ID = "field_root_turtle_root_snare";
    private static final int WARNING_TICKS = 24;
    private static final int SLOW_TICKS = 24;
    private static final double RADIUS = 1.6D;
    private final MobService mobService;
    private final DamageService damageService;
    private final ConditionService conditionService;
    private final StatusService statusService;
    private final ParticleDisplayService particles;
    private final Map<UUID, RootCast> casts = new HashMap<>();

    /**
     * @param mobService 実体・管理個体・同期Schedulerの取得先
     * @param damageService ゲームプレイ対象判定と独自ダメージの適用先
     * @param conditionService 発動者の行動可能判定先
     * @param statusService 根の短時間バフの付与・解除・再計算先
     * @param particles 共通予告と命中粒子の描画先
     */
    public RootTurtleMobSkillExecutor(@NotNull MobService mobService, @NotNull DamageService damageService,
            @NotNull ConditionService conditionService, @NotNull StatusService statusService,
            @NotNull ParticleDisplayService particles) {
        this.mobService = mobService;
        this.damageService = damageService;
        this.conditionService = conditionService;
        this.statusService = statusService;
        this.particles = particles;
    }

    /** @return マスターから参照する専用ID */
    @Override public @NotNull String id() { return SKILL_ID; }

    /** @return 固定予告の技名 */
    @Override public @NotNull String displayName() { return "根の足止め"; }

    /** @return 発動6m、再使用160tick、Executor内予告のため詠唱0tick */
    @Override public @NotNull MobSkillTiming defaultTiming() { return new MobSkillTiming(6.0D, 160L, 0L); }

    /**
     * @param binding マスターの紐付け
     * @throws IllegalArgumentException params、二重予告、射程外値、短すぎるCDを指定した場合
     */
    @Override public void validate(@NotNull MobSkillBinding binding) {
        MobSkillExecutor.super.validate(binding);
        MobSkillTiming timing = resolveTiming(binding);
        if (!Double.isFinite(timing.activationRange()) || timing.activationRange() < 0.5D
                || timing.activationRange() > 6.0D || timing.cooldownTicks() < 80L
                || timing.castTimeTicks() != 0L) {
            throw new IllegalArgumentException("Invalid timing for " + SKILL_ID);
        }
    }

    /**
     * 主対象の足元を固定して予告を開始します。旧実行は回収してから開始します。
     * @param context 対象・射程・発動者を含む文脈
     * @return 生存中のプレイヤーと、到達可能な近い地面を確認できた場合true
     */
    @Override public boolean cast(@NotNull MobSkillContext context) {
        Entity caster = activeCaster(context);
        AstEntity victim = damageService.resolveEntity(context.target());
        Location center = groundAt(context.target().getLocation());
        if (caster == null || !victim.isPlayer() || victim.player() == null || center == null
                || !inRange(caster.getLocation(), context.target().getLocation(), context.timing().activationRange())
                || !clearPath(caster.getLocation(), center)) return false;
        handleMobDestroyed(context.mob().instanceId());
        RootCast cast = new RootCast(context, center, victim.player());
        casts.put(context.mob().instanceId(), cast);
        context.mob().scriptedAction(true);
        try {
            cast.warn();
            center.getWorld().playSound(center, Sound.BLOCK_AZALEA_LEAVES_PLACE, 0.8F, 0.6F);
            cast.task = cast.runTaskTimer(mobService.plugin(), 1L, 1L);
            return true;
        } catch (RuntimeException exception) {
            handleMobDestroyed(context.mob().instanceId());
            throw exception;
        }
    }

    /**
     * 保有する予告task、通常AI停止、今回付与したバフだけを回収します。
     * @param mobInstanceId 破棄・死亡・despawnする管理個体ID
     */
    public void handleMobDestroyed(@NotNull UUID mobInstanceId) {
        RootCast cast = casts.remove(mobInstanceId);
        if (cast != null) cast.finish();
    }

    /** Plugin停止時、残った実行と自分のバフ個体を全回収します。メインスレッド専用です。 */
    public void stop() {
        for (UUID id : List.copyOf(casts.keySet())) handleMobDestroyed(id);
    }

    /** 対象変更・帰還・world変更・行動不能・破棄を確認して実行を中断します。 */
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
                && entity.getWorld() == context.target().getWorld() ? entity : null;
    }

    /** 発動距離と地上戦用の高低差を明示的に再確認します。 */
    private boolean inRange(@NotNull Location from, @NotNull Location to, double range) {
        double dx = from.getX() - to.getX();
        double dz = from.getZ() - to.getZ();
        return from.getWorld() == to.getWorld() && Math.abs(from.getY() - to.getY()) <= 1.25D
                && dx * dx + dz * dz <= range * range;
    }

    /** 体の高さの線分を検査し、壁越しの発動と命中を防ぎます。 */
    private boolean clearPath(@NotNull Location from, @NotNull Location to) {
        Location start = from.clone().add(0.0D, 0.4D, 0.0D);
        Vector delta = to.clone().add(0.0D, 0.4D, 0.0D).toVector().subtract(start.toVector());
        int samples = Math.max(1, (int) Math.ceil(delta.length() / 0.25D));
        for (int i = 0; i <= samples; i++) {
            Location sample = start.clone().add(delta.clone().multiply((double) i / samples));
            if (!start.getWorld().isChunkLoaded(sample.getBlockX() >> 4, sample.getBlockZ() >> 4)) return false;
        }
        return delta.lengthSquared() < 0.0001D || start.getWorld().rayTraceBlocks(start, delta,
                delta.length(), FluidCollisionMode.NEVER, true) == null;
    }

    /** 未読込chunkを触らず、足元1.25m以内の実際の衝突面に円を置きます。 */
    private @Nullable Location groundAt(@NotNull Location feet) {
        if (feet.getWorld() == null || !feet.getWorld().isChunkLoaded(feet.getBlockX() >> 4, feet.getBlockZ() >> 4))
            return null;
        RayTraceResult hit = feet.getWorld().rayTraceBlocks(feet.clone().add(0, 0.2D, 0),
                new Vector(0, -1, 0), 1.45D, FluidCollisionMode.NEVER, true);
        return hit == null ? null : hit.getHitPosition().toLocation(feet.getWorld());
    }

    /** 根の輪と放射状の根筋を固定座標へ描く、有限の一回実行です。 */
    private final class RootCast extends BukkitRunnable {
        private final MobSkillContext context;
        private final Location center;
        private final AstPlayer player;
        private @Nullable ActiveBuff ownedBuff;
        private @Nullable BukkitTask task;
        private int elapsed;
        private boolean finished;
        private boolean ownsAiStop = true;

        /** 今回の固定中心とプレイヤー参照を保持します。 */
        private RootCast(MobSkillContext context, Location center, AstPlayer player) {
            this.context = context;
            this.center = center;
            this.player = player;
        }

        /** 24tickで一度だけ命中判定し、その後24tick以内に減速を解除します。 */
        @Override public void run() {
            try {
                Entity caster = activeCaster(context);
                if (caster == null) { handleMobDestroyed(context.mob().instanceId()); return; }
                if (elapsed < WARNING_TICKS) {
                    Vector velocity = caster.getVelocity();
                    caster.setVelocity(new Vector(0, velocity.getY(), 0));
                }
                elapsed++;
                if (elapsed == WARNING_TICKS) {
                    impact();
                    // 減速だけを残す間は通常AIを再開します。
                    releaseAiStop();
                    if (ownedBuff == null) handleMobDestroyed(context.mob().instanceId());
                } else if (elapsed < WARNING_TICKS && elapsed % 4 == 0) {
                    warn();
                } else if (elapsed >= WARNING_TICKS + SLOW_TICKS) {
                    handleMobDestroyed(context.mob().instanceId());
                }
            } catch (RuntimeException exception) {
                handleMobDestroyed(context.mob().instanceId());
                throw exception;
            }
        }

        /** 円外・高低差・遮蔽物・無効対象を除外して主対象へ一度だけ効果を適用します。 */
        private void impact() {
            if (activeCaster(context) == null) return;
            particles.spawnForNearbyViewers(center, points(), SharedParticleDefinitions.MOB_ERIVA_ROOT_IMPACT);
            center.getWorld().playSound(center, Sound.BLOCK_ROOTED_DIRT_BREAK, 0.8F, 0.7F);
            Location feet = context.target().getLocation();
            if (!inRange(center, feet, RADIUS) || !clearPath(center, feet)) return;
            AstEntity victim = damageService.resolveEntity(context.target());
            if (!victim.isPlayer() || victim.player() != player) return;
            var result = damageService.attack(AstEntity.mob(context.mob()), victim, AttackType.RANGED,
                    List.of(new DamageComponent(DamageElement.NONE, 0.40D)), DamageSource.SKILL);
            if (!result.evaded() && (result.finalDamage() > 0.0D || result.shieldDamage() > 0.0D)
                    && activeCaster(context) != null && damageService.isMobCombatTarget(context.target())) {
                statusService.applyBuff(player, BUFF_ID);
                ownedBuff = player.getActiveBuffs().stream().filter(buff -> BUFF_ID.equals(buff.getType().getId()))
                        .findFirst().orElse(null);
            }
        }

        /** 外周24点に四方向の根筋を加え、4tick間隔で予告を再表示します。 */
        private void warn() {
            particles.spawnForNearbyViewers(center, points(), SharedParticleDefinitions.MOB_ERIVA_ROOT_WARNING);
        }

        /** 当たり判定の境界と同じ円、および根筋を最大36点で表現します。 */
        private List<Location> points() {
            List<Location> result = new ArrayList<>(36);
            for (int i = 0; i < 24; i++) {
                double angle = Math.PI * 2 * i / 24;
                result.add(center.clone().add(Math.cos(angle) * RADIUS, 0.08D, Math.sin(angle) * RADIUS));
            }
            for (int arm = 0; arm < 4; arm++) {
                double angle = Math.PI * arm / 2;
                for (int j = 1; j <= 3; j++) result.add(center.clone().add(
                        Math.cos(angle) * j * 0.4D, 0.08D, Math.sin(angle) * j * 0.4D));
            }
            return result;
        }

        /** 他個体が再付与した同IDバフを消さず、今回の実行を冪等に回収します。 */
        private void finish() {
            if (finished) return;
            finished = true;
            if (task != null) task.cancel();
            releaseAiStop();
            if (ownedBuff != null && player.getActiveBuffs().removeIf(buff -> buff == ownedBuff)) {
                statusService.refreshStatus(player);
            }
            ownedBuff = null;
        }

        /** 予告終了後に始まる別の行動を解除しないよう、所有するAI停止だけを返却します。 */
        private void releaseAiStop() {
            if (ownsAiStop) {
                ownsAiStop = false;
                context.mob().scriptedAction(false);
            }
        }
    }
}
