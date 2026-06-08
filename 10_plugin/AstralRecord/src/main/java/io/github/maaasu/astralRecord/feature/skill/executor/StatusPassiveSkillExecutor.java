package io.github.maaasu.astralRecord.feature.skill.executor;

import io.github.maaasu.astralRecord.feature.skill.model.PassiveSkillContext;
import io.github.maaasu.astralRecord.feature.skill.model.PassiveSkillStatusModifier;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastContext;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastResult;
import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.feature.skill.model.SkillKind;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParameterException;
import io.github.maaasu.astralRecord.feature.status.model.StatusModifierType;
import io.github.maaasu.astralRecord.feature.status.model.StatusType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * implementationId {@code status_passive} のパッシブスキル実装です。
 * params.modifiers に定義した status/type/value を常時補正として返します。
 */
public final class StatusPassiveSkillExecutor implements SkillExecutor {
    private static final String IMPLEMENTATION_ID = "status_passive";

    @Override
    public @NotNull String implementationId() {
        return IMPLEMENTATION_ID;
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
        parseModifiers(skill, true);
    }

    @Override
    public @NotNull List<PassiveSkillStatusModifier> passiveStatusModifiers(@NotNull PassiveSkillContext context) {
        return parseModifiers(context.skill(), false);
    }

    private @NotNull List<PassiveSkillStatusModifier> parseModifiers(
            @NotNull SkillDefinition skill,
            boolean validateOnly
    ) {
        Object rawModifiers = skill.getParams().get("modifiers");
        if (!(rawModifiers instanceof List<?> modifierRows) || modifierRows.isEmpty()) {
            throw new SkillParameterException("modifiers", "1 件以上の list を指定してください");
        }

        List<PassiveSkillStatusModifier> modifiers = new ArrayList<>();
        for (int index = 0; index < modifierRows.size(); index++) {
            Object row = modifierRows.get(index);
            if (!(row instanceof Map<?, ?> map)) {
                throw new SkillParameterException("modifiers[" + index + "]", "map を指定してください");
            }

            StatusType statusType = resolveStatusType(map.get("status"));
            if (statusType == null) {
                throw new SkillParameterException("modifiers[" + index + "].status", "有効な StatusType を指定してください");
            }

            Object rawValue = map.get("value");
            if (!(rawValue instanceof Number number)) {
                throw new SkillParameterException("modifiers[" + index + "].value", "number を指定してください");
            }

            StatusModifierType modifierType = StatusModifierType.fromRaw(stringValue(map.get("type")));
            if (validateOnly) {
                continue;
            }
            modifiers.add(new PassiveSkillStatusModifier(statusType, modifierType, number.doubleValue()));
        }
        return modifiers;
    }

    private @Nullable StatusType resolveStatusType(@Nullable Object raw) {
        String value = stringValue(raw);
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return StatusType.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private @Nullable String stringValue(@Nullable Object raw) {
        return raw == null ? null : raw.toString();
    }
}
