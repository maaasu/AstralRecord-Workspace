package io.github.maaasu.astralRecord.feature.vip.model;

import java.time.Duration;
import java.time.Instant;

/** APIで確定したアカウント特典。期限はUTCの瞬間として保持します。 */
public record AccountBenefitsSnapshot(long instancePriorityUses, Instant donerExpiresAt,
                                      Instant astralderExpiresAt) {
    public static final AccountBenefitsSnapshot EMPTY = new AccountBenefitsSnapshot(0, null, null);

    /** 現在有効なVIP種別を返します。ASTRALDERを先に消化します。 */
    public String tier() { return tierAt(Instant.now()); }

    /** 指定時点に有効な種別を副作用なく返します。 */
    public String tierAt(Instant now) {
        if (astralderExpiresAt != null && astralderExpiresAt.isAfter(now)) return "ASTRALDER";
        if (donerExpiresAt != null && donerExpiresAt.isAfter(now)) return "DONER";
        return "NONE";
    }

    /** 現在有効な種別の期限、通常プレイヤーならnullを返します。 */
    public Instant expiresAt() {
        return switch (tier()) {
            case "ASTRALDER" -> astralderExpiresAt;
            case "DONER" -> donerExpiresAt;
            default -> null;
        };
    }

    /** 期限までの日数を端数切り上げで返します。 */
    public long remainingDays() {
        Instant end = expiresAt();
        return end == null ? 0 : Math.max(1, (Duration.between(Instant.now(), end).getSeconds() + 86399) / 86400);
    }
}
