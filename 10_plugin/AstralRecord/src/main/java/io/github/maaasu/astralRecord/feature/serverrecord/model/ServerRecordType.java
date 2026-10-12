package io.github.maaasu.astralRecord.feature.serverrecord.model;

import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgResource;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** APIと管理コマンドで共通に使用するランキング種別です。 */
public enum ServerRecordType {
    LEVEL("level", PlayerMsgId.P_7830),
    GOLD("gold", PlayerMsgId.P_7831),
    PLAY_TIME("play_time", PlayerMsgId.P_7832),
    DUNGEON_CLEARS("dungeon_clears", PlayerMsgId.P_7833),
    BOSS_DEFEATS("boss_defeats", PlayerMsgId.P_7834),
    MOB_DEFEATS("mob_defeats", PlayerMsgId.P_7835),
    DUNGEON_TYPES("dungeon_types", PlayerMsgId.P_7836),
    QUEST_TYPES("quest_types", PlayerMsgId.P_7837);

    private final String id;
    private final PlayerMsgId messageId;

    ServerRecordType(String id, PlayerMsgId messageId) {
        this.id = id;
        this.messageId = messageId;
    }

    /** @return API・コマンドの種別ID */
    public @NotNull String id() { return id; }

    /** @return プレイヤー向けランキング名 */
    public @NotNull String displayName() { return PlayerMsgResource.getMessage(messageId.getId()); }

    /** @param raw 種別ID @return 一致した種別。不明な場合null */
    public static @Nullable ServerRecordType parse(@NotNull String raw) {
        String normalized = raw.toLowerCase(Locale.ROOT);
        return Arrays.stream(values()).filter(type -> type.id.equals(normalized)).findFirst().orElse(null);
    }

    /** @return コマンド補完に使用する全種別ID */
    public static @NotNull List<String> ids() { return Arrays.stream(values()).map(ServerRecordType::id).toList(); }
}
