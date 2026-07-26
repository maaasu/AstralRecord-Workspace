package io.github.maaasu.astralRecord.feature.skilltree.model;

import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * スキルツリーノードの表示・効果有効化条件です。
 * 職業レベルは条件に含めず、現在職とプレイヤーレベルだけを扱います。
 */
public record SkillTreeUnlockCondition(
        @Nullable String classId,
        int playerLevel
) {
    public static final SkillTreeUnlockCondition NONE = new SkillTreeUnlockCondition(null, 0);

    public SkillTreeUnlockCondition {
        classId = classId == null || classId.isBlank()
                ? null
                : classId.trim().toLowerCase(Locale.ROOT);
        playerLevel = Math.max(0, playerLevel);
    }

    public boolean hasClassCondition() {
        return classId != null;
    }

    public boolean hasPlayerLevelCondition() {
        return playerLevel > 0;
    }
}
