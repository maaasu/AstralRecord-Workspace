package io.github.maaasu.astralRecord.feature.mob.skill.greatroot;

import io.github.maaasu.astralRecord.feature.boss.service.BossParticleEffect;
import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.combat.model.AttackType;
import io.github.maaasu.astralRecord.feature.combat.model.DamageComponent;
import io.github.maaasu.astralRecord.feature.combat.model.DamageElement;
import io.github.maaasu.astralRecord.feature.combat.model.DamageSource;
import io.github.maaasu.astralRecord.feature.combat.service.DamageService;
import io.github.maaasu.astralRecord.feature.condition.service.ConditionService;
import io.github.maaasu.astralRecord.feature.mob.model.MobState;
import io.github.maaasu.astralRecord.feature.mob.service.MobService;
import io.github.maaasu.astralRecord.feature.mob.skill.MobSkillContext;
import io.github.maaasu.astralRecord.shared.effect.ParticleDisplayService;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinition;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.IntPredicate;
import java.util.function.Predicate;

/** 大樹根元の技の同期タスク、通常AI停止、遮蔽物、胞子の重複上限を管理します。 */
public final class GreatrootSkillSupport {
    private final MobService mobs;
    private final DamageService damage;
    private final ConditionService conditions;
    private final ParticleDisplayService particles;
    private final Map<UUID, Session> sessions = new HashMap<>();
    private final Map<UUID, Integer> sporeHits = new HashMap<>();

    /**
     * 3種の専用技で共有する同期実行基盤を作成します。
     * @param mobs Mob取得と同期Schedulerの所有者
     * @param damage 有効対象の取得・独自ダメージの適用先
     * @param conditions 行動不能状態の判定先
     * @param particles 密度設定を適用する一括描画先
     */
    public GreatrootSkillSupport(@NotNull MobService mobs, @NotNull DamageService damage,
            @NotNull ConditionService conditions, @NotNull ParticleDisplayService particles) {
        this.mobs = mobs;
        this.damage = damage;
        this.conditions = conditions;
        this.particles = particles;
    }

    /**
     * 発動者自身の存続・ワールド・行動可否を確認します。
     * @param context 発動文脈
     * @param movement 移動不能も中断条件にするか
     * @return 行動可能な発動者。無効ならnull
     */
    @Nullable Entity impactCaster(MobSkillContext context, boolean movement) {
        var mob = context.mob();
        if (!mobs.plugin().isEnabled() || mobs.getInstance(mob.instanceId()) != mob
                || mob.state() != MobState.COMBAT || mob.currentHealth() <= 0) return null;
        Entity entity = mobs.entityController().getEntity(mob);
        AstEntity caster = AstEntity.mob(mob);
        return entity != null && entity.isValid() && !entity.isDead()
                && entity.getWorld() == context.origin().getWorld()
                && conditions.canRunAi(caster) && conditions.canCastSkill(caster) && conditions.canAttack(caster)
                && (!movement || conditions.canMove(caster)) ? entity : null;
    }

    /**
     * 主対象の変更、死亡、帰還、ワールド変更を含めて戦闘継続を判定します。
     * @param context 発動文脈
     * @param movement 移動不能も中断条件にするか
     * @return 同じ戦闘を継続中の発動者。無効ならnull
     */
    @Nullable Entity activeCaster(MobSkillContext context, boolean movement) {
        if (!context.target().getUniqueId().equals(context.mob().targetId())
                || !damage.isMobCombatTarget(context.target())
                || context.target().getWorld() != context.origin().getWorld()) return null;
        return impactCaster(context, movement);
    }

