package io.github.maaasu.astralRecord.feature.mob.skill.rootweaver;

import io.github.maaasu.astralRecord.feature.boss.service.BossParticleEffect;
import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.combat.model.AttackType;
import io.github.maaasu.astralRecord.feature.combat.model.DamageComponent;
import io.github.maaasu.astralRecord.feature.combat.model.DamageElement;
import io.github.maaasu.astralRecord.feature.combat.model.DamageSource;
import io.github.maaasu.astralRecord.feature.combat.service.DamageService;
import io.github.maaasu.astralRecord.feature.condition.model.ConditionApplyReason;
import io.github.maaasu.astralRecord.feature.condition.model.ConditionApplyRequest;
import io.github.maaasu.astralRecord.feature.condition.model.ConditionType;
import io.github.maaasu.astralRecord.feature.condition.service.ConditionService;
import io.github.maaasu.astralRecord.feature.mob.model.MobInstance;
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
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 対象の足元に根糸の円を固定し、20 tickの予告後に一度だけ遠隔攻撃と冷気を適用します。
 * 地形は変更せず、円外への移動で回避できます。処理はBukkitメインスレッドで実行します。
 */
public final class RootweaverRootSnareMobSkillExecutor implements MobSkillExecutor {
    public static final String SKILL_ID = "mob_manor_root_snare";
    private static final Set<String> PARAMETER_KEYS = Set.of(
            "radius", "damageRatio", "chilledDurationTicks", "chilledChance"
    );
    private static final int WARNING_POINTS = 24;
    private static final int WARNING_INTERVAL_TICKS = 5;
    private static final int IMPACT_DELAY_TICKS = 20;
    private final MobService mobService;
    private final DamageService damageService;
    private final ConditionService conditionService;
    private final ParticleDisplayService particleDisplayService;
    private final Map<UUID, BukkitTask> tasksByCaster = new HashMap<>();

    /**
     * 発動者の存続確認と効果適用の依存先を指定します。
     * @param mobService Mob管理と同期schedulerの取得先
     * @param damageService 通常プレイヤー・召喚ペットへのダメージ適用先
     * @param conditionService 冷気の確率・耐性計算と状態管理先
     * @param particleDisplayService 閲覧者へ予告を一括表示するサービス
     */
    public RootweaverRootSnareMobSkillExecutor(@NotNull MobService mobService,
            @NotNull DamageService damageService, @NotNull ConditionService conditionService,
            @NotNull ParticleDisplayService particleDisplayService) {
        this.mobService = mobService;
        this.damageService = damageService;
        this.conditionService = conditionService;
        this.particleDisplayService = particleDisplayService;
    }

    /** @return マスターから参照するスキルID */
    @Override public @NotNull String id() { return SKILL_ID; }

    /** @return 詠唱表示用の名称 */
    @Override public @NotNull String displayName() { return "根糸の足枷"; }

    /** @return 予告をExecutor内で行うため事前詠唱を持たない既定設定 */
    @Override public @NotNull MobSkillTiming defaultTiming() {
        return new MobSkillTiming(9.0D, 100L, 0L);
    }

    /**
     * 個別パラメータを検証します。
     * @param binding マスター上のスキル設定
     * @throws IllegalArgumentException 未知のキー・値域外・非整数の持続時間・二重詠唱設定がある場合
     */
    @Override public void validate(@NotNull MobSkillBinding binding) {
        if (binding.castTimeTicks() != null && binding.castTimeTicks() != 0L) {
            throw new IllegalArgumentException("castTimeTicks must be zero: warning is handled by executor");
        }
        Map<String, Double> params = binding.params();
        if (!PARAMETER_KEYS.containsAll(params.keySet())) {
            throw new IllegalArgumentException("Unsupported parameter for " + SKILL_ID);
        }
        bounded(params.getOrDefault("radius", 2.5D), "radius", 0.5D, 5.0D);
        bounded(params.getOrDefault("damageRatio", 0.30D), "damageRatio", 0.01D, 2.0D);
        double duration = params.getOrDefault("chilledDurationTicks", 40.0D);
        bounded(duration, "chilledDurationTicks", 1.0D, 200.0D);
        if (duration != Math.rint(duration)) {
            throw new IllegalArgumentException("chilledDurationTicks must be an integer");
        }
        bounded(params.getOrDefault("chilledChance", 60.0D), "chilledChance", 0.0D, 100.0D);
    }

