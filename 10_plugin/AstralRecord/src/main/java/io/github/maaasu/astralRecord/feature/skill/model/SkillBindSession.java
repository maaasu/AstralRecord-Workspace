package io.github.maaasu.astralRecord.feature.skill.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * スキルバインド GUI の編集中セッションです。
 */
public final class SkillBindSession {
    private final List<SkillBindPreset> presets;
    private int selectedPresetIndex;
    private List<String> activeDraft;
    private List<String> passiveDraft;
    private String selectedSkillId;

    public SkillBindSession(@NotNull List<SkillBindPreset> presets) {
        this.presets = presets;
        this.selectedPresetIndex = 1;
        loadPreset(1);
    }

    public @NotNull List<SkillBindPreset> presets() {
        return presets;
    }

    public int selectedPresetIndex() {
        return selectedPresetIndex;
    }

    public @Nullable String selectedSkillId() {
        return selectedSkillId;
    }

    public void selectedSkillId(@Nullable String selectedSkillId) {
        this.selectedSkillId = selectedSkillId;
    }

    public @NotNull List<String> activeDraft() {
        return activeDraft;
    }

    public @NotNull List<String> passiveDraft() {
        return passiveDraft;
    }

    public @NotNull SkillBindPreset selectedPreset() {
        return presets.get(selectedPresetIndex - 1);
    }

    public void loadPreset(int presetIndex) {
        this.selectedPresetIndex = presetIndex;
        SkillBindPreset preset = presets.get(presetIndex - 1);
        this.activeDraft = new ArrayList<>(preset.getActiveSkillSlots());
        this.passiveDraft = new ArrayList<>(preset.getPassiveSkillSlots());
        this.selectedSkillId = null;
    }

    public boolean isDirty() {
        SkillBindPreset preset = selectedPreset();
        return !preset.getActiveSkillSlots().equals(activeDraft)
            || !preset.getPassiveSkillSlots().equals(passiveDraft);
    }

    public void setSlot(@NotNull SkillBindType type, int slotIndex, @Nullable String skillId) {
        if (slotIndex < 0 || slotIndex >= SkillBindPreset.SLOT_COUNT) {
            return;
        }
        List<String> target = type == SkillBindType.ACTIVE ? activeDraft : passiveDraft;
        target.set(slotIndex, skillId == null || skillId.isBlank() ? null : skillId.trim());
    }

    public void clear(@NotNull SkillBindType type) {
        List<String> target = type == SkillBindType.ACTIVE ? activeDraft : passiveDraft;
        for (int i = 0; i < SkillBindPreset.SLOT_COUNT; i++) {
            target.set(i, null);
        }
    }

    public void replaceSelectedPreset(@NotNull SkillBindPreset savedPreset) {
        presets.set(savedPreset.getPresetIndex() - 1, savedPreset);
        loadPreset(savedPreset.getPresetIndex());
    }
}