    /**
     * 通常AIと経路探索を停止し、経過tickを渡して有限の同期処理を開始します。
     * @param context 固定した発動文脈
     * @param movement 移動不能時にも中断するか
     * @param period 同期検査周期。描画は呼出側で5tick以上に制限する
     * @param action 経過tickを受け取り、継続時trueを返す有限処理
     */
    void begin(MobSkillContext context, boolean movement, int period, IntPredicate action) {
        clearCasterState(context.mob().instanceId());
        Session session = new Session(context);
        sessions.put(context.mob().instanceId(), session);
        context.mob().scriptedAction(true);
        mobs.stopPathfinding(context.mob());
        BukkitRunnable runnable = new BukkitRunnable() {
            private int elapsed;
            /** 継続条件を確認し、異常終了を含むすべての終了経路でAI停止を解除します。 */
            @Override public void run() {
                try {
                    if (activeCaster(context, movement) == null || !action.test(elapsed)) {
                        clearCasterState(context.mob().instanceId());
                        return;
                    }
                    elapsed += period;
                } catch (RuntimeException | Error failure) {
                    clearCasterState(context.mob().instanceId());
                    throw failure;
                }
            }
        };
        try {
            session.task = runnable.runTaskTimer(mobs.plugin(), 0L, period);
        } catch (RuntimeException | Error failure) {
            clearCasterState(context.mob().instanceId());
            throw failure;
        }
    }

    /**
     * 最大24点を閲覧者一括で描画し、予告だけ共通の警告色を適用します。
     * @param context 発動文脈
     * @param center 閲覧者を検索する中心
     * @param points 固定描画点
     * @param definition 再利用する共有粒子
     * @param warning 予告色を適用するか
     */
    void draw(MobSkillContext context, Location center, List<Location> points,
            SharedParticleDefinition definition, boolean warning) {
        particles.spawnForNearbyViewers(center, points.subList(0, Math.min(24, points.size())),
                warning ? BossParticleEffect.attackWarning(context.mob(), definition) : definition);
    }

    /**
     * 足元に固定する最大24点の円周を作成します。
     * @param center 固定中心
     * @param radius 半径
     * @param count 希望点数。3から24へ補正
     * @return 足元から0.1m上の円周点
     */
    static List<Location> circle(Location center, double radius, int count) {
        List<Location> points = new ArrayList<>();
        int capped = Math.min(24, Math.max(3, count));
        for (int i = 0; i < capped; i++) {
            double angle = Math.PI * 2 * i / capped;
            points.add(center.clone().add(Math.cos(angle) * radius, 0.1, Math.sin(angle) * radius));
        }
        return points;
    }

    /**
     * 読込済み区間だけを胴体までray traceし、壁越しの命中を防ぎます。
     * @param origin 攻撃中心の足元
     * @param target 命中候補
     * @return 同じワールドの読込済み区間が壁に遮られなければtrue
     */
    static boolean clearPath(Location origin, LivingEntity target) {
        if (origin.getWorld() == null || origin.getWorld() != target.getWorld()) return false;
        Location from = origin.clone().add(0, 0.5, 0);
        Location to = target.getLocation().add(0, Math.min(1, target.getHeight() * 0.5), 0);
        Vector delta = to.toVector().subtract(from.toVector());
        double distance = delta.length();
        int samples = Math.max(1, (int) Math.ceil(distance));
        for (int i = 0; i <= samples; i++) {
            Location sample = from.clone().add(delta.clone().multiply((double) i / samples));
            if (!sample.getWorld().isChunkLoaded(sample.getBlockX() >> 4, sample.getBlockZ() >> 4)) return false;
        }
        return distance < 0.01 || from.getWorld().rayTraceBlocks(from, delta, distance,
                FluidCollisionMode.NEVER, true) == null;
    }

    /**
     * 範囲の有効対象へ一度だけダメージを与えます。候補ごとに発動者の存続を再確認します。
     * @param context 発動文脈
     * @param center 固定足元中心
     * @param radius 水平半径
     * @param type 攻撃種別
     * @param ratio 攻撃力倍率
     * @param shape 円内候補への追加形状判定
     * @param spore 胞子の重複命中を20tickあたり1回へ制限するか
     */
    void hit(MobSkillContext context, Location center, double radius, AttackType type, double ratio,
            Predicate<LivingEntity> shape, boolean spore) {
        for (LivingEntity target : damage.mobCombatTargets(center, radius, 1.25)) {
            if (impactCaster(context, false) == null) return;
            Location location = target.getLocation();
            double x = location.getX() - center.getX(), z = location.getZ() - center.getZ();
            if (!damage.isMobCombatTarget(target) || target.getWorld() != center.getWorld()
                    || x * x + z * z > radius * radius || Math.abs(location.getY() - center.getY()) > 1.25
                    || !shape.test(target) || !clearPath(center, target) || (spore && !claimSpore(target))) continue;
            damage.attack(AstEntity.mob(context.mob()), damage.resolveEntity(target), type,
                    List.of(new DamageComponent(DamageElement.NONE, ratio)), DamageSource.SKILL);
        }
    }

