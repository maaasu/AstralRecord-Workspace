package io.github.maaasu.astralRecord.feature.skill.executor;

import io.github.maaasu.astralRecord.feature.skill.model.SkillCastContext;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastResult;
import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.feature.skill.model.SkillKind;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParamReader;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParameterException;
import io.github.maaasu.astralRecord.feature.skill.model.SkillResourceType;
import org.jetbrains.annotations.NotNull;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** implementationId {@code sharpshooter_inheritance_mastery} の継承の心得パッシブです。 */
public final class SharpshooterInheritanceMasterySkillExecutor implements SkillExecutor {
    public static final String ID = "sharpshooter_inheritance_mastery";
    public static final double NORMAL_ATTACK_DAMAGE_MULTIPLIER = 0.5D;

    @Override
    public @NotNull String implementationId() {
        return ID;
    }

    @Override
    public @NotNull SkillKind kind() {
        return SkillKind.PASSIVE;
    }

    @Override
    public @NotNull SkillCastResult cast(@NotNull SkillCastContext context) {
        return SkillCastResult.failure(null);
    }

    @Override
    public void validateParams(@NotNull SkillDefinition skill) {
        if (!skill.getPassiveBindRequired()) {
            throw new SkillParameterException("passive.bindRequired", "true を指定してください");
        }
        if (skill.getMaxLevel() != 1) {
            throw new SkillParameterException("maxLevel", "継承の心得は maxLevel: 1 を指定してください");
        }

        SkillParamReader params = new SkillParamReader(skill.getId(), skill.getParams());
        double multiplier = params.getDouble("normalAttackDamageMultiplier", Double.NaN);
        if (!Double.isFinite(multiplier)
                || Double.compare(multiplier, NORMAL_ATTACK_DAMAGE_MULTIPLIER) != 0) {
            throw new SkillParameterException(
                    "normalAttackDamageMultiplier",
                    NORMAL_ATTACK_DAMAGE_MULTIPLIER + " を指定してください"
            );
        }
        validateInheritanceBuffs(skill);
    }

    private void validateInheritanceBuffs(@NotNull SkillDefinition skill) {
        Object rawDefinitions = skill.getParams().get("inheritanceBuffs");
        if (!(rawDefinitions instanceof List<?> definitions)) {
            throw new SkillParameterException("inheritanceBuffs", "list を指定してください");
        }

        Set<String> buffIds = new HashSet<>();
        for (int index = 0; index < definitions.size(); index++) {
            Object rawDefinition = definitions.get(index);
            if (!(rawDefinition instanceof Map<?, ?> definition)) {
                throw new SkillParameterException("inheritanceBuffs[" + index + "]", "map を指定してください");
            }

            String buffId = stringValue(definition.get("buffId"));
            if (buffId == null || !buffId.startsWith("buff:") || buffId.length() == "buff:".length()) {
                throw new SkillParameterException(
                        "inheritanceBuffs[" + index + "].buffId",
                        "buff: prefix 付きの参照を指定してください"
                );
            }
            if (!buffIds.add(buffId)) {
                throw new SkillParameterException(
                        "inheritanceBuffs[" + index + "].buffId",
                        "同じ buffId を重複して指定できません"
                );
            }

            requirePositiveInteger(
                    definition.get("durationConsumptionTicks"),
                    "inheritanceBuffs[" + index + "].durationConsumptionTicks"
            );
            validateResourceCost(definition, index);
        }
    }

    private void validateResourceCost(@NotNull Map<?, ?> definition, int index) {
        Object rawType = definition.get("resourceType");
        Object rawCost = definition.get("resourceCost");
        String path = "inheritanceBuffs[" + index + "]";
        if (rawType == null && rawCost == null) {
            return;
        }
        if (rawType == null || rawCost == null) {
            throw new SkillParameterException(
                    path,
                    "resourceType と resourceCost は両方を指定してください"
            );
        }

        try {
            SkillResourceType.valueOf(rawType.toString().trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new SkillParameterException(path + ".resourceType", "有効なリソース種別を指定してください");
        }
        if (!(rawCost instanceof Number number)
                || !Double.isFinite(number.doubleValue())
                || number.doubleValue() < 0.0D) {
            throw new SkillParameterException(path + ".resourceCost", "0 以上の有限値を指定してください");
        }
    }

    private void requirePositiveInteger(Object rawValue, @NotNull String path) {
        if (!(rawValue instanceof Number number)
                || !Double.isFinite(number.doubleValue())
                || number.doubleValue() != Math.rint(number.doubleValue())
                || number.longValue() <= 0L) {
            throw new SkillParameterException(path, "1 以上の整数を指定してください");
        }
    }

    private String stringValue(Object rawValue) {
        return rawValue == null ? null : rawValue.toString().trim();
    }
}
