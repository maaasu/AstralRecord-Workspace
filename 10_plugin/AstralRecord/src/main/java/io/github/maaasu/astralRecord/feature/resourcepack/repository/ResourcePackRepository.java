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

    /** 配布物のハッシュと演出機能の互換情報を識別します。 */
    public record JavaPack(String sha1, @Nullable String actionRingPanel, @Nullable String guiTheme,
                           boolean hasGameplaySounds, boolean sunbirdPresentation, boolean itemTooltips,
                           boolean weaponAttackProjectiles, @Nullable ProfileHud profileHud) {
    }

    /** 個人情報HUDの字形とゲージの互換情報です。 */
    public record ProfileHud(boolean wideCombatMeter, int version) {
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
                JsonElement sounds = java.get("gameplaySounds");
                boolean hasGameplaySounds = sounds != null && sounds.isJsonPrimitive()
                    && sounds.getAsJsonPrimitive().isString() && "1".equals(sounds.getAsString());
                JsonElement sunbird = java.get("sunbirdPresentation");
                boolean sunbirdPresentation = sunbird != null && sunbird.isJsonPrimitive()
                    && sunbird.getAsJsonPrimitive().isString() && "1".equals(sunbird.getAsString());
                JsonElement tooltips = java.get("itemTooltips");
                boolean itemTooltips = tooltips != null && tooltips.isJsonPrimitive()
                    && tooltips.getAsJsonPrimitive().isString() && "1".equals(tooltips.getAsString());
                JsonElement projectiles = java.get("weaponAttackProjectiles");
                boolean weaponAttackProjectiles = projectiles != null && projectiles.isJsonPrimitive()
                    && projectiles.getAsJsonPrimitive().isString() && "1".equals(projectiles.getAsString());
                JsonElement profile = java.get("profileHud");
                String profileVersion = profile != null && profile.isJsonPrimitive()
                    && profile.getAsJsonPrimitive().isString() ? profile.getAsString() : "";
                JsonElement meter = java.get("wideCombatMeter");
                boolean wideCombatMeter = meter != null && meter.isJsonPrimitive()
                    && meter.getAsJsonPrimitive().isString() && "1".equals(meter.getAsString());
                ProfileHud profileHud = switch (profileVersion) {
                    case "2" -> new ProfileHud(wideCombatMeter, 2);
                    case "3" -> new ProfileHud(wideCombatMeter, 3);
                    case "4" -> new ProfileHud(wideCombatMeter, 4);
                    default -> null;
                };
                return new JavaPack(sha1.getAsString(), panel, guiTheme, hasGameplaySounds, sunbirdPresentation,
                    itemTooltips, weaponAttackProjectiles, profileHud);
            }
        } catch (RuntimeException exception) {
            throw new IOException("Invalid resource pack manifest", exception);
        }
        throw new IOException("Missing or invalid Java resource pack SHA-1");
    }
}
