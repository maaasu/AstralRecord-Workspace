package io.github.maaasu.astralRecord.feature.player.command;

import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgResource;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.infrastructure.command.AstCommand;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * プレイヤーの UUID を表示する {@code /uuid} コマンドです。
 */
public final class UuidCommand extends AstCommand {

    /**
     * UUID 表示コマンドを初期化します。
     */
    public UuidCommand() {
        super("uuid", "プレイヤーのUUIDを表示します。", "/uuid [<playerName>]", false);
    }

    /**
     * UUID 表示コマンドを実行します。
     *
     * @param sender コマンド送信者
     * @param args コマンド引数
     */
    @Override
    protected void executeCommand(@NotNull CommandSender sender, @NotNull String[] args) {
        if (args.length > 1) {
            sendUsage(sender);
            return;
        }

        Player target = resolveTarget(sender, args);
        if (target == null) {
            return;
        }

        sendUuid(sender, target);
    }

    /**
     * コマンド引数から UUID 表示対象のオンラインプレイヤーを解決します。
     *
     * @param sender コマンド送信者
     * @param args コマンド引数。省略時はプレイヤー実行者自身を対象とする
     * @return 対象プレイヤー。解決できない場合は {@code null}
     */
    private @Nullable Player resolveTarget(@NotNull CommandSender sender, @NotNull String[] args) {
        if (args.length == 0) {
            if (sender instanceof Player player) {
                return player;
            }
            sendError(sender, PlayerMsgResource.getMessage(PlayerMsgId.P_5305.getId()));
            return null;
        }

        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            sendError(sender, PlayerMsgResource.format(PlayerMsgId.P_5814.getId(), args[0]));
        }
        return target;
    }

    /**
     * 対象プレイヤーの UUID を送信します。
     * プレイヤー実行時は UUID 部分だけをクリックでクリップボードへコピーできる Component を送信し、
     * コンソール実行時は通常の文字列として送信します。
     *
     * @param sender 出力先のコマンド送信者
     * @param target UUID を表示する対象プレイヤー
     */
    private void sendUuid(@NotNull CommandSender sender, @NotNull Player target) {
        String uuid = target.getUniqueId().toString();
        if (sender instanceof Player player) {
            PlayerMessageService.getInstance().sendComponent(player, createUuidMessage(target.getName(), uuid));
            return;
        }
        sendSuccess(sender, PlayerMsgResource.format(PlayerMsgId.P_7100.getId(), target.getName(), uuid));
    }

    /**
     * UUID 部分へクリップボードコピー操作を付与した表示 Component を生成します。
     *
     * @param playerName 対象プレイヤー名
     * @param uuid 対象プレイヤー UUID
     * @return UUID 部分がクリックでコピーできる Component
     */
    private @NotNull Component createUuidMessage(@NotNull String playerName, @NotNull String uuid) {
        Component copyableUuid = Component.text(uuid)
            .clickEvent(ClickEvent.copyToClipboard(uuid));
        return PlayerMsgResource.formatPlainComponent(PlayerMsgId.P_7100.getId(), playerName, uuid)
            .replaceText(builder -> builder
                .matchLiteral(uuid)
                .replacement(copyableUuid));
    }
}
