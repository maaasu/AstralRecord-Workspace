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
    private static final Pattern PANEL_REVISION = Pattern.compile("[0-9a-f]{64}");

    /** 配布物のハッシュ、アクションリング台帳、GUIテーマの互換版を識別します。 */
    public record JavaPack(String sha1, @Nullable String actionRingPanel, @Nullable String guiTheme) {
    }

    /**
     * 配信中の Java 版パックの情報を取得します。HTTP を待つため非同期タスクから呼び出します。
     *
     * @return 配布情報。未配布なら null
     * @throws IOException 通信または配布情報が異常な場合
     * @throws InterruptedException 待機中に割り込まれた場合
     */
    public @Nullable JavaPack loadJavaPack() throws IOException, InterruptedException {
        var request = ApiRequestUtil.buildRequestBuilder(MANIFEST_PATH).GET().build();
        var response = ApiRequestUtil.sharedClient().send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 404) {
            return null;
        }
        if (response.statusCode() != 200) {
            throw new IOException("HTTP " + response.statusCode());
        }
        try {
            var java = JsonParser.parseString(response.body()).getAsJsonObject().getAsJsonObject("java");
            JsonElement sha1 = java.get("sha1");
            if (sha1 != null && sha1.isJsonPrimitive() && sha1.getAsJsonPrimitive().isString()
                    && SHA1.matcher(sha1.getAsString()).matches()) {
                JsonElement revision = java.get("actionRingPanel");
                String panel = revision != null && revision.isJsonPrimitive()
                    && revision.getAsJsonPrimitive().isString()
                    && PANEL_REVISION.matcher(revision.getAsString()).matches() ? revision.getAsString() : null;
                JsonElement gui = java.get("guiTheme");
                String guiTheme = gui != null && gui.isJsonPrimitive()
                    && gui.getAsJsonPrimitive().isString() && "1".equals(gui.getAsString()) ? "1" : null;
                return new JavaPack(sha1.getAsString(), panel, guiTheme);
            }
        } catch (RuntimeException exception) {
            throw new IOException("Invalid resource pack manifest", exception);
        }
        throw new IOException("Missing or invalid Java resource pack SHA-1");
    }
}
