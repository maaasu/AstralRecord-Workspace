package io.github.maaasu.astralRecord.feature.channelboost;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.infrastructure.command.AstCommand;
import org.jetbrains.annotations.NotNull;

/** 全チャンネルのEXP/DROP状態を表示します。 */
public final class ServerInfoCommand extends AstCommand {
    public ServerInfoCommand() {
        super("server-info", "全チャンネルのEXP/DROPブーストを表示します。", "/server-info", true);
    }

    /** キャッシュ済みの全チャンネル状態を表示します。 */
    @Override
    protected void executePlayerCommand(@NotNull AstPlayer player, @NotNull String[] args) {
        var messages = PlayerMessageService.getInstance();
        messages.send(player, PlayerMsgId.P_7611);
        var lines = AstralRecord.getInstance().getChannelBoostService().displayLines();
        if (lines.isEmpty()) messages.send(player, PlayerMsgId.P_7612, "取得中");
        else for (String line : lines) messages.send(player, PlayerMsgId.P_7612, line);
    }
}
