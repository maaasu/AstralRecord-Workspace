package io.github.maaasu.astralrecordproxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

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
            output.writeUTF("romaji");
            output.writeUTF("秘密");
            payload = bytes.toByteArray();
        }

        BackendProtocol.PrivateChat chat = (BackendProtocol.PrivateChat) BackendProtocol.decode(payload);

        assertEquals(playerId, chat.playerId());
        assertEquals("direct", chat.type());
        assertEquals("Target", chat.targetName());
        assertEquals("romaji", chat.original());
        assertEquals("秘密", chat.converted());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/33-network/33_4-統合フロー.md
     * 章・見出し: # 33_4-統合フロー > ## 全体チャット
     * 検証契約: backendの全体チャットは原文と変換後本文を別フィールドでProxyへ渡す。
     */
    @Test
    void decodesChatWithOriginalAndConvertedText() throws Exception {
        byte[] payload;
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
             DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeUTF("chat");
            output.writeUTF(UUID.randomUUID().toString());
            output.writeUTF(UUID.randomUUID().toString());
            output.writeUTF("Sender");
            output.writeUTF("rpg");
            output.writeUTF("Sender#0");
            output.writeInt(10);
            output.writeUTF("Mage");
            output.writeUTF("gakkou");
            output.writeUTF("学校");
            payload = bytes.toByteArray();
        }

        BackendProtocol.Chat chat = (BackendProtocol.Chat) BackendProtocol.decode(payload);

        assertEquals("gakkou", chat.original());
        assertEquals("学校", chat.converted());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/33-network/33_4-統合フロー.md
     * 章・見出し: # 33_4-統合フロー > ## 最高権限とプライベートチャット監視
     * 検証契約: 別backend宛DMの送信者・宛先・変換前後本文をProxyが欠損なく復元する。
     */
    @Test
    void decodesCrossBackendDirectMessage() throws Exception {
        UUID senderId = UUID.fromString("7c72cb6c-8cfd-4d74-8c67-6f39a4b4b9ca");
        byte[] payload;
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
             DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeUTF(BackendProtocol.DIRECT_MESSAGE);
            output.writeUTF(senderId.toString());
            output.writeUTF("target");
            output.writeUTF("sender#0");
            output.writeInt(7);
            output.writeUTF("original");
            output.writeUTF("converted");
            payload = bytes.toByteArray();
        }

        BackendProtocol.DirectMessage message = assertInstanceOf(
            BackendProtocol.DirectMessage.class, BackendProtocol.decode(payload));

        assertEquals(senderId, message.playerId());
        assertEquals("target", message.targetName());
        assertEquals("sender#0", message.senderName());
        assertEquals(7, message.senderLevel());
        assertEquals("original", message.original());
        assertEquals("converted", message.converted());
    }
}
