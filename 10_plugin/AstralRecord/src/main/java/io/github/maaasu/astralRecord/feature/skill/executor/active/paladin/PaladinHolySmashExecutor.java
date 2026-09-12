package io.github.maaasu.astralRecord.feature.skill.executor.active.paladin;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.combat.model.AttackType;
import io.github.maaasu.astralRecord.feature.combat.model.DamageElement;
import io.github.maaasu.astralRecord.feature.combat.model.DamageResult;
import io.github.maaasu.astralRecord.feature.skill.active.service.ActiveSkillServices;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillContext;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillExecutor;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastResult;
import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParamReader;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParameterException;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/** 白い一閃を前方へ放ち、ホーリーフィールド内では敵を弱体化するパラディンの近接スキルです。 */
public final class PaladinHolySmashExecutor extends PlayerActiveSkillExecutor {

    public static final String ID = "paladin_holy_smash";
    private static final String DEFENSE_EFFECT_ID = ID + ":defense";
    private static final double DEFAULT_RANGE = 6.0D;
    private static final double DEFAULT_TARGET_ANGLE = 60.0D;
    private static final int DEFAULT_MAX_TARGETS = 5;
    private static final double DEFAULT_DAMAGE_RATIO = 0.025D;
    private static final double DEFAULT_DEFENSE_REDUCTION_RATIO = 30.0D;
    private static final int DEFAULT_DEFENSE_DEBUFF_DURATION_TICKS = 20;
    private static final double DEFAULT_ENERGY_RECOVERY_AMOUNT = 30.0D;
    private static final int DEFAULT_REDUCED_COOLDOWN_TICKS = 120;
    private static final double[] SLASH_RADIUS_RATIOS = {0.28D, 0.48D, 0.68D, 0.88D};

    private final PaladinHolyFieldRuntimeService holyFieldRuntimeService;

    /**
     * 共有発動スキルサービスとホーリーフィールド実行時状態サービスで初期化します。
     *
     * @param services 共有発動スキルサービス
     * @param holyFieldRuntimeService ホーリーフィールド実行時状態サービス
     */
    public PaladinHolySmashExecutor(
            @NotNull ActiveSkillServices services,
            @NotNull PaladinHolyFieldRuntimeService holyFieldRuntimeService
    ) {
        super(ID, services);
        this.holyFieldRuntimeService = holyFieldRuntimeService;
    }

    /** {@inheritDoc} */
    @Override
    public void validateParams(@NotNull SkillDefinition skill) {
        super.validateParams(skill);
        SkillParamReader params = new SkillParamReader(skill.getId(), skill.getParams());
        requirePositive(params, "range");
        double targetAngle = params.getDouble("targetAngle", 0.0D);
        if (!(targetAngle > 0.0D && targetAngle <= 180.0D)) {
            throw new SkillParameterException("targetAngle", "ホーリースマッシュの対象角度は0より大きく180以下が必要です");
        }
        if (params.getInt("maxTargets", 0) < 1) {
            throw new SkillParameterException("maxTargets", "ホーリースマッシュの最大対象数は1以上が必要です");
        }
        requirePositive(params, "damageRatio");
        requireRange(params, "defenseReductionRatio", 0.0D, 100.0D);
        requirePositiveInt(params, "defenseDebuffDurationTicks");
        requirePositive(params, "energyRecoveryAmount");
        requirePositiveInt(params, "reducedCooldownTicks");
    }

