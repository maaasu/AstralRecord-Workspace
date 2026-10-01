package io.github.maaasu.astralRecord.feature.pet.model;

import com.google.gson.*;

/** ペット契約の任意値を安全に参照する補助処理です。通信・状態変更は行いません。 */
public final class PetJson {
    private PetJson() { }
    /**
     * オブジェクト項目を返します。欠落時は空です。
     * @param value 参照するJSONオブジェクト
     * @param key 契約上の項目名
     * @return 説明した契約に従う結果。通信を伴う処理の失敗は例外またはfutureで通知します
     */
    public static JsonObject object(JsonObject value, String key) {
        JsonElement item = value.get(key);
        return item != null && item.isJsonObject() ? item.getAsJsonObject() : new JsonObject();
    }
    /**
     * 配列項目を返します。欠落時は空です。
     * @param value 参照するJSONオブジェクト
     * @param key 契約上の項目名
     * @return 説明した契約に従う結果。通信を伴う処理の失敗は例外またはfutureで通知します
     */
    public static JsonArray array(JsonObject value, String key) {
        JsonElement item = value.get(key);
        return item != null && item.isJsonArray() ? item.getAsJsonArray() : new JsonArray();
    }
    /**
     * 文字列項目を返します。欠落時は既定値です。
     * @param value 参照するJSONオブジェクト
     * @param key 契約上の項目名
     * @param fallback 欠落時に返す既定値
     * @return 説明した契約に従う結果。通信を伴う処理の失敗は例外またはfutureで通知します
     */
    public static String text(JsonObject value, String key, String fallback) {
        JsonElement item = value.get(key);
        return item == null || item.isJsonNull() ? fallback : item.getAsString();
    }
    /**
     * 有限な数値項目を返します。欠落・非有限値は既定値です。
     * @param value 参照するJSONオブジェクト
     * @param key 契約上の項目名
     * @param fallback 欠落時に返す既定値
     * @return 説明した契約に従う結果。通信を伴う処理の失敗は例外またはfutureで通知します
     */
    public static double number(JsonObject value, String key, double fallback) {
        JsonElement item = value.get(key);
        double result = item == null || item.isJsonNull() ? fallback : item.getAsDouble();
        return Double.isFinite(result) ? result : fallback;
    }
    /**
     * 真偽値項目を返します。欠落時はfalseです。
     * @param value 参照するJSONオブジェクト
     * @param key 契約上の項目名
     * @return 説明した契約に従う結果。通信を伴う処理の失敗は例外またはfutureで通知します
     */
    public static boolean flag(JsonObject value, String key) {
        JsonElement item = value.get(key);
        return item != null && !item.isJsonNull() && item.getAsBoolean();
    }
}
