package io.github.maaasu.astralRecord.feature.trade.model;

import org.jetbrains.annotations.NotNull;

import java.time.Instant;
import java.util.UUID;

public final class TradeRequest {
    private final UUID requestId;
    private final UUID senderUuid;
    private final String senderName;
    private final UUID targetUuid;
    private final String targetName;
    private final Instant createdAt;
    private final Instant expiresAt;
    private TradeRequestStatus status;

    public TradeRequest(
        @NotNull UUID requestId,
        @NotNull UUID senderUuid,
        @NotNull String senderName,
        @NotNull UUID targetUuid,
        @NotNull String targetName,
        @NotNull Instant createdAt,
        @NotNull Instant expiresAt
    ) {
        this.requestId = requestId;
        this.senderUuid = senderUuid;
        this.senderName = senderName;
        this.targetUuid = targetUuid;
        this.targetName = targetName;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
        this.status = TradeRequestStatus.PENDING;
    }

    public UUID getRequestId() { return requestId; }
    public UUID getSenderUuid() { return senderUuid; }
    public String getSenderName() { return senderName; }
    public UUID getTargetUuid() { return targetUuid; }
    public String getTargetName() { return targetName; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public TradeRequestStatus getStatus() { return status; }
    public void setStatus(@NotNull TradeRequestStatus status) { this.status = status; }

    public boolean isExpired(@NotNull Instant now) {
        return !expiresAt.isAfter(now);
    }
}
