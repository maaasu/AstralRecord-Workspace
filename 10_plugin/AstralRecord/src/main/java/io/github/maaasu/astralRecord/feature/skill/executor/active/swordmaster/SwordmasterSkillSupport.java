package io.github.maaasu.astralRecord.feature.skill.executor.active.swordmaster;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.combat.model.AttackType;
import io.github.maaasu.astralRecord.feature.combat.model.DamageElement;
import io.github.maaasu.astralRecord.feature.combat.model.DamageResult;
import io.github.maaasu.astralRecord.feature.condition.model.ConditionType;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.skill.active.model.ActiveSkillCondition;
import io.github.maaasu.astralRecord.feature.skill.active.service.ActiveSkillServices;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillContext;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillExecutor;
import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParamReader;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParameterException;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastResult;
import io.github.maaasu.astralRecord.feature.status.model.StatusType;
import io.github.maaasu.astralRecord.feature.status.model.HealthRecoveryContext;
import io.github.maaasu.astralRecord.feature.skill.service.SkillPresentationUtil;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinition;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.IntConsumer;

/** ソードマスターの命中・有限演出・移動攻撃を共通の戦闘経路へ接続します。 */
abstract class SwordmasterSkillSupport extends PlayerActiveSkillExecutor {
    private final List<String> requiredParams;

    /** 実装ID、共通サービス、必須の数値パラメータで初期化します。 */
    protected SwordmasterSkillSupport(String id, ActiveSkillServices services, String... requiredParams) {
        super(id, services);
        this.requiredParams = List.of(requiredParams);
    }

    /** {@inheritDoc} */
    @Override
    public void validateParams(@NotNull SkillDefinition skill) {
        super.validateParams(skill);
        SkillParamReader params = new SkillParamReader(skill.getId(), skill.getParams());
        for (String key : requiredParams) {
            double value = params.getDouble(key, Double.NaN);
            if (!Double.isFinite(value) || value <= 0.0D) {
                throw new SkillParameterException(key, "ソードマスターの必須値は有限の正数が必要です");
            }
            boolean integer = key.endsWith("Ticks") || key.equals("maxTargets") || key.equals("hitCount");
            double limit = key.endsWith("Ticks") ? 400.0D
                    : key.equals("maxTargets") ? 12.0D : key.equals("hitCount") ? 6.0D
                    : key.equals("angle") ? 180.0D : key.equals("range") ? 12.0D : key.equals("radius") || key.endsWith("Radius") ? 6.0D
                    : key.endsWith("HpRatio") || key.equals("executeThreshold") ? 1.0D
                    : key.equals("conditionChance") ? 100.0D : 30.0D;
            if (value > limit || (integer && value != Math.rint(value))) {
                throw new SkillParameterException(key, "ソードマスターの効果範囲・回数・割合が許容範囲外です");
            }
        }
    }

    /** 検証済みの必須数値を取得します。 */
    protected static double value(PlayerActiveSkillContext context, String key) {
        return context.params().getDouble(key, Double.NaN);
    }

    /** 発動時のワールド・生存状態を維持しているか確認します。 */
    protected static boolean active(PlayerActiveSkillContext context, World world) {
        return context.player().isOnline() && !context.player().isDead()
                && context.player().getWorld() == world
                && context.caster().player().getStatusSnapshot().getCurrentHp() > 0.0D;
    }

    /** 発動ごとの一意なタスクを登録し、死亡・退出・ワールド変更時は打ち切ります。 */
    protected static void sequence(PlayerActiveSkillContext context, int count, int intervalTicks,
                                   IntConsumer frame) {
        UUID owner = context.player().getUniqueId();
        World world = context.player().getWorld();
        String scope = context.source().skill().getId() + ":" + UUID.randomUUID();
        context.services().tasks().repeat(owner, scope, 1L, intervalTicks, count, index -> {
            if (!active(context, world)) {
                context.services().tasks().cancel(owner, scope);
                return;
            }
            frame.accept(index);
        });
    }

    /** 共通のPvE対象選択で視線前方の敵だけを取得します。 */
    protected static List<AstEntity> cone(PlayerActiveSkillContext context) {
        return context.services().targeting().inCone(context.player(), value(context, "range"),
                value(context, "angle"), (int) value(context, "maxTargets"), true);
    }

    /** 遮蔽物を越えない球形範囲で、近い管理Mobから選択します。 */
    protected static List<AstEntity> sphere(PlayerActiveSkillContext context, Location center, double radius) {
        return context.services().targeting().inSphere(context.player(), center, radius,
                (int) value(context, "maxTargets"), true);
    }

    /** 生きた管理Mobへの一撃を適用し、この直接命中による撃破だけを返します。 */
    protected static Hit hit(PlayerActiveSkillContext context, AstEntity target, DamageElement element,
                             double ratio, ActiveSkillCondition... conditions) {
        if (!target.isMob() || target.currentHealth() <= 0.0D
                || target.location().getWorld() != context.player().getWorld()) {
            return new Hit(new DamageResult(0.0D), false);
        }
        double before = target.currentHealth();
        DamageResult result = context.services().combat().hit(
                context.source().skill(), context.attacker(), target, AttackType.MELEE, element, ratio, conditions);
        return new Hit(result, before > 0.0D && result.finalDamage() > 0.0D
                && !result.evaded() && target.currentHealth() <= 0.0D);
    }

    /** 状態異常の確率と時間をレベル解決済みパラメータから構築します。 */
    protected static ActiveSkillCondition condition(PlayerActiveSkillContext context, ConditionType type) {
        return new ActiveSkillCondition(type, value(context, "conditionChance"),
                (int) value(context, "conditionTicks"), 1.0D);
    }

