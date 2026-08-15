package io.github.maaasu.astralRecord.feature.item.service;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** フックショット個体ごとの装填済み状態を inventory entry metadata に保存します。 */
final class HookshotLoadState {
    private static final String ROOT_KEY = "hookshot";
    private static final String LOADED_KEY = "loaded";

    private HookshotLoadState() {
    }

    /** 指定 metadata が装填済みを表すか返します。 */
    static boolean isLoaded(@Nullable String metadataJson) {
        JsonObject root = parseObject(metadataJson);
        if (root == null || !root.has(ROOT_KEY) || !root.get(ROOT_KEY).isJsonObject()) {
            return false;
        }
        JsonObject hookshot = root.getAsJsonObject(ROOT_KEY);
        return hookshot.has(LOADED_KEY)
            && hookshot.get(LOADED_KEY).isJsonPrimitive()
            && hookshot.get(LOADED_KEY).getAsJsonPrimitive().isBoolean()
            && hookshot.get(LOADED_KEY).getAsBoolean();
    }

    /**
     * 装填済み状態だけを更新し、他機能の metadata は保持します。
     *
     * @param metadataJson 変更前 metadata
     * @param loaded 変更後の装填状態
     * @return metadata 更新内容。既存JSONが object でない、hookshot 名前空間が不正、または既存 loaded が boolean 以外なら accepted は false
     */
    static @NotNull Update setLoaded(@Nullable String metadataJson, boolean loaded) {
        JsonObject root;
        if (metadataJson == null || metadataJson.isBlank()) {
            root = new JsonObject();
        } else {
            root = parseObject(metadataJson);
            if (root == null) {
                return Update.rejected();
            }
        }

        JsonObject hookshot;
        if (!root.has(ROOT_KEY)) {
            hookshot = new JsonObject();
            root.add(ROOT_KEY, hookshot);
        } else if (root.get(ROOT_KEY).isJsonObject()) {
            hookshot = root.getAsJsonObject(ROOT_KEY);
        } else {
            return Update.rejected();
        }

        if (hookshot.has(LOADED_KEY)) {
            JsonElement existingLoaded = hookshot.get(LOADED_KEY);
            if (!existingLoaded.isJsonPrimitive()
                || !existingLoaded.getAsJsonPrimitive().isBoolean()) {
                return Update.rejected();
            }
        }

        if (loaded) {
            hookshot.addProperty(LOADED_KEY, true);
        } else {
            hookshot.remove(LOADED_KEY);
            if (hookshot.isEmpty()) {
                root.remove(ROOT_KEY);
            }
        }
        return Update.accepted(root.isEmpty() ? null : root.toString());
    }

    private static @Nullable JsonObject parseObject(@Nullable String metadataJson) {
        if (metadataJson == null || metadataJson.isBlank()) {
            return null;
        }
        try {
            JsonElement parsed = JsonParser.parseString(metadataJson);
            if (!parsed.isJsonObject()) {
                return null;
            }
            return parsed.getAsJsonObject();
        } catch (JsonSyntaxException | IllegalStateException ignored) {
            return null;
        }
    }

    record Update(boolean accepted, @Nullable String metadataJson) {
        static @NotNull Update accepted(@Nullable String metadataJson) {
            return new Update(true, metadataJson);
        }

        static @NotNull Update rejected() {
            return new Update(false, null);
        }
    }
}
