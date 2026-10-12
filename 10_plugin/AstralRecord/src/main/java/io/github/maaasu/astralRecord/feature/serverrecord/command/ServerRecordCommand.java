package io.github.maaasu.astralRecord.feature.serverrecord.command;

import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgResource;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.serverrecord.model.ServerRecordType;
import io.github.maaasu.astralRecord.feature.serverrecord.service.ServerRecordDisplayService;
import io.github.maaasu.astralRecord.feature.serverrecord.service.ServerRecordWorldService;
import io.github.maaasu.astralRecord.feature.user.model.UserPermission;
import io.github.maaasu.astralRecord.infrastructure.command.AstCommand;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;

import java.util.Locale;
import java.util.regex.Pattern;

/** /serverrecord の一般入場と管理者専用表示配置を提供します。 */
public final class ServerRecordCommand extends AstCommand {
    private static final Pattern ID = Pattern.compile("[a-zA-Z0-9_-]{1,64}");
    private final ServerRecordDisplayService displays;
    private final ServerRecordWorldService visits;

    /** @param displays 表示管理 @param visits ワールド入退場 */
    public ServerRecordCommand(ServerRecordDisplayService displays, ServerRecordWorldService visits) {
        super("serverrecord", "サーバーレコードを閲覧・配置します。", "/serverrecord [back|<ranking>|place <ranking> [id]|remove <id>|list|refresh|help]", true);
        this.displays = displays;
        this.visits = visits;
    }

    /** @param player 実行者 @param args 位置と種別を指定する引数 */
    @Override
    protected void executePlayerCommand(AstPlayer player, String[] args) {
        if (args.length == 0) { visits.enter(player.getBukkit()); return; }
        String action = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 1 && action.equals("back")) { visits.back(player.getBukkit()); return; }
        if (args.length == 1 && action.equals("help")) {
            sendInfo(player.getBukkit(), PlayerMsgResource.getMessage(PlayerMsgId.P_7800.getId()));
            return;
        }
        if (!player.hasPermissionLevel(UserPermission.ADMIN.getValue())) {
            sendError(player.getBukkit(), PlayerMsgResource.getMessage(PlayerMsgId.P_5061.getId()));
            return;
        }
        ServerRecordType shorthand = ServerRecordType.parse(action);
        if (shorthand != null && args.length == 1) { place(player, shorthand, null); return; }
        switch (action) {
            case "place" -> {
                if (args.length < 2 || args.length > 3) { sendUsage(player.getBukkit()); return; }
                var type = ServerRecordType.parse(args[1]);
                if (type == null) { sendUsage(player.getBukkit()); return; }
                String id = args.length == 3 ? args[2] : null;
                if (id != null && !ID.matcher(id).matches()) {
                    sendError(player.getBukkit(), PlayerMsgResource.getMessage(PlayerMsgId.P_7814.getId()));
                    return;
                }
                place(player, type, id);
            }
            case "remove" -> {
                if (args.length != 2) { sendUsage(player.getBukkit()); return; }
                try {
                    if (displays.remove(args[1])) sendSuccess(player.getBukkit(), PlayerMsgResource.format(PlayerMsgId.P_7802.getId(), args[1]));
                    else sendError(player.getBukkit(), PlayerMsgResource.format(PlayerMsgId.P_7803.getId(), args[1]));
                } catch (RuntimeException failure) { persistenceFailure(player, failure); }
            }
            case "list" -> {
                if (args.length != 1) { sendUsage(player.getBukkit()); return; }
                String rows = displays.getPlacements().stream().map(placement -> placement.anchor().id() + " (" + placement.type().id() + ")")
                        .reduce((left, right) -> left + ", " + right).orElse("");
                sendInfo(player.getBukkit(), PlayerMsgResource.format(PlayerMsgId.P_7804.getId(), displays.getPlacements().size(), rows));
            }
            case "refresh" -> {
                if (args.length != 1) { sendUsage(player.getBukkit()); return; }
                displays.refresh();
                sendInfo(player.getBukkit(), PlayerMsgResource.getMessage(PlayerMsgId.P_7806.getId()));
            }
            default -> sendUsage(player.getBukkit());
        }
    }

    /** @param player 実行者 @param type 種別 @param id 配置ID。nullなら自動採番 */
    private void place(AstPlayer player, ServerRecordType type, String id) {
        try {
            var placement = displays.place(type, id, player.getBukkit().getLocation());
            sendSuccess(player.getBukkit(), PlayerMsgResource.format(PlayerMsgId.P_7801.getId(), type.displayName(), placement.anchor().id()));
        } catch (RuntimeException failure) { persistenceFailure(player, failure); }
    }

    /** @param player 実行者 @param failure 保存例外 */
    private void persistenceFailure(AstPlayer player, RuntimeException failure) {
        Logger.log(LogId.E_7801, failure, "server_records.yml");
        sendError(player.getBukkit(), PlayerMsgResource.getMessage(PlayerMsgId.P_7805.getId()));
    }
}
