package io.github.maaasu.astralRecord.feature.player.service;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.discord.service.GlobalChatBridge;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgResource;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;

/**
 * プレイヤー向けメッセージ送信を一元管理するサービス。
 * システムメッセージは必ず共通タグを先頭に付与し、
 * 全体チャット・パーティーチャット・ダイレクトメッセージも
 * このサービス経由で送信する。
 */
public final class PlayerMessageService {
    private static final PlayerMessageService FALLBACK_INSTANCE = new PlayerMessageService();
    private @Nullable GlobalChatBridge globalChatBridge;

    /**
     * PlayerMessageService を初期化する。
     */
    public PlayerMessageService() {
    }

    /**
     * プラグインから保持している単一インスタンスを返す。
     * プラグイン未初期化のテスト環境ではフォールバックインスタンスを返す。
     *
     * @return メッセージ送信サービス
     */
    public static @NotNull PlayerMessageService getInstance() {
        AstralRecord plugin = AstralRecord.getInstance();
        if (plugin != null && plugin.getPlayerMessageService() != null) {
            return plugin.getPlayerMessageService();
        }
        return FALLBACK_INSTANCE;
    }

    /**
     * AstPlayer へシステムメッセージを送信する。
     *
     * @param player 送信先プレイヤー
     * @param msgId メッセージID
     * @param args 置換引数
     */
    public void send(@NotNull AstPlayer player, @NotNull PlayerMsgId msgId, Object... args) {
        send(player.getBukkit(), msgId, args);
    }

    /**
     * プレイヤーへシステムメッセージを送信する。
     *
     * @param player 送信先プレイヤー
     * @param msgId メッセージID
     * @param args 置換引数
     */
    public void send(@NotNull Player player, @NotNull PlayerMsgId msgId, Object... args) {
        sendComponent(player, PlayerMsgResource.formatComponent(msgId.getId(), args));
    }

    /**
     * コマンド送信者へシステムメッセージを送信する。
     * プレイヤー送信時のみ共通タグを付与する。
     *
     * @param sender 送信先
     * @param msgId メッセージID
     * @param args 置換引数
     */
    public void send(@NotNull CommandSender sender, @NotNull PlayerMsgId msgId, Object... args) {
        if (sender instanceof Player player) {
            send(player, msgId, args);
            return;
        }
        sender.sendMessage(PlayerMsgResource.format(msgId.getId(), args));
    }

    /**
     * 既存の整形済み文字列をプレイヤーへ送信する。
     * 文字列は共通タグ付きシステムメッセージとして扱う。
     *
     * @param player 送信先プレイヤー
     * @param message 整形済みメッセージ
     */
    public void sendRaw(@NotNull Player player, @NotNull String message) {
        sendComponent(player, PlayerMsgResource.decorateInteractiveArguments(message));
    }

    /**
     * 既存の整形済み文字列をコマンド送信者へ送信する。
     * プレイヤー送信時のみ共通タグを付与する。
     *
     * @param sender 送信先
     * @param message 整形済みメッセージ
     */
    public void sendRaw(@NotNull CommandSender sender, @NotNull String message) {
        if (sender instanceof Player player) {
            sendRaw(player, message);
            return;
        }
        sender.sendMessage(message);
    }

    /**
     * Adventure Component をプレイヤーへシステムメッセージとして送信する。
     *
     * @param player 送信先プレイヤー
     * @param message 本文Component
     */
    public void sendComponent(@NotNull Player player, @NotNull Component message) {
        if (!player.isOnline()) {
            return;
        }
        player.sendMessage(systemPrefix().append(message));
    }

    /**
     * プレイヤー名をプレイヤー情報 GUI のクリック導線として含むメッセージを生成する。
     * Bukkit の参加・退出イベントなど、呼び出し側が配信を担当するメッセージに利用する。
     *
     * @param msgId メッセージ ID
     * @param playerName 対象プレイヤー名
     * @return プレイヤー情報へのクリック導線を含むメッセージ
     */
    public @NotNull Component formatInteractivePlayerMessage(
        @NotNull PlayerMsgId msgId,
        @NotNull String playerName
    ) {
        return PlayerMsgResource.formatPlayerComponent(msgId.getId(), playerName);
    }

    /**
     * 全体チャットの外部中継先を設定する。
     *
     * @param globalChatBridge 外部中継先。{@code null} で解除
     */
    public void setGlobalChatBridge(@Nullable GlobalChatBridge globalChatBridge) {
        this.globalChatBridge = globalChatBridge;
    }

    /**
     * クリック時に指定コマンドを実行するシステムメッセージを送信する。
     *
     * @param player 送信先プレイヤー
     * @param msgId メッセージ ID
     * @param command クリック時に実行するスラッシュ付きコマンド
     * @param args 置換引数
     */
    public void sendClickable(
        @NotNull Player player,
        @NotNull PlayerMsgId msgId,
        @NotNull String command,
        Object... args
    ) {
        sendComponent(
            player,
            PlayerMsgResource.formatPlainComponent(msgId.getId(), args)
                .clickEvent(ClickEvent.runCommand(command))
        );
    }

