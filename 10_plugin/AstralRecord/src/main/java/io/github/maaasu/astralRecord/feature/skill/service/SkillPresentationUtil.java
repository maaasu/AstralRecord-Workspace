package io.github.maaasu.astralRecord.feature.skill.service;

import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.skill.model.ResolvedLearnedSkill;
import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.feature.status.model.StatusType;
import io.github.maaasu.astralRecord.infrastructure.util.ColorCodeUtil;
import io.github.maaasu.astralRecord.shared.masterdata.tag.MasterTagIds;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextReplacementConfig;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * スキル表示名の整形を共通化します。
 */
public final class SkillPresentationUtil {
    private static final TextColor PLACEHOLDER_COLOR = NamedTextColor.YELLOW;
    private static final Pattern PLACEHOLDER_TOKEN = Pattern.compile("\\uE000skill_placeholder_\\d+\\uE001");
    private static final Pattern SKILL_PLACEHOLDER = Pattern.compile(
        "\\{skill\\.([A-Za-z0-9_.-]+)(?:\\[(\\d+)\\])?(?::(integer|decimal|percent|seconds|list))?\\}"
    );
    private SkillPresentationUtil() {
    }

    /**
     * スキル名をプレーンテキストへ整形します。
     *
     * @param definition スキル定義
     * @param fallback   定義未解決時の表示名
     * @return 表示用スキル名
     */
    public static @NotNull String plainName(@Nullable SkillDefinition definition, @NotNull String fallback) {
        if (definition == null) {
            return fallback;
        }

        String name = definition.getName();
        if (name == null || name.isBlank()) {
            return fallback;
        }

        return ColorCodeUtil.toPlainText(name, fallback);
    }

    /**
     * スキル名を legacy color code 付きの表示用文字列へ整形します。
     *
     * @param definition スキル定義
     * @param fallback   定義未解決時の表示名
     * @return 表示用スキル名
     */
    public static @NotNull String legacyName(@Nullable SkillDefinition definition, @NotNull String fallback) {
        if (definition == null) {
            return fallback;
        }

        String name = definition.getName();
        if (name == null || name.isBlank()) {
            return fallback;
        }

        return ColorCodeUtil.toLegacyText(name, fallback);
    }

    /**
     * スキルマスターの名称を GUI 表示用 Component へ正規化します。
     *
     * @param definition    スキル定義
     * @param fallback      定義未解決時の表示名
     * @param fallbackColor カラーコード未指定時の色
     * @return カラーコードを反映した表示名
     */
    public static @NotNull Component skillNameComponent(
        @Nullable SkillDefinition definition,
        @NotNull String fallback,
        @Nullable TextColor fallbackColor
    ) {
        return masterTextComponent(definition == null ? null : definition.getName(), fallback, fallbackColor);
    }

    /**
     * アイテムマスターの名称を GUI 表示用 Component へ正規化します。
     *
     * @param item          アイテム定義
     * @param fallback      定義未解決時の表示名
     * @param fallbackColor カラーコード未指定時の色
     * @return カラーコードを反映した表示名
     */
    public static @NotNull Component itemNameComponent(
        @Nullable ItemModel item,
        @NotNull String fallback,
        @Nullable TextColor fallbackColor
    ) {
        return masterTextComponent(item == null ? null : item.getName(), fallback, fallbackColor);
    }

    /**
     * マスターデータの 1 行を GUI 表示用 Component へ正規化します。
     * GUI / View はマスター文字列を直接 Component 化せず、この API を経由します。
     *
     * @param text          マスター由来の表示文字列
     * @param fallback      未設定時の代替表示
     * @param fallbackColor カラーコード未指定時の色
     * @return カラーコードを反映した表示 Component
     */
    public static @NotNull Component masterTextComponent(
        @Nullable String text,
        @NotNull String fallback,
        @Nullable TextColor fallbackColor
    ) {
        return ColorCodeUtil.toComponent(text, fallback, fallbackColor);
    }

