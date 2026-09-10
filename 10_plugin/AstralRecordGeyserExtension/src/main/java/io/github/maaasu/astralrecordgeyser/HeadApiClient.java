package io.github.maaasu.astralrecordgeyser;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/** 認証付きAPIから起動時にヘッド情報を一括取得する。 */
final class HeadApiClient {
    /** タイムアウト付きGETを実行する。リダイレクト先へAPIキーは転送しない。 */
    static HeadCatalog fetch(HeadApiConfig config) throws IOException, InterruptedException {
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(config.timeout)
            .followRedirects(HttpClient.Redirect.NEVER).build()) {
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
}
