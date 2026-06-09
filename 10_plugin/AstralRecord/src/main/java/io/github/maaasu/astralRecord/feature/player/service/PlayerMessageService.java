package io.github.maaasu.astralRecord.feature.player.service;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgResource;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;

/**
 * プレイヤー向けメッセージ送信を一元管理するサービス。
 * システムメッセージは必ず共通タグを先頭に付与し、
 * 全体チャット・パーティーチャット・ダイレクトメッセージも
 * このサービス経由で送信する。
 */
public final class PlayerMessageService {

    /**
     * PlayerMessageService を初期化する。
     */
    public PlayerMessageService() {
    }

    /**
     * プラグインから保持している単一インスタンスを返す。
     *
     * @return メッセージ送信サービス
     * @throws IllegalStateException プラグイン初期化前でサービスが未生成の場合
     */
    public static @NotNull PlayerMessageService getInstance() {
        AstralRecord plugin = AstralRecord.getInstance();
        if (plugin == null || plugin.getPlayerMessageService() == null) {
            throw new IllegalStateException("PlayerMessageService is not initialized.");
        }
        return plugin.getPlayerMessageService();
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
     * 全体チャットをオンラインプレイヤー全員へ配信する。
     *
     * @param senderName 発言者名
     * @param message チャット本文
     */
    public void broadcastGlobalChat(@NotNull String senderName, @NotNull String message) {
        Component component = PlayerMsgResource.formatComponent(PlayerMsgId.P_5941.getId(), senderName, message);
        for (Player recipient : Bukkit.getOnlinePlayers()) {
            if (recipient.isOnline()) {
                recipient.sendMessage(component);
            }
        }
    }

    /**
     * パーティーチャットを対象プレイヤーへ配信する。
     *
     * @param recipients 受信者一覧
     * @param senderName 発言者名
     * @param message チャット本文
     */
    public void broadcastPartyChat(@NotNull Collection<Player> recipients, @NotNull String senderName, @NotNull String message) {
        Component component = PlayerMsgResource.formatComponent(PlayerMsgId.P_5942.getId(), senderName, message);
        for (Player recipient : recipients) {
            if (recipient.isOnline()) {
                recipient.sendMessage(component);
            }
        }
    }

    /**
     * ダイレクトメッセージを送受信者へ配信する。
     *
     * @param sender 送信者
     * @param target 受信者
     * @param message メッセージ本文
     */
    public void sendDirectMessage(@NotNull Player sender, @NotNull Player target, @NotNull String message) {
        Component sent = PlayerMsgResource.formatComponent(
            PlayerMsgId.P_5943.getId(),
            sender.getName(),
            target.getName(),
            message
        );
        Component received = PlayerMsgResource.formatComponent(
            PlayerMsgId.P_5944.getId(),
            sender.getName(),
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
        return PlayerMsgResource.getComponent(PlayerMsgId.P_5940.getId());
    }
}
