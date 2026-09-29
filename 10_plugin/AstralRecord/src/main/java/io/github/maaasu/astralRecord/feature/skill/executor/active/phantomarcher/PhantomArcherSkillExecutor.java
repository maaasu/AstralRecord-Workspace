package io.github.maaasu.astralRecord.feature.skill.executor.active.phantomarcher;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.combat.model.AttackType;
import io.github.maaasu.astralRecord.feature.combat.model.DamageElement;
import io.github.maaasu.astralRecord.feature.condition.model.ConditionType;
import io.github.maaasu.astralRecord.feature.skill.active.model.ActiveSkillCondition;
import io.github.maaasu.astralRecord.feature.skill.active.model.SkillProjectileSpec;
import io.github.maaasu.astralRecord.feature.skill.active.model.SkillProjectileTermination;
import io.github.maaasu.astralRecord.feature.skill.active.service.ActiveSkillServices;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillContext;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillExecutor;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastResult;
import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParamReader;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParameterException;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** 弱体、貫通、幻影設置を組み合わせるファントムアーチャー専用実装です。 */
public final class PhantomArcherSkillExecutor extends PlayerActiveSkillExecutor {
    private static final String PREFIX = "phantom_archer_";
    private static final Map<UUID, String> SENTRY_SCOPES = new ConcurrentHashMap<>();
    private static final Map<UUID, PhantomArcherCastLifecycle.Cast> RETREATS = new ConcurrentHashMap<>();
    private final String action;

    /**
     * 専用スキルIDと共有戦闘基盤から実装を作成します。
     * @param action スキル名部分
     * @param services 射線・攻撃・有限タスクの共有サービス
     */
    public PhantomArcherSkillExecutor(@NotNull String action, @NotNull ActiveSkillServices services) {
        super(PREFIX + action, services);
        this.action = action;
    }

    /** {@inheritDoc} */
    @Override
    public void validateParams(@NotNull SkillDefinition skill) {
        super.validateParams(skill);
        SkillParamReader params = new SkillParamReader(skill.getId(), skill.getParams());
        switch (action) {
            case "shadow_stitch" -> validateProjectile(params, false, true);
            case "soul_pierce" -> {
                validateProjectile(params, true, false);
                positive(params, "conditionBonusRatio");
            }
            case "dread_bloom" -> {
                validateProjectile(params, false, true);
                positive(params, "impactRadius");
                integer(params, "maxTargets", 1, 12);
            }
            case "spectral_sentry" -> {
                positive(params, "placementRange");
                positive(params, "targetRadius");
                positive(params, "damageRatio");
                positive(params, "projectileSpeed");
                positive(params, "projectileHitRadius");
                integer(params, "shotIntervalTicks", 2, 100);
            }
            case "echo_volley" -> {
                validateProjectile(params, true, false);
                integer(params, "shotCount", 2, 6);
                integer(params, "shotIntervalTicks", 2, 40);
            }
            case "mist_retreat" -> {
                positive(params, "backstepVelocity");
                integer(params, "durationTicks", 1, 200);
                positive(params, "evasionMultiplier");
            }
            default -> throw new SkillParameterException("implementationId", "未対応のファントムアーチャースキルです");
        }
    }

    /** {@inheritDoc} */
    @Override
    protected @NotNull SkillCastResult castPlayer(@NotNull PlayerActiveSkillContext context) {
        PhantomArcherCastLifecycle.Cast cast = PhantomArcherCastLifecycle.begin(context, lifetimeTicks(context.params()));
        try {
            return switch (action) {
                case "shadow_stitch" -> projectile(context, cast, ConditionType.WEAKNESS, false, false);
                case "soul_pierce" -> projectile(context, cast, null, true, false);
                case "dread_bloom" -> projectile(context, cast, ConditionType.BLINDNESS, false, true);
                case "spectral_sentry" -> sentry(context, cast);
                case "echo_volley" -> volley(context, cast);
                case "mist_retreat" -> retreat(context, cast);
                default -> throw new IllegalStateException("validated action missing");
            };
        } catch (RuntimeException failure) {
            cast.close();
            throw failure;
        }
    }

