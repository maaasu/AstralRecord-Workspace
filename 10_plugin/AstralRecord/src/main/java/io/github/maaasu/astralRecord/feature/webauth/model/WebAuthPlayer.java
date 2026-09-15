package io.github.maaasu.astralRecord.feature.webauth.model;

import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/** API から MCID で一意に解決したプレイヤーです。 */
public record WebAuthPlayer(@NotNull UUID userUuid, @NotNull String mcid) {
}
