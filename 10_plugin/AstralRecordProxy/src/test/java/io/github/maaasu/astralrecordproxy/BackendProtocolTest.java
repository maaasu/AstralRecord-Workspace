package io.github.maaasu.astralrecordproxy;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;

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
}
