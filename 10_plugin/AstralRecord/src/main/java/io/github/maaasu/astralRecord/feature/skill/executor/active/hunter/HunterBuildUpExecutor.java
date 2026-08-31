package io.github.maaasu.astralRecord.feature.skill.executor.active.hunter;

import io.github.maaasu.astralRecord.feature.skill.active.service.ActiveSkillServices;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillContext;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillExecutor;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastResult;
import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParamReader;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParameterException;
import org.jetbrains.annotations.NotNull;

/** ハンターの間接攻撃力を一時的に高める発動スキルです。 */
public final class HunterBuildUpExecutor extends PlayerActiveSkillExecutor {

    public static final String ID = "hunter_build_up";
    private static final String BUFF_PARAM = "buffId";
    private static final String BUFF_PREFIX = "buff:";

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
    }

    /** {@inheritDoc} */
    @Override
    protected @NotNull SkillCastResult castPlayer(@NotNull PlayerActiveSkillContext context) {
        String buffId = context.params().getRefId(BUFF_PARAM, BUFF_PREFIX);
        if (!ID.equals(buffId)
                || !context.services().combat().applyBuff(context.caster().player(), buffId)) {
            return SkillCastResult.failure(null);
        }
        return context.success();
    }
}
