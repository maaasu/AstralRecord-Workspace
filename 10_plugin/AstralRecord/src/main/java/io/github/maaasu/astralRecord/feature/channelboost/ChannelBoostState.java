package io.github.maaasu.astralRecord.feature.channelboost;

import java.time.Instant;
import java.util.Map;

/** APIを正本とするチャンネル別の確定済みブーストです。 */
public record ChannelBoostState(Map<String, Channel> channels, long eventCursor) {
    public static final ChannelBoostState EMPTY = new ChannelBoostState(Map.of(), 0);

    /** 期限が来た効果はキャッシュ更新前でも倍率1として扱います。 */
    public record Boost(double multiplier, Instant expiresAt, String activatorAccountName) {
        /** 指定時点で期限内か判定します。 */
        public boolean activeAt(Instant now) {
            return expiresAt != null && expiresAt.isAfter(now) && multiplier > 1.0;
        }

        /** 期限外では1倍を返します。 */
        public double factorAt(Instant now) { return activeAt(now) ? multiplier : 1.0; }
    }

    /** EXP/DROPを独立して保持します。 */
    public record Channel(String channelId, String displayName, boolean networkBoostEnabled, Boost exp, Boost drop) {
        /** EXPの現在倍率を返します。 */
        public double expFactorAt(Instant now) { return exp == null ? 1.0 : exp.factorAt(now); }
        /** DROPの現在倍率を返します。 */
        public double dropFactorAt(Instant now) { return drop == null ? 1.0 : drop.factorAt(now); }
    }

    /** 識別子の大文字小文字を無視してチャンネルを探します。 */
    public Channel current(String channelId) {
        return channels.get(channelId.toLowerCase(java.util.Locale.ROOT));
    }
}
