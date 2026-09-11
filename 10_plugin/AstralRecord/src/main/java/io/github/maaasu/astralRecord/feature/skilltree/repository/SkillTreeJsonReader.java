package io.github.maaasu.astralRecord.feature.skilltree.repository;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Set;

/** スキルツリーJSONの厳格な基本値読込を共通化します。 */
final class SkillTreeJsonReader {
    private SkillTreeJsonReader() {
    }

    static JsonObject readObject(File file) {
        try (Reader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            JsonElement root = JsonParser.parseReader(reader);
            if (root == null || !root.isJsonObject()) {
                throw invalid(file, "root must be a JSON object");
            }
            return root.getAsJsonObject();
        } catch (IOException | JsonParseException e) {
            throw new IllegalStateException("Failed to read skilltree JSON: " + file.getAbsolutePath(), e);
        }
    }

    static void requireOnlyKeys(JsonObject object, Set<String> allowed, File file, String path) {
        for (String key : object.keySet()) {
            if (!allowed.contains(key)) {
                throw invalid(file, path + " contains unsupported property '" + key + "'");
            }
        }
    }

    static String requiredString(JsonObject object, String key, File file, String path) {
        JsonElement element = object.get(key);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            throw invalid(file, path + "." + key + " must be a string");
        }
        String value = element.getAsString();
        if (value.isBlank()) {
            throw invalid(file, path + "." + key + " must not be blank");
        }
        return value;
    }

    static int requiredInt(JsonObject object, String key, File file, String path) {
        JsonElement element = object.get(key);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            throw invalid(file, path + "." + key + " must be an integer");
        }
        try {
            BigDecimal decimal = element.getAsBigDecimal();
            if (decimal.stripTrailingZeros().scale() > 0) {
                throw invalid(file, path + "." + key + " must be an integer");
            }
            return decimal.intValueExact();
        } catch (ArithmeticException | NumberFormatException e) {
            throw invalid(file, path + "." + key + " must be a 32-bit integer");
        }
    }

    static double requiredDouble(JsonObject object, String key, File file, String path) {
        JsonElement element = object.get(key);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            throw invalid(file, path + "." + key + " must be a number");
        }
        double value = element.getAsDouble();
        if (!Double.isFinite(value)) {
            throw invalid(file, path + "." + key + " must be finite");
        }
        return value;
    }

    /**
     * 0.1ブロック刻みの相対座標を読み込みます。
     *
     * @param object 読込元JSONオブジェクト
     * @param key 座標キー
     * @param file 読込元ファイル
     * @param path エラー表示用JSONパス
     * @return 32-bitブロック範囲内の0.1刻み座標
     * @throws IllegalStateException 値が有限数、範囲内、または0.1刻みでない場合
     */
    static double requiredTenthBlockCoordinate(JsonObject object, String key, File file, String path) {
        JsonElement element = object.get(key);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            throw invalid(file, path + "." + key + " must be a 0.1-block coordinate");
        }
        try {
            BigDecimal coordinate = element.getAsBigDecimal();
            if (coordinate.compareTo(BigDecimal.valueOf(Integer.MIN_VALUE)) < 0
                    || coordinate.compareTo(BigDecimal.valueOf(Integer.MAX_VALUE)) > 0
                    || coordinate.movePointRight(1).stripTrailingZeros().scale() > 0) {
                throw invalid(file, path + "." + key + " must be a 32-bit coordinate in 0.1-block increments");
            }
            return coordinate.doubleValue();
        } catch (NumberFormatException e) {
            throw invalid(file, path + "." + key + " must be a finite 0.1-block coordinate");
        }
    }

    static JsonArray requiredArray(JsonObject object, String key, File file, String path) {
        JsonElement element = object.get(key);
        if (element == null || !element.isJsonArray()) {
            throw invalid(file, path + "." + key + " must be an array");
        }
        return element.getAsJsonArray();
    }

    static JsonObject requiredObject(JsonElement element, File file, String path) {
        if (element == null || !element.isJsonObject()) {
            throw invalid(file, path + " must be an object");
        }
        return element.getAsJsonObject();
    }

    static IllegalStateException invalid(File file, String detail) {
        return new IllegalStateException("Invalid skilltree JSON " + file.getAbsolutePath() + ": " + detail);
    }
}
