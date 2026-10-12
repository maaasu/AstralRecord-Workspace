package io.github.maaasu.astralRecord.feature.serverrecord.command;

import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.serverrecord.model.ServerRecordType;
import io.github.maaasu.astralRecord.feature.serverrecord.service.ServerRecordDisplayService;
import io.github.maaasu.astralRecord.feature.user.model.UserPermission;
import io.github.maaasu.astralRecord.infrastructure.command.AstTabCompleter;

import java.util.ArrayList;
import java.util.List;

/** 管理者には種別を、通常プレイヤーには閲覧操作を補完します。 */
public final class ServerRecordTabCompleter extends AstTabCompleter {
    private final ServerRecordDisplayService displays;

    /** @param displays 配置IDを補完する表示管理 */
    public ServerRecordTabCompleter(ServerRecordDisplayService displays) { super(true); this.displays = displays; }

    /** @param player 実行者 @param args 現在入力 @return 権限に応じた補完候補 */
    @Override
    protected List<String> getPlayerCompletions(AstPlayer player, String[] args) {
        boolean admin = player.hasPermissionLevel(UserPermission.ADMIN.getValue());
        if (args.length == 1) {
            var result = new ArrayList<>(List.of("back", "help"));
            if (admin) { result.addAll(ServerRecordType.ids()); result.addAll(List.of("place", "remove", "list", "refresh")); }
            return result;
        }
        if (admin && args.length == 2) {
            if (args[0].equalsIgnoreCase("place")) return ServerRecordType.ids();
            if (args[0].equalsIgnoreCase("remove")) return displays.getPlacements().stream().map(row -> row.anchor().id()).toList();
        }
        return List.of();
    }
}
