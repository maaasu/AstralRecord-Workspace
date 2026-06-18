package io.github.maaasu.astralRecord.feature.skilltree.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * スキルツリーノード解放に使用するポイント種別です。
 */
public enum SkillTreePointType {
    CLASS_POINT("CP"),
    PASSIVE_POINT("PP");

    private final String displayName;

    SkillTreePointType(@NotNull String displayName) {
        this.displayName = displayName;
    }

    public @NotNull String displayName() {
        return displayName;
    }

    public static @NotNull SkillTreePointType fromRaw(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return PASSIVE_POINT;
        }
        String normalized = raw.trim().replace('-', '_').replace(' ', '_').toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "CP", "CLASS", "CLASS_POINT" -> CLASS_POINT;
            case "PP", "PASSIVE", "PASSIVE_POINT" -> PASSIVE_POINT;
            default -> PASSIVE_POINT;
        };
    }
}
