package io.github.maaasu.astralRecord.feature.skill.executor.active.swordsman;

import io.github.maaasu.astralRecord.feature.skill.active.service.ActiveSkillServices;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillContext;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillExecutor;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastResult;
import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParamReader;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParameterException;
import org.jetbrains.annotations.NotNull;

/** 通常攻撃後の短い受付中に管理Mobの直接攻撃を軽減して反撃するソードマンスキルです。 */
public final class SwordsmanBladeCounterExecutor extends PlayerActiveSkillExecutor {

    public static final String ID = "swordsman_blade_counter";
    private static final long DEFAULT_BUFF_DURATION_TICKS = 400L;
    private static final long DEFAULT_RECEPTION_TICKS = 10L;
    private final SwordsmanBladeCounterRuntimeService runtimeService;

    /**
     * 共有発動基盤と専用runtimeサービスで初期化します。
     *
     * @param services 共通発動サービス
     * @param runtimeService ブレードカウンターruntime
     */
    public SwordsmanBladeCounterExecutor(
            @NotNull ActiveSkillServices services,
            @NotNull SwordsmanBladeCounterRuntimeService runtimeService
    ) {
        super(ID, services);
        this.runtimeService = runtimeService;
    }

    /** {@inheritDoc} */
    @Override
    public void validateParams(@NotNull SkillDefinition skill) {
        super.validateParams(skill);
        SkillParamReader params = new SkillParamReader(skill.getId(), skill.getParams());
        if (params.getInt("buffDurationTicks", 0) != DEFAULT_BUFF_DURATION_TICKS) {
            throw new SkillParameterException("buffDurationTicks", "ブレードカウンターの持続時間は400tickが必要です");
        }
        if (params.getInt("receptionTicks", 0) != DEFAULT_RECEPTION_TICKS) {
            throw new SkillParameterException("receptionTicks", "ブレードカウンターの受付時間は10tickが必要です");
        }
        if (params.getInt("maximumCounters", 0) != 3) {
            throw new SkillParameterException("maximumCounters", "最大反撃回数の基礎値は3が必要です");
        }
        if (params.getDouble("counterDamageRatio", 0.0D) != 1.0D) {
            throw new SkillParameterException("counterDamageRatio", "反撃倍率は1.0が必要です");
        }
        if (params.getDouble("damageReductionRate", 0.0D) != 0.5D) {
            throw new SkillParameterException("damageReductionRate", "被ダメージ軽減率は0.5が必要です");
        }
    }

    /** {@inheritDoc} */
    @Override
    protected @NotNull SkillCastResult castPlayer(@NotNull PlayerActiveSkillContext context) {
        SkillParamReader params = context.params();
        runtimeService.activate(
                context.caster().player(),
                context.attacker(),
                params.getInt("buffDurationTicks", (int) DEFAULT_BUFF_DURATION_TICKS),
                params.getInt("receptionTicks", (int) DEFAULT_RECEPTION_TICKS),
                params.getInt("maximumCounters", 3),
                params.getDouble("counterDamageRatio", 1.0D),
                params.getDouble("damageReductionRate", 0.5D)
        );
        return context.success();
    }
}
