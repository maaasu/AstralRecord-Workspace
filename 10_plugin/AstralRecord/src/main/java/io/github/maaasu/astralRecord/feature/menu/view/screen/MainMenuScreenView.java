package io.github.maaasu.astralRecord.feature.menu.view.screen;

import io.github.maaasu.astralRecord.feature.menu.model.MenuIconDefinition;
import io.github.maaasu.astralRecord.feature.menu.model.PlayerGuiRenderContext;
import io.github.maaasu.astralRecord.feature.menu.view.MenuIconFactory;
import io.github.maaasu.astralRecord.shared.gui.GuiItems;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.jetbrains.annotations.NotNull;

public final class MainMenuScreenView extends BaseMenuScreenView {
    public static final int STATUS_SLOT = 20;
    public static final int EQUIPMENT_GUI_SLOT = 21;
    public static final int SKILL_BIND_SLOT = 22;
    public static final int QUEST_SLOT = 23;
    public static final int PLAYER_SETTING_SLOT = 24;
    public static final int ADVENTURE_RECORD_SLOT = 29;
    public static final int MAIL_SLOT = 30;
    public static final int PARTY_SLOT = 31;
    public static final int PLAYER_INFO_SLOT = 32;
    public static final int CURRENCY_SLOT = 38;
    public static final int GUIDE_SLOT = 39;
    public static final int RETURN_TO_BASE_SLOT = 40;
    public static final int TRASH_SLOT = 41;

    /**
     * メインメニューを描画します。
     *
     * @param inventory 描画先インベントリ
     * @param player スキンを表示する対象プレイヤー
     * @param context プレイヤー依存の GUI 描画コンテキスト
     */
    public void render(
        @NotNull Inventory inventory,
        @NotNull Player player,
        @NotNull PlayerGuiRenderContext context
    ) {
        fill(inventory);
        inventory.setItem(BACK_SLOT, GuiItems.closeButton());
        inventory.setItem(STATUS_SLOT, MenuIconFactory.createPlayerInfo(player));
        inventory.setItem(QUEST_SLOT, MenuIconFactory.create(MenuIconDefinition.QUEST));
        inventory.setItem(PLAYER_SETTING_SLOT, MenuIconFactory.create(MenuIconDefinition.PLAYER_SETTING));
        inventory.setItem(EQUIPMENT_GUI_SLOT, MenuIconFactory.create(
            MenuIconDefinition.EQUIPMENT,
            MenuIconFactory.equipmentDetails(context)
        ));
        inventory.setItem(TRASH_SLOT, MenuIconFactory.create(MenuIconDefinition.TRASH));
        inventory.setItem(GUIDE_SLOT, MenuIconFactory.create(MenuIconDefinition.GUIDE));
        inventory.setItem(RETURN_TO_BASE_SLOT, MenuIconFactory.create(
            MenuIconDefinition.RETURN_TO_BASE,
            MenuIconFactory.returnToBaseDetails(context)
        ));
        inventory.setItem(ADVENTURE_RECORD_SLOT, MenuIconFactory.create(MenuIconDefinition.ADVENTURE_RECORD));
        inventory.setItem(MAIL_SLOT, MenuIconFactory.create(MenuIconDefinition.MAIL));
        inventory.setItem(SKILL_BIND_SLOT, MenuIconFactory.create(MenuIconDefinition.SKILL_BIND));
        inventory.setItem(CURRENCY_SLOT, MenuIconFactory.create(
            MenuIconDefinition.CURRENCY,
            MenuIconFactory.currencyDetails(context)
        ));
        inventory.setItem(PARTY_SLOT, MenuIconFactory.create(MenuIconDefinition.PARTY));
        inventory.setItem(PLAYER_INFO_SLOT, MenuIconFactory.create(MenuIconDefinition.PLAYER_LIST));
    }
}
