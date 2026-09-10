package io.github.maaasu.astralrecordproxy;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class BackendProtocolTest {
    @Test
    void connectCarriesLobbyPermission() throws Exception {
        byte[] payload;
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
             DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeUTF("connect");
            output.writeUTF("ch1");
            output.writeInt(5);
            payload = bytes.toByteArray();
        }

        BackendProtocol.Connect connect = (BackendProtocol.Connect) BackendProtocol.decode(payload);

        assertEquals("ch1", connect.targetServer());
        assertEquals(5, connect.permission());
    }

    @Test
    void legacyConnectDefaultsToPlayerPermission() throws Exception {
        byte[] payload;
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
             DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeUTF("connect");
            output.writeUTF("lobby");
            payload = bytes.toByteArray();
        }

        BackendProtocol.Connect connect = (BackendProtocol.Connect) BackendProtocol.decode(payload);

        assertEquals(0, connect.permission());
    }

    @Test
    void decodesBackendMspt() throws Exception {
        byte[] payload;
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
             DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeUTF("server_metrics");
            output.writeDouble(12.34D);
            payload = bytes.toByteArray();
        }

        BackendProtocol.ServerMetrics metrics =
            (BackendProtocol.ServerMetrics) BackendProtocol.decode(payload);

        assertEquals(12.34D, metrics.mspt());
    }

    @Test
    void decodesDonorPermissionFromMetadata() throws Exception {
        byte[] payload;
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
             DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeUTF("metadata");
            output.writeUTF(UUID.randomUUID().toString());
            output.writeUTF("mcid");
            output.writeUTF("rpg");
            output.writeUTF("account#0");
            output.writeInt(4);
            output.writeUTF("MAG");
            output.writeBoolean(false);
            output.writeInt(5);
            payload = bytes.toByteArray();
        }

        BackendProtocol.Metadata metadata = (BackendProtocol.Metadata) BackendProtocol.decode(payload);

        assertEquals(5, metadata.permission());
    }

    @Test
    void legacyMetadataDefaultsToPlayerPermission() throws Exception {
        byte[] payload;
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
             DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeUTF("metadata");
            output.writeUTF(UUID.randomUUID().toString());
            output.writeUTF("mcid");
            output.writeUTF("rpg");
            output.writeUTF("account#0");
            output.writeInt(4);
            output.writeUTF("MAG");
            output.writeBoolean(false);
            payload = bytes.toByteArray();
        }

        BackendProtocol.Metadata metadata = (BackendProtocol.Metadata) BackendProtocol.decode(payload);

        assertEquals(0, metadata.permission());
    }

    @Test
    void decodesPrivateChatMonitorMessage() throws Exception {
        UUID playerId = UUID.randomUUID();
        byte[] payload;
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
             DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeUTF("private_chat");
            output.writeUTF(playerId.toString());
            output.writeUTF("direct");
            output.writeUTF("Sender");
            output.writeUTF("Target");
            output.writeUTF("");
            output.writeUTF("secret");
            payload = bytes.toByteArray();
        }

        BackendProtocol.PrivateChat chat = (BackendProtocol.PrivateChat) BackendProtocol.decode(payload);

        assertEquals(playerId, chat.playerId());
        assertEquals("direct", chat.type());
        assertEquals("Target", chat.targetName());
        assertEquals("secret", chat.message());
    }
}
