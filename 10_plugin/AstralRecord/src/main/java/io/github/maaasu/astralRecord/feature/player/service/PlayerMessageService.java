package io.github.maaasu.astralRecord.feature.player.service;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.account.service.AccountDisplayNameFormatter;
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
import java.util.regex.Pattern;

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
        sendComponent(player, decorateAccountPlayerArguments(
            PlayerMsgResource.formatComponent(msgId.getId(), args),
            args
        ));
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
        Component decorated = PlayerMsgResource.decorateInteractiveArguments(message);
        sendComponent(player, decorateOnlineAccountPlayers(decorated));
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
     * アカウント表示名をプレイヤー情報へのクリック導線として埋め込んだメッセージを生成します。
     * 表示文字列はアカウント名とスロット番号、クリック先の識別子は MCID です。
     *
     * @param msgId メッセージ ID
     * @param astPlayer 表示対象プレイヤー
     * @return アカウント表示名を含むメッセージ
     */
    public @NotNull Component formatInteractiveAccountMessage(
        @NotNull PlayerMsgId msgId,
        @NotNull AstPlayer astPlayer
    ) {
        String displayName = AccountDisplayNameFormatter.toPlain(astPlayer.getAccount());
        return replaceAccountDisplay(
            PlayerMsgResource.formatPlainComponent(msgId.getId(), displayName),
            astPlayer
        );
    }

    /**
     * アカウント表示名を使った参加・退出メッセージを全員へ配信します。
     *
     * @param msgId メッセージ ID
     * @param astPlayer 表示対象プレイヤー
     */
    public void broadcastAccountMessage(@NotNull PlayerMsgId msgId, @NotNull AstPlayer astPlayer) {
        Component component = formatInteractiveAccountMessage(msgId, astPlayer);
        for (Player recipient : Bukkit.getOnlinePlayers()) {
            if (recipient.isOnline()) {
                recipient.sendMessage(component);
            }
        }
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
        Component message = PlayerMsgResource.formatPlainComponent(msgId.getId(), args)
            .clickEvent(ClickEvent.runCommand(command));
        sendComponent(
            player,
            decorateAccountPlayerArguments(message, args)
        );
    }

    /**
     * メッセージ引数がオンラインプレイヤーの MCID である場合、その表示部分をアカウント表示へ置換します。
     * クリック時の識別には引き続き MCID を使用します。
     *
     * @param message 置換対象のメッセージ
     * @param args メッセージ引数
     * @return アカウント表示へ置換したメッセージ
     */
    public @NotNull Component decorateAccountPlayerArguments(
        @NotNull Component message,
        Object... args
    ) {
        Component decorated = message;
        if (args == null) {
            return decorated;
        }
        for (Object arg : args) {
            if (!(arg instanceof String playerName)) {
                continue;
            }
            Player player = Bukkit.getPlayerExact(playerName);
            AstPlayer astPlayer = player == null ? null : AstPlayerCache.get(player);
            if (astPlayer == null) {
                continue;
            }
            decorated = replaceAccountDisplay(decorated, astPlayer, playerName);
        }
        return decorated;
    }

    /**
     * 整形済みメッセージ内のオンラインプレイヤー名を、ロード済みアカウントの表示へ置換します。
     * 既に {@code accountName#slotIndex} 形式になっている箇所は置換対象から除外します。
     *
     * @param message 置換対象のメッセージ
     * @return アカウント表示へ置換したメッセージ
     */
    private @NotNull Component decorateOnlineAccountPlayers(@NotNull Component message) {
        Component decorated = message;
        for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
            AstPlayer astPlayer = AstPlayerCache.get(onlinePlayer);
            if (astPlayer == null || astPlayer.getAccount() == null) {
                continue;
            }
            decorated = replaceAccountDisplay(decorated, astPlayer, onlinePlayer.getName());
        }
        return decorated;
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
        AstPlayer astPlayer = AstPlayerCache.get(sender);
        String displayName = astPlayer == null
            ? sender.getName()
            : AccountDisplayNameFormatter.toPlain(astPlayer.getAccount());
        Component component = PlayerMsgResource.formatPlainComponent(
            PlayerMsgId.P_5941.getId(),
            resolvePlayerLevel(sender),
            displayName,
            ""
        ).append(Component.text(normalizedMessage));
        if (astPlayer != null) {
            component = replaceAccountDisplay(component, astPlayer);
        }
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
        AstPlayer astPlayer = AstPlayerCache.get(sender);
        String displayName = astPlayer == null
            ? sender.getName()
            : AccountDisplayNameFormatter.toPlain(astPlayer.getAccount());
        Component component = PlayerMsgResource.formatPlainComponent(
            PlayerMsgId.P_5941.getId(),
            resolvePlayerLevel(sender),
            displayName,
            ""
        ).append(
            Component.text(itemName)
                .hoverEvent(itemTooltip.asHoverEvent())
                .clickEvent(ClickEvent.copyToClipboard(itemName))
        );
        if (astPlayer != null) {
            component = replaceAccountDisplay(component, astPlayer);
        }
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
        AstPlayer astPlayer = AstPlayerCache.get(sender);
        String displayName = astPlayer == null
            ? sender.getName()
            : AccountDisplayNameFormatter.toPlain(astPlayer.getAccount());
        Component component = PlayerMsgResource.formatPlainComponent(
            PlayerMsgId.P_5942.getId(),
            resolvePlayerLevel(sender),
            displayName,
            message
        );
        if (astPlayer != null) {
            component = replaceAccountDisplay(component, astPlayer);
        }
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
        AstPlayer senderAstPlayer = AstPlayerCache.get(sender);
        AstPlayer targetAstPlayer = AstPlayerCache.get(target);
        String senderDisplayName = senderAstPlayer == null
            ? sender.getName()
            : AccountDisplayNameFormatter.toPlain(senderAstPlayer.getAccount());
        String targetDisplayName = targetAstPlayer == null
            ? target.getName()
            : AccountDisplayNameFormatter.toPlain(targetAstPlayer.getAccount());
        Component sent = PlayerMsgResource.formatPlainComponent(
            PlayerMsgId.P_5943.getId(),
            resolvePlayerLevel(sender),
            senderDisplayName,
            resolvePlayerLevel(target),
            targetDisplayName,
            message
        );
        Component received = PlayerMsgResource.formatPlainComponent(
            PlayerMsgId.P_5944.getId(),
            resolvePlayerLevel(sender),
            senderDisplayName,
            resolvePlayerLevel(target),
            targetDisplayName,
            message
        );
        if (senderAstPlayer != null) {
            sent = replaceAccountDisplay(sent, senderAstPlayer);
            received = replaceAccountDisplay(received, senderAstPlayer);
        }
        if (targetAstPlayer != null) {
            sent = replaceAccountDisplay(sent, targetAstPlayer);
            received = replaceAccountDisplay(received, targetAstPlayer);
        }
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

    private @NotNull Component replaceAccountDisplay(
        @NotNull Component message,
        @NotNull AstPlayer astPlayer,
        @NotNull String matchText
    ) {
        Component display = AccountDisplayNameFormatter.toComponent(astPlayer.getAccount())
            .clickEvent(ClickEvent.runCommand("/player info " + astPlayer.getBukkit().getName()))
            .hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(
                Component.text("クリックでプレイヤー情報を開く")
            ));
        return message.replaceText(builder -> builder
            .match(Pattern.compile(Pattern.quote(matchText) + "(?!#\\d+)"))
            .replacement(display));
    }

    private @NotNull Component replaceAccountDisplay(
        @NotNull Component message,
        @NotNull AstPlayer astPlayer
    ) {
        return replaceAccountDisplay(
            message,
            astPlayer,
            AccountDisplayNameFormatter.toPlain(astPlayer.getAccount())
        );
    }

    private @NotNull String resolvePlayerLevel(@NotNull Player player) {
        AstPlayer astPlayer = AstPlayerCache.get(player);
        if (astPlayer == null) {
            return "---";
        }
        return Integer.toString(Math.max(1, astPlayer.getAccount().getLevel()));
    }
}
