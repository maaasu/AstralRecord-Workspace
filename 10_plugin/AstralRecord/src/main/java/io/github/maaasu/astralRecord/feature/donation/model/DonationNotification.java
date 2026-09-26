package io.github.maaasu.astralRecord.feature.donation.model;

import org.jetbrains.annotations.NotNull;

/** API に保留された寄付結果の通知です。 */
public record DonationNotification(
    @NotNull String id,
    @NotNull String kind,
    long amount,
    @NotNull String message
) {
}
