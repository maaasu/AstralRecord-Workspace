package io.github.maaasu.astralrecordlobby;

import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.UUID;

final class BackendProtocol {
    static final String CHANNEL = "astralrecord:network";
    static final String OPEN_MENU = "open_menu";

    private BackendProtocol() {
    }

    static boolean isOpenMenu(byte[] payload) {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload))) {
            return OPEN_MENU.equals(input.readUTF());
        } catch (IOException ignored) {
            return false;
        }
    }

    /**
     * Lobbyで確認済みの権限を添えてProxyへ接続要求を送る。
     *
     * @param plugin 送信元プラグイン
     * @param player 接続するプレイヤー
     * @param targetServer 接続先backend名
     * @param permission API admissionで取得したユーザー権限
     */
    static void sendConnect(Plugin plugin, Player player, String targetServer, int permission) {
        send(plugin, player, output -> {
            output.writeUTF("connect");
            output.writeUTF(targetServer);
            output.writeInt(permission);
        });
    }

    static void sendChat(Plugin plugin, Player player, String channel, String message) {
        send(plugin, player, output -> {
            output.writeUTF("chat");
            output.writeUTF(UUID.randomUUID().toString());
            output.writeUTF(player.getUniqueId().toString());
            output.writeUTF(player.getName());
            output.writeUTF(channel);
            output.writeUTF(player.getName());
            output.writeInt(1);
            output.writeUTF("");
            output.writeUTF(message);
        });
    }

    /**
     * Lobbyサーバーの平均MSPTをProxyへ送る。
     *
     * @param plugin 送信元プラグイン
     * @param player Proxy接続に利用するオンラインプレイヤー
     * @param mspt Lobbyサーバーの平均MSPT
     */
    static void sendServerMetrics(Plugin plugin, Player player, double mspt) {
        send(plugin, player, output -> {
            output.writeUTF("server_metrics");
            output.writeDouble(mspt);
        });
    }

    private static void send(Plugin plugin, Player player, Writer writer) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                writer.write(output);
            }
            player.sendPluginMessage(plugin, CHANNEL, bytes.toByteArray());
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to encode proxy message", exception);
        }
    }

    @FunctionalInterface
    private interface Writer {
        void write(DataOutputStream output) throws IOException;
    }
}
