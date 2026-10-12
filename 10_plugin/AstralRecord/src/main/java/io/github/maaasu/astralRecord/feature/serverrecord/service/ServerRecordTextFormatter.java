package io.github.maaasu.astralRecord.feature.serverrecord.service;

import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgResource;
import io.github.maaasu.astralRecord.feature.serverrecord.model.ServerRecordSnapshot;
import io.github.maaasu.astralRecord.feature.serverrecord.model.ServerRecordType;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/** 金・銀・銅の順位と暗色背景に合わせたランキング本文を生成します。 */
public final class ServerRecordTextFormatter {
    private static final DateTimeFormatter UPDATED_AT = DateTimeFormatter.ofPattern("MM/dd HH:mm:ss")
            .withZone(ZoneId.of("Asia/Tokyo"));

    private ServerRecordTextFormatter() { }

    /**
     * 読込中・通信失敗時を区別し、最後に取得した順位を保持して表示します。
     * @param type ランキング種別
     * @param snapshot 最新成功応答。未取得の場合null
     * @param failed 直近の取得失敗
     * @param interval 更新間隔（秒）
     * @return 色コード付き本文
     */
    public static String format(ServerRecordType type, ServerRecordSnapshot snapshot, boolean failed, long interval) {
        var lines = new java.util.ArrayList<String>();
        lines.add(message(PlayerMsgId.P_7820));
        lines.add(PlayerMsgResource.format(PlayerMsgId.P_7821.getId(), type.displayName()));
        lines.add(message(PlayerMsgId.P_7822));
        if (snapshot == null) {
            lines.add(message(failed ? PlayerMsgId.P_7827 : PlayerMsgId.P_7826));
        } else {
            List<ServerRecordSnapshot.Entry> entries = snapshot.rankings().get(type);
            if (entries.isEmpty()) lines.add(message(PlayerMsgId.P_7825));
            for (ServerRecordSnapshot.Entry entry : entries) {
                PlayerMsgId row = switch (entry.rank()) {
                    case 1 -> PlayerMsgId.P_7840;
                    case 2 -> PlayerMsgId.P_7841;
                    case 3 -> PlayerMsgId.P_7842;
                    default -> PlayerMsgId.P_7823;
                };
                String name = entry.playerName().replaceAll("[&§\\r\\n]", "");
                if (name.length() > 32) name = name.substring(0, 32);
                lines.add(PlayerMsgResource.format(row.getId(), entry.rank(), name, value(type, entry.value())));
            }
        }
        lines.add(message(PlayerMsgId.P_7822));
        if (snapshot != null) lines.add(PlayerMsgResource.format(PlayerMsgId.P_7824.getId(), UPDATED_AT.format(snapshot.generatedAt())));
        lines.add(PlayerMsgResource.format(PlayerMsgId.P_7828.getId(), interval));
        if (type == ServerRecordType.PLAY_TIME) lines.add(message(PlayerMsgId.P_7843));
        if (snapshot != null && failed) lines.add(message(PlayerMsgId.P_7827));
        return String.join("\n", lines);
    }

    /** @param type 表示種別 @param value 集計値 @return 単位付き表示 */
    private static String value(ServerRecordType type, long value) {
        if (type == ServerRecordType.PLAY_TIME) {
            return PlayerMsgResource.format(PlayerMsgId.P_7844.getId(), value / 3600L, (value / 60L) % 60L);
        }
        String number = String.format(Locale.JAPAN, "%,d", value);
        return switch (type) {
            case LEVEL -> PlayerMsgResource.format(PlayerMsgId.P_7845.getId(), number);
            case GOLD -> PlayerMsgResource.format(PlayerMsgId.P_7846.getId(), number);
            case DUNGEON_TYPES, QUEST_TYPES -> PlayerMsgResource.format(PlayerMsgId.P_7847.getId(), number);
            default -> PlayerMsgResource.format(PlayerMsgId.P_7848.getId(), number);
        };
    }

    /** @param id 表示リソースID @return テンプレート */
    private static String message(PlayerMsgId id) { return PlayerMsgResource.getMessage(id.getId()); }
}