    /** {@inheritDoc} */
    @Override
    protected @NotNull SkillCastResult castPlayer(@NotNull PlayerActiveSkillContext context) {
        SkillParamReader params = context.params();
        double range = params.getDouble("range", DEFAULT_RANGE);
        double targetAngle = params.getDouble("targetAngle", DEFAULT_TARGET_ANGLE);
        int maxTargets = params.getInt("maxTargets", DEFAULT_MAX_TARGETS);
        double damageRatio = params.getDouble("damageRatio", DEFAULT_DAMAGE_RATIO);
        double defenseReductionRatio = params.getDouble(
                "defenseReductionRatio", DEFAULT_DEFENSE_REDUCTION_RATIO
        );
        int defenseDebuffDurationTicks = params.getInt(
                "defenseDebuffDurationTicks", DEFAULT_DEFENSE_DEBUFF_DURATION_TICKS
        );
        double energyRecoveryAmount = params.getDouble(
                "energyRecoveryAmount", DEFAULT_ENERGY_RECOVERY_AMOUNT
        );
        int reducedCooldownTicks = params.getInt(
                "reducedCooldownTicks", DEFAULT_REDUCED_COOLDOWN_TICKS
        );

        Player player = context.player();
        List<AstEntity> targets = context.services().targeting()
                .inCone(player, range, targetAngle, maxTargets, true);
        renderSlash(context, range, targetAngle);

        boolean fieldBonusActive = holyFieldRuntimeService.isCasterWithinField(player.getUniqueId());
        boolean fieldBonusTriggered = false;
        double defenseMultiplier = 1.0D - defenseReductionRatio / 100.0D;
        for (AstEntity target : targets) {
            DamageResult result = context.services().combat().hit(
                    context.source().skill(),
                    context.attacker(),
                    target,
                    AttackType.MELEE,
                    DamageElement.NONE,
                    damageRatio
            );
            if (result.evaded() || !fieldBonusActive) {
                continue;
            }
            context.services().temporaryEffects().applyDefenseMultiplier(
                    target.id(),
                    DEFENSE_EFFECT_ID,
                    defenseDebuffDurationTicks,
                    defenseMultiplier
            );
            if (!fieldBonusTriggered) {
                context.services().combat().recoverEnergy(
                        context.caster().player(),
                        energyRecoveryAmount
                );
                fieldBonusTriggered = true;
            }
        }
        return fieldBonusTriggered
                ? context.successWithCooldownTicks(reducedCooldownTicks)
                : context.success();
    }

    /** 白い弧を重ね、炎剣技とは異なる単段の聖なる一閃を表示します。 */
    private void renderSlash(
            @NotNull PlayerActiveSkillContext context,
            double range,
            double targetAngle
    ) {
        Location origin = context.eyeLocation()
                .add(context.direction().multiply(0.35D))
                .subtract(0.0D, 0.25D, 0.0D);
        double halfAngle = targetAngle * 0.42D;
        for (int index = 0; index < SLASH_RADIUS_RATIOS.length; index++) {
            double radius = range * SLASH_RADIUS_RATIOS[index];
            context.services().effects().viewArcSegment(
                    origin,
                    context.direction(),
                    radius,
                    -halfAngle + index * 2.0D,
                    halfAngle + index * 2.0D,
                    8,
                    SharedParticleDefinitions.SKILL_PALADIN_HOLY_SMASH_DUST
            );
            context.services().effects().viewArcSegment(
                    origin.clone().add(0.0D, 0.10D + index * 0.03D, 0.0D),
                    context.direction(),
                    radius * 0.90D,
                    -halfAngle * 0.72D,
                    halfAngle * 0.72D,
                    4,
                    SharedParticleDefinitions.SKILL_PALADIN_HOLY_SMASH_END_ROD
            );
        }
        Location flash = origin.clone().add(context.direction().multiply(range * 0.78D));
        context.services().effects().point(flash, SharedParticleDefinitions.SKILL_PALADIN_HOLY_SMASH_END_ROD);
        context.services().effects().sound(
                origin,
                Sound.ENTITY_PLAYER_ATTACK_SWEEP,
                1.0F,
                1.32F
        );
    }

    private static void requirePositive(@NotNull SkillParamReader params, @NotNull String key) {
        if (!(params.getDouble(key, 0.0D) > 0.0D)) {
            throw new SkillParameterException(key, "ホーリースマッシュの params[" + key + "] は正数が必要です");
        }
    }

    private static void requirePositiveInt(@NotNull SkillParamReader params, @NotNull String key) {
        if (params.getInt(key, 0) < 1) {
            throw new SkillParameterException(key, "ホーリースマッシュの params[" + key + "] は1以上の整数が必要です");
        }
    }

    private static void requireRange(
            @NotNull SkillParamReader params,
            @NotNull String key,
            double minimum,
            double maximum
    ) {
        double value = params.getDouble(key, Double.NaN);
        if (!(value >= minimum && value <= maximum)) {
            throw new SkillParameterException(key, "ホーリースマッシュの params[" + key + "] は"
                    + minimum + "以上" + maximum + "以下が必要です");
        }
    }
}