    /**
     * 複数の胞子円が重なっても同じ対象への命中を20tickに1回へ制限し、古い参照を除去します。
     * @param target 今回の命中候補
     * @return この20tick以内で最初の胞子命中ならtrue
     */
    private boolean claimSpore(LivingEntity target) {
        int now = Bukkit.getCurrentTick();
        sporeHits.entrySet().removeIf(entry -> now - entry.getValue() >= 20);
        return sporeHits.putIfAbsent(target.getUniqueId(), now) == null;
    }

    /**
     * 地表の小刻みな掘進で、実体と0.15mの余白、足場、未読込chunkを全区間確認します。
     * @param entity 掘進する実体
     * @param from 現在位置
     * @param to 次の位置。Y変化は0.35m以下のみ許可
     * @return 全サンプルに余白と足場が存在する場合true
     */
    static boolean safeStep(Entity entity, Location from, Location to) {
        World world = from.getWorld();
        if (world == null || world != entity.getWorld() || world != to.getWorld()
                || Math.abs(to.getY() - from.getY()) > 0.35) return false;
        Vector delta = to.toVector().subtract(from.toVector());
        int samples = Math.max(1, (int) Math.ceil(delta.length() / 0.15));
        for (int i = 0; i <= samples; i++) {
            Location location = from.clone().add(delta.clone().multiply((double) i / samples));
            BoundingBox box = entity.getBoundingBox().clone()
                    .shift(location.toVector().subtract(entity.getLocation().toVector())).expand(0.15, 0, 0.15);
            int minY = (int) Math.floor(box.getMinY()), maxY = (int) Math.floor(Math.nextDown(box.getMaxY()));
            if (minY - 1 < world.getMinHeight() || maxY >= world.getMaxHeight()) return false;
            for (int x = (int) Math.floor(box.getMinX()); x <= (int) Math.floor(Math.nextDown(box.getMaxX())); x++) {
                for (int z = (int) Math.floor(box.getMinZ()); z <= (int) Math.floor(Math.nextDown(box.getMaxZ())); z++) {
                    if (!world.isChunkLoaded(x >> 4, z >> 4)) return false;
                    for (int y = minY; y <= maxY; y++) {
                        if (world.getBlockAt(x, y, z).getCollisionShape().overlaps(box.clone().shift(-x, -y, -z))) return false;
                    }
                }
            }
            // 中心と四隅の全てに地表があり、落下や角の足場抜けが起きない地点だけを許可します。
            for (double x : new double[]{box.getMinX(), location.getX(), box.getMaxX()}) {
                for (double z : new double[]{box.getMinZ(), location.getZ(), box.getMaxZ()}) {
                    Location foot = new Location(world, x, location.getY() + 0.05, z);
                    if (!world.isChunkLoaded(foot.getBlockX() >> 4, foot.getBlockZ() >> 4)
                            || world.rayTraceBlocks(foot, new Vector(0, -1, 0), 0.3,
                            FluidCollisionMode.NEVER, true) == null) return false;
                }
            }
        }
        return true;
    }

    /**
     * Mob破棄時にタスク・通常AI停止・発動文脈の参照を回収します。
     * @param mobInstanceId 回収対象のインスタンスUUID
     */
    public void clearCasterState(@NotNull UUID mobInstanceId) {
        Session session = sessions.remove(mobInstanceId);
        if (session != null) {
            if (session.task != null) session.task.cancel();
            session.context.mob().scriptedAction(false);
        }
    }

    /** Plugin停止時に全タスク、通常AI停止、胞子の重複判定を回収します。 */
    public void stop() {
        for (UUID id : List.copyOf(sessions.keySet())) clearCasterState(id);
        sporeHits.clear();
    }

    /** 同期taskとAI停止を解除するため、破棄後も発動文脈だけを終了まで保持します。 */
    private static final class Session {
        private final MobSkillContext context;
        private BukkitTask task;
        /** @param context タスク終了まで保持する発動文脈 */
        private Session(MobSkillContext context) { this.context = context; }
    }
}
