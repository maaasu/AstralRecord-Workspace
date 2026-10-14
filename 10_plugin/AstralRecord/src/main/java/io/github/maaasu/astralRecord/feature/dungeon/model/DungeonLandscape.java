package io.github.maaasu.astralRecord.feature.dungeon.model;

/** ダンジョンの屋内・屋外生成方式です。 */
public enum DungeonLandscape {
    INDOOR,
    PLAINS,
    FOREST_POND;

    /**
     * 空が見える屋外地形として扱うかを返します。
     *
     * @return 草原または森林の池の場合は true
     */
    public boolean isOutdoor() {
        return this != INDOOR;
    }
}
