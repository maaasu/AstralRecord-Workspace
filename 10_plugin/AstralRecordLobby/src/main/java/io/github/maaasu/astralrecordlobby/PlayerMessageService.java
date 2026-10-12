package io.github.maaasu.astralrecordlobby;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.entity.Player;

import java.text.MessageFormat;
import java.util.Locale;
import java.util.ResourceBundle;

/** ロビーのネットワーク通知をリソースと共通システム接頭辞で表示します。 */
final class PlayerMessageService {
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
        if (!player.isOnline()) return;
        Component prefix = serializer.deserialize(messages.getString(PlayerMsgId.P_5940.name()));
        Component body = serializer.deserialize(MessageFormat.format(messages.getString(messageId.name()), arguments));
        player.sendMessage(prefix.append(Component.space()).append(body));
    }
}
