package io.github.maaasu.astralRecord.feature.dungeon.command;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.dungeon.service.DungeonService;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.infrastructure.command.AstCommand;
import org.jetbrains.annotations.NotNull;

/** クリア待機中のダンジョン報酬 GUI を再表示するコマンドです。 */
public final class DungeonDropCommand extends AstCommand {
    /** {@code /drop} コマンドを構成します。 */
    public DungeonDropCommand() {
        super(
                "drop",
                "ダンジョンクリア報酬を再表示します。",
                "/drop",
                true
        );
    }

    @Override
    protected void executePlayerCommand(@NotNull AstPlayer astPlayer, @NotNull String[] args) {
        if (args.length != 0) {
            sendUsage(astPlayer.getBukkit());
            return;
        }

        AstralRecord plugin = AstralRecord.getInstance();
        DungeonService service = plugin == null ? null : plugin.getDungeonService();
        if (service == null) {
            PlayerMessageService.getInstance().send(astPlayer, PlayerMsgId.P_7000);
            return;
        }
        if (!service.openRewardDrop(astPlayer.getBukkit())) {
            PlayerMessageService.getInstance().send(astPlayer, PlayerMsgId.P_7032);
        }
    }
}
