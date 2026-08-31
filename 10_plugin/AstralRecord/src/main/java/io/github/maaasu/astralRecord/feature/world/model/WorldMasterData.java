package io.github.maaasu.astralRecord.feature.world.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

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
 * @param showSpawnParticle スポーン地点演出の表示有無
 * @param spawnLocation ワールド固有のスポーン地点
 * @param description 説明
 * @param guiIconMaterial オーバーワールド転送 GUI のアイコン Material 名
 * @param adventureGuide オーバーワールド転送 GUI の冒険ガイド
 * @param overworldTeleportGui オーバーワールド転送 GUI の配置設定
 * @param requiredItemId オーバーワールド転送に必要な CURRENCY item ID。未設定なら制限なし
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
        boolean showSpawnParticle,
        @NotNull WorldSpawnLocation spawnLocation,
        @NotNull String description,
        @Nullable String guiIconMaterial,
        @Nullable WorldAdventureGuide adventureGuide,
        @Nullable OverworldTeleportGuiSetting overworldTeleportGui,
        @Nullable String requiredItemId
) {

    /**
     * 入場アイテム項目が導入される前の WorldMasterData 生成形式を維持します。
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
     * @param showSpawnParticle スポーン地点演出の表示有無
     * @param spawnLocation ワールド固有のスポーン地点
     * @param description 説明
     * @param guiIconMaterial オーバーワールド転送 GUI のアイコン Material 名
     * @param adventureGuide オーバーワールド転送 GUI の冒険ガイド
     * @param overworldTeleportGui オーバーワールド転送 GUI の配置設定
     */
    public WorldMasterData(
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
            boolean showSpawnParticle,
            @NotNull WorldSpawnLocation spawnLocation,
            @NotNull String description,
            @Nullable String guiIconMaterial,
            @Nullable WorldAdventureGuide adventureGuide,
            @Nullable OverworldTeleportGuiSetting overworldTeleportGui
    ) {
        this(
                schemaVersion,
                id,
                displayName,
                worldType,
                baseWorldPath,
                instanceRootPath,
                autoLoad,
                instanceEnabled,
                maxPlayers,
                allowBlockBreak,
                allowBlockPlace,
                allowMobSpawn,
                showSpawnParticle,
                spawnLocation,
                description,
                guiIconMaterial,
                adventureGuide,
                overworldTeleportGui,
                null
        );
    }
}
