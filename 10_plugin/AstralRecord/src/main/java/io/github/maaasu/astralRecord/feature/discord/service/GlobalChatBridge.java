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
     * Minecraftから外部サービスへの送信を一時停止する状態を設定します。
     *
     * @param maintenanceMode メンテナンス中なら {@code true}
     */
    default void setMaintenanceMode(boolean maintenanceMode) {
        // 外部中継実装ごとに必要な場合だけ上書きします。
    }

    /**
     * 中継を停止する。
     */
    @Override
    void close();
}
