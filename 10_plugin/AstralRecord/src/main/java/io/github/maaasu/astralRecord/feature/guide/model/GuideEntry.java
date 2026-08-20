package io.github.maaasu.astralRecord.feature.guide.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * ゲーム内ガイドのマスター定義です。
 *
 * @param schemaVersion スキーマバージョン
 * @param id ガイド ID
 * @param category ガイド分類
 * @param displayOrder 同一分類内の表示順
 * @param title 表示タイトル
 * @param iconMaterial GUI アイコン Material 名
 * @param summary 一覧用の短い説明
 * @param steps 表示順に並んだ達成手順。達成判定は各 step の条件を個別に評価する
 */
public record GuideEntry(
    int schemaVersion,
    @NotNull String id,
    @NotNull String category,
    int displayOrder,
    @NotNull String title,
    @Nullable String iconMaterial,
    @Nullable String summary,
    @NotNull List<GuideStep> steps
) {
    public GuideEntry {
        steps = List.copyOf(steps);
    }
}
