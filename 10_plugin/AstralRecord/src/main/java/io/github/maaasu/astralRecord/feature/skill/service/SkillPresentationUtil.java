package io.github.maaasu.astralRecord.feature.skill.service;

import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.infrastructure.util.ColorCodeUtil;
import io.github.maaasu.astralRecord.shared.masterdata.tag.MasterTagIds;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * スキル表示名の整形を共通化します。
 */
public final class SkillPresentationUtil {
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
        appendMasterLine(lines, definition.getDescription(), fallbackColor);
        for (String line : definition.getLore()) {
            appendMasterLine(lines, line, fallbackColor);
        }
        return List.copyOf(lines);
    }

    /**
     * スキルタグをプレイヤー向けの日本語名へ変換します。
     *
     * @param definition スキル定義
     * @return 区切り付きの日本語タグ名。タグ未設定時は空文字列
     */
    public static @NotNull String skillTagDisplayNames(@Nullable SkillDefinition definition) {
        if (definition == null || definition.getTags().isEmpty()) {
            return "";
        }
        return definition.getTags().stream()
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
}
