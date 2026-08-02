package io.github.maaasu.astralRecord.feature.skill.service;

import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.skill.model.SkillManagerEntry;
import io.github.maaasu.astralRecord.feature.skill.model.SkillSigilSlotDefinition;
import org.jetbrains.annotations.NotNull;

/**
 * スキル合成素材の可否を、素材選択・プレビュー・確定操作で共通に判定します。
 *
 * <p>ここで拒否された素材は選択状態にもせず、合成 mutation も開始しません。API 側の
 * 同じ検証は最終防衛線として維持します。</p>
 */
public final class SkillSynthesisMaterialEligibility {
    private SkillSynthesisMaterialEligibility() {
    }

    /**
     * 合成対象の習得個体と素材を照合します。
     *
     * <p>呼出側は {@link MaterialKind#usable()} が {@code false} の場合、素材を選択状態へ移さず、
     * 非表示予約や API mutation を開始してはいけません。</p>
     *
     * @param entry 合成対象の習得済みスキル。定義と現在の装着シジルを持つこと
     * @param item  プレイヤー所持欄から選択された素材候補
     * @return 素材種別と拒否理由。{@link MaterialKind#usable()} が true の値だけが合成可能
     */
    public static @NotNull MaterialKind resolve(@NotNull SkillManagerEntry entry, @NotNull ItemModel item) {
        if (item.getSkillGem() != null) {
            return sameId(item.getSkillGem().getSkillId(), entry.learnedSkill().getSkillId())
                && entry.learnedSkill().getLevel() < entry.definition().getMaxLevel()
                ? MaterialKind.GEM
                : MaterialKind.INVALID_GEM;
        }
        if (item.getSigil() == null) {
            return MaterialKind.NONE;
        }
        if (entry.definition().getAllowedSigilIds().stream().noneMatch(id -> sameId(id, item.getId()))) {
            return MaterialKind.SIGIL_NOT_ALLOWED;
        }
        boolean duplicateGroup = entry.learnedSkill().getSigils().stream()
            .anyMatch(sigil -> sameId(sigil.getEquipGroupId(), item.getSigil().getEquipGroupId()));
        if (duplicateGroup) {
            return MaterialKind.DUPLICATE_SIGIL_GROUP;
        }
        return entry.learnedSkill().getSigils().size() >= sigilSlotCount(entry)
            ? MaterialKind.NO_SIGIL_SLOT
            : MaterialKind.SIGIL;
    }

    /**
     * 現在レベルで利用できるシジル枠数を返します。
     *
     * @param entry 合成対象の習得済みスキル。レベル別枠定義を持つこと
     * @return 現在レベル以下で最大の枠数。枠定義がなければ 0
     */
    public static int sigilSlotCount(@NotNull SkillManagerEntry entry) {
        return entry.definition().getSigilSlotsByLevel().stream()
            .filter(slot -> slot.getLevel() <= entry.learnedSkill().getLevel())
            .mapToInt(SkillSigilSlotDefinition::getSlots)
            .max()
            .orElse(0);
    }

    /**
     * 合成画面を開く余地があるかを返します。
     *
     * <p>同スキルジェムでのレベルアップ余地、またはシジル枠の空きのどちらかがあれば true です。
     * 個別素材の可否は {@link #resolve(SkillManagerEntry, ItemModel)} で判定します。</p>
     *
     * @param entry 合成対象の習得済みスキル
     * @return 合成画面を開ける場合 true
     */
    public static boolean canOpenSynthesis(@NotNull SkillManagerEntry entry) {
        return entry.learnedSkill().getLevel() < entry.definition().getMaxLevel()
            || entry.learnedSkill().getSigils().size() < sigilSlotCount(entry);
    }

    private static boolean sameId(@NotNull String left, @NotNull String right) {
        return left.trim().equalsIgnoreCase(right.trim());
    }

    /** 合成素材としての分類と、選択を許可するかを表します。 */
    public enum MaterialKind {
        NONE(false),
        INVALID_GEM(false),
        GEM(true),
        SIGIL(true),
        SIGIL_NOT_ALLOWED(false),
        NO_SIGIL_SLOT(false),
        DUPLICATE_SIGIL_GROUP(false);

        private final boolean usable;

        MaterialKind(boolean usable) {
            this.usable = usable;
        }

        /**
         * 選択および合成 mutation を進めてよい素材種別かを返します。
         *
         * @return 合成処理を開始できる場合 true
         */
        public boolean usable() {
            return usable;
        }
    }
}
