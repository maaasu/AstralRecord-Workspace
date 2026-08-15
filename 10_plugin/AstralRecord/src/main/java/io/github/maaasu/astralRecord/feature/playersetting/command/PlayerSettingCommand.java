package io.github.maaasu.astralRecord.feature.playersetting.command;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgResource;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.playersetting.PlayerSettingMsgId;
import io.github.maaasu.astralRecord.feature.playersetting.gui.PlayerSettingGui;
import io.github.maaasu.astralRecord.feature.playersetting.model.PlayerSettingChangeRequest;
import io.github.maaasu.astralRecord.feature.playersetting.model.PlayerSettingKey;
import io.github.maaasu.astralRecord.feature.playersetting.service.PlayerSettingService;
import io.github.maaasu.astralRecord.infrastructure.command.AstCommand;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import io.github.maaasu.astralRecord.infrastructure.util.AsyncTaskUtil;
import org.jetbrains.annotations.NotNull;

/**
 * プレイヤー設定コマンドです。
 */
public final class PlayerSettingCommand extends AstCommand {

    public PlayerSettingCommand() {
        super("setting", "プレイヤー設定を表示または更新します。", "/setting [gui|<key> <value>]", true);
    }

    @Override
    protected void executePlayerCommand(@NotNull AstPlayer player, @NotNull String[] args) {
        PlayerSettingService service = AstralRecord.getInstance().getPlayerSettingService();
        if (service == null) {
            sendError(player.getBukkit(), PlayerMsgResource.getMessage(PlayerSettingMsgId.P_5325.getId()));
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
            && !player.hasAdminPermission()) {
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

        var plugin = AstralRecord.getInstance();
        var request = new PlayerSettingChangeRequest(
            player.getUser().getUuid(),
            key,
            parsedValue,
            player.getUser().getUuid()
        );
        long sessionToken = service.captureSessionToken(request.userId());
        AsyncTaskUtil.supplyAsync(plugin, () -> service.updatePlayerSetting(request, sessionToken))
            .whenComplete((result, throwable) -> AsyncTaskUtil.runSync(plugin, () -> {
                if (!player.getBukkit().isOnline()) {
                    return;
                }
                if (throwable != null) {
                    Logger.log(LogId.E_5312, throwable, key.getCode());
                    sendError(player.getBukkit(), PlayerMsgResource.getMessage(PlayerSettingMsgId.P_5326.getId()));
                    return;
                }
                if (result.staleSession()) {
                    return;
                }
                if (key == PlayerSettingKey.ARMOR_DISPLAY) {
                    plugin.getItemStackPacketAdapter().refreshEquipmentView(player.getBukkit());
                }
                if (key == PlayerSettingKey.ACTION_RING_HOLD_SELECT) {
                    player.getBukkit().updateInventory();
                }
                if (result.conflict()) {
                    sendError(player.getBukkit(), result.message());
                    return;
                }
                sendSuccess(
                    player.getBukkit(),
                    PlayerMsgResource.format(
                        PlayerSettingMsgId.P_5321.getId(),
                        key.getDisplayNameJa(),
                        key.formatValue(parsedValue)
                    )
                );
            }));
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
