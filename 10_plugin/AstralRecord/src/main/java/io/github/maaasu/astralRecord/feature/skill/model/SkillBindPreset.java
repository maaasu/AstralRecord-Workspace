package io.github.maaasu.astralRecord.feature.skill.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * スキルバインドプリセットの API モデルです。
 */
public final class SkillBindPreset {
    public static final int SLOT_COUNT = 8;

    private final UUID presetId;
    private final UUID accountId;
    private final int presetIndex;
    private final List<String> activeSkillSlots;
    private final List<String> passiveSkillSlots;
    private final boolean unlocked;
    private final boolean saved;
    private final int version;

    public SkillBindPreset(
        @Nullable UUID presetId,
        @NotNull UUID accountId,
        int presetIndex,
        @NotNull List<String> activeSkillSlots,
        @NotNull List<String> passiveSkillSlots,
        boolean unlocked,
        boolean saved,
        int version
    ) {
        this.presetId = presetId;
        this.accountId = accountId;
        this.presetIndex = presetIndex;
        this.activeSkillSlots = normalizeSlots(activeSkillSlots);
        this.passiveSkillSlots = normalizeSlots(passiveSkillSlots);
        this.unlocked = unlocked;
        this.saved = saved;
        this.version = version;
    }

    public @Nullable UUID getPresetId() {
        return presetId;
    }

    public @NotNull UUID getAccountId() {
        return accountId;
    }

    public int getPresetIndex() {
        return presetIndex;
    }

    public @NotNull List<String> getActiveSkillSlots() {
        return activeSkillSlots;
    }

    public @NotNull List<String> getPassiveSkillSlots() {
        return passiveSkillSlots;
    }

    public boolean isUnlocked() {
        return unlocked;
    }

    public boolean isSaved() {
        return saved;
    }

    public int getVersion() {
        return version;
    }

    public static @NotNull List<String> normalizeSlots(@NotNull List<String> slots) {
        List<String> normalized = new ArrayList<>(SLOT_COUNT);
        for (int i = 0; i < SLOT_COUNT; i++) {
            String value = i < slots.size() ? slots.get(i) : null;
            normalized.add(value == null || value.isBlank() ? null : value.trim());
        }
        return normalized;
    }
}
