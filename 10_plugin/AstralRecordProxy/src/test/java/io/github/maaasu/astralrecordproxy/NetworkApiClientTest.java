package io.github.maaasu.astralrecordproxy;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.UUID;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NetworkApiClientTest {
    private static final char[] STORE_PASSWORD = "changeit".toCharArray();

    @TempDir
    Path temporaryDirectory;

    @Test
    void insecureTlsAcceptsSelfSignedCertificateWithMismatchedHostName() throws Exception {
        SSLContext serverContext = createServerContext();
        byte[] responseBody = "{\"generationId\":\"test-generation\",\"messages\":[]}".getBytes(StandardCharsets.UTF_8);

        try (SSLServerSocket server = (SSLServerSocket) serverContext.getServerSocketFactory()
            .createServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
             ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<?> response = executor.submit(() -> serveOneRequest(server, responseBody));
            ProxyConfig config = new ProxyConfig(
                "lobby", List.of("dev"), Map.of(), Map.of(), 30L, 2L, 10L,
                "https://127.0.0.1:" + server.getLocalPort(), "test-key", "sync-key", 3000, 500L, 5L, true,
                "mc.astralrecord.com", List.of(), java.util.Set.of());

            NetworkApiClient.DiscordChatBatch batch = new NetworkApiClient(config).getDiscordChat(0L).get(5, TimeUnit.SECONDS);

            assertEquals("test-generation", batch.generationId());
            assertEquals(List.of(), batch.messages());
            response.get(5, TimeUnit.SECONDS);
        }
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/33-network/33_4-統合フロー.md
     * 章・見出し: # 33_4-統合フロー > ## 全体チャット
     * 検証契約: Minecraft発言はDiscordスキン解決用のUUIDとMCIDをNetwork APIへ登録する。
     */
    @Test
    void minecraftChatRequestIncludesPlayerIdentity() {
        UUID messageId = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();
        var chat = new BackendProtocol.Chat(
            messageId, playerId, "AstralRecord", "ch1", "AstralRecord#1", 10, "剣士",
            "konnichiha", "こんにちは");

        JsonObject body = NetworkApiClient.minecraftChatRequest(chat, "ch1");

        assertEquals(playerId.toString(), body.get("authorPlayerId").getAsString());
        assertEquals("AstralRecord", body.get("authorMinecraftName").getAsString());
        assertEquals("AstralRecord#1", body.get("authorName").getAsString());
        assertEquals("konnichiha[こんにちは]", body.get("message").getAsString());
    }

    /**
     * 設計入力: 00_docs/20_API設計書/feature/33-network/1-モデル定義/33_1.00-モデル定義.md
     * 検証契約: Lifecycle通知はプレイヤー識別情報とアクション種別をNetwork APIへ登録する。
     */
    @Test
    void lifecycleRequestIncludesPlayerIdentityAndAction() {
        UUID playerId = UUID.randomUUID();

        JsonObject body = NetworkApiClient.lifecycleRequest(
            "ch1", playerId, "AstralRecord", "channel_connect", "AstralRecordさんが接続しました");

        assertEquals(playerId.toString(), body.get("authorPlayerId").getAsString());
        assertEquals("AstralRecord", body.get("authorMinecraftName").getAsString());
        assertEquals("AstralRecord", body.get("authorName").getAsString());
        assertEquals("lifecycle", body.get("kind").getAsString());
        assertEquals("channel_connect", body.get("action").getAsString());
    }

    @Test
    void admissionParsesBanReasonAndUtcExpiry() {
        NetworkApiClient.Admission admission = NetworkApiClient.Admission.fromJson(JsonParser.parseString("""
            {"admitted":false,"denyReason":"banned","permission":0,"banIndefinite":false,
             "banExpiresAtUtc":"2026-09-18T00:00:00+00:00","banReason":"不正利用"}
            """).getAsJsonObject());

        assertEquals(false, admission.admitted());
        assertEquals("banned", admission.denyReason());
        assertEquals("2026-09-18T00:00Z", admission.banExpiresAtUtc().toString());
        assertEquals("不正利用", admission.banReason());
    }

    private SSLContext createServerContext() throws Exception {
        Path keyStorePath = temporaryDirectory.resolve("development-certificate.p12");
        Path keytool = Path.of(
            System.getProperty("java.home"),
            "bin",
            System.getProperty("os.name").toLowerCase().contains("win") ? "keytool.exe" : "keytool");
        Process process = new ProcessBuilder(
            keytool.toString(), "-genkeypair",
            "-alias", "development",
            "-keyalg", "RSA",
            "-storetype", "PKCS12",
            "-keystore", keyStorePath.toString(),
            "-storepass", String.valueOf(STORE_PASSWORD),
            "-keypass", String.valueOf(STORE_PASSWORD),
            "-dname", "CN=localhost",
            "-ext", "SAN=dns:localhost",
            "-validity", "1",
            "-noprompt")
            .redirectErrorStream(true)
            .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!process.waitFor(10, TimeUnit.SECONDS) || process.exitValue() != 0) {
            throw new IllegalStateException("keytool failed: " + output);
        }

        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (var input = Files.newInputStream(keyStorePath)) {
            keyStore.load(input, STORE_PASSWORD);
        }
        KeyManagerFactory keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagerFactory.init(keyStore, STORE_PASSWORD);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(keyManagerFactory.getKeyManagers(), null, null);
        return context;
    }

    private static void serveOneRequest(SSLServerSocket server, byte[] body) {
        try (SSLSocket socket = (SSLSocket) server.accept();
             BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
             OutputStream output = socket.getOutputStream()) {
            String line;
            do {
                line = reader.readLine();
            } while (line != null && !line.isEmpty());
            byte[] headers = ("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: "
                + body.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
            output.write(headers);
            output.write(body);
            output.flush();
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to serve test HTTPS response", exception);
        }
    }
}
