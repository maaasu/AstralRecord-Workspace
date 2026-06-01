package io.github.maaasu.astralRecord.feature.adventurerecord.model;

import io.github.maaasu.astralRecord.feature.mob.model.MobCategory;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 冒険記録の Mob 一覧種別です。
 */
public enum AdventureRecordListType {
    ENEMY("魔物録", MobCategory.ENEMY),
    BOSS("厄災録", MobCategory.BOSS),
    SEARCH("モブ検索結果", null);

    private final String title;
    private final MobCategory category;

    AdventureRecordListType(@NotNull String title, @Nullable MobCategory category) {
        this.title = title;
        this.category = category;
    }

    public @NotNull String getTitle() {
        return title;
    }

    public @Nullable MobCategory getCategory() {
        return category;
    }
}
