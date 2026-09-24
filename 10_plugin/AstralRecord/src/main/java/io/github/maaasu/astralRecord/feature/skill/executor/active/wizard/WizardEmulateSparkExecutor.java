package io.github.maaasu.astralRecord.feature.skill.executor.active.wizard;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.combat.model.AttackType;
import io.github.maaasu.astralRecord.feature.combat.model.DamageElement;
import io.github.maaasu.astralRecord.feature.condition.model.ConditionType;
import io.github.maaasu.astralRecord.feature.skill.active.model.ActiveSkillCondition;
import io.github.maaasu.astralRecord.feature.skill.active.model.SkillLineTargetHit;
import io.github.maaasu.astralRecord.feature.skill.active.service.ActiveSkillServices;
import io.github.maaasu.astralRecord.feature.skill.active.service.SkillTargetingService;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillContext;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillExecutor;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastResult;
import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParamReader;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParameterException;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 地面で跳ね、壁で反射する雷弾を前方へ散開させるウィザード魔法です。 */
public final class WizardEmulateSparkExecutor extends PlayerActiveSkillExecutor {

    public static final String ID = "wizard_emulate_spark";
    private static final double GRAVITY_PER_TICK_SQUARED = 0.04D;
    private static final double INITIAL_DOWNWARD_SPEED = -0.12D;
    private static final double SURFACE_OFFSET = 0.05D;
    private static final double MOVEMENT_EPSILON = 1.0E-7D;
    private static final int MAX_REFLECTIONS_PER_TICK = 4;
    private static final int PARTICLE_INTERVAL_TICKS = 2;
    private static final float DISPLAY_SCALE = 0.28F;
    private final Map<UUID, SparkRuntime> runtimes = new HashMap<>();

    /**
     * 共有発動スキルサービスで初期化します。
     *
     * @param services 対象判定、戦闘、演出、反復処理の共有サービス
     */
    public WizardEmulateSparkExecutor(@NotNull ActiveSkillServices services) {
        super(ID, services);
    }

    /** {@inheritDoc} */
    @Override
    public void validateParams(@NotNull SkillDefinition skill) {
        super.validateParams(skill);
        SkillParamReader params = new SkillParamReader(skill.getId(), skill.getParams());
        requirePositive(params, "damageRatio");
        requirePositive(params, "projectileSpeedPerSecond");
        requirePositive(params, "projectileHitRadius");
        requirePositive(params, "bounceHeight");
        if (params.getDouble("bounceHeight", 0.0D) <= SURFACE_OFFSET) {
            throw new SkillParameterException("bounceHeight", "イミュレートスパークの跳躍高は衝突面オフセットより大きい値が必要です");
        }
        if (params.getInt("projectileCount", 0) < 1) {
            throw new SkillParameterException("projectileCount", "イミュレートスパークの弾数は1以上が必要です");
        }
        if (params.getInt("durationTicks", 0) < 1) {
            throw new SkillParameterException("durationTicks", "イミュレートスパークの持続tickは1以上が必要です");
        }
        if (params.getInt("shockDurationTicks", 0) < 1) {
            throw new SkillParameterException("shockDurationTicks", "イミュレートスパークの感電時間は1以上が必要です");
        }
        double spreadAngle = params.getDouble("spreadAngle", -1.0D);
        if (spreadAngle < 0.0D || spreadAngle > 180.0D) {
            throw new SkillParameterException("spreadAngle", "イミュレートスパークの散開角は0～180度が必要です");
        }
        double shockChance = params.getDouble("shockChance", -1.0D);
        if (shockChance < 0.0D || shockChance > 100.0D) {
            throw new SkillParameterException("shockChance", "イミュレートスパークの感電確率は0～100%が必要です");
        }
    }

