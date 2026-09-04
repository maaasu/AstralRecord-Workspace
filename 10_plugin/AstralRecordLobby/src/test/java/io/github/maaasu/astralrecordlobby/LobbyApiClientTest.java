package io.github.maaasu.astralrecordlobby;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LobbyApiClientTest {
    private static final char[] STORE_PASSWORD = "changeit".toCharArray();

    @TempDir
    Path temporaryDirectory;

    @Test
    void insecureTlsAcceptsSelfSignedCertificateWithMismatchedHostName() throws Exception {
        SSLContext serverContext = createServerContext();
        byte[] responseBody = "{\"generationId\":\"test-generation\",\"messages\":[]}"
            .getBytes(StandardCharsets.UTF_8);

        try (SSLServerSocket server = (SSLServerSocket) serverContext.getServerSocketFactory()
            .createServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
             ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<?> response = executor.submit(() -> serveOneRequest(server, responseBody));
            YamlConfiguration config = new YamlConfiguration();
            config.set("api.baseUrl", "https://127.0.0.1:" + server.getLocalPort());
            config.set("api.apiKey", "test-key");
            config.set("api.timeoutMillis", 3000);
            config.set("api.allowInsecureTls", true);

            LobbyApiClient.ChatBatch batch = new LobbyApiClient(config).getMinecraftChat(0L);

            assertEquals("test-generation", batch.generationId());
            assertEquals(List.of(), batch.messages());
            response.get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void serverPresenceCalculatesViewerSpecificLimit() {
        LobbyApiClient.ServerPresence presence =
            new LobbyApiClient.ServerPresence("ch1", "online", 32, 40, 5, 1);

        assertEquals(40, presence.limitFor(0));
        assertEquals(45, presence.limitFor(5));
        assertEquals(46, presence.limitFor(99));
        assertEquals(0, presence.extraFor(0));
        assertEquals(5, presence.extraFor(5));
        assertEquals(6, presence.extraFor(99));
        assertFalse(presence.fullFor(0));

        LobbyApiClient.ServerPresence fullForPlayer =
            new LobbyApiClient.ServerPresence("ch1", "online", 40, 40, 5, 1);
        assertTrue(fullForPlayer.fullFor(0));
        assertFalse(fullForPlayer.fullFor(5));

        LobbyApiClient.ServerPresence maximumValues = new LobbyApiClient.ServerPresence(
            "ch1", "online", Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE);
        assertEquals(Integer.MAX_VALUE, maximumValues.extraFor(99));
        assertEquals(Integer.MAX_VALUE, maximumValues.limitFor(99));
        assertTrue(maximumValues.fullFor(99));
    }

    /**
     * ホスト名がlocalhostだけの自己署名サーバー証明書を生成する。
     *
     * @return テストHTTPSサーバー用TLSコンテキスト
     * @throws Exception keytoolまたは鍵ストア初期化に失敗した場合
     */
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

    /**
     * テスト用HTTPS接続を1件受け付けて固定JSONを返す。
     *
     * @param server 待受中のTLSサーバーソケット
     * @param body 送信するレスポンス本文
     * @throws IllegalStateException 通信に失敗した場合
     */
    private static void serveOneRequest(SSLServerSocket server, byte[] body) {
        try (SSLSocket socket = (SSLSocket) server.accept();
             BufferedReader reader = new BufferedReader(
                 new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
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
