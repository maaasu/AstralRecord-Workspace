package io.github.maaasu.astralRecord.feature.world.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * WorldMasterData のワールド種別です。
 */
public enum WorldType {
    /** ダンジョンやボス挑戦前の待機所、マイスペース。 */
    HUB("ハブ"),
    /** NPC や他プレイヤーと交流する拠点。 */
    BASE("拠点"),
    /** 敵が湧く通常探索ワールド。 */
    OVERWORLD("オーバーワールド"),
    /** 特定の敵が特定数湧くダンジョン。 */
    DUNGEON("ダンジョン"),
    /** 特定ボスが湧くボス戦フィールド。 */
    BOSS_FIELD("ボスフィールド");

    private final String regionDisplayName;

    /**
     * ワールド種別へ地域既定表示名を関連付けます。
     *
     * @param regionDisplayName 地域既定表示名
     */
    WorldType(@NotNull String regionDisplayName) {
        this.regionDisplayName = regionDisplayName;
    }

    /**
     * このワールド種別を地域として扱う場合の表示名を返します。
     *
     * @return 地域表示名
     */
    @NotNull
    public String getRegionDisplayName() {
        return regionDisplayName;
    }

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