    /** {@inheritDoc} */
    @Override
    protected @NotNull SkillCastResult castPlayer(@NotNull PlayerActiveSkillContext context) {
        SkillParamReader params = context.params();
        double damageRatio = params.getDouble("damageRatio", 1.15D);
        int projectileCount = params.getInt("projectileCount", 1);
        int durationTicks = params.getInt("durationTicks", 30);
        double speedPerTick = params.getDouble("projectileSpeedPerSecond", 12.0D) / 20.0D;
        double hitRadius = params.getDouble("projectileHitRadius", 0.35D);
        double bounceSpeed = Math.sqrt(
                2.0D * GRAVITY_PER_TICK_SQUARED
                        * (params.getDouble("bounceHeight", 1.3D) - SURFACE_OFFSET)
        );
        double spreadAngle = params.getDouble("spreadAngle", 30.0D);
        ActiveSkillCondition shocked = new ActiveSkillCondition(
                ConditionType.SHOCKED,
                params.getDouble("shockChance", 12.0D),
                params.getInt("shockDurationTicks", 100),
                1.0D
        );
        Vector forward = context.direction().setY(0.0D);
        if (forward.lengthSquared() <= MOVEMENT_EPSILON) {
            double yaw = Math.toRadians(context.player().getYaw());
            forward = new Vector(-Math.sin(yaw), 0.0D, Math.cos(yaw));
        }
        forward.normalize();
        Location origin = context.eyeLocation().add(forward.clone().multiply(0.35D));
        List<SparkState> sparks = spreadStates(origin, forward, projectileCount, spreadAngle);
        try {
            for (SparkState spark : sparks) {
                spark.spawnDisplay();
            }
        } catch (RuntimeException exception) {
            sparks.forEach(SparkState::destroyDisplay);
            throw exception;
        }
        SparkCast cast = new SparkCast(
                context.attacker(), origin, sparks, durationTicks, speedPerTick,
                hitRadius, bounceSpeed, damageRatio, shocked
        );
        UUID casterId = context.player().getUniqueId();
        SparkRuntime runtime = runtimes.get(casterId);
        if (runtime != null) {
            if (runtime.advancing) {
                runtime.pendingCasts.add(cast);
            } else {
                runtime.casts.add(cast);
            }
            return context.success();
        }
        SparkRuntime created = new SparkRuntime(context.player(), context.services());
        created.casts.add(cast);
        runtimes.put(casterId, created);
        try {
            context.services().tasks().repeat(
                    casterId, created.scope, 0L, 1L, Integer.MAX_VALUE,
                    tick -> advanceRuntime(created),
                    () -> {
                        created.casts.forEach(SparkCast::destroyDisplays);
                        created.pendingCasts.forEach(SparkCast::destroyDisplays);
                        created.casts.clear();
                        created.pendingCasts.clear();
                        runtimes.remove(casterId, created);
                    }
            );
        } catch (RuntimeException exception) {
            created.casts.forEach(SparkCast::destroyDisplays);
            runtimes.remove(casterId, created);
            throw exception;
        }
        return context.success();
    }

