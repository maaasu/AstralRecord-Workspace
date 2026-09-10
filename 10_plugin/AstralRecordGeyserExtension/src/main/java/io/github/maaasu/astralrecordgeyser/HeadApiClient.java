package io.github.maaasu.astralrecordgeyser;

import java.io.IOException;
import java.net.Socket;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedTrustManager;

/** 認証付きAPIから起動時にヘッド情報を一括取得する。 */
final class HeadApiClient {
    /** タイムアウト付きGETを実行する。リダイレクト先へAPIキーは転送しない。 */
    static HeadCatalog fetch(HeadApiConfig config) throws IOException, InterruptedException {
        HttpClient.Builder builder = HttpClient.newBuilder().connectTimeout(config.timeout)
            .followRedirects(HttpClient.Redirect.NEVER);
        if (config.allowInsecureTls) {
            builder.sslContext(createInsecureSslContext());
            SSLParameters sslParameters = new SSLParameters();
            sslParameters.setEndpointIdentificationAlgorithm(null);
            builder.sslParameters(sslParameters);
        }
        try (HttpClient client = builder.build()) {
            HttpRequest request = HttpRequest.newBuilder(config.endpoint).timeout(config.timeout)
                .header("X-Api-Key", config.apiKey).header("Accept", "application/json").GET().build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) throw new IOException("Head API returned HTTP " + response.statusCode());
            try {
                return HeadCatalog.parse(response.body());
            } catch (RuntimeException exception) {
                throw new IOException("Invalid head catalog response");
            }
        }
    }

    private static SSLContext createInsecureSslContext() {
        TrustManager[] trustManagers = {new X509ExtendedTrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) {
                // 閉域・開発環境向け設定ではクライアント証明書を検証しない。
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) {
                // 閉域・開発環境向け設定ではサーバー証明書を検証しない。
            }

            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket) {
                // 閉域・開発環境向け設定ではクライアント証明書を検証しない。
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket) {
                // 閉域・開発環境向け設定ではサーバー証明書とホスト名を検証しない。
            }

            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine) {
                // 閉域・開発環境向け設定ではクライアント証明書を検証しない。
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine) {
                // 閉域・開発環境向け設定ではサーバー証明書とホスト名を検証しない。
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        }};
        try {
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, trustManagers, new SecureRandom());
            return context;
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Failed to initialize insecure TLS context", exception);
        }
    }
}