    /** 最後の発射と弾の最大飛翔時間を含め、旧定義の命中を無効化するまでのtickを求めます。 */
    private long lifetimeTicks(SkillParamReader params) {
        long flightTicks = (long) Math.ceil(Math.max(params.getDouble("range", 0.0D),
                params.getDouble("targetRadius", 0.0D)) / params.getDouble("projectileSpeed", 1.0D)) + 3L;
        return switch (action) {
            case "spectral_sentry" -> 1L + 5L * params.getInt("shotIntervalTicks", 20) + flightTicks;
            case "echo_volley" -> 1L + (params.getInt("shotCount", 4) - 1L)
                    * params.getInt("shotIntervalTicks", 6) + flightTicks;
            case "mist_retreat" -> params.getInt("durationTicks", 60);
            default -> flightTicks;
        };
    }

    /** 共通仮想弾で単体・貫通・着弾範囲を処理します。 */
    private SkillCastResult projectile(PlayerActiveSkillContext context, PhantomArcherCastLifecycle.Cast cast,
                                       ConditionType condition,
                                       boolean piercing, boolean area) {
        SkillParamReader p = context.params();
        Location origin = context.eyeLocation();
        Vector direction = context.direction();
        SkillProjectileSpec spec = spec(p, piercing);
        context.services().effects().sound(origin, Sound.ENTITY_ARROW_SHOOT, 0.8F, 0.7F);
        context.services().projectiles().launchWithTermination(context.player(), origin, direction, spec,
                (target, impact) -> {
                    if (!cast.active()) return;
                    if (area) {
                        bloom(context, cast, impact, p);
                    } else {
                        double ratio = p.getDouble("damageRatio", 1.0D);
                        if (piercing && (context.services().combat().hasCondition(target, ConditionType.WEAKNESS)
                                || context.services().combat().hasCondition(target, ConditionType.BLINDNESS))) {
                            ratio *= 1.0D + p.getDouble("conditionBonusRatio", 0.0D);
                        }
                        hit(context, cast, target, ratio, condition == null ? new ActiveSkillCondition[0]
                                : new ActiveSkillCondition[] {condition(p, condition)});
                    }
                }, termination -> {
                    if (cast.active() && area && termination.type() == SkillProjectileTermination.Type.BLOCK) {
                        bloom(context, cast, termination.location(), p);
                    }
                });
        return context.success();
    }

    /** 着弾地点から見える管理Mobだけへ盲目を伴う範囲攻撃を適用します。 */
    private void bloom(PlayerActiveSkillContext context, PhantomArcherCastLifecycle.Cast cast,
                       Location impact, SkillParamReader p) {
        double radius = p.getDouble("impactRadius", 3.0D);
        context.services().effects().ring(impact, radius, 28, SharedParticleDefinitions.PHANTOM_ARCHER_TRAIL);
        context.services().effects().sound(impact, Sound.ENTITY_PHANTOM_FLAP, 0.9F, 0.65F);
        for (AstEntity target : context.services().targeting().inSphere(
                context.player(), impact, radius, p.getInt("maxTargets", 5), true)) {
            hit(context, cast, target, p.getDouble("damageRatio", 1.0D), condition(p, ConditionType.BLINDNESS));
        }
    }

