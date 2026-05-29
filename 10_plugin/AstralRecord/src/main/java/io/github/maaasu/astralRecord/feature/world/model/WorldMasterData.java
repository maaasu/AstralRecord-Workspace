package io.github.maaasu.astralRecord.feature.world.model;

import org.jetbrains.annotations.NotNull;

/**
 * API から取得する WorldMasterData です。
 *
 * @param schemaVersion スキーマバージョン
 * @param id ワールド定義 ID
 * @param displayName 表示名
 * @param worldType ワールド種別
 * @param baseWorldPath 複製元ワールドパス
 * @param instanceRootPath インスタンス生成先ルート
 * @param autoLoad 自動ロード対象
 * @param instanceEnabled インスタンス対応フラグ
 * @param maxPlayers 最大プレイヤー数
 * @param allowBlockBreak ブロック破壊許可
 * @param allowBlockPlace ブロック設置許可
 * @param allowMobSpawn Mob 自然スポーン許可
 * @param spawnLocation ワールド固有のスポーン地点
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
        @NotNull WorldSpawnLocation spawnLocation,
        @NotNull String description
) {
}