    /**
     * 現在の対象の足元を固定し、20 tickの予告を開始します。同一発動者の旧予告は回収します。
     * @param context 発動文脈
     * @return 生存中の発動者と有効な地面・戦闘対象を確認できた場合true
     */
    @Override public boolean cast(@NotNull MobSkillContext context) {
        Location center = resolveGround(context.target().getLocation());
        Entity casterEntity = center == null ? null : activeCaster(context.mob(), center);
        if (casterEntity == null || !damageService.isMobCombatTarget(context.target())
                || !unobstructed(casterEntity.getLocation(), context.target().getLocation())) return false;
        Map<String, Double> params = context.binding().params();
        double radius = params.getOrDefault("radius", 2.5D);
        double ratio = params.getOrDefault("damageRatio", 0.30D);
        long duration = Math.round(params.getOrDefault("chilledDurationTicks", 40.0D));
        double chance = params.getOrDefault("chilledChance", 60.0D);
        handleMobDestroyed(context.mob().instanceId());
        renderWarning(context.mob(), center, radius);
        center.getWorld().playSound(center, Sound.ENTITY_SPIDER_AMBIENT, 0.7F, 0.7F);
        BukkitRunnable runnable = new BukkitRunnable() {
            private int elapsedTicks;
            @Override public void run() {
                if (activeCaster(context.mob(), center) == null) {
                    handleMobDestroyed(context.mob().instanceId());
                    return;
                }
                elapsedTicks += WARNING_INTERVAL_TICKS;
                if (elapsedTicks >= IMPACT_DELAY_TICKS) {
                    // 着弾前にタスクを外し、ダメージ処理中の破棄にも重複実行させません。
                    handleMobDestroyed(context.mob().instanceId());
                    impact(context.mob(), center, radius, ratio, duration, chance);
                } else {
                    renderWarning(context.mob(), center, radius);
                }
            }
        };
        tasksByCaster.put(context.mob().instanceId(), runnable.runTaskTimer(
                mobService.plugin(), WARNING_INTERVAL_TICKS, WARNING_INTERVAL_TICKS));
        return true;
    }

    /**
     * Mob破棄時にそのMobの予告タスクを回収します。
     * @param mobInstanceId 破棄されるMobのID
     */
    public void handleMobDestroyed(@NotNull UUID mobInstanceId) {
        BukkitTask task = tasksByCaster.remove(mobInstanceId);
        if (task != null) task.cancel();
    }

    /** Plugin停止時に全予告タスクと保持参照を回収します。 */
    public void stop() {
        tasksByCaster.values().forEach(BukkitTask::cancel);
        tasksByCaster.clear();
    }

    /** 同じ管理個体が生存し、同じワールドに残っていることを確認します。 */
    private @Nullable Entity activeCaster(@NotNull MobInstance caster, @NotNull Location center) {
        if (!mobService.plugin().isEnabled() || mobService.getInstance(caster.instanceId()) != caster
                || caster.state() == MobState.DEAD || caster.currentHealth() <= 0.0D) return null;
        Entity entity = mobService.entityController().getEntity(caster);
        return entity != null && entity.isValid() && !entity.isDead()
                && entity.getWorld() == center.getWorld() ? entity : null;
    }