    /** 六射だけ維持する幻影の弓を1人1個のタスクとして配置します。 */
    private SkillCastResult sentry(PlayerActiveSkillContext context, PhantomArcherCastLifecycle.Cast cast) {
        SkillParamReader p = context.params();
        UUID owner = context.player().getUniqueId();
        Location eye = context.eyeLocation();
        Location ground = context.services().targeting().groundTarget(
                context.player(), p.getDouble("placementRange", 12.0D));
        Location center = ground.clone().add(0.0D, 1.4D, 0.0D);
        World world = center.getWorld();
        ItemDisplay bow = world.spawn(center, ItemDisplay.class, display -> {
            display.setItemStack(new ItemStack(Material.BOW));
            display.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
            display.setGravity(false);
            display.setInvulnerable(true);
            display.setPersistent(false);
            display.setSilent(true);
            display.setBrightness(new Display.Brightness(12, 15));
            display.setViewRange(32.0F);
            display.setTransformation(new Transformation(new Vector3f(0.0F, 0.0F, 0.0F),
                    new Quaternionf(), new Vector3f(1.25F, 1.25F, 1.25F), new Quaternionf()));
        });
        cast.onClose(() -> { if (bow.isValid()) bow.remove(); });
        String scope = PREFIX + "sentry:" + UUID.randomUUID();
        String previous = SENTRY_SCOPES.put(owner, scope);
        if (previous != null) context.services().tasks().cancel(owner, previous);
        cast.trackScope(scope);
        context.services().effects().line(eye, center, 0.5D, SharedParticleDefinitions.PHANTOM_ARCHER_TRAIL);
        context.services().tasks().repeat(owner, scope, 1L, p.getInt("shotIntervalTicks", 20), 6, index -> {
            if (!cast.active() || !active(context, world) || !scope.equals(SENTRY_SCOPES.get(owner))) {
                context.services().tasks().cancel(owner, scope);
                return;
            }
            context.services().effects().ring(center, 0.5D, 12, SharedParticleDefinitions.PHANTOM_ARCHER_SENTRY);
            List<AstEntity> candidates = context.services().targeting().inSphere(context.player(), center,
                    p.getDouble("targetRadius", 10.0D), 1, true);
            if (candidates.isEmpty()) return;
            AstEntity target = candidates.getFirst();
            Location targetCenter = context.services().targeting().center(target);
            Vector direction = targetCenter.toVector().subtract(center.toVector());
            if (direction.lengthSquared() <= 1.0E-8D) return;
            context.services().projectiles().launch(context.player(), center, direction.normalize(),
                    new SkillProjectileSpec(p.getDouble("targetRadius", 10.0D),
                            p.getDouble("projectileSpeed", 1.8D), p.getDouble("projectileHitRadius", 0.35D),
                            false, 1, SharedParticleDefinitions.PHANTOM_ARCHER_TRAIL,
                            SharedParticleDefinitions.PHANTOM_ARCHER_IMPACT),
                    (victim, ignored) -> hit(context, cast, victim, p.getDouble("damageRatio", 0.8D)), ignored -> { });
            context.services().effects().sound(center, Sound.ENTITY_ARROW_SHOOT, 0.6F, 1.25F);
        }, () -> {
            SENTRY_SCOPES.remove(owner, scope);
            if (bow.isValid()) bow.remove();
        });
        return context.success();
    }

    /** 発動時の視点を固定し、間隔を空けて細い貫通弾を放ちます。 */
    private SkillCastResult volley(PlayerActiveSkillContext context, PhantomArcherCastLifecycle.Cast cast) {
        SkillParamReader p = context.params();
        Location origin = context.eyeLocation();
        Vector direction = context.direction();
        World world = origin.getWorld();
        UUID owner = context.player().getUniqueId();
        String scope = PREFIX + "echo:" + UUID.randomUUID();
        cast.trackScope(scope);
        context.services().tasks().repeat(owner, scope, 1L, p.getInt("shotIntervalTicks", 6),
                p.getInt("shotCount", 4), index -> {
                    if (!cast.active() || !active(context, world)) {
                        context.services().tasks().cancel(owner, scope);
                        return;
                    }
                    context.services().projectiles().launch(context.player(), origin, direction, spec(p, true),
                            (target, ignored) -> hit(context, cast, target, p.getDouble("damageRatio", 0.7D)),
                            ignored -> { });
                    context.services().effects().sound(origin, Sound.ENTITY_ARROW_SHOOT, 0.6F, 1.6F);
                });
        return context.success();
    }

