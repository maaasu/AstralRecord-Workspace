package io.github.maaasu.astralRecord.feature.quest.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

public enum QuestObjectiveType {
    KILL_MOB,
    GATHERING;

    public static @Nullable QuestObjectiveType from(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return valueOf(raw.trim().replace('-', '_').toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    public @NotNull String displayName() {
        return switch (this) {
            case KILL_MOB -> "討伐";
            case GATHERING -> "採取";
        };
    }
}
