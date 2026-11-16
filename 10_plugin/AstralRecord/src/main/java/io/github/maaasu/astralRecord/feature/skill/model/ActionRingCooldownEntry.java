package io.github.maaasu.astralRecord.feature.skill.model;

/** Read-only HUD projection for a configured action-ring position. */
public record ActionRingCooldownEntry(
        int index, String skillId, long remainingTicks, long totalCooldownTicks, boolean available
) {
}