    /** 自己回復をHP上限・回復阻害・既存の回復通知へ通します。 */
    protected static void heal(PlayerActiveSkillContext context, double maxHpRatio) {
        context.services().combat().recoverHp(context.caster().player(),
                context.source().statusSnapshot().getMaxValue(StatusType.MAX_HEALTH) * maxHpRatio,
                HealthRecoveryContext.by(context.caster().player(),
                        SkillPresentationUtil.plainName(context.source().skill(), "")));
    }

    /** 前進が成功した実経路だけを斬り、撃破時は次tickで再使用可能な結果を返します。 */
    protected static SkillCastResult dash(PlayerActiveSkillContext context, DamageElement element,
                                          SharedParticleDefinition color, ActiveSkillCondition... conditions) {
        var movement = context.services().movement().dash(context.player(), context.attacker(), value(context, "range"));
        if (!movement.moved()) {
            return SkillCastResult.failure(PlayerMsgId.P_5805);
        }
        Location from = movement.start().clone().add(0.0D, 0.9D, 0.0D);
        Location to = movement.end().clone().add(0.0D, 0.9D, 0.0D);
        Vector direction = to.toVector().subtract(from.toVector());
        double distance = direction.length();
        boolean killed = false;
        for (AstEntity target : context.services().targeting().inLineBeforeBlock(
                context.player(), from, direction, distance, value(context, "hitRadius"),
                (int) value(context, "maxTargets"))) {
            killed |= hit(context, target, element, value(context, "damageRatio"), conditions).killed();
        }
        trail(context, from, to, color);
        slash(context, movement.end().clone().add(0.0D, 0.9D, 0.0D),
                direction, 2.2D, color, false);
        if (killed) {
            burst(context, to, 1.1D, SharedParticleDefinitions.SWORDMASTER_GOLD);
            context.services().effects().sound(to, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.9F, 1.8F);
        }
        return killed ? context.successWithCooldownTicks(1L) : context.success();
    }

    /** 斬撃の二重弧をまとめて描き、命中の向きと距離を示します。 */
    protected static void slash(PlayerActiveSkillContext context, Location center, Vector direction,
                                double radius, SharedParticleDefinition color, boolean rising) {
        Vector forward = direction.clone().setY(0.0D);
        if (forward.lengthSquared() < 1.0E-8D) {
            forward = new Vector(0.0D, 0.0D, 1.0D);
        }
        forward.normalize();
        Vector right = new Vector(-forward.getZ(), 0.0D, forward.getX());
        List<Location> points = new ArrayList<>(54);
        for (int band = 0; band < 2; band++) {
            for (int step = 0; step <= 26; step++) {
                double angle = Math.toRadians(-65.0D + step * 5.0D);
                Vector offset = forward.clone().multiply(Math.cos(angle) * radius)
                        .add(right.clone().multiply(Math.sin(angle) * radius));
                points.add(center.clone().add(offset).add(0.0D,
                        band * 0.14D + (rising ? Math.sin(angle) * 1.1D : 0.0D), 0.0D));
            }
        }
        context.services().effects().points(center, points, color);
        context.services().effects().sound(center, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.8F, rising ? 1.4F : 0.9F);
    }

    /** 二本の剣閃と細い螺旋で前進経路を描きます。 */
    protected static void trail(PlayerActiveSkillContext context, Location from, Location to,
                                SharedParticleDefinition color) {
        Vector delta = to.toVector().subtract(from.toVector());
        Vector side = new Vector(-delta.getZ(), 0.0D, delta.getX());
        if (side.lengthSquared() > 1.0E-8D) side.normalize().multiply(0.3D);
        List<Location> points = new ArrayList<>(96);
        for (int i = 0; i <= 28; i++) {
            double t = i / 28.0D;
            Location point = from.clone().add(delta.clone().multiply(t));
            points.add(point.clone().add(side));
            points.add(point.clone().subtract(side));
            points.add(point.clone().add(0.0D, Math.sin(t * Math.PI * 4.0D) * 0.4D, 0.0D));
        }
        context.services().effects().points(from, points, color);
        context.services().effects().sound(to, Sound.ENTITY_PLAYER_ATTACK_STRONG, 0.85F, 1.3F);
    }

    /** 放射状の八枚の剣閃を一括表示します。常時タスクや画面全体の閃光は使いません。 */
    protected static void burst(PlayerActiveSkillContext context, Location center, double radius,
                                SharedParticleDefinition color) {
        List<Location> points = new ArrayList<>(80);
        for (int arm = 0; arm < 8; arm++) {
            double angle = arm * Math.PI / 4.0D;
            for (int i = 1; i <= 8; i++) {
                double r = radius * i / 8.0D;
                points.add(center.clone().add(Math.cos(angle) * r,
                        Math.sin(i * Math.PI / 8.0D) * 0.35D, Math.sin(angle) * r));
            }
        }
        context.services().effects().points(center, points, color);
        context.services().effects().ring(center, radius, 32, color);
    }

    /** 実ダメージ、会心、直接撃破を分離した一撃の結果です。 */
    protected record Hit(DamageResult result, boolean killed) {
        /** 回避や無効化を除くHP/Shieldへの有効命中を返します。 */
        boolean landed() {
            return !result.evaded() && (result.finalDamage() > 0.0D || result.shieldDamage() > 0.0D);
        }
        /** 有効命中に付いた通常会心または超星会心だけを返します。 */
        boolean critical() {
            return landed() && (result.critical() || result.superStarCritical());
        }
    }
}
