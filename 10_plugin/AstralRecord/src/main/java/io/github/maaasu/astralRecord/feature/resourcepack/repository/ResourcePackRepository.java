package io.github.maaasu.astralRecord.feature.resourcepack.repository;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import io.github.maaasu.astralRecord.infrastructure.util.ApiRequestUtil;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.util.regex.Pattern;

/** 配置済みパックの識別子を内部APIから取得します。 */
public final class ResourcePackRepository {
    private static final String MANIFEST_PATH = "/api/resource-packs/manifest";
    private static final Pattern SHA1 = Pattern.compile("[0-9a-f]{40}");

    /**
     * 配信中のJava用パックのSHA-1を取得します。HTTPを待つため非同期タスクから呼び出します。
     *
     * @return 40桁の小文字SHA-1。未配布ならnull
     * @throws IOException 通信または配布情報が異常な場合
     * @throws InterruptedException 待機中に割り込まれた場合
     */
    public @Nullable String loadJavaSha1() throws IOException, InterruptedException {
        var request = ApiRequestUtil.buildRequestBuilder(MANIFEST_PATH).GET().build();
        var response = ApiRequestUtil.sharedClient().send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 404) {
            return null;
        }
        if (response.statusCode() != 200) {
            throw new IOException("HTTP " + response.statusCode());
        }
        try {
            JsonElement sha1 = JsonParser.parseString(response.body()).getAsJsonObject()
                    .getAsJsonObject("java").get("sha1");
            if (sha1 != null && sha1.isJsonPrimitive() && sha1.getAsJsonPrimitive().isString()
                    && SHA1.matcher(sha1.getAsString()).matches()) {
                return sha1.getAsString();
            }
        } catch (RuntimeException exception) {
            throw new IOException("Invalid resource pack manifest", exception);
        }
        throw new IOException("Missing or invalid Java resource pack SHA-1");
    }
}
