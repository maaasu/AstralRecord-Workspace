package io.github.maaasu.astralRecord.feature.world.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * WorldMasterData のワールド種別です。
 */
public enum WorldType {
    /** ダンジョンやボス挑戦前の待機所、マイスペース。 */
    HUB,
    /** NPC や他プレイヤーと交流する拠点。 */
    BASE,
    /** 敵が湧く通常探索ワールド。 */
    OVERWORLD,
    /** 特定の敵が特定数湧くダンジョン。 */
    DUNGEON,
    /** 特定ボスが湧くボス戦フィールド。 */
    BOSS_FIELD;

    /**
     * 文字列から WorldType を解決します。
     *
     * @param raw 入力文字列
     * @return 解決できた WorldType。不正な場合は {@link #BASE}
     */
    @NotNull
    public static WorldType from(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return BASE;
        }

        try {
            return WorldType.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ignored) {
            return BASE;
        }
    }
}
