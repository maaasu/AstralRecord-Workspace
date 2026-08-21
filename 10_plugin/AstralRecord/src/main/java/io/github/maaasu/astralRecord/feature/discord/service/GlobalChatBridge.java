package io.github.maaasu.astralRecord.feature.discord.service;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * AstralRecordの全体チャットを外部サービスへ中継する契約。
 */
public interface GlobalChatBridge extends AutoCloseable {

    /**
     * Minecraftの全体チャットを外部サービスへ送信する。
     *
     * @param sender 発言者
     * @param message 本文
     */
    void publishMinecraftGlobalChat(@NotNull Player sender, @NotNull String message);

    /**
     * 中継を停止する。
     */
    @Override
    void close();
}
