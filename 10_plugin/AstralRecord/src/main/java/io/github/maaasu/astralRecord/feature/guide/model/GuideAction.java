package io.github.maaasu.astralRecord.feature.guide.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * ガイド詳細画面のクリック時に実行する型付きアクションです。
 *
 * @param type アクション種別
 * @param description クリック操作の表示説明
 * @param npcId NPC案内時の NPC マスタ ID
 * @param menuId メニュー起動時のメニュー ID
 */
public record GuideAction(
    @NotNull GuideActionType type,
    @NotNull String description,
    @Nullable String npcId,
    @Nullable String menuId
) {
    public GuideAction {
        Objects.requireNonNull(type, "type");
        description = description == null ? "" : description;
        npcId = normalize(npcId);
        menuId = normalize(menuId);
        switch (type) {
            case NAVIGATE_NPC -> {
                if (npcId == null) {
                    throw new IllegalArgumentException("npcId is required for NAVIGATE_NPC");
                }
            }
            case OPEN_MENU -> {
                if (menuId == null) {
                    throw new IllegalArgumentException("menuId is required for OPEN_MENU");
                }
            }
        }
    }

    private static @Nullable String normalize(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
