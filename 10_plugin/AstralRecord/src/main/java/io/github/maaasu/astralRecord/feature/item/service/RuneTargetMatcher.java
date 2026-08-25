package io.github.maaasu.astralRecord.feature.item.service;

import io.github.maaasu.astralRecord.feature.item.model.ItemEquipment;
import io.github.maaasu.astralRecord.feature.item.model.ItemRune;
import org.jetbrains.annotations.NotNull;

final class RuneTargetMatcher {

    private RuneTargetMatcher() {
    }

    static boolean matches(@NotNull ItemRune rune, @NotNull ItemEquipment equipment) {
        String slot = equipment.getSlot() == null ? "" : equipment.getSlot().name();
        boolean slotMatches = rune.getTargetSlots().stream()
            .anyMatch(targetSlot -> targetSlot != null
                && ("ANY".equalsIgnoreCase(targetSlot.trim())
                    || targetSlot.trim().equalsIgnoreCase(slot)));
        if (!slotMatches) {
            return false;
        }

        String equipmentTag = equipment.getTag();
        return rune.getTargetTags().isEmpty()
            || rune.getTargetTags().stream().anyMatch(targetTag -> targetTag != null
                && equipmentTag != null
                && targetTag.trim().equals(equipmentTag.trim()));
    }
}
