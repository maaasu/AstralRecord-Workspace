package io.github.maaasu.astralRecord.feature.network;

import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.service.ChatMessageConversion;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

final class BackendProtocol {
    static final String CHANNEL = "astralrecord:network";
    private static final int MAX_PAYLOAD_SIZE = 32_767;
    private static final String PREPARE_CONNECT = "prepare_connect";
    private static final byte[] GAMEPLAY_SOUNDS_READY = emptyNotification("gameplay_sounds_ready");
    private static final byte[] ITEM_TOOLTIPS_READY = emptyNotification("item_tooltips_ready");
    private static final byte[] WEAPON_ATTACK_PROJECTILES_READY =
            emptyNotification("weapon_attack_projectiles_ready");
    private static final byte[] SKILL_EFFECTS_READY = emptyNotification("skill_effects_ready");
    private static final byte[] SELENEA_READY = emptyNotification("selenea_ready");
    private static final String ACTION_RING_HUD_READY = "action_ring_hud_ready";
    private static final int ACTION_RING_HUD_NOTIFICATION_SIZE = 2 + ACTION_RING_HUD_READY.length() + 2 + 64;
    private static final String BEDROCK_SKILL_MENU = "bedrock_skill_menu";
    private static final String BEDROCK_SKILL_MENU_RESULT = "bedrock_skill_menu_result";
    private static final int BEDROCK_SKILL_MENU_RESULT_SIZE = 2 + BEDROCK_SKILL_MENU_RESULT.length() + 2 * Long.BYTES + 1;
    private static final int BEDROCK_SKILL_MENU_MAX_BUTTONS = 8;

    /** 対応パックを登録済みの統合版接続について、Proxy から届く完全な通知か判定します。 */
    static boolean isGameplaySoundsReady(byte[] payload) {
        return Arrays.equals(GAMEPLAY_SOUNDS_READY, payload);
    }

    /** 対応パックのツールチップを使える統合版接続の完全な通知か判定します。 */
    static boolean isItemTooltipsReady(byte[] payload) {
        return Arrays.equals(ITEM_TOOLTIPS_READY, payload);
    }

    /** 通常攻撃の飛翔演出を使える統合版接続の完全な通知か判定します。 */
    static boolean isWeaponAttackProjectilesReady(byte[] payload) {
        return Arrays.equals(WEAPON_ATTACK_PROJECTILES_READY, payload);
    }

    /** スキル演出を使える統合版接続の完全な通知か判定します。 */
    static boolean isSkillEffectsReady(byte[] payload) {
        return Arrays.equals(SKILL_EFFECTS_READY, payload);
    }

    /**
     * セレネアの対応パックを読み込んだ統合版接続について、完全な通知か判定します。
     * @param payload Proxyから届いたデータ
     * @return 通知の正規形と一致する場合はtrue
     */
    static boolean isSeleneaReady(byte[] payload) {
        return Arrays.equals(SELENEA_READY, payload);
    }

