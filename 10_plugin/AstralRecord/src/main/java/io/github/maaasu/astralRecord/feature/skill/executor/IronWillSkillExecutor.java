package io.github.maaasu.astralRecord.feature.skill.executor;

import io.github.maaasu.astralRecord.feature.skill.model.PassiveSkillContext;
import io.github.maaasu.astralRecord.feature.skill.model.PassiveSkillStatusModifier;
import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.feature.skill.model.SkillKind;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParameterException;
import io.github.maaasu.astralRecord.feature.status.model.StatusModifierType;
import io.github.maaasu.astralRecord.feature.status.model.StatusType;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * implementationId {@code iron_will} のパッシブスキル実装です。
 */
public final class IronWillSkillExecutor implements SkillExecutor {
    private static final String IMPLEMENTATION_ID = "iron_will";

    @Override
    public @NotNull String implementationId() {
        return IMPLEMENTATION_ID;
    }

    @Override
    public @NotNull SkillKind kind() {
        return SkillKind.PASSIVE;
    }

    @Override
    public void validateParams(@NotNull SkillDefinition skill) {
        requireOptionalNumber(skill, "defenseFlat");
        requireOptionalNumber(skill, "magicDefenseFlat");
        requireOptionalNumber(skill, "defenseScalar");
        requireOptionalNumber(skill, "magicDefenseScalar");
    }

    @Override
    public @NotNull List<PassiveSkillStatusModifier> passiveStatusModifiers(@NotNull PassiveSkillContext context) {
        SkillDefinition skill = context.skill();
        List<PassiveSkillStatusModifier> modifiers = new ArrayList<>();
        addModifier(modifiers, StatusType.DEFENSE, StatusModifierType.FLAT, readDouble(skill, "defenseFlat"));
        addModifier(modifiers, StatusType.MAGIC_DEFENSE, StatusModifierType.FLAT, readDouble(skill, "magicDefenseFlat"));
        addModifier(modifiers, StatusType.DEFENSE, StatusModifierType.SCALAR, readDouble(skill, "defenseScalar"));
        addModifier(modifiers, StatusType.MAGIC_DEFENSE, StatusModifierType.SCALAR, readDouble(skill, "magicDefenseScalar"));
        return modifiers;
    }

    private void addModifier(
            @NotNull List<PassiveSkillStatusModifier> modifiers,
            @NotNull StatusType statusType,
            @NotNull StatusModifierType type,
            double value
    ) {
        if (value == 0.0D) {
            return;
        }
        modifiers.add(new PassiveSkillStatusModifier(statusType, type, value));
    }

    private double readDouble(@NotNull SkillDefinition skill, @NotNull String key) {
        Object raw = skill.getParams().get(key);
        return raw instanceof Number number ? number.doubleValue() : 0.0D;
    }

    private void requireOptionalNumber(@NotNull SkillDefinition skill, @NotNull String key) {
        Object raw = skill.getParams().get(key);
        if (raw == null) {
            return;
        }
        if (!(raw instanceof Number)) {
            throw new SkillParameterException(key, "number を指定してください");
        }
    }
}
