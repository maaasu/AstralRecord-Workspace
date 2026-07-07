package io.github.maaasu.astralRecord.feature.mob.model;

import org.jetbrains.annotations.NotNull;

import java.util.Locale;

/**
 * Mob の見た目上の個体差をマスタデータで固定する設定です。
 *
 * @param age           年齢表現。`ADULT` / `BABY` を指定できます。
 * @param kind          エンティティ固有の種類。Cat/Rabbit/Fox/Frog/Axolotl/Parrot/Mooshroom などの variant/type に使用します。
 * @param color         エンティティ固有の色。Sheep/Horse/Llama などに使用します。
 * @param style         Horse などの模様に使用します。
 * @param profession    Villager / ZombieVillager の職業に使用します。
 * @param villagerType  Villager のバイオーム種別に使用します。
 * @param villagerLevel Villager の取引レベルに使用します。
 * @param pattern       TropicalFish などの模様に使用します。
 * @param bodyColor     TropicalFish などの体色に使用します。
 * @param patternColor  TropicalFish などの模様色に使用します。
 * @param mainGene      Panda の主遺伝子に使用します。
 * @param hiddenGene    Panda の隠し遺伝子に使用します。
 */
public record MobVariantConfig(
        @NotNull Age age,
        String kind,
        String color,
        String style,
        String profession,
        String villagerType,
        Integer villagerLevel,
        String pattern,
        String bodyColor,
        String patternColor,
        String mainGene,
        String hiddenGene
) {

    public static final MobVariantConfig DEFAULT = new MobVariantConfig(
            Age.ADULT,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null
    );

    public MobVariantConfig(@NotNull Age age) {
        this(age, null, null, null, null, null, null, null, null, null, null, null);
    }

    public MobVariantConfig {
        if (age == null) {
            age = Age.ADULT;
        }
        kind = normalize(kind);
        color = normalize(color);
        style = normalize(style);
        profession = normalize(profession);
        villagerType = normalize(villagerType);
        pattern = normalize(pattern);
        bodyColor = normalize(bodyColor);
        patternColor = normalize(patternColor);
        mainGene = normalize(mainGene);
        hiddenGene = normalize(hiddenGene);
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

    private static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return raw.trim().replace('-', '_').replace(' ', '_').toUpperCase(Locale.ROOT);
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
