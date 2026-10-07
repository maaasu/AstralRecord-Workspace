package io.github.maaasu.astralRecord.feature.builder.model;

/** 範囲選択と情報表示に使う固定コマンド。ワールドのブロックは変更しません。 */
public enum BuilderSelectionAction {
    CUBOID("/sel cuboid", null), WAND("/wand", "worldedit.wand"),
    POS1("/pos1", "worldedit.selection.pos"), POS2("/pos2", "worldedit.selection.pos"),
    HPOS1("/hpos1", "worldedit.selection.hpos"), HPOS2("/hpos2", "worldedit.selection.hpos"),
    SIZE("/size", "worldedit.selection.size"), DRAW("/drawsel", "worldedit.drawsel"),
    EXPAND("/expand", "worldedit.selection.expand"), CONTRACT("/contract", "worldedit.selection.contract");
    public final String command;
    public final String permission;
    /** 固定コマンドと必要権限を保持します。null権限はWorldEdit側の判定に委ねます。 */
    BuilderSelectionAction(String command, String permission) { this.command = command; this.permission = permission; }
}
