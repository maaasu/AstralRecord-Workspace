package io.github.maaasu.astralRecord.feature.player.service;

import org.jetbrains.annotations.NotNull;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Collections;

/**
 * ASCIIローマ字をひらがなへ変換する規則ベースの変換器です。
 */
public final class RomajiKanaConverter {
    private static final Map<String, String> SYLLABLES = createSyllables();

    private RomajiKanaConverter() {
        // utility class
    }

    /**
     * ローマ字をひらがなへ変換します。認識できない文字はそのまま残します。
     *
     * @param source 変換元文字列
     * @return ひらがなへ置換した文字列
     */
    public static @NotNull String convert(@NotNull String source) {
        StringBuilder result = new StringBuilder(source.length());
        String lower = source.toLowerCase(Locale.ROOT);
        int index = 0;
        while (index < source.length()) {
            char current = lower.charAt(index);
            if (!isAsciiLetter(current)) {
                result.append(source.charAt(index++));
                continue;
            }
            if (current == 'n' && index + 1 < lower.length() && lower.charAt(index + 1) == '\'') {
                result.append('ん');
                index += 2;
                continue;
            }
            if (isSokuon(lower, index)) {
                result.append('っ');
                index++;
                continue;
            }
            if (current == 'n' && isNasalN(lower, index)) {
                result.append('ん');
                index++;
                continue;
            }

            String kana = null;
            int matchedLength = 0;
            for (String romaji : SYLLABLES.keySet()) {
                if (lower.startsWith(romaji, index)) {
                    kana = SYLLABLES.get(romaji);
                    matchedLength = romaji.length();
                    break;
                }
            }
            if (kana == null) {
                result.append(source.charAt(index++));
                continue;
            }
            result.append(kana);
            index += matchedLength;
        }
        return result.toString();
    }

    private static boolean isSokuon(@NotNull String source, int index) {
        if (index + 1 >= source.length()) {
            return false;
        }
        char current = source.charAt(index);
        char next = source.charAt(index + 1);
        return current == next && isAsciiLetter(current) && current != 'a' && current != 'i'
            && current != 'u' && current != 'e' && current != 'o' && current != 'n';
    }

    private static boolean isNasalN(@NotNull String source, int index) {
        if (index + 1 >= source.length()) {
            return true;
        }
        char next = source.charAt(index + 1);
        return !isAsciiLetter(next) || (next != 'a' && next != 'i' && next != 'u' && next != 'e'
            && next != 'o' && next != 'y');
    }

    private static boolean isAsciiLetter(char value) {
        return value >= 'a' && value <= 'z';
    }

    private static @NotNull Map<String, String> createSyllables() {
        Map<String, String> result = new LinkedHashMap<>();
        add(result, "xtsu", "っ"); add(result, "ltsu", "っ");
        add(result, "xya", "ゃ"); add(result, "xyu", "ゅ"); add(result, "xyo", "ょ");
        add(result, "lya", "ゃ"); add(result, "lyu", "ゅ"); add(result, "lyo", "ょ");
        add(result, "kya", "きゃ"); add(result, "kyu", "きゅ"); add(result, "kyo", "きょ");
        add(result, "gya", "ぎゃ"); add(result, "gyu", "ぎゅ"); add(result, "gyo", "ぎょ");
        add(result, "sha", "しゃ"); add(result, "shu", "しゅ"); add(result, "sho", "しょ");
        add(result, "sya", "しゃ"); add(result, "syu", "しゅ"); add(result, "syo", "しょ");
        add(result, "zya", "じゃ"); add(result, "zyu", "じゅ"); add(result, "zyo", "じょ");
        add(result, "jya", "じゃ"); add(result, "jyu", "じゅ"); add(result, "jyo", "じょ");
        add(result, "cha", "ちゃ"); add(result, "chu", "ちゅ"); add(result, "cho", "ちょ");
        add(result, "tya", "ちゃ"); add(result, "tyu", "ちゅ"); add(result, "tyo", "ちょ");
        add(result, "nya", "にゃ"); add(result, "nyu", "にゅ"); add(result, "nyo", "にょ");
        add(result, "hya", "ひゃ"); add(result, "hyu", "ひゅ"); add(result, "hyo", "ひょ");
        add(result, "bya", "びゃ"); add(result, "byu", "びゅ"); add(result, "byo", "びょ");
        add(result, "pya", "ぴゃ"); add(result, "pyu", "ぴゅ"); add(result, "pyo", "ぴょ");
        add(result, "mya", "みゃ"); add(result, "myu", "みゅ"); add(result, "myo", "みょ");
        add(result, "rya", "りゃ"); add(result, "ryu", "りゅ"); add(result, "ryo", "りょ");
        add(result, "dya", "ぢゃ"); add(result, "dyu", "ぢゅ"); add(result, "dyo", "ぢょ");
        add(result, "shi", "し"); add(result, "chi", "ち"); add(result, "tsu", "つ");
        add(result, "fu", "ふ"); add(result, "ji", "じ"); add(result, "zu", "ず");
        add(result, "ka", "か"); add(result, "ki", "き"); add(result, "ku", "く"); add(result, "ke", "け"); add(result, "ko", "こ");
        add(result, "ga", "が"); add(result, "gi", "ぎ"); add(result, "gu", "ぐ"); add(result, "ge", "げ"); add(result, "go", "ご");
        add(result, "sa", "さ"); add(result, "si", "し"); add(result, "su", "す"); add(result, "se", "せ"); add(result, "so", "そ");
        add(result, "za", "ざ"); add(result, "zi", "じ"); add(result, "ze", "ぜ"); add(result, "zo", "ぞ");
        add(result, "ta", "た"); add(result, "ti", "ち"); add(result, "tu", "つ"); add(result, "te", "て"); add(result, "to", "と");
        add(result, "da", "だ"); add(result, "di", "ぢ"); add(result, "du", "づ"); add(result, "de", "で"); add(result, "do", "ど");
        add(result, "na", "な"); add(result, "ni", "に"); add(result, "nu", "ぬ"); add(result, "ne", "ね"); add(result, "no", "の");
        add(result, "ha", "は"); add(result, "hi", "ひ"); add(result, "hu", "ふ"); add(result, "he", "へ"); add(result, "ho", "ほ");
        add(result, "ba", "ば"); add(result, "bi", "び"); add(result, "bu", "ぶ"); add(result, "be", "べ"); add(result, "bo", "ぼ");
        add(result, "pa", "ぱ"); add(result, "pi", "ぴ"); add(result, "pu", "ぷ"); add(result, "pe", "ぺ"); add(result, "po", "ぽ");
        add(result, "ma", "ま"); add(result, "mi", "み"); add(result, "mu", "む"); add(result, "me", "め"); add(result, "mo", "も");
        add(result, "ya", "や"); add(result, "yu", "ゆ"); add(result, "yo", "よ");
        add(result, "ra", "ら"); add(result, "ri", "り"); add(result, "ru", "る"); add(result, "re", "れ"); add(result, "ro", "ろ");
        add(result, "wa", "わ"); add(result, "wo", "を"); add(result, "wi", "うぃ"); add(result, "we", "うぇ");
        add(result, "a", "あ"); add(result, "i", "い"); add(result, "u", "う"); add(result, "e", "え"); add(result, "o", "お");
        return Collections.unmodifiableMap(result);
    }

    private static void add(@NotNull Map<String, String> target, @NotNull String romaji, @NotNull String kana) {
        target.put(romaji, kana);
    }
}
