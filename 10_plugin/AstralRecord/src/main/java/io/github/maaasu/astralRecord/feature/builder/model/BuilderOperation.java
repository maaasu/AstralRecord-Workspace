package io.github.maaasu.astralRecord.feature.builder.model;

import org.bukkit.Material;

/** GUIから利用できる操作だけを列挙し、自由入力のコマンド実行を許可しません。 */
public enum BuilderOperation {
    SET("塗りつぶす", Material.BRICKS, "worldedit.region.set", true, false, true),
    REPLACE("素材を置き換える", Material.COPPER_BLOCK, "worldedit.region.replace", true, false, true),
    WALLS("四方の壁を作る", Material.STONE_BRICKS, "worldedit.region.walls", true, false, true),
    FACES("箱の外側を作る", Material.GLASS, "worldedit.region.faces", true, false, true),
    DELETE("範囲内を消す", Material.BARRIER, "worldedit.region.set", true, false, true),
    COPY("建築をコピー", Material.PAPER, "worldedit.clipboard.copy", true, false, false),
    PASTE("コピーを貼り付け", Material.SLIME_BLOCK, "worldedit.clipboard.paste", false, true, true),
    ROTATE_RIGHT("コピーを右へ90度回転", Material.COMPASS, "worldedit.clipboard.rotate", false, true, false),
    ROTATE_LEFT("コピーを左へ90度回転", Material.COMPASS, "worldedit.clipboard.rotate", false, true, false),
    FLIP_EAST("コピーを東西反転", Material.IRON_TRAPDOOR, "worldedit.clipboard.flip", false, true, false),
    FLIP_UP("コピーを上下反転", Material.OAK_TRAPDOOR, "worldedit.clipboard.flip", false, true, false),
    UNDO("直前の編集を取り消す", Material.CLOCK, "worldedit.history.undo", false, false, true),
    REDO("取り消した編集をやり直す", Material.RECOVERY_COMPASS, "worldedit.history.redo", false, false, true),
    STACK("建築を繰り返し並べる", Material.BOOKSHELF, "worldedit.region.stack", true, false, true),
    MOVE("建築を移動する", Material.PISTON, "worldedit.region.move", true, false, true),
    SPHERE("球を作る", Material.SNOW_BLOCK, "worldedit.generation.sphere", false, false, true),
    HOLLOW_SPHERE("中が空の球を作る", Material.WHITE_STAINED_GLASS, "worldedit.generation.sphere", false, false, true),
    CYLINDER("円柱を作る", Material.QUARTZ_PILLAR, "worldedit.generation.cylinder", false, false, true),
    HOLLOW_CYLINDER("中が空の円柱を作る", Material.CHISELED_QUARTZ_BLOCK, "worldedit.generation.cylinder", false, false, true),
    SMOOTH("地面の起伏をなだらかに", Material.GRASS_BLOCK, "worldedit.region.smooth", true, false, true);

    public final String label;
    public final Material icon;
    public final String permission;
    public final boolean selectionRequired;
    public final boolean clipboardRequired;
    public final boolean changesWorld;

    /** 表示名・アイコン・個別権限と操作の前提条件を固定します。 */
    BuilderOperation(String label, Material icon, String permission, boolean selectionRequired,
                     boolean clipboardRequired, boolean changesWorld) {
        this.label = label;
        this.icon = icon;
        this.permission = permission;
        this.selectionRequired = selectionRequired;
        this.clipboardRequired = clipboardRequired;
        this.changesWorld = changesWorld;
    }
}
