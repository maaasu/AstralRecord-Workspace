package io.github.maaasu.astralRecord.feature.quest.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

public enum QuestCompletionMode {
    AUTO,
    NPC;

    public static @NotNull QuestCompletionMode from(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return NPC;
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return NPC;
        }
    }
}
