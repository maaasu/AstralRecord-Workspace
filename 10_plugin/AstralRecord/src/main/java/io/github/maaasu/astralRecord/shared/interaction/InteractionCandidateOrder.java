package io.github.maaasu.astralRecord.shared.interaction;

/**
 * 同一 tier・同一命中距離になった候補の決定的な比較順です。
 * 距離が異なる場合は本値より hitDistance が優先されます。
 */
public final class InteractionCandidateOrder {
    public static final int BOSS_CONTROLLER = 10;
    public static final int BOSS_ENTRY = 10;
    public static final int DUNGEON_CONTROLLER = 10;
    public static final int DUNGEON_ENTRY = 10;
    public static final int WORLD_SPAWN_ACTION = 15;
    public static final int NPC = 20;
    public static final int SKILL_TREE = 30;
    public static final int WAYSTONE = 40;
    public static final int GATHERING = 50;
    public static final int MOB_SPAWNER = 60;
    public static final int GATHERING_SPAWNER = 70;
    public static final int VANILLA_INTERACTION = 90;
    public static final int MENU_SHORTCUT = 90;
    public static final int OPEN_ACTION_RING = 100;
    public static final int ATTACK_CONDITION_GUARD = 150;
    public static final int WEAPON_ACTION = 200;
    public static final int ITEM_VANILLA_GUARD = 220;
    public static final int VANILLA_COMBAT = 300;
    public static final int NEW_ACTION_RING = 400;
    public static final int RIGHT_CLICK_ITEM_VANILLA_GUARD = 450;
    public static final int PLAYER_CONTROL = 500;

    private InteractionCandidateOrder() {
    }
}
