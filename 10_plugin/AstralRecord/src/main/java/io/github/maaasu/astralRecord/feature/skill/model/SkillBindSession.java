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
    private String leftClickDraft;
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
        if (slotIndex < 0 || slotIndex >= slotCount(type)) {
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
     * 選択中スロット、または種別に応じた優先順位で空きスロットへスキルを割り当てます。
     * 発動スキルは action ring の空き枠を優先し、すべて埋まっている場合だけ空の左クリック枠を使用します。
     *
     * @param skillId 割り当てるスキル ID
     * @param skillKind 割り当てるスキル種別。passive は passive 枠、active は action ring、最後に左クリック枠の順で配置先を決定します
     * @return 割当できた場合は {@code true}
     */
    public boolean assignSelectedOrNextSlot(@NotNull String skillId, @NotNull SkillKind skillKind) {
        return assignSelectedOrNextSlot(skillId, skillKind, SkillBindPreset.PASSIVE_SLOT_COUNT);
    }

    /**
     * 選択中スロット、または現在有効な種別対応スロットへスキルを割り当てます。
     *
     * <p>パッシブは {@code availablePassiveSlotCount} より小さい空き枠だけを自動選択し、
     * 発動スキルは action ring の空き枠、最後に空の左クリック枠を使用します。</p>
     *
     * @param skillId 割り当てる習得済みスキル個体 ID
     * @param skillKind 割り当てるスキル種別
     * @param availablePassiveSlotCount 現在利用できるパッシブ枠数
     * @return 割当できた場合は {@code true}
     */
    public boolean assignSelectedOrNextSlot(
        @NotNull String skillId,
        @NotNull SkillKind skillKind,
        int availablePassiveSlotCount
    ) {
        SkillBindType targetType = selectedBindType;
        int targetIndex = selectedBindSlotIndex;
        if (targetType == null || targetIndex < 0 || targetIndex >= slotCount(targetType)) {
            targetType = skillKind.isPassive() ? SkillBindType.PASSIVE : SkillBindType.ACTIVE;
            targetIndex = targetType == SkillBindType.PASSIVE
                ? findNextFreeSlot(passiveDraft, availablePassiveSlotCount)
                : findNextFreeSlot(activeDraft);
            if (targetIndex < 0 && targetType == SkillBindType.ACTIVE && isLeftClickUnassigned()) {
                targetType = SkillBindType.LEFT_CLICK;
                targetIndex = 0;
            }
        } else if (!matches(targetType, skillKind)) {
            return false;
        }
        if (targetType == SkillBindType.PASSIVE
            && targetIndex >= Math.max(0, Math.min(availablePassiveSlotCount, passiveDraft.size()))) {
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
        if (slotIndex < 0 || slotIndex >= slotCount(type)) {
            return null;
        }
        if (type == SkillBindType.LEFT_CLICK) {
            return leftClickDraft;
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

    public @Nullable String leftClickDraft() {
        return leftClickDraft;
    }

    public @NotNull SkillBindPreset selectedPreset() {
        return presets.get(selectedPresetIndex - 1);
    }

    public void loadPreset(int presetIndex) {
        this.selectedPresetIndex = presetIndex;
        SkillBindPreset preset = presets.get(presetIndex - 1);
        this.activeDraft = new ArrayList<>(preset.getActiveSkillSlots());
        this.leftClickDraft = preset.getLeftClickSkillId();
        this.passiveDraft = new ArrayList<>(preset.getPassiveSkillSlots());
        clearSelectedBindSlot();
    }

    public boolean isDirty() {
        SkillBindPreset preset = selectedPreset();
        return !preset.getActiveSkillSlots().equals(activeDraft)
            || !java.util.Objects.equals(preset.getLeftClickSkillId(), leftClickDraft)
            || !preset.getPassiveSkillSlots().equals(passiveDraft);
    }

    public void setSlot(@NotNull SkillBindType type, int slotIndex, @Nullable String skillId) {
        if (slotIndex < 0 || slotIndex >= slotCount(type)) {
            return;
        }
        if (type == SkillBindType.LEFT_CLICK) {
            setLeftClickSkillId(skillId);
            return;
        }
        List<String> target = type == SkillBindType.ACTIVE ? activeDraft : passiveDraft;
        target.set(slotIndex, skillId == null || skillId.isBlank() ? null : skillId.trim());
    }

    public void clear(@NotNull SkillBindType type) {
        if (type == SkillBindType.LEFT_CLICK) {
            setLeftClickSkillId(null);
            if (selectedBindType == type) {
                clearSelectedBindSlot();
            }
            return;
        }
        List<String> target = type == SkillBindType.ACTIVE ? activeDraft : passiveDraft;
        for (int i = 0; i < target.size(); i++) {
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
        return findNextFreeSlot(slots, slots.size());
    }

    private int findNextFreeSlot(@NotNull List<String> slots, int availableSlotCount) {
        int limit = Math.max(0, Math.min(availableSlotCount, slots.size()));
        for (int index = 0; index < limit; index++) {
            String skillId = slots.get(index);
            if (skillId == null || skillId.isBlank()) {
                return index;
            }
        }
        return -1;
    }

    /**
     * 左クリックバインドが未設定かを判定します。
     *
     * <p>発動スキルの自動配置で action ring がすべて埋まっている場合に、最後の優先順位で左クリック枠を使用できるか判定します。</p>
     *
     * @return 左クリックバインドが {@code null} または空白の場合は {@code true}
     */
    private boolean isLeftClickUnassigned() {
        return leftClickDraft == null || leftClickDraft.isBlank();
    }

    /** 左クリックバインドを更新します。 */
    public void setLeftClickSkillId(@Nullable String skillId) {
        this.leftClickDraft = skillId == null || skillId.isBlank() ? null : skillId.trim();
    }

    private int slotCount(@NotNull SkillBindType type) {
        if (type == SkillBindType.LEFT_CLICK) {
            return 1;
        }
        return type == SkillBindType.PASSIVE
            ? SkillBindPreset.PASSIVE_SLOT_COUNT
            : SkillBindPreset.ACTION_RING_SLOT_COUNT;
    }
}
