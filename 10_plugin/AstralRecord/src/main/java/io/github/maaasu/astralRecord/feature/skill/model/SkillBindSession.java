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
    private SkillBindType selectedBindType;
    private int selectedBindSlotIndex = -1;

    public SkillBindSession(@NotNull List<SkillBindPreset> presets) {
        this(presets, 1);
    }

    public SkillBindSession(@NotNull List<SkillBindPreset> presets, int initialPresetIndex) {
        this.presets = presets;
        int safePresetIndex = Math.max(1, Math.min(initialPresetIndex, presets.size()));
        this.selectedPresetIndex = safePresetIndex;
        loadPreset(safePresetIndex);
    }

    public @NotNull List<SkillBindPreset> presets() {
        return presets;
    }

    public int selectedPresetIndex() {
        return selectedPresetIndex;
    }

    /**
     * 現在選択中のバインド種別を返します。
     *
     * @return 選択中のバインド種別。未選択の場合は {@code null}
     */
    public @Nullable SkillBindType selectedBindType() {
        return selectedBindType;
    }

    /**
     * 現在選択中のバインドスロット番号を返します。
     *
     * @return 0 始まりのスロット番号。未選択の場合は {@code -1}
     */
    public int selectedBindSlotIndex() {
        return selectedBindSlotIndex;
    }

    /**
     * 指定されたバインドスロットが選択中かどうかを返します。
     *
     * @param type      バインド種別
     * @param slotIndex 0 始まりのスロット番号
     * @return 選択中の場合は {@code true}
     */
    public boolean isSelectedBindSlot(@NotNull SkillBindType type, int slotIndex) {
        return selectedBindType == type && selectedBindSlotIndex == slotIndex;
    }

    /**
     * スキル割当先のバインドスロットを選択します。
     *
     * @param type      バインド種別
     * @param slotIndex 0 始まりのスロット番号
     */
    public void selectBindSlot(@NotNull SkillBindType type, int slotIndex) {
        if (slotIndex < 0 || slotIndex >= SkillBindPreset.SLOT_COUNT) {
            clearSelectedBindSlot();
            return;
        }
        this.selectedBindType = type;
        this.selectedBindSlotIndex = slotIndex;
    }

    /**
     * バインドスロット選択を解除します。
     */
    public void clearSelectedBindSlot() {
        this.selectedBindType = null;
        this.selectedBindSlotIndex = -1;
    }

    /**
     * 選択中スロット、または次の空きスロットへスキルを割り当てます。
     *
     * @param skillId 割り当てるスキル ID
     * @return 割当できた場合は {@code true}
     */
    public boolean assignSelectedOrNextSlot(@NotNull String skillId, @NotNull SkillKind skillKind) {
        SkillBindType targetType = selectedBindType;
        int targetIndex = selectedBindSlotIndex;
        if (targetType == null || targetIndex < 0 || targetIndex >= SkillBindPreset.SLOT_COUNT) {
            targetType = skillKind.isPassive() ? SkillBindType.PASSIVE : SkillBindType.ACTIVE;
            targetIndex = findNextFreeSlot(targetType == SkillBindType.ACTIVE ? activeDraft : passiveDraft);
        } else if (!matches(targetType, skillKind)) {
            return false;
        }
        if (targetIndex < 0) {
            return false;
        }
        setSlot(targetType, targetIndex, skillId);
        clearSelectedBindSlot();
        return true;
    }

    private boolean matches(@NotNull SkillBindType bindType, @NotNull SkillKind skillKind) {
        return bindType == SkillBindType.PASSIVE ? skillKind.isPassive() : !skillKind.isPassive();
    }

    /**
     * 指定スロットの現在のスキル ID を返します。
     *
     * @param type      バインド種別
     * @param slotIndex 0 始まりのスロット番号
     * @return 設定済みスキル ID。未設定の場合は {@code null}
     */
    public @Nullable String skillIdAt(@NotNull SkillBindType type, int slotIndex) {
        if (slotIndex < 0 || slotIndex >= SkillBindPreset.SLOT_COUNT) {
            return null;
        }
        List<String> target = type == SkillBindType.ACTIVE ? activeDraft : passiveDraft;
        return target.get(slotIndex);
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
        clearSelectedBindSlot();
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
        if (selectedBindType == type) {
            clearSelectedBindSlot();
        }
    }

    public void replaceSelectedPreset(@NotNull SkillBindPreset savedPreset) {
        presets.set(savedPreset.getPresetIndex() - 1, savedPreset);
        loadPreset(savedPreset.getPresetIndex());
    }

    private int findNextFreeSlot(@NotNull List<String> slots) {
        for (int index = 0; index < SkillBindPreset.SLOT_COUNT; index++) {
            String skillId = slots.get(index);
            if (skillId == null || skillId.isBlank()) {
                return index;
            }
        }
        return -1;
    }
}
