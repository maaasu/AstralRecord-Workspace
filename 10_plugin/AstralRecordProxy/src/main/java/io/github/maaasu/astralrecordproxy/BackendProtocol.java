package io.github.maaasu.astralrecordproxy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.UUID;

final class BackendProtocol {
    static final String CHANNEL = "astralrecord:network";
    static final String CONNECT = "connect";
    static final String METADATA = "metadata";
    static final String CHAT = "chat";
    static final String SERVER_METRICS = "server_metrics";
    static final String PRIVATE_CHAT = "private_chat";
    static final String OPEN_MENU = "open_menu";

    private BackendProtocol() {
    }

    static Incoming decode(byte[] payload) throws IOException {
        if (payload.length > 32_767) {
            throw new IOException("Plugin message is too large");
        }
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload))) {
            String type = input.readUTF();
            return switch (type) {
                case CONNECT -> new Connect(input.readUTF(), input.available() >= Integer.BYTES ? input.readInt() : 0);
                case METADATA -> new Metadata(
                    UUID.fromString(input.readUTF()), input.readUTF(), input.readUTF(), input.readUTF(),
                    input.readInt(), input.readUTF(), input.readBoolean(),
                    input.available() >= Integer.BYTES ? input.readInt() : 0);
                case CHAT -> {
                    UUID messageId = UUID.fromString(input.readUTF());
                    UUID playerId = UUID.fromString(input.readUTF());
                    String authorName = input.readUTF();
                    String channel = input.readUTF();
                    String displayName = input.readUTF();
                    int level = input.readInt();
                    String className = input.readUTF();
                    String original = input.readUTF();
                    String converted = input.available() > 0 ? input.readUTF() : original;
                    yield new Chat(messageId, playerId, authorName, channel, displayName, level, className, original, converted);
                }
                case SERVER_METRICS -> new ServerMetrics(input.readDouble());
                case PRIVATE_CHAT -> {
                    UUID playerId = UUID.fromString(input.readUTF());
                    String chatType = input.readUTF();
                    String senderName = input.readUTF();
                    String targetName = input.readUTF();
                    String partyName = input.readUTF();
                    String original = input.readUTF();
                    String converted = input.available() > 0 ? input.readUTF() : original;
                    yield new PrivateChat(playerId, chatType, senderName, targetName, partyName, original, converted);
                }
                default -> throw new IOException("Unknown plugin message type: " + type);
            };
        }
    }

    static byte[] openMenu() {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.writeUTF(OPEN_MENU);
            }
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    sealed interface Incoming permits Connect, Metadata, Chat, ServerMetrics, PrivateChat {
    }

    record Connect(String targetServer, int permission) implements Incoming {
    }

    record Metadata(
        UUID playerId,
        String mcid,
        String channel,
        String displayName,
        int level,
        String className,
        boolean afk,
        int permission
    ) implements Incoming {
    }

    record Chat(
        UUID messageId,
        UUID playerId,
        String authorName,
        String channel,
        String displayName,
        int level,
        String className,
        String original,
        String converted
    ) implements Incoming {
    }

    record ServerMetrics(double mspt) implements Incoming {
    }

    record PrivateChat(
        UUID playerId,
        String type,
        String senderName,
        String targetName,
        String partyName,
        String original,
        String converted
    ) implements Incoming {
    }
}
