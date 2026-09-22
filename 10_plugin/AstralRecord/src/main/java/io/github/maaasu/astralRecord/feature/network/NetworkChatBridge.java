package io.github.maaasu.astralRecord.feature.network;

import io.github.maaasu.astralRecord.feature.player.service.ChatMessageConversion;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.UUID;

/** Proxy経由の全体チャット送信口です。 */
@FunctionalInterface
public interface NetworkChatBridge {
    /**
     * @return Proxyへ送信した場合true。falseの場合は呼び出し側がローカル配信へフォールバックします。
     */
    boolean publish(@NotNull Player sender, @NotNull ChatMessageConversion message);

    /**
     * Proxyの最高権限ユーザーへDM監視メッセージを送ります。
     *
     * @param sender 送信元プレイヤー
     * @param senderName 送信者表示名
     * @param targetName 受信者表示名
     * @param participantIds チャット当事者のUUID一覧。監視対象から除外する
     * @param message 変換前後を保持する本文
     */
    default void publishDirectMessage(
        @NotNull Player sender,
        @NotNull String senderName,
        @NotNull String targetName,
        @NotNull Collection<UUID> participantIds,
        @NotNull ChatMessageConversion message
    ) {
    }

    /**
     * 別backendに接続中のプレイヤーへDMを配送します。
     *
     * @param sender 送信元プレイヤー
     * @param targetName 受信者のMCID
     * @param message 変換前後を保持する本文
     * @return Proxyへの配送要求を送信した場合true
     */
    default boolean publishRemoteDirectMessage(
        @NotNull Player sender,
        @NotNull String targetName,
        @NotNull ChatMessageConversion message
    ) {
        return false;
    }

    /**
     * Proxyの最高権限ユーザーへパーティーチャット監視メッセージを送ります。
     *
     * @param sender 送信元プレイヤー
     * @param senderName 発言者表示名
     * @param partyName パーティー識別名
     * @param participantIds チャット当事者のUUID一覧。監視対象から除外する
     * @param message 変換前後を保持する本文
     */
    default void publishPartyMessage(
        @NotNull Player sender,
        @NotNull String senderName,
        @NotNull String partyName,
        @NotNull Collection<UUID> participantIds,
        @NotNull ChatMessageConversion message
    ) {
    }
}
