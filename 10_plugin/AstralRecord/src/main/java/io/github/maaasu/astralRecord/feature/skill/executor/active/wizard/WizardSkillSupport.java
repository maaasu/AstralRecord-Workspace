package io.github.maaasu.astralRecord.feature.skill.executor.active.wizard;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.combat.model.AttackType;
import io.github.maaasu.astralRecord.feature.combat.model.DamageElement;
import io.github.maaasu.astralRecord.feature.combat.model.DamageResult;
import io.github.maaasu.astralRecord.feature.condition.model.ConditionType;
import io.github.maaasu.astralRecord.feature.skill.active.model.ActiveSkillCondition;
import io.github.maaasu.astralRecord.feature.skill.active.service.ActiveSkillServices;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillContext;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillExecutor;
import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParamReader;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParameterException;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinition;
import org.bukkit.Location;
import org.jetbrains.annotations.NotNull;
import java.util.List;
import java.util.UUID;

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

    /** 攻撃の届く直線を共通particle定義で表示します。 */
    protected static void beam(PlayerActiveSkillContext context, SharedParticleDefinition particle) {
        Location end = context.services().targeting().clippedEnd(
                context.eyeLocation(), context.direction(), value(context, "range"));
        context.services().effects().line(context.eyeLocation(), end, 0.35D, particle);
        context.services().effects().ring(end, 0.5D, 16, particle);
    }

    /** 瞬間的な範囲演出を表示します。持続魔法陣の登録はしません。 */
    protected static void ring(PlayerActiveSkillContext context, Location center, double radius,
                               SharedParticleDefinition particle) {
        context.services().effects().ring(center, radius, 32, particle);
        context.services().effects().ring(center.clone().add(0.0D, 0.3D, 0.0D), radius * 0.65D, 24, particle);
    }
}
