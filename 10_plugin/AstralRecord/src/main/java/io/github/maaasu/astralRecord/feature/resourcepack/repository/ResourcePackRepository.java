package io.github.maaasu.astralRecord.feature.resourcepack.repository;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import io.github.maaasu.astralRecord.infrastructure.util.ApiRequestUtil;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/** 配置済みパックの識別子を内部APIから取得します。 */
public final class ResourcePackRepository {
    private static final String MANIFEST_PATH = "/api/resource-packs/manifest";
    private static final Pattern SHA1 = Pattern.compile("[0-9a-f]{40}");
    private static final Pattern PANEL_REVISION = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern MODEL_ID = Pattern.compile("[1-9][0-9]{0,7}");

    /** 配布物のハッシュと演出機能の互換情報を識別します。 */
    public record JavaPack(String sha1, @Nullable String actionRingPanel, @Nullable String guiTheme,
                           boolean hasGameplaySounds, boolean sunbirdPresentation, boolean sunbirdPresentationV2, boolean seleneaPresentation,
                           boolean mossZombiePresentation,
                           boolean archmagePhoenixPresentation, boolean nyxeronPresentation, boolean itemTooltips,
                           boolean weaponAttackProjectiles, @Nullable ProfileHud profileHud, boolean skillEffects,
                           boolean combatRewardPlaque, int questHudVersion, boolean receptionDisplay,
                           boolean locationHud, Set<Integer> wornHelmetModels, boolean fishingRodTip, boolean skillCooldownHud, boolean challengeCountdown,
                           boolean dungeonClearSound, boolean actionRingCooldownHud) {
    }

