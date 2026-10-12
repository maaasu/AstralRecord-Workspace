package io.github.maaasu.astralRecord.feature.serverrecord.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** @param generatedAt APIで集計した時刻 @param rankings 種別ごとの順位 */
public record ServerRecordSnapshot(Instant generatedAt, Map<ServerRecordType, List<Entry>> rankings) {
    /** 応答を不変スナップショットにします。 */
    public ServerRecordSnapshot { rankings = Map.copyOf(rankings); }

    /** @param rank 順位 @param playerName プレイヤー名 @param value 集計値 */
    public record Entry(int rank, String playerName, long value) { }
}
