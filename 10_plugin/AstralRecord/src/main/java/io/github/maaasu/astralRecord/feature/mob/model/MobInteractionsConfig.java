package io.github.maaasu.astralRecord.feature.mob.model;

import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * NPC のクリックインタラクション設定です。
 *
 * @param leftClick  左クリック時に実行するアクション一覧
 * @param rightClick 右クリック時に実行するアクション一覧
 */
public record MobInteractionsConfig(
        @NotNull List<MobInteractionActionConfig> leftClick,
        @NotNull List<MobInteractionActionConfig> rightClick
) {
    public static final MobInteractionsConfig EMPTY = new MobInteractionsConfig(List.of(), List.of());

    public MobInteractionsConfig {
        leftClick = leftClick == null ? List.of() : List.copyOf(leftClick);
        rightClick = rightClick == null ? List.of() : List.copyOf(rightClick);
    }
}