    /** 固定円内・高さ1m以内で壁に遮られていない戦闘対象へ一度だけ効果を適用します。 */
    private void impact(@NotNull MobInstance caster, @NotNull Location center, double radius,
            double ratio, long duration, double chance) {
        World world = center.getWorld();
        particleDisplayService.spawnForNearbyViewers(center, circlePoints(center, radius),
                SharedParticleDefinitions.MOB_FOREST_SPIDER_WEB_IMPACT);
        world.playSound(center, Sound.BLOCK_AZALEA_LEAVES_BREAK, 0.9F, 0.7F);
        for (Entity entity : world.getNearbyEntities(center, radius, 1.0D, radius)) {
            if (activeCaster(caster, center) == null) return;
            Location feet = entity.getLocation();
            double dx = feet.getX() - center.getX();
            double dz = feet.getZ() - center.getZ();
            if (!damageService.isMobCombatTarget(entity) || Math.abs(feet.getY() - center.getY()) > 1.0D
                    || dx * dx + dz * dz > radius * radius || !unobstructed(center, feet)) continue;
            AstEntity victim = damageService.resolveEntity(entity);
            var result = damageService.attack(AstEntity.mob(caster), victim, AttackType.RANGED,
                    List.of(new DamageComponent(DamageElement.NONE, ratio)), DamageSource.SKILL);
            if (!result.evaded() && (result.finalDamage() > 0.0D || result.shieldDamage() > 0.0D)) {
                conditionService.applyCondition(new ConditionApplyRequest(victim, AstEntity.mob(caster),
                        ConditionType.CHILLED, AttackType.RANGED, duration, chance, 1.0D,
                        null, null, null, null, ConditionApplyReason.SKILL));
            }
        }
    }

    /** 地面から対象足元までの水平付近の経路を調べ、壁越しの命中を防ぎます。 */
    private boolean unobstructed(@NotNull Location center, @NotNull Location feet) {
        Location from = center.clone().add(0.0D, 0.5D, 0.0D);
        Vector displacement = feet.clone().add(0.0D, 0.5D, 0.0D).toVector().subtract(from.toVector());
        double distance = displacement.length();
        return distance < 0.001D || center.getWorld().rayTraceBlocks(from, displacement.normalize(),
                distance, FluidCollisionMode.NEVER, true) == null;
    }

    /**
     * 閲覧者の取得を一回にまとめ、ボスには共通の攻撃予見円を表示します。
     * @param caster 発動者
     * @param center 攻撃予告の中心
     * @param radius 攻撃半径
     */
    private void renderWarning(@NotNull MobInstance caster, @NotNull Location center, double radius) {
        particleDisplayService.spawnForNearbyViewers(center, circlePoints(center, radius),
                BossParticleEffect.attackWarning(caster, SharedParticleDefinitions.MOB_FOREST_SPIDER_WEB_TRAIL));
    }

    /** 固定された中心の外周24点を地面の少し上へ配置します。 */
    private @NotNull List<Location> circlePoints(@NotNull Location center, double radius) {
        List<Location> points = new ArrayList<>(WARNING_POINTS);
        for (int index = 0; index < WARNING_POINTS; index++) {
            double angle = Math.PI * 2.0D * index / WARNING_POINTS;
            points.add(center.clone().add(Math.cos(angle) * radius, 0.08D, Math.sin(angle) * radius));
        }
        return points;
    }

    /** 足元から最大6ブロック下の衝突面を探し、対象の正確なXZを維持します。 */
    private @Nullable Location resolveGround(@NotNull Location target) {
        World world = target.getWorld();
        if (world == null) return null;
        int minimumY = Math.max(world.getMinHeight(), target.getBlockY() - 6);
        for (int y = target.getBlockY(); y >= minimumY; y--) {
            Block block = world.getBlockAt(target.getBlockX(), y, target.getBlockZ());
            if (!block.isPassable()) {
                return new Location(world, target.getX(), block.getBoundingBox().getMaxY(), target.getZ());
            }
        }
        return null;
    }

    /** 非有限値および指定範囲外の値を拒否します。 */
    private void bounded(double value, @NotNull String key, double minimum, double maximum) {
        if (!Double.isFinite(value) || value < minimum || value > maximum) {
            throw new IllegalArgumentException(key + " must be between " + minimum + " and " + maximum);
        }
    }
}
