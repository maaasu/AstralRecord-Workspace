package io.github.maaasu.astralRecord.feature.webauth.command;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.feature.webauth.model.WebLoginChallengeIssueResult;
import io.github.maaasu.astralRecord.feature.webauth.service.WebAuthService;
import io.github.maaasu.astralRecord.infrastructure.command.AstCommand;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;

/** Web ログインコードを発行する {@code /web} コマンドです。 */
public class WebAuthCommand extends AstCommand {
    private static final DateTimeFormatter EXPIRES_AT_FORMATTER = DateTimeFormatter
        .ofPattern("yyyy-MM-dd HH:mm:ss z", Locale.JAPAN)
        .withZone(ZoneId.of("Asia/Tokyo"));

    private final WebAuthService webAuthService;

    /**
     * WebAuthCommand を初期化します。
     *
     * @param webAuthService Web 認証サービス
     */
    public WebAuthCommand(@NotNull WebAuthService webAuthService) {
        super("web", "Webログインコードを発行します。", "/web login [MCID]（MCID指定はコンソール限定）");
        this.webAuthService = webAuthService;
    }

    /**
     * 実行者に応じて本人用またはコンソール指定用のコード発行を処理します。
     *
     * @param sender 実行者
     * @param args コマンド引数
     */
    @Override
    protected void executeCommand(@NotNull CommandSender sender, @NotNull String[] args) {
        if (sender instanceof Player) {
            executePlayerLogin(sender, args);
            return;
        }
        if (!(sender instanceof ConsoleCommandSender)) {
            PlayerMessageService.getInstance().send(sender, PlayerMsgId.P_6403);
            return;
        }
        executeConsoleLogin(sender, args);
    }

    /**
     * ゲーム内プレイヤー自身のログインコード発行を処理します。
     *
     * @param sender 実行したプレイヤー
     * @param args コマンド引数
     */
    private void executePlayerLogin(@NotNull CommandSender sender, @NotNull String[] args) {
        AstPlayer player = getAstPlayer(sender);
        if (player == null) {
            PlayerMessageService.getInstance().send(sender, PlayerMsgId.P_5060);
            return;
        }
        if (args.length != 1 || !"login".equalsIgnoreCase(args[0])) {
            if (args.length == 2 && "login".equalsIgnoreCase(args[0])) {
                PlayerMessageService.getInstance().send(sender, PlayerMsgId.P_6403);
            } else {
                sendUsage(sender);
            }
            return;
        }

        UUID userUuid = player.getUser().getUuid();
        String mcid = player.getUser().getMcid();
        PlayerMessageService messageService = PlayerMessageService.getInstance();
        messageService.send(player, PlayerMsgId.P_6402);

        AstralRecord plugin = AstralRecord.getInstance();
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                WebLoginChallengeIssueResult result = webAuthService.issueLoginChallenge(userUuid, mcid);
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (player.getBukkit().isOnline()) {
                        sendChallenge(messageService, player.getBukkit(), result);
                    }
                });
            } catch (RuntimeException ex) {
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (player.getBukkit().isOnline()) {
                        messageService.send(player, PlayerMsgId.P_6401);
                    }
                });
            }
        });
    }

    /**
     * サーバーコンソールから指定MCIDのログインコード発行を処理します。
     *
     * @param sender 実行したサーバーコンソール
     * @param args コマンド引数
     */
    private void executeConsoleLogin(@NotNull CommandSender sender, @NotNull String[] args) {
        if (args.length == 1 && "login".equalsIgnoreCase(args[0])) {
            PlayerMessageService.getInstance().send(sender, PlayerMsgId.P_5305);
            return;
        }
        if (args.length != 2 || !"login".equalsIgnoreCase(args[0]) || args[1].isBlank()) {
            sendUsage(sender);
            return;
        }

        String mcid = args[1].trim();
        PlayerMessageService messageService = PlayerMessageService.getInstance();
        messageService.send(sender, PlayerMsgId.P_6402);
        AstralRecord plugin = AstralRecord.getInstance();
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                WebLoginChallengeIssueResult result = webAuthService.issueLoginChallengeForMcid(mcid);
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (result == null) {
                        messageService.send(sender, PlayerMsgId.P_6404);
                        return;
                    }
                    sendChallenge(messageService, sender, result);
                });
            } catch (RuntimeException ex) {
                plugin.getServer().getScheduler().runTask(plugin, () -> messageService.send(sender, PlayerMsgId.P_6401));
            }
        });
    }

    /**
     * 発行済みチャレンジをプレイヤーまたはサーバーコンソールへ表示します。
     *
     * @param messageService プレイヤーメッセージサービス
     * @param recipient 表示先
     * @param result 発行済みチャレンジ
     */
    private static void sendChallenge(
        @NotNull PlayerMessageService messageService,
        @NotNull CommandSender recipient,
        @NotNull WebLoginChallengeIssueResult result
    ) {
        messageService.send(
            recipient,
            PlayerMsgId.P_6400,
            result.loginCode(),
            EXPIRES_AT_FORMATTER.format(result.expiresAt()),
            result.loginUrl()
        );
    }
}
