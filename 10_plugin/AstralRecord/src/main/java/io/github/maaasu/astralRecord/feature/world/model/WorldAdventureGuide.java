package io.github.maaasu.astralRecord.feature.world.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 冒険先ワールド選択 GUI に表示する補助情報です。
 *
 * @param recommendedLevelMin 推奨レベル下限
 * @param recommendedLevelMax 推奨レベル上限
 * @param recommendedPartySizeMin 推奨人数下限
 * @param recommendedPartySizeMax 推奨人数上限
 * @param notes 補足メモ
 */
public record WorldAdventureGuide(
        @Nullable Integer recommendedLevelMin,
        @Nullable Integer recommendedLevelMax,
        @Nullable Integer recommendedPartySizeMin,
        @Nullable Integer recommendedPartySizeMax,
        @NotNull List<String> notes
) {
    /**
     * 不要な空要素を除外し、不変リスト化します。
     */
    public WorldAdventureGuide {
        notes = notes == null
                ? List.of()
                : notes.stream()
                .filter(note -> note != null && !note.isBlank())
                .map(String::trim)
                .toList();
    }

    /**
     * 推奨レベル表示があるかを返します。
     *
     * @return 推奨レベルを表示できる場合は {@code true}
     */
    public boolean hasRecommendedLevel() {
        return recommendedLevelMin != null || recommendedLevelMax != null;
    }

    /**
     * 推奨人数表示があるかを返します。
     *
     * @return 推奨人数を表示できる場合は {@code true}
     */
    public boolean hasRecommendedPartySize() {
        return recommendedPartySizeMin != null || recommendedPartySizeMax != null;
    }
}
