package io.github.maaasu.astralRecord.feature.account.model;

import java.util.List;

/** 職業とビルドに対応する戦闘検証用の進行プリセットです。 */
public record ProgressionPreset(String id, String name, String classId, String buildId, List<Checkpoint> levels) {
    public static final int MIN_LEVEL = 1;
    public static final int MAX_LEVEL = 50;

    /** レベル順のスナップショットを不変リストとして保持します。 */
    public ProgressionPreset {
        levels = List.copyOf(levels);
    }

    /**
     * 指定レベル以下で最後に定義されたスナップショットを取得します。
     * @param level 適用するプレイヤーレベル（1〜50）
     * @return 適用する戦闘構成
     */
    public Checkpoint atLevel(int level) {
        if (level < MIN_LEVEL || level > MAX_LEVEL) throw new IllegalArgumentException("level: " + level);
        for (int index = levels.size() - 1; index >= 0; index--) {
            if (levels.get(index).level() <= level) return levels.get(index);
        }
        throw new IllegalStateException("Missing level 1: " + id);
    }

    /** 指定レベル以降に使用する職業レベル・習得ノード・装備・スキル枠です。 */
    public record Checkpoint(int level, int classLevel, List<Integer> nodes, List<Equipment> equipment,
                             List<SkillBinding> skills, List<SkillBinding> passives) {
        /** リストを不変のスナップショットとして保持します。 */
        public Checkpoint {
            nodes = List.copyOf(nodes);
            equipment = List.copyOf(equipment);
            skills = List.copyOf(skills);
            passives = List.copyOf(passives);
        }
    }

    /** 装備先の枠、item: 参照、強化値です。 */
    public record Equipment(String slot, String item, int enhanceLevel) { }

    /** 1始まりの設定枠、skill: 参照、個体のレベルとシジルです。 */
    public record SkillBinding(int slot, String skill, int skillLevel, List<Sigil> sigils) {
        /** シジルを不変リストとして保持します。 */
        public SkillBinding {
            sigils = List.copyOf(sigils);
        }
    }

    /** スキル個体内の1始まりのシジル枠と item: 参照です。 */
    public record Sigil(int slot, String item) { }
}
