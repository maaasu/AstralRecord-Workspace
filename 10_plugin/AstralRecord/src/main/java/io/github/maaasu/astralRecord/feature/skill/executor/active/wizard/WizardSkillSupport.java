package io.github.maaasu.astralRecord.feature.skill.executor.active.wizard;

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
import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParamReader;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParameterException;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinition;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.jetbrains.annotations.NotNull;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/** ウィザードの有限範囲魔法を共通の対象選択・ダメージ計算へ接続します。 */
abstract class WizardSkillSupport extends PlayerActiveSkillExecutor {
    private final List<String> required;

    /** 実装IDと必須の有限数値を登録します。 */
    protected WizardSkillSupport(String id, ActiveSkillServices services, String... required) {
        super(id, services);
        this.required = List.of(required);
    }

    /** {@inheritDoc} */
    @Override
    public void validateParams(@NotNull SkillDefinition skill) {
        super.validateParams(skill);
        SkillParamReader params = new SkillParamReader(skill.getId(), skill.getParams());
        for (String key : required) {
            double value = params.getDouble(key, Double.NaN);
            boolean integer = key.endsWith("Ticks") || key.equals("maxTargets");
            double limit = key.endsWith("Ticks") ? 400.0D : key.equals("maxTargets") ? 8.0D
                    : key.equals("range") ? 24.0D : key.endsWith("Radius") || key.equals("radius") ? 6.0D
                    : key.equals("conditionChance") ? 100.0D : key.equals("manaRecovery") ? 50.0D
                    : key.equals("manaThreshold") || key.equals("incomingMultiplier") ? 1.0D : 20.0D;
            if (!Double.isFinite(value) || value <= 0.0D || value > limit
                    || (integer && value != Math.rint(value))) {
                throw new SkillParameterException(key, "ウィザードの効果値・範囲・回数が許容範囲外です");
            }
        }
    }

    /** レベル解決済みの必須値を返します。 */
    protected static double value(PlayerActiveSkillContext context, String key) {
        return context.params().getDouble(key, Double.NaN);
    }

    /** 視線を地形で遮り、前方の管理対象Mobを距離順に選びます。 */
    protected static List<AstEntity> line(PlayerActiveSkillContext context) {
        Location origin = context.eyeLocation();
        Location end = context.services().targeting().clippedEnd(origin, context.direction(), value(context, "range"));
        return context.services().targeting().inLineBeforeBlock(context.player(), context.eyeLocation(),
                context.direction(), origin.distance(end), value(context, "hitRadius"),
                (int) value(context, "maxTargets"));
    }

    /** 本体の共通消費後に、有償の状態異常追撃を起こす攻撃を一度だけ実行します。 */
    protected static void afterCosts(PlayerActiveSkillContext context, Runnable attack) {
        var world = context.player().getWorld();
        context.services().tasks().later(context.player().getUniqueId(),
                context.source().skill().getId() + ":" + UUID.randomUUID(), 1L, () -> {
                    if (context.player().isOnline() && !context.player().isDead()
                            && context.player().getWorld() == world
                            && context.caster().player().getStatusSnapshot().getCurrentHp() > 0.0D) {
                        attack.run();
                    }
                });
    }

    /** 範囲中心から遮蔽のない管理対象Mobを有限数だけ選びます。 */
    protected static List<AstEntity> sphere(PlayerActiveSkillContext context, Location center) {
        return context.services().targeting().inSphere(context.player(), center, value(context, "radius"),
                (int) value(context, "maxTargets"), true);
    }

    /** スキル定義付きの単一属性魔法ダメージを適用します。 */
    protected static DamageResult hit(PlayerActiveSkillContext context, AstEntity target, DamageElement element,
                                      double ratio, ActiveSkillCondition... conditions) {
        if (!target.isMob() || target.currentHealth() <= 0.0D
                || target.location().getWorld() != context.player().getWorld()) return new DamageResult(0.0D);
        return context.services().combat().hit(context.source().skill(), context.attacker(), target,
                AttackType.MAGIC, element, ratio, conditions);
    }

    /** パラメータから状態異常の確率と持続時間を構成します。 */
    protected static ActiveSkillCondition condition(PlayerActiveSkillContext context, ConditionType type) {
        return new ActiveSkillCondition(type, value(context, "conditionChance"),
                (int) value(context, "conditionTicks"), 1.0D);
    }

    /**
     * 発動時の視線へ非貫通の魔法弾を放ち、敵・地形・最大射程の終端で一度だけ範囲効果を起こします。
     * 本体の消費後に共有taskから進行し、地形命中時は壁の手前を範囲中心にします。
     *
     * @param context 発動情報。方向と射程は発射時に固定する
     * @param trail 既存の色付き軌跡
     * @param accent 軌跡と着弾リングへ重ねる非dust粒子
     * @param onImpact 有効な発動者の着弾地点で一度だけ実行する処理
     */
    protected static void launchImpact(PlayerActiveSkillContext context, SharedParticleDefinition trail,
                                       SharedParticleDefinition accent, Consumer<Location> onImpact) {
        Location origin = context.eyeLocation();
        var projectile = new SkillProjectileSpec(value(context, "range"), 0.9D, 0.35D, false, 1,
                trail, null, accent);
        context.services().effects().point(origin, SharedParticleDefinitions.WIZARD_SPELL_CORE.withCount(8));
        context.services().projectiles().launchWithTermination(context.player(), origin, context.direction(),
                projectile, (target, impact) -> { }, termination -> {
                    if (!context.player().isOnline() || context.player().isDead()
                            || context.player().getWorld() != origin.getWorld()
                            || context.caster().player().getStatusSnapshot().getCurrentHp() <= 0.0D) return;
                    Location center = termination.type() == SkillProjectileTermination.Type.BLOCK
                            ? termination.effectLocation() : termination.location();
                    onImpact.accept(center);
                    context.services().effects().ring(center, value(context, "radius"), 32, accent);
                    context.services().effects().point(center, SharedParticleDefinitions.WIZARD_ARCANE_BURST);
                    context.services().effects().sound(center, Sound.ENTITY_FIREWORK_ROCKET_BLAST, 0.8F, 1.2F);
                });
    }

    /** 攻撃の届く直線へ色付きの線と非dustの明るい芯を重ね、終端も表示します。 */
    protected static void beam(PlayerActiveSkillContext context, SharedParticleDefinition particle,
                               SharedParticleDefinition accent) {
        Location end = context.services().targeting().clippedEnd(
                context.eyeLocation(), context.direction(), value(context, "range"));
        context.services().effects().line(context.eyeLocation(), end, 0.35D, particle);
        context.services().effects().line(context.eyeLocation(), end, 0.55D, accent);
        context.services().effects().ring(end, 0.5D, 16, particle);
        context.services().effects().point(end, accent.withCount(12));
    }

    /** 瞬間的な範囲演出を表示します。持続魔法陣の登録はしません。 */
    protected static void ring(PlayerActiveSkillContext context, Location center, double radius,
                               SharedParticleDefinition particle) {
        context.services().effects().ring(center, radius, 32, particle);
        context.services().effects().ring(center.clone().add(0.0D, 0.3D, 0.0D), radius * 0.65D, 24, particle);
    }
}