    /** 既存移動可否判定を通して後退し、短い回避率倍率を設定します。 */
    private SkillCastResult retreat(PlayerActiveSkillContext context, PhantomArcherCastLifecycle.Cast cast) {
        SkillParamReader p = context.params();
        Vector moved = context.services().movement().backstepVelocity(context.player(), context.attacker(),
                p.getDouble("backstepVelocity", 1.4D));
        if (moved == null) {
            cast.close();
            return context.success();
        }
        UUID owner = context.player().getUniqueId();
        PhantomArcherCastLifecycle.Cast previous = RETREATS.remove(owner);
        if (previous != null) previous.close();
        RETREATS.put(owner, cast);
        cast.onClose(() -> {
            if (RETREATS.remove(owner, cast))
                context.services().temporaryEffects().clear(owner, PREFIX + "mist_retreat");
        });
        context.services().temporaryEffects().applyDefenseAndEvasionMultipliers(context.player().getUniqueId(),
                PREFIX + "mist_retreat", p.getInt("durationTicks", 60), 1.0D,
                p.getDouble("evasionMultiplier", 1.4D));
        Location center = context.player().getLocation().add(0.0D, 0.8D, 0.0D);
        context.services().effects().ring(center, 1.0D, 20, SharedParticleDefinitions.PHANTOM_ARCHER_TRAIL);
        context.services().effects().sound(center, Sound.ENTITY_PHANTOM_FLAP, 0.7F, 1.3F);
        return context.success();
    }

    /** 所有者が同じワールドで戦闘可能な状態を維持しているか確認します。 */
    private boolean active(PlayerActiveSkillContext context, World world) {
        return context.player().isOnline() && !context.player().isDead()
                && context.player().getWorld() == world
                && context.caster().player().getStatusSnapshot().getCurrentHp() > 0.0D;
    }

    /** スキル定義付きの共通命中を適用します。状態異常は有効命中時だけ付与されます。 */
    private void hit(PlayerActiveSkillContext context, PhantomArcherCastLifecycle.Cast cast,
                     AstEntity target, double ratio,
                     ActiveSkillCondition... conditions) {
        if (!cast.active() || !active(context, target.location().getWorld())
                || !target.isMob() || target.currentHealth() <= 0.0D
                || target.location().getWorld() != context.player().getWorld()) return;
        context.services().combat().hit(context.source().skill(), context.attacker(), target,
                AttackType.RANGED, DamageElement.NONE, ratio, conditions);
    }

    /** YAMLの確率と継続時間を共通状態異常へ渡します。 */
    private ActiveSkillCondition condition(SkillParamReader p, ConditionType type) {
        return new ActiveSkillCondition(type, p.getDouble("conditionChance", 0.0D),
                p.getInt("conditionTicks", 1), 1.0D);
    }

    /** 速度・当たり判定・貫通上限をYAMLから仮想弾へ変換します。 */
    private SkillProjectileSpec spec(SkillParamReader p, boolean piercing) {
        return new SkillProjectileSpec(p.getDouble("range", 16.0D), p.getDouble("projectileSpeed", 1.8D),
                p.getDouble("projectileHitRadius", 0.4D), piercing,
                piercing ? p.getInt("maxHits", 4) : 1,
                SharedParticleDefinitions.PHANTOM_ARCHER_TRAIL,
                SharedParticleDefinitions.PHANTOM_ARCHER_IMPACT);
    }

    /** 共通弾に必要な値を検証します。 */
    private void validateProjectile(SkillParamReader p, boolean piercing, boolean condition) {
        positive(p, "range");
        positive(p, "damageRatio");
        positive(p, "projectileSpeed");
        positive(p, "projectileHitRadius");
        if (piercing) integer(p, "maxHits", 1, 8);
        if (condition) {
            double chance = p.getDouble("conditionChance", Double.NaN);
            if (!Double.isFinite(chance) || chance < 0.0D || chance > 100.0D)
                throw new SkillParameterException("conditionChance", "0から100が必要です");
            integer(p, "conditionTicks", 1, 400);
        }
    }

    /** 有限の正数パラメータを要求します。 */
    private void positive(SkillParamReader p, String key) {
        double value = p.getDouble(key, Double.NaN);
        if (!Double.isFinite(value) || value <= 0.0D)
            throw new SkillParameterException(key, "有限の正数が必要です");
    }

    /** 整数パラメータを指定範囲へ制限します。 */
    private void integer(SkillParamReader p, String key, int min, int max) {
        int value = p.getInt(key, -1);
        if (value < min || value > max)
            throw new SkillParameterException(key, min + "から" + max + "の整数が必要です");
    }
}
