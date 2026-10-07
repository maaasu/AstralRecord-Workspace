package io.github.maaasu.astralRecord.feature.builder.model;

import org.bukkit.Material;

/** GUIで選ぶ素材と数値。プレイヤーごとにメインスレッドでだけ使用します。 */
public final class BuilderSettings {
    public Material material = Material.STONE;
    public Material replaceFrom = Material.DIRT;
    public int radius = 3;
    public int height = 5;
    public int amount = 1;
    public Direction direction = Direction.NORTH;
    public boolean ignoreAir = true;

    /** 方角を固定して、確認後の視線変更による操作方向の変化を防ぎます。 */
    public enum Direction {
        NORTH("north", "北（Z−）"), EAST("east", "東（X＋）"), SOUTH("south", "南（Z＋）"),
        WEST("west", "西（X−）"), UP("up", "上"), DOWN("down", "下");
        public final String argument;
        public final String label;
        /** WorldEditの方角引数とプレイヤー向け表示を対応付けます。 */
        Direction(String argument, String label) { this.argument = argument; this.label = label; }
    }
}
