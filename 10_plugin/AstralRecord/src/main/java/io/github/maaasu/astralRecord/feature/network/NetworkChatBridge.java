package io.github.maaasu.astralRecord.feature.network;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/** Proxy経由の全体チャット送信口です。 */
@FunctionalInterface
public interface NetworkChatBridge {
    /**
     * @return Proxyへ送信した場合true。falseの場合は呼び出し側がローカル配信へフォールバックします。
     */
    boolean publish(@NotNull Player sender, @NotNull String message);
}