    /**
     * 習得個体を持たない画面向けに、基礎 params だけでスキル文を展開します。
     *
     * @param definition スキル定義
     * @param text 展開対象のマスターテキスト
     * @return プレースホルダー展開後のlegacy文字列
     */
    public static @NotNull String renderSkillTemplate(
        @Nullable SkillDefinition definition,
        @Nullable String text
    ) {
        return renderPlaceholders(text, definition == null ? Map.of() : definition.getParams());
    }

    /**
     * スキルマスターの説明と lore を GUI 用の行へ変換します。
     *
     * @param definition    スキル定義
     * @param fallbackColor カラーコード未指定時の色
     * @return 説明と lore の表示行
     */
    public static @NotNull List<Component> skillDescriptionAndLore(
        @Nullable SkillDefinition definition,
        @Nullable TextColor fallbackColor
    ) {
        if (definition == null) {
            return List.of();
        }
        List<Component> lines = new ArrayList<>();
        appendMasterLine(
            lines,
            renderPlaceholdersComponent(definition.getDescription(), definition.getParams(), fallbackColor)
        );
        for (String line : definition.getLore()) {
            appendMasterLine(lines, renderPlaceholdersComponent(line, definition.getParams(), fallbackColor));
        }
        return List.copyOf(lines);
    }

    /**
     * スキルの説明と効果文を表示用に変換します。
     * 消費リソースとクールダウンを一行へ詰めた旧 lore は、GUI 側の専用表示と重複するため除外します。
     */
    public static @NotNull List<Component> skillDescriptionAndFlavorLore(
        @Nullable SkillDefinition definition,
        @Nullable TextColor fallbackColor
    ) {
        return skillDescriptionAndFlavorLore(definition, Map.of(), fallbackColor);
    }

    /**
     * 習得レベル・シジル補正を反映したスキルの説明と効果文を表示用に変換します。
     *
     * @param resolved レベル・シジル補正済みのスキル
     * @param fallbackColor カラーコード未指定時の色
     * @return 補正済みプレースホルダーを展開した表示行
     */
    public static @NotNull List<Component> skillDescriptionAndFlavorLore(
        @Nullable ResolvedLearnedSkill resolved,
        @Nullable TextColor fallbackColor
    ) {
        if (resolved == null) return List.of();
        return skillDescriptionAndFlavorLore(
            resolved.definition(),
            descriptionValues(resolved),
            fallbackColor
        );
    }

    private static @NotNull List<Component> skillDescriptionAndFlavorLore(
        @Nullable SkillDefinition definition,
        @NotNull Map<String, Object> values,
        @Nullable TextColor fallbackColor
    ) {
        if (definition == null) {
            return List.of();
        }
        List<Component> lines = new ArrayList<>();
        appendMasterLine(
            lines,
            renderResolvedLineComponent(definition.getDescription(), values, fallbackColor)
        );
        for (String line : definition.getLore()) {
            String rendered = renderPlaceholders(line, values);
            String plain = ColorCodeUtil.toPlainText(rendered, "");
            if (plain.contains("消費") && plain.contains("クールダウン")) {
                continue;
            }
            appendMasterLine(lines, renderResolvedLineComponent(line, values, fallbackColor));
        }
        return List.copyOf(lines);
    }

    private static @NotNull Component renderResolvedLineComponent(
        @Nullable String text,
        @NotNull Map<String, Object> values,
        @Nullable TextColor fallbackColor
    ) {
        TokenizedText tokenized = tokenizePlaceholders(text, values);
        return renderTokenizedComponent(tokenized, fallbackColor);
    }