    /**
     * 全体チャットをオンラインプレイヤー全員へ配信する。
     * 発言者のプレイヤーレベルを表示する。
     *
     * @param sender 発言者
     * @param message チャット本文
     */
    public void broadcastGlobalChat(@NotNull Player sender, @NotNull String message) {
        String normalizedMessage = ChatMessageSanitizer.normalize(message);
        Component component = PlayerMsgResource.formatComponent(
            PlayerMsgId.P_5941.getId(),
            resolvePlayerLevel(sender),
            sender.getName(),
            ""
        ).append(Component.text(normalizedMessage));
        for (Player recipient : Bukkit.getOnlinePlayers()) {
            if (recipient.isOnline()) {
                recipient.sendMessage(component);
            }
        }
        if (!normalizedMessage.isBlank() && globalChatBridge != null) {
            globalChatBridge.publishMinecraftGlobalChat(sender, normalizedMessage);
        }
    }

    /**
     * Discordの全体チャットをオンラインプレイヤーへAstralRecord形式で配信する。
     *
     * @param authorName Discord表示名
     * @param message メッセージ本文
     */
    public void broadcastDiscordGlobalChat(@NotNull String authorName, @NotNull String message) {
        String normalizedAuthorName = ChatMessageSanitizer.normalize(authorName, 64);
        String normalizedMessage = ChatMessageSanitizer.normalize(message);
        if (normalizedAuthorName.isBlank() || normalizedMessage.isBlank()) {
            return;
        }
        Component component = PlayerMsgResource.getComponent(PlayerMsgId.P_6990.getId())
            .append(Component.space())
            .append(Component.text(normalizedAuthorName))
            .append(Component.text(": "))
            .append(Component.text(normalizedMessage));
        for (Player recipient : Bukkit.getOnlinePlayers()) {
            if (recipient.isOnline()) {
                recipient.sendMessage(component);
            }
        }
    }

    /**
     * アイテム名をホバー・コピー操作付きで全体チャットへ配信する。
     * 発言者のプレイヤーレベルを表示する。
     *
     * @param sender 送信者
     * @param itemName チャットへ表示し、クリック時にコピーする装飾なしのアイテム名
     * @param itemTooltip ホバー表示に使うアイテムのスナップショット
     */
    public void broadcastGlobalItemChat(
        @NotNull Player sender,
        @NotNull String itemName,
        @NotNull ItemStack itemTooltip
    ) {
        Component component = PlayerMsgResource.formatComponent(
            PlayerMsgId.P_5941.getId(),
            resolvePlayerLevel(sender),
            sender.getName(),
            ""
        ).append(
            Component.text(itemName)
                .hoverEvent(itemTooltip.asHoverEvent())
                .clickEvent(ClickEvent.copyToClipboard(itemName))
        );
        for (Player recipient : Bukkit.getOnlinePlayers()) {
            if (recipient.isOnline()) {
                recipient.sendMessage(component);
            }
        }
    }

    /**
     * パーティーチャットを対象プレイヤーへ配信する。
     * 発言者のプレイヤーレベルを表示する。
     *
     * @param recipients 受信者一覧
     * @param sender 発言者
     * @param message チャット本文
     */
    public void broadcastPartyChat(@NotNull Collection<Player> recipients, @NotNull Player sender, @NotNull String message) {
        Component component = PlayerMsgResource.formatComponent(
            PlayerMsgId.P_5942.getId(),
            resolvePlayerLevel(sender),
            sender.getName(),
            message
        );
        for (Player recipient : recipients) {
            if (recipient.isOnline()) {
                recipient.sendMessage(component);
            }
        }
    }

    /**
     * ダイレクトメッセージを送受信者へ配信する。
     * 送受信者それぞれのプレイヤーレベルを表示する。
     *
     * @param sender 送信者
     * @param target 受信者
     * @param message メッセージ本文
     */
    public void sendDirectMessage(@NotNull Player sender, @NotNull Player target, @NotNull String message) {
        Component sent = PlayerMsgResource.formatComponent(
            PlayerMsgId.P_5943.getId(),
            resolvePlayerLevel(sender),
            sender.getName(),
            resolvePlayerLevel(target),
            target.getName(),
            message
        );
        Component received = PlayerMsgResource.formatComponent(
            PlayerMsgId.P_5944.getId(),
            resolvePlayerLevel(sender),
            sender.getName(),
            resolvePlayerLevel(target),
            target.getName(),
            message
        );
        if (sender.isOnline()) {
            sender.sendMessage(sent);
        }
        if (target.isOnline()) {
            target.sendMessage(received);
        }
    }

    private @NotNull Component systemPrefix() {
        return PlayerMsgResource.getComponent(PlayerMsgId.P_5940.getId()).append(Component.space());
    }

    private @NotNull String resolvePlayerLevel(@NotNull Player player) {
        AstPlayer astPlayer = AstPlayerCache.get(player);
        if (astPlayer == null) {
            return "---";
        }
        return Integer.toString(Math.max(1, astPlayer.getAccount().getLevel()));
    }
}
