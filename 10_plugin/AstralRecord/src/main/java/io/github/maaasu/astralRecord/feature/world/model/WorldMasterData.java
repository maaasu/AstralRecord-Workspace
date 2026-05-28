package io.github.maaasu.astralRecord.feature.world.model;

import org.jetbrains.annotations.NotNull;

/**
 * API から取得した WorldMasterData です。
 *
 * @param schemaVersion スキーマバージョン
 * @param id ワールド定義 ID
 * @param displayName 表示名
 * @param worldType ワールド種別
 * @param baseWorldPath 複製元ワールドパス
 * @param instanceRootPath インスタンス生成先ルート
 * @param autoLoad 起動時自動ロード対象か
 * @param instanceEnabled インスタンス化対象か
 * @param maxPlayers 最大参加人数
 * @param allowBlockBreak ブロック破壊を許可するか
 * @param allowBlockPlace ブロック設置を許可するか
 * @param allowMobSpawn Mob スポーンを許可するか
 * @param description 説明
 */
public record WorldMasterData(
        int schemaVersion,
        @NotNull String id,
        @NotNull String displayName,
        @NotNull WorldType worldType,
        @NotNull String baseWorldPath,
        @NotNull String instanceRootPath,
        boolean autoLoad,
        boolean instanceEnabled,
        int maxPlayers,
        boolean allowBlockBreak,
        boolean allowBlockPlace,
        boolean allowMobSpawn,
        @NotNull String description
) {
}
