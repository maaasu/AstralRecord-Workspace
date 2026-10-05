package io.github.maaasu.astralRecord.feature.skill.executor.active.phantomarcher;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.combat.model.AttackType;
import io.github.maaasu.astralRecord.feature.combat.model.DamageElement;
import io.github.maaasu.astralRecord.feature.combat.model.DamageResult;
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
    private static final Map<UUID, PhantomArcherCastLifecycle.Cast> SENTRIES = new ConcurrentHashMap<>();
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
                positive(params, "markDamageRatio");
            }
            case "eclipse_shot" -> {
                validateProjectile(params, true, false);
                positive(params, "markDamageRatio");
                integer(params, "chargeTicks", 1, 40);
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
                integer(params, "shotCount", 1, 16);
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
        if (!PhantomArcherRuntime.hasBow(context.caster().player()))
            return SkillCastResult.failure(null);
        PhantomArcherCastLifecycle.Cast cast = PhantomArcherCastLifecycle.begin(context, lifetimeTicks(context.params()));
        try {
            return switch (action) {
                case "shadow_stitch" -> projectile(context, cast, ConditionType.WEAKNESS, false, false);
                case "soul_pierce" -> projectile(context, cast, null, true, false);
                case "eclipse_shot" -> eclipse(context, cast);
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
            case "spectral_sentry" -> 1L + (params.getInt("shotCount", 8) - 1L)
                    * params.getInt("shotIntervalTicks", 12) + flightTicks;
            case "eclipse_shot" -> params.getInt("chargeTicks", 16) + flightTicks + 2L;
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
        PhantomArcherEffects.drawBow(context, origin, direction, 0.9D);
        PhantomArcherEffects.launch(context, origin, direction, piercing);
        context.services().effects().sound(origin, Sound.ENTITY_ARROW_SHOOT, 0.8F, 0.7F);
        context.services().projectiles().launchWithTermination(context.player(), origin, direction, spec,
                (target, impact) -> {
                    if (!cast.active()) return;
                    if (area) {
                        bloom(context, cast, impact, p);
                    } else {
                        hit(context, cast, target, damageRatio(context, target, p),
                                piercing && "soul_pierce".equals(action), false,
                                condition == null ? new ActiveSkillCondition[0]
                                        : new ActiveSkillCondition[] {condition(p, condition)});
                    }
                }, termination -> {
                    if (cast.active() && termination.type() == SkillProjectileTermination.Type.BLOCK) {
                        if (area) bloom(context, cast, termination.location(), p);
                        else blockImpact(context, cast, termination.location());
                    }
                });
        if (!area) launchReadyArrow(context, cast, origin, direction, p);
        return context.success();
    }

    /** 退避後の一回限りの幻影矢を、元のスキルとは独立した命中として放ちます。 */
    private void launchReadyArrow(PlayerActiveSkillContext context, PhantomArcherCastLifecycle.Cast cast,
                                  Location origin, Vector direction, SkillParamReader p) {
        if (!PhantomArcherRuntime.takeArrow(context.caster().player())) return;
        double ratio = p.getDouble("damageRatio", 1.0D) * PhantomArcherRuntime.passiveRatio(
                PhantomArcherPassiveExecutor.WRAITH_ID, "phantomDamageRatio");
        Vector offset = direction.clone().crossProduct(new Vector(0, 1, 0));
        if (offset.lengthSquared() <= 1.0E-8D) offset = new Vector(1, 0, 0);
        Location shifted = origin.clone().add(offset.normalize().multiply(0.35D));
        PhantomArcherEffects.launch(context, shifted, direction, false);
        context.services().projectiles().launchWithTermination(context.player(), shifted, direction, spec(p, false),
                (target, impact) -> hit(context, cast, target, ratio, false, true),
                termination -> blockTermination(context, cast, termination));
    }

    /** 印と衰弱・盲目を読んで、本体の着弾倍率を計算します。 */
    private double damageRatio(PlayerActiveSkillContext context, AstEntity target, SkillParamReader p) {
        double ratio = p.getDouble("damageRatio", 1.0D);
        if (!"soul_pierce".equals(action) && !"eclipse_shot".equals(action)) return ratio;
        int stacks = PhantomArcherRuntime.marks(context.player().getUniqueId(), target.id());
        double perMark = p.getDouble("markDamageRatio", 0.0D);
        if ("soul_pierce".equals(action) && stacks > 0
                && PhantomArcherRuntime.activePassive(context.caster().player(), PhantomArcherPassiveExecutor.CURSE_ID)) {
            perMark += PhantomArcherRuntime.passiveRatio(PhantomArcherPassiveExecutor.CURSE_ID, "markBonusRatio");
        }
        ratio += stacks * perMark;
        if ("soul_pierce".equals(action) && (context.services().combat().hasCondition(target, ConditionType.WEAKNESS)
                || context.services().combat().hasCondition(target, ConditionType.BLINDNESS))) {
            ratio *= 1.0D + p.getDouble("conditionBonusRatio", 0.0D);
        }
        return ratio;
    }

    /** 着弾地点から見える管理Mobだけへ盲目を伴う範囲攻撃を適用します。 */
    private void bloom(PlayerActiveSkillContext context, PhantomArcherCastLifecycle.Cast cast,
                       Location impact, SkillParamReader p) {
        double radius = p.getDouble("impactRadius", 3.0D);
        PhantomArcherEffects.bloom(context, impact, radius);
        context.services().effects().sound(impact, Sound.ENTITY_PHANTOM_FLAP, 0.9F, 0.65F);
        for (AstEntity target : context.services().targeting().inSphere(
                context.player(), impact, radius, p.getInt("maxTargets", 5), true)) {
            hit(context, cast, target, p.getDouble("damageRatio", 1.0D), false, false,
                    condition(p, ConditionType.BLINDNESS));
        }
    }

    /** 十六tickの予備動作後、現在の目線から印を破裂させる貫通矢を放ちます。 */
    private SkillCastResult eclipse(PlayerActiveSkillContext context, PhantomArcherCastLifecycle.Cast cast) {
        SkillParamReader p = context.params();
        UUID owner = context.player().getUniqueId();
        World world = context.player().getWorld();
        int charge = p.getInt("chargeTicks", 16);
        String chargeScope = PREFIX + "eclipse-charge:" + UUID.randomUUID();
        cast.trackScope(chargeScope);
        context.services().tasks().repeat(owner, chargeScope, 1L, Math.max(1, charge / 3), 3, index -> {
            if (!cast.active() || !active(context, world) || !PhantomArcherRuntime.hasBow(context.caster().player())) {
                cast.close();
                return;
            }
            PhantomArcherEffects.drawBow(context, context.eyeLocation(), context.direction(), 0.9D + index * 0.35D);
        });
        String shotScope = PREFIX + "eclipse-shot:" + UUID.randomUUID();
        cast.trackScope(shotScope);
        context.services().tasks().later(owner, shotScope, charge, () -> {
            if (!cast.active() || !active(context, world) || !PhantomArcherRuntime.hasBow(context.caster().player())) {
                cast.close();
                return;
            }
            Location origin = context.eyeLocation();
            Vector direction = context.direction();
            PhantomArcherEffects.drawBow(context, origin, direction, 2.0D);
            PhantomArcherEffects.launch(context, origin, direction, true);
            context.services().effects().sound(origin, Sound.ENTITY_ARROW_SHOOT, 1.4F, 0.5F);
            context.services().projectiles().launchWithTermination(context.player(), origin, direction, spec(p, true),
                    (target, impact) -> hit(context, cast, target, damageRatio(context, target, p), true, false),
                    termination -> blockTermination(context, cast, termination));
            launchReadyArrow(context, cast, origin, direction, p);
        });
        return context.success();
    }

    /** YAML指定の射数だけ維持する幻影の弓を、所有者ごとに1個配置します。 */
    private SkillCastResult sentry(PlayerActiveSkillContext context, PhantomArcherCastLifecycle.Cast cast) {
        SkillParamReader p = context.params();
        UUID owner = context.player().getUniqueId();
        PhantomArcherCastLifecycle.Cast previousCast = SENTRIES.put(owner, cast);
        if (previousCast != null) previousCast.close();
        cast.onClose(() -> SENTRIES.remove(owner, cast));
        Location eye = context.eyeLocation();
        double placementRange = p.getDouble("placementRange", 12.0D);
        Location ground = context.services().targeting().groundTarget(
                context.player(), placementRange);
        Location surfaceCenter = ground.clone().add(0.0D, 1.4D, 0.0D);
        // 地表探索は崖下まで届くため、弓の中心が射程外なら遮蔽済みの視線終点へ浮かべます。
        Location center = eye.distanceSquared(surfaceCenter) <= placementRange * placementRange
                ? surfaceCenter
                : context.services().targeting().clippedEnd(eye, context.direction(), placementRange);
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
        SENTRY_SCOPES.put(owner, scope);
        cast.onClose(() -> SENTRY_SCOPES.remove(owner, scope));
        cast.trackScope(scope);
        context.services().effects().line(eye, center, 0.5D, SharedParticleDefinitions.PHANTOM_ARCHER_TRAIL);
        context.services().tasks().repeat(owner, scope, 1L, p.getInt("shotIntervalTicks", 12),
                p.getInt("shotCount", 8), index -> {
            if (!cast.active() || !active(context, world)
                    || !PhantomArcherRuntime.hasBow(context.caster().player())
                    || !scope.equals(SENTRY_SCOPES.get(owner))) {
                cast.close();
                return;
            }
            List<AstEntity> candidates = context.services().targeting().inSphere(context.player(), center,
                    p.getDouble("targetRadius", 10.0D), 1, true);
            if (candidates.isEmpty()) return;
            AstEntity target = candidates.getFirst();
            Location targetCenter = context.services().targeting().center(target);
            Vector direction = targetCenter.toVector().subtract(center.toVector());
            if (direction.lengthSquared() <= 1.0E-8D) return;
            direction.normalize();
            Location facing = center.clone().setDirection(direction);
            bow.setRotation(facing.getYaw(), facing.getPitch());
            PhantomArcherEffects.sentry(context, center, direction, index);
            context.services().projectiles().launchWithTermination(context.player(), center, direction.normalize(),
                    new SkillProjectileSpec(p.getDouble("targetRadius", 10.0D),
                            p.getDouble("projectileSpeed", 1.8D), p.getDouble("projectileHitRadius", 0.35D),
                            false, 1, SharedParticleDefinitions.PHANTOM_ARCHER_TRAIL,
                            SharedParticleDefinitions.PHANTOM_ARCHER_IMPACT),
                    (victim, ignored) -> hit(context, cast, victim, p.getDouble("damageRatio", 0.8D),
                            false, false), termination -> blockTermination(context, cast, termination));
            context.services().effects().sound(center, Sound.ENTITY_ARROW_SHOOT, 0.6F, 1.25F);
        }, () -> {
            SENTRY_SCOPES.remove(owner, scope);
            if (bow.isValid()) bow.remove();
        });
        return context.success();
    }

    /** 各射撃の瞬間に視点を取り直し、細い貫通弾で追い照準します。 */
    private SkillCastResult volley(PlayerActiveSkillContext context, PhantomArcherCastLifecycle.Cast cast) {
        SkillParamReader p = context.params();
        World world = context.player().getWorld();
        UUID owner = context.player().getUniqueId();
        String scope = PREFIX + "echo:" + UUID.randomUUID();
        cast.trackScope(scope);
        context.services().tasks().repeat(owner, scope, 1L, p.getInt("shotIntervalTicks", 6),
                p.getInt("shotCount", 4), index -> {
            if (!cast.active() || !active(context, world) || !PhantomArcherRuntime.hasBow(context.caster().player())) {
                cast.close();
                        return;
                    }
                    Location origin = context.eyeLocation();
                    Vector direction = context.direction();
                    PhantomArcherEffects.drawBow(context, origin, direction, 0.75D);
                    PhantomArcherEffects.launch(context, origin, direction, false);
                    context.services().projectiles().launchWithTermination(context.player(), origin, direction, spec(p, true),
                            (target, ignored) -> hit(context, cast, target, p.getDouble("damageRatio", 0.7D),
                                    false, false),
                            termination -> blockTermination(context, cast, termination));
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
        if (PhantomArcherRuntime.activePassive(context.caster().player(), PhantomArcherPassiveExecutor.WRAITH_ID)) {
            PhantomArcherRuntime.readyArrow(owner, PhantomArcherRuntime.passiveTicks(
                    PhantomArcherPassiveExecutor.WRAITH_ID, "readyDurationTicks"));
        }
        Location center = context.player().getLocation().add(0.0D, 0.8D, 0.0D);
        PhantomArcherEffects.retreat(context, center, context.direction());
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
                     AstEntity target, double ratio, boolean consumeMarks, boolean phantomArrow,
                     ActiveSkillCondition... conditions) {
        if (!cast.active() || !active(context, target.location().getWorld())
                || !PhantomArcherRuntime.hasBow(context.caster().player())
                || !target.isMob() || target.currentHealth() <= 0.0D
                || target.location().getWorld() != context.player().getWorld()) return;
        DamageResult result = context.services().combat().hit(context.source().skill(), context.attacker(), target,
                AttackType.RANGED, DamageElement.NONE, ratio, conditions);
        if (!PhantomArcherRuntime.effective(result)) return;
        PhantomArcherEffects.impact(context, target.location(), consumeMarks);
        if (consumeMarks) {
            PhantomArcherRuntime.consume(context.player().getUniqueId(), target.id());
        } else if (!phantomArrow && ("shadow_stitch".equals(action) || "dread_bloom".equals(action)
                || "spectral_sentry".equals(action) || "echo_volley".equals(action))) {
            PhantomArcherRuntime.addMark(context.caster().player(), target);
            PhantomArcherEffects.resonance(context, target.location(),
                    PhantomArcherRuntime.marks(context.player().getUniqueId(), target.id()));
        }
    }

    /** 地形に止められた霊矢にも固有の着弾演出と音を付けます。攻撃や印付与は行いません。 */
    private void blockImpact(PlayerActiveSkillContext context, PhantomArcherCastLifecycle.Cast cast,
                             Location impact) {
        if (cast.active() && active(context, impact.getWorld())
                && PhantomArcherRuntime.hasBow(context.caster().player())) {
            PhantomArcherEffects.impact(context, impact, "eclipse_shot".equals(action));
        }
    }

    /** 命中・射程切れを着弾演出へ重複通知せず、地形衝突だけを表示します。 */
    private void blockTermination(PlayerActiveSkillContext context, PhantomArcherCastLifecycle.Cast cast,
                                  SkillProjectileTermination termination) {
        if (termination.type() == SkillProjectileTermination.Type.BLOCK) {
            blockImpact(context, cast, termination.location());
        }
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