    /** アクションリング HUD 通知の互換性識別子を返します。他種別や不正な通知は null を返します。 */
    static @Nullable String decodeActionRingHudRevision(byte[] payload) {
        if (payload.length != ACTION_RING_HUD_NOTIFICATION_SIZE) {
            return null;
        }
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload))) {
            if (!ACTION_RING_HUD_READY.equals(input.readUTF())) {
                return null;
            }
            String revision = input.readUTF();
            return input.available() == 0 && revision.matches("[0-9a-f]{64}") ? revision : null;
        } catch (IOException ignored) {
            return null;
        }
    }

    /**
     * 統合版スキルメニューの選択結果を復元します。
     *
     * @param payload 受信データ
     * @return 要求トークンと選択位置。他種別や不正なデータの場合は null
     */
    static @Nullable BedrockSkillMenuResult decodeBedrockSkillMenuResult(byte[] payload) {
        if (payload.length != BEDROCK_SKILL_MENU_RESULT_SIZE) {
            return null;
        }
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload))) {
            if (!BEDROCK_SKILL_MENU_RESULT.equals(input.readUTF())) {
                return null;
            }
            UUID token = new UUID(input.readLong(), input.readLong());
            int index = input.readByte();
            return index >= -1 && index < BEDROCK_SKILL_MENU_MAX_BUTTONS
                ? new BedrockSkillMenuResult(token, index) : null;
        } catch (IOException ignored) {
            return null;
        }
    }

    record BedrockSkillMenuResult(UUID token, int index) {
    }

    /**
     * 順序付きのボタン一覧を、統合版スキルメニューの表示要求として Proxy へ送信します。
     *
     * @param plugin 送信元プラグイン
     * @param player プラグインメッセージを送信するオンラインプレイヤー
     * @param token 要求を識別するトークン
     * @param labels 表示順のボタンラベル
     * @return 送信した場合は true、ボタン数またはデータサイズが範囲外の場合は false
     */
    static boolean sendBedrockSkillMenu(
        @NotNull Plugin plugin,
        @NotNull Player player,
        @NotNull UUID token,
        @NotNull List<String> labels
    ) {
        if (labels.isEmpty() || labels.size() > BEDROCK_SKILL_MENU_MAX_BUTTONS) {
            return false;
        }
        byte[] payload;
        try {
            payload = encode(output -> {
                output.writeUTF(BEDROCK_SKILL_MENU);
                output.writeLong(token.getMostSignificantBits());
                output.writeLong(token.getLeastSignificantBits());
                output.writeByte(labels.size());
                for (String label : labels) {
                    output.writeUTF(label);
                }
            });
        } catch (IOException ignored) {
            return false;
        }
        if (payload.length > MAX_PAYLOAD_SIZE) {
            return false;
        }
        player.sendPluginMessage(plugin, CHANNEL, payload);
        return true;
    }

    /** UTF 長ヘッダーを含む、引数のない通知の正規形を作成します。 */
    private static byte[] emptyNotification(String name) {
        try (var bytes = new ByteArrayOutputStream(); var output = new DataOutputStream(bytes)) {
            output.writeUTF(name);
            return bytes.toByteArray();
        } catch (IOException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    /**
     * Proxyのチャンネル接続通知を復元します。他種別はnullを返します。
     * @param payload Plugin message payload
     * @return プレイヤー名とチャンネル表示名。他種別の場合はnull
     * @throws IOException payloadが不正な場合
     */
    static ChannelConnection decodeChannelConnection(byte[] payload) throws IOException {
        if (payload.length > MAX_PAYLOAD_SIZE) throw new IOException("Plugin message is too large");
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload))) {
            if (!"channel_connection".equals(input.readUTF())) return null;
            String playerName = input.readUTF();
            String channelName = input.readUTF();
            if (playerName.isBlank() || channelName.isBlank() || input.available() > 0) {
                throw new IOException("Invalid channel_connection payload");
            }
            return new ChannelConnection(playerName, channelName);
        }
    }

    record ChannelConnection(String playerName, String channelName) {
    }

    private BackendProtocol() {
    }

    static void sendConnect(@NotNull Plugin plugin, @NotNull Player player, @NotNull String targetServer) {
        send(plugin, player, output -> {
            output.writeUTF("connect");
            output.writeUTF(targetServer);
        });
    }

    /**
     * Proxyから受信した保存付き接続準備要求を復元する。
     *
     * @param payload Plugin message payload
     * @return 接続先backend名
     * @throws IOException payloadが不正な場合
     */
    static @NotNull String decodePrepareConnect(byte[] payload) throws IOException {
        if (payload.length > MAX_PAYLOAD_SIZE) throw new IOException("Plugin message is too large");
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload))) {
            String type = input.readUTF();
            if (!PREPARE_CONNECT.equals(type)) {
                throw new IOException("Unknown proxy message type: " + type);
            }
            String targetServer = input.readUTF().trim();
            if (targetServer.isEmpty() || input.available() > 0) {
                throw new IOException("Invalid prepare_connect payload");
            }
            return targetServer;
        }
    }

    /** 選択アカウントのVIP種別とプレイヤーレベルを末尾に含めてProxyのTAB表示へ送信します。 */
    static void sendMetadata(
        @NotNull Plugin plugin,
        @NotNull AstPlayer player,
        @NotNull String channel,
        @NotNull String displayName,
        @NotNull String className,
        boolean afk,
        boolean classLevelMax,
        boolean accountNameOnly,
        @NotNull String vipTier
    ) {
        send(plugin, player.getBukkit(), output -> {
            output.writeUTF("metadata");
            output.writeUTF(player.getBukkit().getUniqueId().toString());
            output.writeUTF(player.getBukkit().getName());
            output.writeUTF(channel);
            output.writeUTF(displayName);
            output.writeInt(player.getClassLevel());
            output.writeUTF(className);
            output.writeBoolean(afk);
            output.writeInt(player.getEffectivePermissionLevel());
            output.writeBoolean(classLevelMax);
            output.writeBoolean(accountNameOnly);
            output.writeUTF(vipTier);
            output.writeInt(player.getAccount().getLevel());
        });
    }

    static void sendChat(
        @NotNull Plugin plugin,
        @NotNull AstPlayer player,
        @NotNull String channel,
        @NotNull String displayName,
        @NotNull String className,
        @NotNull ChatMessageConversion message
    ) {
        sendChat(
            plugin,
            player.getBukkit(),
            channel,
            displayName,
            player.getClassLevel(),
            className,
            message);
    }

    /**
     * プレイヤーデータのロード前でもProxyへグローバルチャットを送る。
     *
     * @param plugin 送信元プラグイン
     * @param player Proxy接続に利用するオンラインプレイヤー
     * @param channel 送信元チャンネル名
     * @param displayName 表示名
     * @param level クラスレベル。未ロードの場合は0
     * @param className クラス名。未ロードの場合は空文字列
     * @param message チャット本文
     */
    static void sendChat(
        @NotNull Plugin plugin,
        @NotNull Player player,
        @NotNull String channel,
        @NotNull String displayName,
        int level,
        @NotNull String className,
        @NotNull ChatMessageConversion message
    ) {
        send(plugin, player, output -> {
            output.writeUTF("chat");
            output.writeUTF(UUID.randomUUID().toString());
            output.writeUTF(player.getUniqueId().toString());
            output.writeUTF(player.getName());
            output.writeUTF(channel);
            output.writeUTF(displayName);
            output.writeInt(level);
            output.writeUTF(className);
            output.writeUTF(message.original());
            output.writeUTF(message.converted());
        });
    }

    /**
     * RPGサーバーの平均MSPTをProxyへ送る。
     *
     * @param plugin 送信元プラグイン
     * @param player Proxy接続に利用するオンラインプレイヤー
     * @param mspt RPGサーバーの平均MSPT
     */
    static void sendServerMetrics(@NotNull Plugin plugin, @NotNull Player player, double mspt) {
        send(plugin, player, output -> {
            output.writeUTF("server_metrics");
            output.writeDouble(mspt);
        });
    }

    /**
     * プライベートチャットの監視情報をProxyへ送る。
     *
     * @param plugin 送信元プラグイン
     * @param player Proxy接続に利用する発言者
     * @param type directまたはparty
     * @param senderName 発言者表示名
     * @param targetName DM受信者表示名。partyでは空文字
     * @param partyName party識別名。DMでは空文字
     * @param participantIds チャット当事者のUUID一覧
     * @param message 本文
     */
    static void sendPrivateChat(
        @NotNull Plugin plugin,
        @NotNull Player player,
        @NotNull String type,
        @NotNull String senderName,
        @NotNull String targetName,
        @NotNull String partyName,
        @NotNull Collection<UUID> participantIds,
        @NotNull ChatMessageConversion message
    ) {
        send(plugin, player, output -> {
            output.writeUTF("private_chat");
            output.writeUTF(player.getUniqueId().toString());
            output.writeUTF(type);
            output.writeUTF(senderName);
            output.writeUTF(targetName);
            output.writeUTF(partyName);
            output.writeUTF(message.original());
            output.writeUTF(message.converted());
            var ids = participantIds.stream()
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .toList();
            output.writeInt(ids.size());
            for (UUID participantId : ids) {
                output.writeUTF(participantId.toString());
            }
        });
    }

    /**
     * 別backendに接続中のプレイヤーへ配送するDMをProxyへ要求します。
     *
     * @param plugin 送信元プラグイン
     * @param sender 送信者
     * @param targetName 受信者のMCID
     * @param senderName 送信者表示名
     * @param senderLevel 送信者レベル
     * @param message 変換前後を保持する本文
     */
    static void sendDirectMessage(
        @NotNull Plugin plugin,
        @NotNull Player sender,
        @NotNull String targetName,
        @NotNull String senderName,
        int senderLevel,
        @NotNull ChatMessageConversion message
    ) {
        send(plugin, sender, output -> {
            output.writeUTF("direct_message");
            output.writeUTF(sender.getUniqueId().toString());
            output.writeUTF(targetName);
            output.writeUTF(senderName);
            output.writeInt(senderLevel);
            output.writeUTF(message.original());
            output.writeUTF(message.converted());
        });
    }

    private static void send(@NotNull Plugin plugin, @NotNull Player player, @NotNull Writer writer) {
        try {
            player.sendPluginMessage(plugin, CHANNEL, encode(writer));
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to encode proxy message", exception);
        }
    }

    /**
     * 指定されたフィールドをプラグインメッセージのバイト列に変換します。
     *
     * @param writer メッセージの書き込み処理
     * @return エンコードしたデータ
     * @throws IOException フィールドをデータに変換できない場合
     */
    private static byte[] encode(@NotNull Writer writer) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            writer.write(output);
        }
        return bytes.toByteArray();
    }

    @FunctionalInterface
    private interface Writer {
        void write(DataOutputStream output) throws IOException;
    }
}
