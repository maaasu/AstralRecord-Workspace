package io.github.maaasu.astralRecord.feature.resourcepack.service;

import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgResource;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.infrastructure.config.ConfigProperties;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Java Edition 向けリソースパック要求の送信と、クライアント側ステータス通知の処理を担当します。
 */
public class ResourcePackService {

    private static final int SHA1_LENGTH = 40;

    private final ConfigProperties configProperties;

    /**
     * リソースパックサービスを生成します。
     *
     * @param configProperties プラグイン設定
     */
    public ResourcePackService(ConfigProperties configProperties) {
        this.configProperties = configProperties;
    }

    /**
     * リソースパック要求を送信できる設定か判定します。
     *
     * @return 有効なら true
     */
    public boolean isEnabled() {
        return configProperties.isResourcePackEnabled() && !getPackUrl().isBlank();
    }

    /**
     * 指定プレイヤーへリソースパック要求を送信します。
     *
     * @param player 送信対象プレイヤー
     */
    public void applyTo(Player player) {
        if (!isEnabled()) {
            return;
        }

        if (shouldSkipBedrock(player)) {
            Logger.log(LogId.I_5551, player.getName());
            return;
        }

        UUID packId = getPackId();
        String prompt = blankToNull(configProperties.getResourcePackPrompt());
        String sha1 = blankToNull(configProperties.getResourcePackSha1());

        if (sha1 != null && !isValidSha1(sha1)) {
            Logger.log(LogId.W_5550, getPackUrl(), sha1);
            sha1 = null;
        }

        player.setResourcePack(
                packId,
                getPackUrl(),
                sha1,
                prompt == null ? null : Component.text(prompt),
                configProperties.isResourcePackForce()
        );

        Logger.log(
                LogId.I_5550,
                player.getName(),
                getPackUrl(),
                configProperties.isResourcePackForce()
        );
    }

    /**
     * 指定 ID がこのサービスで送信したリソースパック ID か判定します。
     *
     * @param packId 判定対象リソースパック ID
     * @return 管理対象なら true
     */
    public boolean isManagedPack(UUID packId) {
        return getPackId().equals(packId);
    }

    /**
     * リソースパックのクライアント側ステータスを処理します。
     *
     * @param player 通知元プレイヤー
     * @param status リソースパックステータス
     */
    public void handleStatus(Player player, PlayerResourcePackStatusEvent.Status status) {
        switch (status) {
            case ACCEPTED -> Logger.log(LogId.I_5552, player.getName());
            case DOWNLOADED -> Logger.log(LogId.I_5553, player.getName());
            case SUCCESSFULLY_LOADED -> Logger.log(LogId.I_5554, player.getName());
            case DECLINED -> handleDeclined(player);
            case FAILED_DOWNLOAD -> sendPlayerMessage(player, PlayerMsgId.P_5550);
            case INVALID_URL -> sendPlayerMessage(player, PlayerMsgId.P_5551);
            case FAILED_RELOAD -> sendPlayerMessage(player, PlayerMsgId.P_5552);
            case DISCARDED -> Logger.log(LogId.W_5551, player.getName());
            default -> Logger.log(LogId.D_5550, player.getName(), status);
        }
    }

    /**
     * リソースパック拒否時の通知とログを処理します。
     *
     * @param player 拒否したプレイヤー
     */
    private void handleDeclined(Player player) {
        sendPlayerMessage(player, PlayerMsgId.P_5553);
        Logger.log(LogId.W_5552, player.getName());
    }

    /**
     * 設定済みリソースパック URL を返します。
     *
     * @return 前後空白を除去した URL
     */
    private String getPackUrl() {
        return configProperties.getResourcePackUrl().trim();
    }

    /**
     * リソースパック URL から固定 UUID を生成します。
     *
     * @return リソースパック ID
     */
    private UUID getPackId() {
        return UUID.nameUUIDFromBytes(getPackUrl().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Bedrock プレイヤー向けに Java リソースパック要求をスキップすべきか判定します。
     *
     * @param player 判定対象プレイヤー
     * @return スキップ対象なら true
     */
    private boolean shouldSkipBedrock(Player player) {
        if (!configProperties.isResourcePackSkipBedrock()) {
            return false;
        }

        var p = AstPlayerCache.get(player);
        if (p == null) {
            return false;
        }
        return p.isBedrock();
    }

    /**
     * SHA-1 文字列として妥当か判定します。
     *
     * @param value 判定対象文字列
     * @return 40 文字の 16 進文字列なら true
     */
    private static boolean isValidSha1(String value) {
        if (value.length() != SHA1_LENGTH) {
            return false;
        }

        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean hex = (c >= '0' && c <= '9')
                    || (c >= 'a' && c <= 'f')
                    || (c >= 'A' && c <= 'F');
            if (!hex) {
                return false;
            }
        }
        return true;
    }

    /**
     * 空文字列を null に変換します。
     *
     * @param value 変換対象文字列
     * @return trim 後に空なら null、それ以外は trim 済み文字列
     */
    private static @Nullable String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * プレイヤー向けメッセージ定義を使って通知します。
     *
     * @param player 通知対象プレイヤー
     * @param msgId メッセージ ID
     */
    private static void sendPlayerMessage(Player player, PlayerMsgId msgId) {
        player.sendMessage(PlayerMsgResource.getMessage(msgId.getId()));
    }
}
