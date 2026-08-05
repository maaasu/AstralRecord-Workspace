package io.github.maaasu.astralRecord.feature.skill.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.List;

/**
 * {@link SkillDefinition#getParams()} の値を実装側から安全に参照するための補助。
 * <p>
 * 各実装で生の Map アクセスを散在させないよう、必須/任意の取り出しと
 * 型不一致時の例外化をこのクラスに集約する。
 */
public final class SkillParamReader {

    private final String skillId;
    private final Map<String, Object> params;

    /**
     * 読み取り対象の {@code params} と所属スキル ID を紐付けて生成します。
     *
     * @param skillId スキル ID（例外メッセージへ含める）
     * @param params  対象 params
     */
    public SkillParamReader(@NotNull String skillId, @NotNull Map<String, Object> params) {
        this.skillId = skillId;
        this.params = params;
    }

    /**
     * 必須文字列を取得します。
     *
     * @param key キー名
     * @return 値
     * @throws SkillParameterException 未定義または非文字列の場合
     */
    @NotNull
    public String requireString(@NotNull String key) {
        Object raw = params.get(key);
        if (raw == null) {
            throw new SkillParameterException(key,
                    "skillId=" + skillId + " の params[" + key + "] が未定義です");
        }
        if (!(raw instanceof String value)) {
            throw new SkillParameterException(key,
                    "skillId=" + skillId + " の params[" + key + "] は文字列が必要です: actual="
                            + raw.getClass().getSimpleName());
        }
        return value;
    }

    /**
     * 数値値を取得します。未定義時は {@code defaultValue} を返します。
     * 型が {@link Number} でなければ例外を送出します。
     *
     * @param key          キー名
     * @param defaultValue 未定義時のデフォルト値
     * @return 解決された数値
     * @throws SkillParameterException 数値型ではない値が指定されている場合
     */
    public double getDouble(@NotNull String key, double defaultValue) {
        Object raw = params.get(key);
        if (raw == null) return defaultValue;
        if (raw instanceof Number number) return number.doubleValue();
        throw new SkillParameterException(key,
                "skillId=" + skillId + " の params[" + key + "] は数値が必要です: actual="
                        + raw.getClass().getSimpleName());
    }

    /**
     * 整数値を取得します。未定義時は {@code defaultValue} を返します。
     *
     * @param key キー名
     * @param defaultValue 未定義時のデフォルト値
     * @return 解決された整数値
     * @throws SkillParameterException 整数へ安全に変換できない値が指定されている場合
     */
    public int getInt(@NotNull String key, int defaultValue) {
        Object raw = params.get(key);
        if (raw == null) return defaultValue;
        if (!(raw instanceof Number number) || !Double.isFinite(number.doubleValue())) {
            throw new SkillParameterException(key,
                    "skillId=" + skillId + " の params[" + key + "] は数値が必要です");
        }
        double value = number.doubleValue();
        if (value != Math.rint(value) || value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw new SkillParameterException(key,
                    "skillId=" + skillId + " の params[" + key + "] は整数が必要です: actual=" + value);
        }
        return (int) value;
    }

    /**
     * 数値配列を取得します。配列要素はJSON/YAML読込後のNumberである必要があります。
     *
     * @param key キー名
     * @param defaultValue 未定義時のデフォルト値
     * @return immutableな数値配列
     * @throws SkillParameterException 配列または数値以外が指定されている場合
     */
    public @NotNull List<Double> getDoubleList(
            @NotNull String key,
            @NotNull List<Double> defaultValue
    ) {
        Object raw = params.get(key);
        if (raw == null) return List.copyOf(defaultValue);
        if (!(raw instanceof List<?> values)) {
            throw new SkillParameterException(key,
                    "skillId=" + skillId + " の params[" + key + "] は数値配列が必要です");
        }
        java.util.ArrayList<Double> result = new java.util.ArrayList<>(values.size());
        for (Object value : values) {
            if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue())) {
                throw new SkillParameterException(key,
                        "skillId=" + skillId + " の params[" + key + "] に不正な配列要素があります");
            }
            result.add(number.doubleValue());
        }
        return List.copyOf(result);
    }

    /**
     * 真偽値を取得します。未定義時は {@code defaultValue} を返します。
     *
     * @param key          キー名
     * @param defaultValue 未定義時のデフォルト値
     * @return 真偽値
     * @throws SkillParameterException 真偽型ではない値が指定されている場合
     */
    public boolean getBoolean(@NotNull String key, boolean defaultValue) {
        Object raw = params.get(key);
        if (raw == null) return defaultValue;
        if (raw instanceof Boolean value) return value;
        throw new SkillParameterException(key,
                "skillId=" + skillId + " の params[" + key + "] は真偽値が必要です: actual="
                        + raw.getClass().getSimpleName());
    }

    /**
     * {@code skill:} / {@code buff:} 等の prefix 付き参照値を取得し、prefix 除去後の素 ID を返します。
     *
     * @param key    キー名
     * @param prefix 期待する参照 prefix（例: "skill:"）
     * @return prefix 除去後の素 ID。未定義なら {@code null}
     * @throws SkillParameterException prefix 一致しない値が指定されている場合
     */
    @Nullable
    public String getRefId(@NotNull String key, @NotNull String prefix) {
        Object raw = params.get(key);
        if (raw == null) return null;
        if (!(raw instanceof String value)) {
            throw new SkillParameterException(key,
                    "skillId=" + skillId + " の params[" + key + "] は参照値が必要です: actual="
                            + raw.getClass().getSimpleName());
        }
        if (!value.startsWith(prefix)) {
            throw new SkillParameterException(key,
                    "skillId=" + skillId + " の params[" + key + "] には prefix " + prefix
                            + " が必要です: actual=" + value);
        }
        return value.substring(prefix.length());
    }
}