    /**
     * 発動者の全castを1tickで進め、Mob候補を共有して粒子種別ごとに表示点を近傍viewerへまとめて送信します。
     *
     * @param runtime 発動者単位の追跡状態
     */
    private void advanceRuntime(@NotNull SparkRuntime runtime) {
        if (!runtime.player.isOnline()) {
            runtime.services.tasks().cancel(runtime.casterId, runtime.scope);
            return;
        }
        runtime.casts.removeIf(cast -> {
            boolean expired = cast.origin.getWorld() != runtime.player.getWorld()
                    || cast.sparks.isEmpty() || cast.elapsedTicks >= cast.durationTicks;
            if (expired) {
                cast.destroyDisplays();
            }
            return expired;
        });
        if (runtime.casts.isEmpty()) {
            runtime.services.tasks().cancel(runtime.casterId, runtime.scope);
            return;
        }
        SkillTargetingService.LineTargetSnapshot snapshot =
                runtime.services.targeting().captureLineTargetSnapshot(runtime.player);
        List<Location> visible = new ArrayList<>();
        runtime.advancing = true;
        try {
            for (Iterator<SparkCast> castIterator = runtime.casts.iterator(); castIterator.hasNext(); ) {
                SparkCast cast = castIterator.next();
                for (Iterator<SparkState> sparkIterator = cast.sparks.iterator(); sparkIterator.hasNext(); ) {
                    SparkState spark = sparkIterator.next();
                    if (advanceSpark(runtime, snapshot, cast, spark)) {
                        spark.destroyDisplay();
                        sparkIterator.remove();
                    } else {
                        spark.updateDisplay();
                        if (cast.elapsedTicks % PARTICLE_INTERVAL_TICKS == 0) {
                            visible.add(spark.location.clone());
                        }
                    }
                }
                cast.elapsedTicks++;
                if (cast.sparks.isEmpty() || cast.elapsedTicks >= cast.durationTicks) {
                    cast.destroyDisplays();
                    castIterator.remove();
                }
            }
        } finally {
            runtime.advancing = false;
            if (!runtime.pendingCasts.isEmpty()) {
                runtime.casts.addAll(runtime.pendingCasts);
                runtime.pendingCasts.clear();
            }
        }
        runtime.services.effects().pointsNearViewers(visible, SharedParticleDefinitions.WIZARD_EMULATE_SPARK);
        runtime.services.effects().pointsNearViewers(
                visible, SharedParticleDefinitions.WIZARD_EMULATE_SPARK_FIREWORK
        );
        if (runtime.casts.isEmpty()) {
            runtime.services.tasks().cancel(runtime.casterId, runtime.scope);
        }
    }

    /**
     * 一つの雷弾を1tick進め、Blockより手前で敵へ当たれば直ちに終了します。
     *
     * @param runtime 発動者単位の共有サービスとプレイヤー
     * @param snapshot 同tickの管理対象Mob候補
     * @param cast 弾ごとの倍率と寿命を持つ発動状態
     * @param spark 更新する雷弾
     * @return 敵へ命中して雷弾を消す場合はtrue
     */
    private boolean advanceSpark(
            @NotNull SparkRuntime runtime,
            @NotNull SkillTargetingService.LineTargetSnapshot snapshot,
            @NotNull SparkCast cast,
            @NotNull SparkState spark
    ) {
        double remainingTime = 1.0D;
        for (int reflection = 0; reflection < MAX_REFLECTIONS_PER_TICK
                && remainingTime > MOVEMENT_EPSILON; reflection++) {
            Vector step = spark.horizontal.clone().multiply(cast.speedPerTick * remainingTime)
                    .add(new Vector(0.0D,
                            spark.verticalSpeed * remainingTime
                                    - 0.5D * GRAVITY_PER_TICK_SQUARED * remainingTime * remainingTime,
                            0.0D));
            double distance = step.length();
            if (distance <= MOVEMENT_EPSILON) {
                break;
            }
            Vector direction = step.clone().multiply(1.0D / distance);
            SkillTargetingService.BlockHit blockHit = runtime.services.targeting().blockHit(
                    spark.location, direction, distance
            );
            double collisionDistance = blockHit == null
                    ? distance
                    : Math.min(distance, spark.location.distance(blockHit.location()));
            SkillLineTargetHit targetHit = runtime.services.targeting().lineTargetHits(
                    runtime.player, snapshot, spark.location, direction,
                    collisionDistance, cast.hitRadius, 1, blockHit == null
            ).stream().findFirst().orElse(null);
            if (targetHit != null) {
                runtime.services.combat().hit(
                        cast.attacker, targetHit.target(), AttackType.MAGIC, DamageElement.LIGHTNING,
                        cast.damageRatio, cast.shocked
                );
                runtime.services.effects().point(
                        targetHit.location(), SharedParticleDefinitions.WIZARD_EMULATE_SPARK
                );
                runtime.services.effects().point(
                        targetHit.location(), SharedParticleDefinitions.WIZARD_EMULATE_SPARK_FIREWORK
                );
                return true;
            }
            if (blockHit == null) {
                spark.location.add(step);
                spark.verticalSpeed -= GRAVITY_PER_TICK_SQUARED * remainingTime;
                break;
            }
            double elapsed = remainingTime * Math.clamp(collisionDistance / distance, 0.0D, 1.0D);
            spark.verticalSpeed -= GRAVITY_PER_TICK_SQUARED * elapsed;
            spark.location = blockHit.location().add(blockHit.normal().clone().multiply(SURFACE_OFFSET));
            reflectAtBlock(spark, blockHit.normal(), cast.bounceSpeed);
            remainingTime -= elapsed;
        }
        return false;
    }

