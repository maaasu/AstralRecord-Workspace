package io.github.maaasu.astralRecord.feature.skill.executor.active.paladin;

import io.github.maaasu.astralRecord.feature.skill.active.service.ActiveSkillServices;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillContext;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillExecutor;
import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParamReader;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParameterException;
import org.jetbrains.annotations.NotNull;
import java.util.List;

/** Holy / Guardian の追加技能が共有する有限パラメータ検証です。 */
abstract class PaladinBuildSkillSupport extends PlayerActiveSkillExecutor {
    private final List<String> keys;

    /** 実装IDと共有サービス、実行と表示が参照する必須キーを設定します。 */
    protected PaladinBuildSkillSupport(String id, ActiveSkillServices services, String... keys) {
        super(id, services);
        this.keys = List.of(keys);
    }

    /** {@inheritDoc} */
    @Override
    public void validateParams(@NotNull SkillDefinition skill) {
        super.validateParams(skill);
        SkillParamReader params = new SkillParamReader(skill.getId(), skill.getParams());
        for (String key : keys) {
            double number = params.getDouble(key, Double.NaN);
            double maximum = switch (key) {
                case "range", "radius", "height", "maxTargets" -> 12.0D;
                case "hitRadius", "healHpRatio", "shieldHealRatio", "incomingMultiplier", "outgoingMultiplier" -> 1.0D;
                case "durationTicks" -> 100.0D;
                case "healAmount" -> 200.0D;
                default -> 5.0D;
            };
            boolean integral = key.endsWith("Ticks") || key.equals("maxTargets");
            if (!Double.isFinite(number) || number <= 0.0D || number > maximum
                    || (integral && number != Math.rint(number))) {
                throw new SkillParameterException(key, "パラディン技能の必須値が許容範囲外です");
            }
        }
    }

    /** レベル・シジル適用済みの必須値を返します。 */
    protected static double value(PlayerActiveSkillContext context, String key) {
        return context.params().getDouble(key, Double.NaN);
    }
}
