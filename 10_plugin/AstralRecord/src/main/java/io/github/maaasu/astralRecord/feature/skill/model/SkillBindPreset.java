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
    public static final int ACTION_RING_SLOT_COUNT = 6;
    public static final int PASSIVE_SLOT_COUNT = 9;
    public static final String WEAPON_NORMAL_ATTACK_BINDING_ID = "__weapon_normal_attack__";

    private final UUID presetId;
    private final UUID accountId;
    private final int presetIndex;
    private final List<String> activeSkillSlots;
    private final String leftClickSkillId;
    private final List<String> passiveSkillSlots;
    private final boolean unlocked;
    private final boolean saved;
    private final int version;
    private final boolean selected;

    public SkillBindPreset(
        @Nullable UUID presetId,
        @NotNull UUID accountId,
        int presetIndex,
        @NotNull List<String> activeSkillSlots,
        @Nullable String leftClickSkillId,
        @NotNull List<String> passiveSkillSlots,
        boolean unlocked,
        boolean saved,
        int version
    ) {
        this(presetId, accountId, presetIndex, activeSkillSlots, leftClickSkillId, passiveSkillSlots,
            unlocked, saved, version, false);
    }

    /**
     * APIから取得したスキルバインドプリセットを生成します。
     *
     * @param presetId プリセット行 ID。未保存の場合は {@code null}
     * @param accountId アカウント ID
     * @param presetIndex プリセット番号
     * @param activeSkillSlots アクションリングのバインド
     * @param leftClickSkillId 左クリックバインド
     * @param passiveSkillSlots パッシブのバインド
     * @param unlocked 解放済みかどうか
     * @param saved APIへ保存済みかどうか
     * @param version 保存バージョン
     * @param selected 現在選択中かどうか
     */
    public SkillBindPreset(
        @Nullable UUID presetId,
        @NotNull UUID accountId,
        int presetIndex,
        @NotNull List<String> activeSkillSlots,
        @Nullable String leftClickSkillId,
        @NotNull List<String> passiveSkillSlots,
        boolean unlocked,
        boolean saved,
        int version,
        boolean selected
    ) {
        this.presetId = presetId;
        this.accountId = accountId;
        this.presetIndex = presetIndex;
        this.activeSkillSlots = normalizeActionRingSlots(activeSkillSlots);
        this.leftClickSkillId = normalizeSkillId(leftClickSkillId);
        this.passiveSkillSlots = normalizePassiveSlots(passiveSkillSlots);
        this.unlocked = unlocked;
        this.saved = saved;
        this.version = version;
        this.selected = selected;
    }

    /**
     * 左クリックバインド未導入時の呼び出し元との互換コンストラクタです。
     */
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
        this(presetId, accountId, presetIndex, activeSkillSlots,
            WEAPON_NORMAL_ATTACK_BINDING_ID,
            passiveSkillSlots, unlocked, saved, version);
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

    /**
     * 左クリックへ割り当てたスキル ID を返します。
     *
     * @return 武器通常攻撃の予約 ID、任意スキル ID、または未設定時は {@code null}
     */
    public @Nullable String getLeftClickSkillId() {
        return leftClickSkillId;
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

    /**
     * 現在選択中のプリセットかどうかを返します。
     *
     * @return 選択中の場合は {@code true}
     */
    public boolean isSelected() {
        return selected;
    }

    public static @NotNull List<String> normalizeActionRingSlots(@NotNull List<String> slots) {
        return normalizeSlots(slots, ACTION_RING_SLOT_COUNT);
    }

    public static @NotNull List<String> normalizePassiveSlots(@NotNull List<String> slots) {
        return normalizeSlots(slots, PASSIVE_SLOT_COUNT);
    }

    private static @NotNull List<String> normalizeSlots(@NotNull List<String> slots, int slotCount) {
        List<String> normalized = new ArrayList<>(slotCount);
        for (int i = 0; i < slotCount; i++) {
            normalized.add(i < slots.size() ? normalizeSkillId(slots.get(i)) : null);
        }
        return normalized;
    }

    private static @Nullable String normalizeSkillId(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