    /**
     * スキルの説明文に使用する解決済み表示値を作成します。
     * paramsの値を正本とし、レベル・シジル由来のスキルダメージ補正と、
     * 倍率を補正した表示用エイリアスを追加します。
     */
    private static @NotNull Map<String, Object> descriptionValues(
        @NotNull ResolvedLearnedSkill resolved
    ) {
        Map<String, Object> values = new HashMap<>();
        values.putAll(resolved.definition().getParams());
        values.put("level", resolved.learnedSkill().getLevel());
        values.put("maxLevel", resolved.definition().getMaxLevel());
        double damageIncrease = resolved.statusBonuses().getOrDefault(
            StatusType.SKILL_DAMAGE_INCREASE,
            0.0D
        );
        values.put("skillDamageIncrease", damageIncrease);
        values.put("damageIncrease", damageIncrease);

        Object damageRatio = values.get("damageRatio");
        if (damageRatio instanceof Number number) {
            values.put(
                "effectiveDamageRatio",
                number.doubleValue() * (1.0D + damageIncrease / 100.0D)
            );
        }
        Object damageRatios = values.get("damageRatios");
        if (damageRatios instanceof Collection<?> collection) {
            List<Double> effective = new ArrayList<>();
            for (Object value : collection) {
                if (value instanceof Number number) {
                    effective.add(number.doubleValue() * (1.0D + damageIncrease / 100.0D));
                }
            }
            values.put("effectiveDamageRatios", List.copyOf(effective));
            values.put("maxHits", effective.size());
            Object bounceCount = values.get("bounceCount");
            if (bounceCount instanceof Number number) {
                values.put(
                    "bounceCount",
                    Math.min(Math.max(0, number.intValue()), Math.max(0, effective.size() - 1))
                );
            }
        }
        return Map.copyOf(values);
    }

    /** プレースホルダーを解決済み表示値へ置換します。未知の値は安全に「?」へ置換します。 */
    private static @NotNull String renderPlaceholders(
        @Nullable String text,
        @NotNull Map<String, Object> values
    ) {
        return tokenizePlaceholders(text, values).resolve();
    }

    private static @NotNull Component renderPlaceholdersComponent(
        @Nullable String text,
        @NotNull Map<String, Object> values,
        @Nullable TextColor fallbackColor
    ) {
        return renderTokenizedComponent(tokenizePlaceholders(text, values), fallbackColor);
    }

    private static @NotNull Component renderTokenizedComponent(
        @NotNull TokenizedText tokenized,
        @Nullable TextColor fallbackColor
    ) {
        if (tokenized.template().isBlank()) {
            return Component.empty();
        }
        Component component = masterTextComponent(tokenized.template(), "", fallbackColor);
        if (tokenized.replacements().isEmpty()) {
            return component;
        }
        return component.replaceText(TextReplacementConfig.builder()
            .match(PLACEHOLDER_TOKEN)
            .replacement((match, ignored) -> Component.text(
                tokenized.replacements().getOrDefault(match.group(), "?"),
                PLACEHOLDER_COLOR
            ))
            .build());
    }

    private static @NotNull TokenizedText tokenizePlaceholders(
        @Nullable String text,
        @NotNull Map<String, Object> values
    ) {
        if (text == null || text.isBlank()) {
            return new TokenizedText(text == null ? "" : text, Map.of());
        }
        Matcher matcher = SKILL_PLACEHOLDER.matcher(text);
        StringBuffer result = new StringBuffer();
        Map<String, String> replacements = new LinkedHashMap<>();
        int tokenIndex = 0;
        while (matcher.find()) {
            Object value = values.get(matcher.group(1));
            Integer index = matcher.group(2) == null ? null : Integer.valueOf(matcher.group(2));
            String format = matcher.group(3);
            String token = "\uE000skill_placeholder_" + tokenIndex++ + "\uE001";
            replacements.put(token, formatValue(value, index, format));
            matcher.appendReplacement(result, Matcher.quoteReplacement(token));
        }
        matcher.appendTail(result);
        return new TokenizedText(result.toString(), Map.copyOf(replacements));
    }

    private static @NotNull String formatValue(
        @Nullable Object value,
        @Nullable Integer index,
        @Nullable String format
    ) {
        if (value == null) return "?";
        if (index != null) {
            if (!(value instanceof List<?> list) || index < 0 || index >= list.size()) return "?";
            return formatScalar(list.get(index), format);
        }
        if (value instanceof Collection<?> collection) {
            String itemFormat = "percent".equals(format) ? "percent" : "decimal";
            return collection.stream()
                .map(item -> formatScalar(item, itemFormat))
                .collect(java.util.stream.Collectors.joining(" / "));
        }
        return formatScalar(value, format);
    }

