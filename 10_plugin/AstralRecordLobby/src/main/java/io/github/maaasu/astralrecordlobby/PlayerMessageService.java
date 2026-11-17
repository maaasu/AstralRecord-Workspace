package io.github.maaasu.astralrecordlobby;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.entity.Player;

import java.text.MessageFormat;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.ResourceBundle;

/** ロビーのネットワーク通知をリソースと共通システム接頭辞で表示します。 */
final class PlayerMessageService {
    private static final DateTimeFormatter LOGIN_CODE_EXPIRY =
        DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT).withZone(ZoneId.of("Asia/Tokyo"));
    private final ResourceBundle messages = ResourceBundle.getBundle(
        "network-player", Locale.ROOT, PlayerMessageService.class.getClassLoader());
    private final LegacyComponentSerializer serializer = LegacyComponentSerializer.legacyAmpersand();

    /**
     * オンラインの受信プレイヤーへ、メッセージIDに対応するシステム通知を表示します。
     * @param player 受信プレイヤー
     * @param messageId 本文のメッセージID
     * @param arguments 本文に埋め込む表示値
     */
    void send(Player player, PlayerMsgId messageId, Object... arguments) {
        send(player, format(messageId, arguments));
    }

    /** コードとURLに別々の操作を付け、受信者だけに送ります。 */
    void sendWebLoginCode(Player player, LobbyApiClient.WebLoginCode challenge) {
        String url = challenge.loginUrl().toASCIIString();
        Component body = format(PlayerMsgId.WEB_LOGIN_CODE, "<code>",
            LOGIN_CODE_EXPIRY.format(challenge.expiresAt()), "<url>")
            .replaceText(config -> config.matchLiteral("<code>").once().replacement((match, builder) ->
                builder.content(challenge.code()).clickEvent(ClickEvent.copyToClipboard(challenge.code()))))
            .replaceText(config -> config.matchLiteral("<url>").once().replacement((match, builder) ->
                builder.content(url).clickEvent(ClickEvent.openUrl(url))));
        send(player, body);
    }

    private Component format(PlayerMsgId messageId, Object... arguments) {
        return serializer.deserialize(MessageFormat.format(messages.getString(messageId.name()), arguments));
    }

    private void send(Player player, Component body) {
        if (!player.isOnline()) return;
        Component prefix = serializer.deserialize(messages.getString(PlayerMsgId.P_5940.name()));
        player.sendMessage(prefix.append(Component.space()).append(body));
    }
}
