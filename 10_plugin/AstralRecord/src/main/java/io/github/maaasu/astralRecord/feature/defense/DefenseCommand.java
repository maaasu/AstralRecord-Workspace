package io.github.maaasu.astralRecord.feature.defense;

import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.infrastructure.command.AstCommand;
import java.util.Locale;

/** プレイヤー本人の防衛戦受付・退出・状態を提供します。開催開始は管理Webが所有します。 */
public final class DefenseCommand extends AstCommand {
    private final DefenseService service;

    /** @param service 接続先チャンネルの防衛戦 */
    public DefenseCommand(DefenseService service) {
        super("defense", "防衛戦に参加します。", "/defense <join|leave|status|help>", true);
        this.service = service;
    }

    /** @param player 実行者 @param args 操作名。プレイヤー指定は受け付けません */
    @Override protected void executePlayerCommand(AstPlayer player, String[] args) {
        if (args.length > 1) { help(player); return; }
        String action = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
        switch (action) {
            case "join" -> service.join(player);
            case "leave" -> service.leave(player);
            case "status" -> service.showStatus(player);
            default -> help(player);
        }
    }

    private void help(AstPlayer player) { PlayerMessageService.getInstance().send(player, PlayerMsgId.P_7911); }
}
