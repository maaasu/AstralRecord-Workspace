package io.github.maaasu.astralRecord.feature.network;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.UUID;

/** Network APIが返すManagementDB正本のBAN状態です。 */
public record NetworkBanState(
    @NotNull UUID userUuid,
    @NotNull String mcid,
    int revision,
    boolean banned,
    boolean active,
    boolean indefinite,
    @Nullable Instant expiresAtUtc,
    @Nullable String reason
) {
}
