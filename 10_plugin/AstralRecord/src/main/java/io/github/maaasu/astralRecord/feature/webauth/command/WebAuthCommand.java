package io.github.maaasu.astralRecord.feature.webauth.command;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.feature.webauth.model.WebLoginChallengeIssueResult;
import io.github.maaasu.astralRecord.feature.webauth.service.WebAuthService;
import io.github.maaasu.astralRecord.infrastructure.command.AstCommand;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;

/**
 * Web ログインコードを発行する `/web` コマンドです。
 */
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
        super("web", "Webログインコードを発行します。", "/web login", true);
        this.webAuthService = webAuthService;
    }

    @Override
    protected void executePlayerCommand(@NotNull AstPlayer player, @NotNull String[] args) {
        if (args.length != 1 || !"login".equalsIgnoreCase(args[0])) {
            sendUsage(player.getBukkit());
            return;
        }

        Player bukkitPlayer = player.getBukkit();
        UUID userUuid = player.getUser().getUuid();
        String mcid = player.getUser().getMcid();
        PlayerMessageService messageService = PlayerMessageService.getInstance();
        messageService.send(player, PlayerMsgId.P_6402);

        AstralRecord plugin = AstralRecord.getInstance();
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                WebLoginChallengeIssueResult result = webAuthService.issueLoginChallenge(userUuid, mcid);
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (bukkitPlayer.isOnline()) {
                        messageService.send(
                            bukkitPlayer,
                            PlayerMsgId.P_6400,
                            result.loginCode(),
                            EXPIRES_AT_FORMATTER.format(result.expiresAt()),
                            result.loginUrl()
                        );
                    }
                });
            } catch (RuntimeException ex) {
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (bukkitPlayer.isOnline()) {
                        messageService.send(bukkitPlayer, PlayerMsgId.P_6401);
                    }
                });
            }
        });
    }
}
