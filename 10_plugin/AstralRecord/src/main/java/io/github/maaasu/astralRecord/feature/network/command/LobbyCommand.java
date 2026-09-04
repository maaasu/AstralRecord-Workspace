package io.github.maaasu.astralRecord.feature.network.command;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.network.NetworkBridgeService;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.infrastructure.command.AstCommand;
import org.jetbrains.annotations.NotNull;

/** 全保存完了後にロビーへ戻るコマンドです。 */
public final class LobbyCommand extends AstCommand {
    public LobbyCommand() {
        super("lobby", "プレイヤーデータを保存してロビーへ戻ります。", "/lobby", true);
    }

    @Override
    protected void executePlayerCommand(@NotNull AstPlayer player, @NotNull String[] args) {
        NetworkBridgeService bridge = AstralRecord.getInstance().getNetworkBridgeService();
        if (bridge == null) {
            PlayerMessageService.getInstance().send(player, PlayerMsgId.P_7150);
            return;
        }
        bridge.transferToLobby(player);
    }
}
