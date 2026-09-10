package io.github.maaasu.astralRecord.feature.skill.executor.active.hunter;

import io.github.maaasu.astralRecord.feature.skill.active.service.ActiveSkillServices;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillContext;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillExecutor;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastResult;
import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParamReader;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParameterException;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/** ハンターの間接攻撃力を一時的に高める発動スキルです。 */
public final class HunterBuildUpExecutor extends PlayerActiveSkillExecutor {

    public static final String ID = "hunter_build_up";
    private static final String BUFF_PARAM = "buffId";
    private static final String BUFF_PREFIX = "buff:";
    private static final String ENERGY_RECOVERY_RATIO_PARAM = "energyRecoveryRatio";
    private static final String RANGED_ATTACK_INCREASE_RATIO_PARAM = "rangedAttackIncreaseRatio";
    private static final String ENERGY_RECOVERY_TASK_SCOPE = "hunter_build_up:energy_recovery";
    private static final long ENERGY_RECOVERY_INTERVAL_TICKS = 20L;
    private static final int ENERGY_RECOVERY_COUNT = 10;
    private static final double BASE_RANGED_ATTACK_INCREASE_RATIO = 0.10D;
    private static final double RATIO_TOLERANCE = 1.0E-9D;
    private static final List<String> BUFF_IDS_BY_LEVEL = List.of(
            ID,
            ID + "_lv2",
            ID + "_lv3",
            ID + "_lv4",
            ID + "_lv5"
    );

    /** 共有発動スキルサービスで初期化します。 */
    public HunterBuildUpExecutor(@NotNull ActiveSkillServices services) {
        super(ID, services);
    }

    /** {@inheritDoc} */
    @Override
    public void validateParams(@NotNull SkillDefinition skill) {
        super.validateParams(skill);
        SkillParamReader params = new SkillParamReader(skill.getId(), skill.getParams());
        String buffId = params.getRefId(BUFF_PARAM, BUFF_PREFIX);
        if (buffId == null || buffId.isBlank()) {
            throw new SkillParameterException(
                    BUFF_PARAM,
                    "ビルドアップには buff:hunter_build_up の参照が必要です"
            );
        }
        if (!ID.equals(buffId)) {
            throw new SkillParameterException(
                    BUFF_PARAM,
                    "ビルドアップは buff:hunter_build_up だけを参照できます"
            );
        }
        resolveLevel(params);
    }

    /** {@inheritDoc} */
    @Override
    protected @NotNull SkillCastResult castPlayer(@NotNull PlayerActiveSkillContext context) {
        SkillParamReader params = context.params();
        String buffId = params.getRefId(BUFF_PARAM, BUFF_PREFIX);
        if (!ID.equals(buffId)) {
            return SkillCastResult.failure(null);
        }
        int level = resolveLevel(params);
        String levelBuffId = BUFF_IDS_BY_LEVEL.get(level - 1);
        if (!context.services().combat().applyBuff(context.caster().player(), levelBuffId)) {
            return SkillCastResult.failure(null);
        }
        double energyRecoveryRatio = params.getDouble(ENERGY_RECOVERY_RATIO_PARAM, 0.0D);
        context.services().tasks().repeat(
                context.player().getUniqueId(),
                ENERGY_RECOVERY_TASK_SCOPE,
                ENERGY_RECOVERY_INTERVAL_TICKS,
                ENERGY_RECOVERY_INTERVAL_TICKS,
                ENERGY_RECOVERY_COUNT,
                ignored -> context.services().combat().recoverEnergyByMaxRatio(
                        context.caster().player(),
                        energyRecoveryRatio
                )
        );
        return context.success();
    }

    /**
     * 解決済みパラメータからスキルレベルを求め、ENG回復量と間接攻撃力補正の整合性を検証します。
     *
     * @param params 解決済みスキルパラメータ
     * @return 1から5までの解決済みスキルレベル
     * @throws SkillParameterException 回復割合または攻撃力補正が定義外の場合
     */
    private int resolveLevel(@NotNull SkillParamReader params) {
        double energyRecoveryRatio = params.getDouble(ENERGY_RECOVERY_RATIO_PARAM, 0.0D);
        int level = (int) Math.rint(energyRecoveryRatio * 100.0D);
        if (level < 1 || level > BUFF_IDS_BY_LEVEL.size()
                || Math.abs(energyRecoveryRatio - level / 100.0D) > RATIO_TOLERANCE) {
            throw new SkillParameterException(
                    ENERGY_RECOVERY_RATIO_PARAM,
                    "ビルドアップの毎秒ENG回復量は最大ENGの1%から5%である必要があります"
            );
        }
        double rangedAttackIncreaseRatio = params.getDouble(RANGED_ATTACK_INCREASE_RATIO_PARAM, 0.0D);
        double expectedRatio = BASE_RANGED_ATTACK_INCREASE_RATIO + energyRecoveryRatio;
        if (!Double.isFinite(rangedAttackIncreaseRatio)
                || Math.abs(rangedAttackIncreaseRatio - expectedRatio) > RATIO_TOLERANCE) {
            throw new SkillParameterException(
                    RANGED_ATTACK_INCREASE_RATIO_PARAM,
                    "ビルドアップの間接攻撃力補正は10%にスキルレベル%を加えた値が必要です"
            );
        }
        return level;
    }
}
