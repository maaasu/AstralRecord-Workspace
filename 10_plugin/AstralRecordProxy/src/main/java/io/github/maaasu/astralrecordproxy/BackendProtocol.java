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
                    input.readInt(), input.readUTF(), input.readBoolean());
                case CHAT -> new Chat(
                    UUID.fromString(input.readUTF()), UUID.fromString(input.readUTF()), input.readUTF(),
                    input.readUTF(), input.readUTF(), input.readInt(), input.readUTF(), input.readUTF());
                case SERVER_METRICS -> new ServerMetrics(input.readDouble());
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

    sealed interface Incoming permits Connect, Metadata, Chat, ServerMetrics {
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
        boolean afk
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
        String message
    ) implements Incoming {
    }

    record ServerMetrics(double mspt) implements Incoming {
    }
}
