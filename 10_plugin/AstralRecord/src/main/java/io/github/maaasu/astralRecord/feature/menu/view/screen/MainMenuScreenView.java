package io.github.maaasu.astralRecord.feature.menu.view.screen;

import io.github.maaasu.astralRecord.feature.menu.model.MenuIconDefinition;
import io.github.maaasu.astralRecord.feature.menu.model.PlayerGuiRenderContext;
import io.github.maaasu.astralRecord.feature.menu.view.MenuIconFactory;
import io.github.maaasu.astralRecord.shared.gui.GuiItems;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.jetbrains.annotations.NotNull;

public final class MainMenuScreenView extends BaseMenuScreenView {
    public static final int STATUS_SLOT = 10;
    public static final int EQUIPMENT_GUI_SLOT = 11;
    public static final int SKILL_BIND_SLOT = 12;
    public static final int CURRENCY_SLOT = 13;
    public static final int PLAYER_SETTING_SLOT = 14;
    public static final int ADVENTURE_RECORD_SLOT = 20;
    public static final int GUIDE_SLOT = 21;
    public static final int QUEST_SLOT = 22;
    public static final int PARTY_SLOT = 30;
    public static final int PLAYER_INFO_SLOT = 31;
    public static final int MAIL_SLOT = 38;
    public static final int RETURN_TO_BASE_SLOT = 39;
    public static final int TRASH_SLOT = 40;

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
        inventory.setItem(EQUIPMENT_GUI_SLOT, MenuIconFactory.create(
            MenuIconDefinition.EQUIPMENT,
            MenuIconFactory.equipmentDetails(context)
        ));
        inventory.setItem(SKILL_BIND_SLOT, MenuIconFactory.create(MenuIconDefinition.SKILL_BIND));
        inventory.setItem(CURRENCY_SLOT, MenuIconFactory.create(
            MenuIconDefinition.CURRENCY,
            MenuIconFactory.currencyDetails(context)
        ));
        inventory.setItem(PLAYER_SETTING_SLOT, MenuIconFactory.create(MenuIconDefinition.PLAYER_SETTING));
        inventory.setItem(ADVENTURE_RECORD_SLOT, MenuIconFactory.create(MenuIconDefinition.ADVENTURE_RECORD));
        inventory.setItem(GUIDE_SLOT, MenuIconFactory.create(MenuIconDefinition.GUIDE));
        inventory.setItem(QUEST_SLOT, MenuIconFactory.create(MenuIconDefinition.QUEST));
        inventory.setItem(PARTY_SLOT, MenuIconFactory.create(MenuIconDefinition.PARTY));
        inventory.setItem(PLAYER_INFO_SLOT, MenuIconFactory.create(MenuIconDefinition.PLAYER_LIST));
        inventory.setItem(MAIL_SLOT, MenuIconFactory.create(MenuIconDefinition.MAIL));
        inventory.setItem(RETURN_TO_BASE_SLOT, MenuIconFactory.create(
            MenuIconDefinition.RETURN_TO_BASE,
            MenuIconFactory.returnToBaseDetails(context)
        ));
        inventory.setItem(TRASH_SLOT, MenuIconFactory.create(MenuIconDefinition.TRASH));
    }
}
