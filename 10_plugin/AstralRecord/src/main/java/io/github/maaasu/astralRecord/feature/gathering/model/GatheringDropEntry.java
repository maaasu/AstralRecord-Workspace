package io.github.maaasu.astralRecord.feature.gathering.model;

import org.jetbrains.annotations.NotNull;

public record GatheringDropEntry(@NotNull String itemId, double rate, @NotNull String amount, boolean luckAffected, boolean hidden) {
}