    private static @NotNull String formatScalar(@Nullable Object value, @Nullable String format) {
        if (value instanceof Boolean || value instanceof String) return String.valueOf(value);
        if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue())) return "?";
        double numeric = number.doubleValue();
        if ("percent".equals(format)) numeric *= 100.0D;
        if ("seconds".equals(format)) numeric /= 20.0D;
        if ("integer".equals(format)) {
            return BigDecimal.valueOf(numeric).setScale(0, RoundingMode.HALF_UP).toPlainString();
        }
        return BigDecimal.valueOf(numeric)
            .setScale(1, RoundingMode.HALF_UP)
            .stripTrailingZeros()
            .toPlainString();
    }

    /**
     * スキルタグをプレイヤー向けの日本語名へ変換します。
     *
     * @param definition スキル定義
     * @return 区切り付きの日本語タグ名。タグ未設定時は空文字列
     */
    public static @NotNull String skillTagDisplayNames(@Nullable SkillDefinition definition) {
        return skillTagDisplayNames(definition, Set.of());
    }

    /** スキルタグを日本語表示へ変換し、種別と重複するタグなどを除外します。 */
    public static @NotNull String skillTagDisplayNames(
        @Nullable SkillDefinition definition,
        @NotNull Set<String> excludedTagIds
    ) {
        if (definition == null || definition.getTags().isEmpty()) {
            return "";
        }
        return definition.getTags().stream()
            .filter(tag -> excludedTagIds.stream().noneMatch(excluded -> excluded.equalsIgnoreCase(tag)))
            .map(SkillPresentationUtil::skillTagDisplayName)
            .filter(tag -> !tag.isBlank())
            .distinct()
            .collect(java.util.stream.Collectors.joining(" / "));
    }

    /**
     * スキルタグIDをプレイヤー向け日本語名へ変換します。
     *
     * @param tagId スキルタグID
     * @return 日本語表示名。カタログ未登録のタグは「その他」
     */
    public static @NotNull String skillTagDisplayName(@Nullable String tagId) {
        if (tagId == null || tagId.isBlank()) {
            return "";
        }
        String normalizedTagId = tagId.trim();
        MasterTagIds.Definition definition = MasterTagIds.find(normalizedTagId);
        if (definition == null) {
            definition = MasterTagIds.find(normalizedTagId.toLowerCase(Locale.ROOT));
        }
        return definition != null && definition.appliesTo().contains("SKILL")
            ? definition.displayName()
            : "その他";
    }

    /**
     * アイテムマスターの lore を GUI 用の行へ変換します。
     *
     * @param item          アイテム定義
     * @param fallbackColor カラーコード未指定時の色
     * @return lore の表示行
     */
    public static @NotNull List<Component> itemLoreComponents(
        @Nullable ItemModel item,
        @Nullable TextColor fallbackColor
    ) {
        if (item == null) {
            return List.of();
        }
        List<Component> lines = new ArrayList<>();
        for (String line : item.getLore()) {
            appendMasterLine(lines, line, fallbackColor);
        }
        return List.copyOf(lines);
    }

    private static void appendMasterLine(
        @NotNull List<Component> lines,
        @Nullable String text,
        @Nullable TextColor fallbackColor
    ) {
        if (text == null || text.isBlank()) {
            return;
        }
        lines.add(masterTextComponent(text, "", fallbackColor));
    }

    private static void appendMasterLine(
        @NotNull List<Component> lines,
        @NotNull Component component
    ) {
        if (!component.equals(Component.empty())) {
            lines.add(component);
        }
    }

    private record TokenizedText(
        @NotNull String template,
        @NotNull Map<String, String> replacements
    ) {
        private @NotNull String resolve() {
            Matcher matcher = PLACEHOLDER_TOKEN.matcher(template);
            StringBuffer resolved = new StringBuffer();
            while (matcher.find()) {
                matcher.appendReplacement(
                    resolved,
                    Matcher.quoteReplacement(replacements.getOrDefault(matcher.group(), "?"))
                );
            }
            matcher.appendTail(resolved);
            return resolved.toString();
        }
    }
}
