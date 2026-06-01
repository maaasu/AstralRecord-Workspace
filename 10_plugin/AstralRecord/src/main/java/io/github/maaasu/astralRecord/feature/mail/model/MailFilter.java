package io.github.maaasu.astralRecord.feature.mail.model;

import org.jetbrains.annotations.NotNull;

import java.util.Locale;

public enum MailFilter {
    ALL("all", "すべて"),
    UNREAD("unread", "未読"),
    READ("read", "既読");

    private final String apiValue;
    private final String displayNameJa;

    MailFilter(@NotNull String apiValue, @NotNull String displayNameJa) {
        this.apiValue = apiValue;
        this.displayNameJa = displayNameJa;
    }

    public @NotNull String getApiValue() {
        return apiValue;
    }

    public @NotNull String getDisplayNameJa() {
        return displayNameJa;
    }

    public @NotNull MailFilter next() {
        return switch (this) {
            case ALL -> UNREAD;
            case UNREAD -> READ;
            case READ -> ALL;
        };
    }

    public static @NotNull MailFilter fromApiValue(@NotNull String value) {
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (MailFilter filter : values()) {
            if (filter.apiValue.equals(normalized)) {
                return filter;
            }
        }
        return ALL;
    }
}
