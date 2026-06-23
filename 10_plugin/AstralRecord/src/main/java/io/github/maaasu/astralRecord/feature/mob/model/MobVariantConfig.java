package io.github.maaasu.astralRecord.feature.mob.model;

import org.jetbrains.annotations.NotNull;

import java.util.Locale;

/**
 * Mob の見た目上の個体差をマスタデータで固定する設定です。
 *
 * @param age 年齢表現。`ADULT` / `BABY` を指定できます。
 */
public record MobVariantConfig(@NotNull Age age) {

    public static final MobVariantConfig DEFAULT = new MobVariantConfig(Age.ADULT);

    public MobVariantConfig {
        if (age == null) {
            age = Age.ADULT;
        }
    }

    /**
     * API / filebase の文字列から variant 設定を作成します。
     *
     * @param rawAge 年齢表現。未指定または不正値は `ADULT` として扱います。
     * @return Mob variant 設定
     */
    public static @NotNull MobVariantConfig fromRawAge(String rawAge) {
        return new MobVariantConfig(Age.fromRaw(rawAge));
    }

    public enum Age {
        ADULT,
        BABY;

        /**
         * API / filebase の文字列から年齢表現を解決します。
         *
         * @param raw 年齢表現
         * @return 解決された年齢表現
         */
        public static @NotNull Age fromRaw(String raw) {
            if (raw == null || raw.isBlank()) {
                return ADULT;
            }
            try {
                return Age.valueOf(raw.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                return ADULT;
            }
        }
    }
}
