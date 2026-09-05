package io.github.maaasu.astralRecord.feature.item.service;

import io.github.maaasu.astralRecord.feature.item.model.EquipmentInstance;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipment;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentEnhanceFailAction;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentEnhanceLevel;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.item.model.ItemOrbEffect;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.ThreadLocalRandom;

/** プレイヤー待機中にAPIを呼ばず、装備強化の結果を計算する純粋な補助です。 */
final class OrbLocalMutationCalculator {
    private OrbLocalMutationCalculator() {
    }

    static @Nullable EnhancementResult enhance(
        @NotNull ItemOrbEffect effect,
        @NotNull ItemModel model,
        @NotNull EquipmentInstance current
    ) {
        OrbEligibility.EnhancementPlan plan = OrbEligibility.resolveEnhancement(effect, model, current);
        if (plan == null || model.getEquipment() == null) {
            return null;
        }
        ItemEquipment equipment = model.getEquipment();
        ItemEquipmentEnhanceLevel definition = plan.levelDefinition();
        double successRate = Math.max(0.0D, Math.min(1.0D, definition.getSuccessRate()));
        boolean succeeded = ThreadLocalRandom.current().nextDouble() < successRate;
        ItemEquipmentEnhanceFailAction failAction = definition.getFailAction();
        int maxLevel = OrbEligibility.effectiveEnhanceMaxLevel(
            equipment,
            current.getTranscendenceRank()
        );
        int appliedLevel = succeeded
            ? plan.targetLevel()
            : switch (failAction) {
                case SET_LEVEL -> Math.max(0, Math.min(
                    maxLevel,
                    definition.getFailTargetLevel() == null
                        ? current.getEnhanceLevel()
                        : definition.getFailTargetLevel()
                ));
                case DECREASE_ONE -> Math.max(0, current.getEnhanceLevel() - 1);
                case NONE -> current.getEnhanceLevel();
            };

        int currentBonus = equipment.getEnhance().getLevels().stream()
            .filter(level -> level.getLevel() <= current.getEnhanceLevel())
            .map(ItemEquipmentEnhanceLevel::getDurabilityBonus)
            .filter(java.util.Objects::nonNull)
            .mapToInt(Integer::intValue)
            .sum();
        int targetBonus = equipment.getEnhance().getLevels().stream()
            .filter(level -> level.getLevel() <= appliedLevel)
            .map(ItemEquipmentEnhanceLevel::getDurabilityBonus)
            .filter(java.util.Objects::nonNull)
            .mapToInt(Integer::intValue)
            .sum();
        int durabilityDelta = targetBonus - currentBonus;
        int durabilityMax = current.getDurabilityMax() + durabilityDelta;
        int durabilityValue = Math.max(0, Math.min(
            durabilityMax,
            current.getDurabilityValue() + durabilityDelta
        ));
        EquipmentInstance updated = new EquipmentInstance(
            current.getEquipmentInstanceId(),
            current.getAccountId(),
            current.getItemId(),
            appliedLevel,
            current.getRuneMaxSlots(),
            current.getTranscendenceRank(),
            durabilityMax,
            durabilityValue,
            current.getCreatedAt(),
            current.getUpdatedAt(),
            current.getStatRolls(),
            current.getEnchants(),
            current.getRunes()
        );
        return new EnhancementResult(
            updated,
            succeeded,
            successRate,
            failAction,
            current.getEnhanceLevel(),
            current.getTranscendenceRank()
        );
    }

    record EnhancementResult(
        @NotNull EquipmentInstance instance,
        boolean succeeded,
        double successRate,
        @NotNull ItemEquipmentEnhanceFailAction failAction,
        int baseEnhanceLevel,
        int baseTranscendenceRank
    ) {
    }
}
