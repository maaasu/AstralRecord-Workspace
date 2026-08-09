package io.github.maaasu.astralRecord.feature.dungeon.command;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.dungeon.service.DungeonService;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.infrastructure.command.AstCommand;
import org.jetbrains.annotations.NotNull;

import java.util.Locale;

/** プレイヤー向けダンジョン開始・離脱コマンドです。 */
public final class DungeonCommand extends AstCommand {
    public DungeonCommand() {
        super(
                "dungeon",
                "自動生成ダンジョンを開始・離脱します。",
                "/dungeon <start <dungeonId>|leave>",
                true
        );
    }

    @Override
    protected void executePlayerCommand(@NotNull AstPlayer astPlayer, @NotNull String[] args) {
        DungeonService service = AstralRecord.getInstance() == null
                ? null
                : AstralRecord.getInstance().getDungeonService();
        if (service == null) {
            PlayerMessageService.getInstance().send(astPlayer, PlayerMsgId.P_7000);
            return;
        }
        if (args.length == 0) {
            sendUsage(astPlayer.getBukkit());
            return;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "start" -> start(astPlayer, service, args);
            case "leave" -> leave(astPlayer, service);
            default -> sendUsage(astPlayer.getBukkit());
        }
    }

    private void start(
            @NotNull AstPlayer astPlayer,
            @NotNull DungeonService service,
            @NotNull String[] args
    ) {
        if (args.length != 2) {
            sendUsage(astPlayer.getBukkit());
            return;
        }
        if (!requireGameplayMode(astPlayer)) {
            return;
        }
        DungeonService.StartRequestResult result = service.requestStart(astPlayer.getBukkit(), args[1]);
        PlayerMessageService messages = PlayerMessageService.getInstance();
        switch (result.status()) {
            case ACCEPTED -> {
                // 準備開始メッセージは参加者全員へ DungeonService から送信済みです。
            }
            case UNAVAILABLE -> messages.send(astPlayer, PlayerMsgId.P_7000);
            case NOT_FOUND -> messages.send(astPlayer, PlayerMsgId.P_7002, args[1]);
            case NOT_PARTY_LEADER -> messages.send(astPlayer, PlayerMsgId.P_7003);
            case PARTY_SIZE -> messages.send(
                    astPlayer,
                    PlayerMsgId.P_7004,
                    result.min(),
                    result.max(),
                    result.current()
            );
            case PARTICIPANT_BUSY -> messages.send(astPlayer, PlayerMsgId.P_7005);
            case NOT_GAMEPLAY -> messages.send(astPlayer, PlayerMsgId.P_5065);
            case NOT_AT_ENTRY -> messages.send(astPlayer, PlayerMsgId.P_7018);
            case HUB_UNAVAILABLE -> messages.send(astPlayer, PlayerMsgId.P_7019);
        }
    }

    private void leave(@NotNull AstPlayer astPlayer, @NotNull DungeonService service) {
        DungeonService.LeaveResult result = service.leave(astPlayer.getBukkit());
        if (result == DungeonService.LeaveResult.NO_SESSION) {
            PlayerMessageService.getInstance().send(astPlayer, PlayerMsgId.P_7014);
        }
    }
}
