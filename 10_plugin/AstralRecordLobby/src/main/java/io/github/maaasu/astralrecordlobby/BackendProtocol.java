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

    static void sendConnect(Plugin plugin, Player player, String targetServer) {
        send(plugin, player, output -> {
            output.writeUTF("connect");
            output.writeUTF(targetServer);
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
