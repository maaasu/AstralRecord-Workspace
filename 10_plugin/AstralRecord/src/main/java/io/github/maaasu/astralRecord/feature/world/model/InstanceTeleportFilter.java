package io.github.maaasu.astralRecord.feature.world.model;

import org.bukkit.Material;
import org.jetbrains.annotations.NotNull;

/** インスタンス受付一覧の表示種別です。ALL は種別による絞り込みを解除します。 */
public enum InstanceTeleportFilter {
    ALL("すべて", Material.BARRIER),
    BOSS("ボス", Material.NETHER_STAR),
    DUNGEON("ダンジョン", Material.MOSSY_STONE_BRICKS);

    private final String displayName;
    private final Material icon;

    /**
     * 種別の表示情報を初期化します。
     * @param displayName プレイヤー向け表示名
     * @param icon 候補一覧のアイコン
     */
    InstanceTeleportFilter(@NotNull String displayName, @NotNull Material icon) {
        this.displayName = displayName;
        this.icon = icon;
    }

    /** @return プレイヤー向け種別名 */
    public @NotNull String displayName() {
        return displayName;
    }

    /** @return 種別を表すアイコン */
    public @NotNull Material icon() {
        return icon;
    }

    /**
     * 候補が現在の種別フィルターに一致するかを返します。
     * @param kind 候補の種別。BOSS または DUNGEON
     * @return 全件表示または同じ種別なら true
     */
    public boolean includes(@NotNull InstanceTeleportFilter kind) {
        return this == ALL || this == kind;
    }
}
