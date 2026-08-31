package io.github.maaasu.astralRecord.feature.skill.service;

import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.skill.model.LearnedSkillInstance;
import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
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
            return MaterialKind.GEM_PURCHASE_ONLY;
        }
        return resolve(entry.learnedSkill(), entry.definition(), item);
    }

    /**
     * 習得済みスキルとシジル素材を照合します。
     *
     * @param learnedSkill 合成対象の習得済みスキル
     * @param definition 対象スキル定義
     * @param item 所持シジル候補
     * @return シジル素材の適合結果
     */
    public static @NotNull MaterialKind resolve(
        @NotNull LearnedSkillInstance learnedSkill,
        @NotNull SkillDefinition definition,
        @NotNull ItemModel item
    ) {
        if (item.getSigil() == null) {
            return MaterialKind.NONE;
        }
        if (definition.getAllowedSigilIds().stream().noneMatch(id -> sameId(id, item.getId()))) {
            return MaterialKind.SIGIL_NOT_ALLOWED;
        }
        boolean duplicateGroup = learnedSkill.getSigils().stream()
            .anyMatch(sigil -> sameId(sigil.getEquipGroupId(), item.getSigil().getEquipGroupId()));
        if (duplicateGroup) {
            return MaterialKind.DUPLICATE_SIGIL_GROUP;
        }
        return learnedSkill.getSigils().size() >= sigilSlotCount(learnedSkill, definition)
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
        return sigilSlotCount(entry.learnedSkill(), entry.definition());
    }

    /**
     * 習得済みスキルの現在レベルで利用できるシジル枠数を返します。
     *
     * @param learnedSkill 習得済みスキル
     * @param definition レベル別枠定義
     * @return 現在レベル以下で最大の枠数。枠定義がなければ 0
     */
    public static int sigilSlotCount(
        @NotNull LearnedSkillInstance learnedSkill,
        @NotNull SkillDefinition definition
    ) {
        return definition.getSigilSlotsByLevel().stream()
            .filter(slot -> slot.getLevel() <= learnedSkill.getLevel())
            .mapToInt(SkillSigilSlotDefinition::getSlots)
            .max()
            .orElse(0);
    }

    /**
     * 合成画面を開く余地があるかを返します。
     *
     * <p>シジル枠の空きがあれば true です。スキルレベルはジェム購入時に上昇するため、
     * ジェム所持や最大レベルは合成画面の可否へ含めません。</p>
     *
     * @param entry 合成対象の習得済みスキル
     * @return 合成画面を開ける場合 true
     */
    public static boolean canOpenSynthesis(@NotNull SkillManagerEntry entry) {
        return entry.learnedSkill().getSigils().size() < sigilSlotCount(entry);
    }

    private static boolean sameId(@NotNull String left, @NotNull String right) {
        return left.trim().equalsIgnoreCase(right.trim());
    }

    /** 合成素材としての分類と、選択を許可するかを表します。 */
    public enum MaterialKind {
        NONE(false),
        GEM_PURCHASE_ONLY(false),
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
