package io.github.maaasu.astralRecord.feature.playersetting.command;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.playersetting.PlayerSettingMsgId;
import io.github.maaasu.astralRecord.feature.playersetting.gui.PlayerSettingGui;
import io.github.maaasu.astralRecord.feature.playersetting.model.PlayerSettingChangeRequest;
import io.github.maaasu.astralRecord.feature.playersetting.model.PlayerSettingKey;
import io.github.maaasu.astralRecord.feature.playersetting.service.PlayerSettingService;
import io.github.maaasu.astralRecord.feature.user.model.UserPermission;
import io.github.maaasu.astralRecord.infrastructure.command.AstCommand;
import org.jetbrains.annotations.NotNull;

/**
 * プレイヤー設定コマンドです。
 */
public final class PlayerSettingCommand extends AstCommand {

    public PlayerSettingCommand() {
        super("setting", "Show or update player settings.", "/setting [gui|<key> <value>]", true);
    }

    @Override
    protected void executePlayerCommand(@NotNull AstPlayer player, @NotNull String[] args) {
        PlayerSettingService service = AstralRecord.getInstance().getPlayerSettingService();
        if (service == null) {
            sendError(player.getBukkit(), "PlayerSettingService is unavailable.");
            return;
        }

        if (args.length == 0) {
            showCurrentSettings(player, service);
            return;
        }

        if (args.length == 1 && args[0].equalsIgnoreCase("gui")) {
            PlayerSettingGui gui = AstralRecord.getInstance().getPlayerSettingGui();
            if (gui != null) {
                gui.open(player.getBukkit());
            }
            return;
        }

        if (args.length < 2) {
            sendUsage(player.getBukkit());
            return;
        }

        PlayerSettingKey key = PlayerSettingKey.fromInput(args[0]);
        if (key == null) {
            sendError(player.getBukkit(), io.github.maaasu.astralRecord.feature.player.PlayerMsgResource.format(
                PlayerSettingMsgId.P_5323.getId(),
                args[0]
            ));
            return;
        }
        if (key == PlayerSettingKey.ADVENTURE_RECORD_SUPER_MODE
            && player.getUser().getPermission() < UserPermission.ADMIN.getValue()) {
            sendError(player.getBukkit(), io.github.maaasu.astralRecord.feature.player.PlayerMsgResource.getMessage(
                io.github.maaasu.astralRecord.feature.player.PlayerMsgId.P_5061.getId()
            ));
            return;
        }

        Object parsedValue = key.parseInputValue(args[1]);
        if (parsedValue == null) {
            sendError(player.getBukkit(), io.github.maaasu.astralRecord.feature.player.PlayerMsgResource.format(
                PlayerSettingMsgId.P_5324.getId(),
                key.getCode(),
                args[1]
            ));
            return;
        }

        PlayerSettingService.UpdateResult result = service.updatePlayerSetting(
            new PlayerSettingChangeRequest(
                player.getUser().getUuid(),
                key,
                parsedValue,
                player.getUser().getUuid()
            )
        );
        if (result.conflict()) {
            sendError(player.getBukkit(), result.message());
            return;
        }

        sendSuccess(
            player.getBukkit(),
            io.github.maaasu.astralRecord.feature.player.PlayerMsgResource.format(
                PlayerSettingMsgId.P_5321.getId(),
                key.getDisplayNameJa(),
                key.formatValue(parsedValue)
            )
        );
    }

    private void showCurrentSettings(@NotNull AstPlayer player, @NotNull PlayerSettingService service) {
        for (PlayerSettingKey key : PlayerSettingKey.values()) {
            if (key == PlayerSettingKey.ADVENTURE_RECORD_SUPER_MODE) {
                continue;
            }
            Object value = service.getPlayerSetting(player.getUser().getUuid(), key);
            sendInfo(
                player.getBukkit(),
                io.github.maaasu.astralRecord.feature.player.PlayerMsgResource.format(
                    PlayerSettingMsgId.P_5322.getId(),
                    key.getDisplayNameJa(),
                    key.formatValue(value)
                )
            );
        }
    }
}
