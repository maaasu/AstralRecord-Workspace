package io.github.maaasu.astralRecord.feature.quest.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

public enum QuestRepeatMode {
    ONCE,
    REPEATABLE,
    COOLDOWN;

    public static @NotNull QuestRepeatMode from(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return ONCE;
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return ONCE;
        }
    }
}
