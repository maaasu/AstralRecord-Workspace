package io.github.maaasu.astralRecord.feature.inventory.state;

import java.time.Instant;
import java.util.UUID;

/** 保存を停止した瞬間の診断情報。payload は復元用ではなく調査用です。 */
public record PlayerStateFailure(UUID accountId, UUID snapshotId, Instant occurredAt,
                                 String trigger, int attempt, String payload, Throwable cause) {
}
