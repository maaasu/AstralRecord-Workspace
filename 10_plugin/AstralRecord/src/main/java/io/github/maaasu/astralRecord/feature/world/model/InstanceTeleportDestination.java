package io.github.maaasu.astralRecord.feature.world.model;

import io.github.maaasu.astralRecord.feature.dungeon.model.DungeonDefinition;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 読込済みマスタから構築するインスタンス受付の表示情報です。内部戦闘地点は保持しません。
 * @param key 種別とマスタ ID の組。ID は GUI に表示しない
 * @param displayName マスタの表示名
 * @param level ダンジョンの推奨レベル、またはボス自身のレベル
 * @param overview ボスマスタの説明文
 * @param roomCount ダンジョンの生成部屋数範囲。ボスでは null
 * @param bossDisplayName ダンジョン最深部ボスの表示名。ボスでは null
 * @param partyMin 最少参加人数
 * @param partyMax 最大参加人数
 * @param timeLimitSeconds 制限時間。制限なしの場合は null
 * @param deathLimit パーティー共有の復帰可能死亡回数
 * @param entryWorldId 受付ワールドのマスタ ID
 * @param x 受付 X 座標
 * @param y 受付 Y 座標
 * @param z 受付 Z 座標
 */
public record InstanceTeleportDestination(
        @NotNull Key key,
        @NotNull String displayName,
        int level,
        @NotNull List<String> overview,
        @Nullable DungeonDefinition.IntRange roomCount,
        @Nullable String bossDisplayName,
        int partyMin,
        int partyMax,
        @Nullable Long timeLimitSeconds,
        int deathLimit,
        @Nullable String entryWorldId,
        double x,
        double y,
        double z
) {
    /**
     * 表示用の説明文を変更不能なコピーとして保持します。
     * @param key 受付の識別情報
     * @param displayName マスタの表示名
     * @param level 表示レベル
     * @param overview 説明文
     * @param roomCount 部屋数範囲
     * @param bossDisplayName 最深部ボス表示名
     * @param partyMin 最少人数
     * @param partyMax 最大人数
     * @param timeLimitSeconds 制限秒数
     * @param deathLimit 復帰可能死亡回数
     * @param entryWorldId 受付ワールド ID
     * @param x 受付 X 座標
     * @param y 受付 Y 座標
     * @param z 受付 Z 座標
     */
    public InstanceTeleportDestination {
        overview = List.copyOf(overview);
    }

    /**
     * 受付の表示名・ワールド参照・座標が使用可能な形式かを返します。
     * @return 表示名とワールド参照があり、全座標が有限なら true
     */
    public boolean hasValidEntry() {
        return !displayName.isBlank() && entryWorldId != null && !entryWorldId.isBlank()
                && Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z);
    }

    /**
     * ボス ID とダンジョン ID が同名でも区別する受付キーです。
     * @param kind BOSS または DUNGEON
     * @param masterId マスタ ID。内部照合専用
     */
    public record Key(@NotNull InstanceTeleportFilter kind, @NotNull String masterId) {
    }
}
