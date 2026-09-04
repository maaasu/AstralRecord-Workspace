package io.github.maaasu.astralRecord.feature.network;

import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.UUID;

final class BackendProtocol {
    static final String CHANNEL = "astralrecord:network";

    private BackendProtocol() {
    }

    static void sendConnect(@NotNull Plugin plugin, @NotNull Player player, @NotNull String targetServer) {
        send(plugin, player, output -> {
            output.writeUTF("connect");
            output.writeUTF(targetServer);
        });
    }

    static void sendMetadata(
        @NotNull Plugin plugin,
        @NotNull AstPlayer player,
        @NotNull String channel,
        @NotNull String displayName,
        @NotNull String className,
        boolean afk
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
        });
    }

    static void sendChat(
        @NotNull Plugin plugin,
        @NotNull AstPlayer player,
        @NotNull String channel,
        @NotNull String displayName,
        @NotNull String className,
        @NotNull String message
    ) {
        send(plugin, player.getBukkit(), output -> {
            output.writeUTF("chat");
            output.writeUTF(UUID.randomUUID().toString());
            output.writeUTF(player.getBukkit().getUniqueId().toString());
            output.writeUTF(player.getBukkit().getName());
            output.writeUTF(channel);
            output.writeUTF(displayName);
            output.writeInt(player.getClassLevel());
            output.writeUTF(className);
            output.writeUTF(message);
        });
    }

    private static void send(@NotNull Plugin plugin, @NotNull Player player, @NotNull Writer writer) {
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