    /** 対応を確認した文字列マーカーだけを受け入れます。 */
    static int questHudVersion(@Nullable JsonElement value) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) return 0;
        return switch (value.getAsString()) {
            case "2" -> 2;
            case "3" -> 3;
            default -> 0;
        };
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
                String sunbirdVersion = sunbird != null && sunbird.isJsonPrimitive()
                    && sunbird.getAsJsonPrimitive().isString() ? sunbird.getAsString() : "";
                boolean sunbirdPresentationV2 = "2".equals(sunbirdVersion);
                boolean sunbirdPresentation = "1".equals(sunbirdVersion) || sunbirdPresentationV2;
                JsonElement mossZombie = java.get("mossZombiePresentation");
                boolean mossZombiePresentation = mossZombie != null && mossZombie.isJsonPrimitive()
                    && mossZombie.getAsJsonPrimitive().isString() && "2".equals(mossZombie.getAsString());
                JsonElement selenea = java.get("seleneaPresentation");
                boolean seleneaPresentation = selenea != null && selenea.isJsonPrimitive()
                    && selenea.getAsJsonPrimitive().isString() && "1".equals(selenea.getAsString());
                JsonElement phoenix = java.get("archmagePhoenixPresentation");
                boolean archmagePhoenixPresentation = phoenix != null && phoenix.isJsonPrimitive()
                    && phoenix.getAsJsonPrimitive().isString() && "1".equals(phoenix.getAsString());
                JsonElement nyxeron = java.get("nyxeronPresentation");
                // 互換版2: 弾幕・衝撃環・光刃・星雨の形状と専用効果音を含む。
                boolean nyxeronPresentation = nyxeron != null && nyxeron.isJsonPrimitive()
                    && nyxeron.getAsJsonPrimitive().isString() && "2".equals(nyxeron.getAsString());
                JsonElement tooltips = java.get("itemTooltips");
                boolean itemTooltips = tooltips != null && tooltips.isJsonPrimitive()
                    && tooltips.getAsJsonPrimitive().isString() && "1".equals(tooltips.getAsString());
                JsonElement projectiles = java.get("weaponAttackProjectiles");
                boolean weaponAttackProjectiles = projectiles != null && projectiles.isJsonPrimitive()
                    && projectiles.getAsJsonPrimitive().isString() && "1".equals(projectiles.getAsString());
                JsonElement effects = java.get("skillEffects");
                boolean skillEffects = effects != null && effects.isJsonPrimitive()
                    && effects.getAsJsonPrimitive().isString() && "1".equals(effects.getAsString());
                JsonElement plaque = java.get("combatRewardPlaque");
                boolean combatRewardPlaque = plaque != null && plaque.isJsonPrimitive()
                    && plaque.getAsJsonPrimitive().isString() && "1".equals(plaque.getAsString());
                JsonElement countdown = java.get("challengeCountdown");
                boolean challengeCountdown = countdown != null && countdown.isJsonPrimitive()
                    && countdown.getAsJsonPrimitive().isString() && "1".equals(countdown.getAsString());
                JsonElement dungeonClear = java.get("dungeonClearSound");
                boolean dungeonClearSound = dungeonClear != null && dungeonClear.isJsonPrimitive()
                    && dungeonClear.getAsJsonPrimitive().isString() && "1".equals(dungeonClear.getAsString());
                JsonElement actionRingCooldown = java.get("actionRingCooldownHud");
                boolean actionRingCooldownHud = actionRingCooldown != null && actionRingCooldown.isJsonPrimitive()
                    && actionRingCooldown.getAsJsonPrimitive().isString() && "1".equals(actionRingCooldown.getAsString());
                JsonElement reception = java.get("receptionDisplay");
                boolean receptionDisplay = reception != null && reception.isJsonPrimitive()
                    && reception.getAsJsonPrimitive().isString() && "1".equals(reception.getAsString());
                JsonElement rodTip = java.get("fishingRodTip");
                boolean fishingRodTip = rodTip != null && rodTip.isJsonPrimitive()
                    && rodTip.getAsJsonPrimitive().isString() && "1".equals(rodTip.getAsString());
                JsonElement location = java.get("locationHud");
                boolean locationHud = location != null && location.isJsonPrimitive()
                    && location.getAsJsonPrimitive().isString()
                    && ("4".equals(location.getAsString()) || "5".equals(location.getAsString()));
                boolean skillCooldownHud = locationHud && "5".equals(location.getAsString());
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
                    case "5" -> new ProfileHud(wideCombatMeter, 5);
                    case "6" -> new ProfileHud(wideCombatMeter, 6);
                    default -> null;
                };
                return new JavaPack(sha1.getAsString(), panel, guiTheme, hasGameplaySounds, sunbirdPresentation, sunbirdPresentationV2,
                    seleneaPresentation, mossZombiePresentation, archmagePhoenixPresentation, nyxeronPresentation, itemTooltips,
                    weaponAttackProjectiles, profileHud,
                    skillEffects, combatRewardPlaque,
                    questHudVersion(java.get("questTrackingHud")), receptionDisplay, locationHud,
                    parseWornHelmetModels(java.get("wornHelmetModels")), fishingRodTip, skillCooldownHud, challengeCountdown,
                    dungeonClearSound, actionRingCooldownHud);
            }
        } catch (RuntimeException exception) {
            throw new IOException("Invalid resource pack manifest", exception);
        }
        throw new IOException("Missing or invalid Java resource pack SHA-1");
    }

    /**
     * 着用モデルの一覧を検証し、欠落や不正があれば標準防具を使う空集合を返します。
     *
     * @param values 配布パックの革のヘルメットに対応するモデルIDの一覧
     * @return パケット送信スレッドと共有できる不変集合
     */
    private static Set<Integer> parseWornHelmetModels(@Nullable JsonElement values) {
        if (values == null || !values.isJsonArray()) {
            return Set.of();
        }
        Set<Integer> modelIds = new HashSet<>();
        for (JsonElement value : values.getAsJsonArray()) {
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()
                    || !MODEL_ID.matcher(value.getAsString()).matches()) {
                return Set.of();
            }
            int modelId = value.getAsInt();
            if (modelId > (1 << 24) || !modelIds.add(modelId)) {
                return Set.of();
            }
        }
        return Set.copyOf(modelIds);
    }
}