    /**
     * 地面・天井では鉛直速度、壁では水平方向を衝突面から更新します。
     *
     * @param spark 反射させる雷弾
     * @param normal Block衝突面の外向き法線
     * @param bounceSpeed 地面から1.3m跳ね上がるための初速度
     */
    private static void reflectAtBlock(
            @NotNull SparkState spark,
            @NotNull Vector normal,
            double bounceSpeed
    ) {
        if (normal.getY() > 0.5D) {
            spark.verticalSpeed = bounceSpeed;
            return;
        }
        if (normal.getY() < -0.5D) {
            spark.verticalSpeed = -Math.abs(spark.verticalSpeed);
            return;
        }
        Vector wallNormal = normal.clone().setY(0.0D).normalize();
        spark.horizontal.subtract(wallNormal.multiply(2.0D * spark.horizontal.dot(wallNormal))).normalize();
    }

    /**
     * 視点の水平前方を中心として雷弾を指定角度へ均等に散開させます。
     *
     * @param origin 発生地点
     * @param forward 水平前方の単位ベクトル
     * @param count 雷弾数
     * @param angleDegrees 全散開角
     * @return 発動単位で独立した雷弾状態
     */
    private static @NotNull List<SparkState> spreadStates(
            @NotNull Location origin,
            @NotNull Vector forward,
            int count,
            double angleDegrees
    ) {
        List<SparkState> sparks = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            double angle = count == 1 ? 0.0D
                    : Math.toRadians(-angleDegrees / 2.0D + angleDegrees * index / (count - 1));
            sparks.add(new SparkState(origin.clone(), forward.clone().rotateAroundY(angle)));
        }
        return sparks;
    }

    /**
     * マスタの正数パラメーターを検証します。
     *
     * @param params スキルマスタの基礎params
     * @param key 正数を要求するパラメーター名
     */
    private static void requirePositive(@NotNull SkillParamReader params, @NotNull String key) {
        if (!(params.getDouble(key, 0.0D) > 0.0D)) {
            throw new SkillParameterException(key, "イミュレートスパークの params[" + key + "] は正数が必要です");
        }
    }

    /** 一人の発動者が残している全castを一つのtaskへ集約します。 */
    private static final class SparkRuntime {
        private final Player player;
        private final ActiveSkillServices services;
        private final UUID casterId;
        private final String scope;
        private final List<SparkCast> casts = new ArrayList<>();
        private final List<SparkCast> pendingCasts = new ArrayList<>();
        private boolean advancing;

        /**
         * 発動者と共有サービスからtask追跡状態を初期化します。
         *
         * @param player 発動者
         * @param services 共有発動スキルサービス
         */
        private SparkRuntime(@NotNull Player player, @NotNull ActiveSkillServices services) {
            this.player = player;
            this.services = services;
            this.casterId = player.getUniqueId();
            this.scope = "wizard-emulate-spark:" + UUID.randomUUID();
        }
    }

    /** 一回の発動ごとに固定した攻撃値と、残存する弾・寿命を保持します。 */
    private static final class SparkCast {
        private final AstEntity attacker;
        private final Location origin;
        private final List<SparkState> sparks;
        private final int durationTicks;
        private final double speedPerTick;
        private final double hitRadius;
        private final double bounceSpeed;
        private final double damageRatio;
        private final ActiveSkillCondition shocked;
        private int elapsedTicks;

        /**
         * 発動時に確定した弾道、攻撃値、寿命からcastを作ります。
         *
         * @param attacker 発動時の攻撃者スナップショット
         * @param origin 発生地点とworldの基準
         * @param sparks 発射する独立した雷弾
         * @param durationTicks 発動ごとの寿命
         * @param speedPerTick 水平移動量
         * @param hitRadius 命中半径
         * @param bounceSpeed 地面からの上向き初速度
         * @param damageRatio 一発の魔法攻撃倍率
         * @param shocked 命中時の感電条件
         */
        private SparkCast(
                @NotNull AstEntity attacker,
                @NotNull Location origin,
                @NotNull List<SparkState> sparks,
                int durationTicks,
                double speedPerTick,
                double hitRadius,
                double bounceSpeed,
                double damageRatio,
                @NotNull ActiveSkillCondition shocked
        ) {
            this.attacker = attacker;
            this.origin = origin;
            this.sparks = sparks;
            this.durationTicks = durationTicks;
            this.speedPerTick = speedPerTick;
            this.hitRadius = hitRadius;
            this.bounceSpeed = bounceSpeed;
            this.damageRatio = damageRatio;
            this.shocked = shocked;
        }

        /** 命中・期限切れ・中断時に残存する雷弾の表示をすべて破棄します。 */
        private void destroyDisplays() {
            sparks.forEach(SparkState::destroyDisplay);
        }
    }

    /** 発動ごと、弾ごとに独立した位置と速度を保持します。 */
    private static final class SparkState {
        private Location location;
        private final Vector horizontal;
        private double verticalSpeed = INITIAL_DOWNWARD_SPEED;
        @Nullable
        private BlockDisplay display;

        /**
         * 発生地点と散開方向から雷弾を初期化します。
         *
         * @param location 発生地点
         * @param horizontal 水平の進行方向
         */
        private SparkState(@NotNull Location location, @NotNull Vector horizontal) {
            this.location = location;
            this.horizontal = horizontal.normalize();
        }

        /** 雷弾の位置へ小型アメジストブロックの表示Entityを生成します。 */
        private void spawnDisplay() {
            World world = location.getWorld();
            if (world == null) {
                throw new IllegalStateException("イミュレートスパークの表示worldがありません");
            }
            display = world.spawn(location, BlockDisplay.class, entity -> {
                entity.setBlock(Material.AMETHYST_BLOCK.createBlockData());
                entity.setBillboard(Display.Billboard.FIXED);
                entity.setGravity(false);
                entity.setInvulnerable(true);
                entity.setPersistent(false);
                entity.setSilent(true);
                entity.setShadowRadius(0.0F);
                entity.setShadowStrength(0.0F);
                entity.setTeleportDuration(1);
                entity.setInterpolationDuration(1);
                entity.setBrightness(new Display.Brightness(15, 15));
                entity.setViewRange(32.0F);
                entity.setTransformation(new Transformation(
                        new Vector3f(-DISPLAY_SCALE / 2.0F, -DISPLAY_SCALE / 2.0F, -DISPLAY_SCALE / 2.0F),
                        new Quaternionf(),
                        new Vector3f(DISPLAY_SCALE, DISPLAY_SCALE, DISPLAY_SCALE),
                        new Quaternionf()
                ));
            });
        }

        /** 生存中の表示Entityを次の雷弾位置へ追従させます。 */
        private void updateDisplay() {
            if (display != null && display.isValid()) {
                display.teleport(location);
            }
        }

        /** 表示Entityを一度だけ破棄し、参照を解放します。 */
        private void destroyDisplay() {
            if (display != null) {
                if (display.isValid()) {
                    display.remove();
                }
                display = null;
            }
        }
    }
}
