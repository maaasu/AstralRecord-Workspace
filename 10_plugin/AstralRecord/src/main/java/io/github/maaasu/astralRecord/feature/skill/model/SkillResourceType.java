package io.github.maaasu.astralRecord.feature.skill.model;

import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * スキル発動時に消費するリソース種別です。
 */
public enum SkillResourceType {

    MANA(PlayerMsgId.P_5801),
    ENERGY(PlayerMsgId.P_5806),
    ;

    private final PlayerMsgId insufficientMessageId;

    SkillResourceType(@NotNull PlayerMsgId insufficientMessageId) {
        this.insufficientMessageId = insufficientMessageId;
    }

    public @NotNull PlayerMsgId insufficientMessageId() {
        return insufficientMessageId;
    }

    public static @NotNull SkillResourceType fromRaw(@Nullable Object rawValue) {
        if (!(rawValue instanceof String value) || value.isBlank()) {
            return MANA;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return MANA;
        }
    }
}
