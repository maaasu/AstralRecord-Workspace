package io.github.maaasu.astralRecord.feature.menu.model;

public enum MenuScreen {
    MAIN,
    BUFF,
    CLASS,
    EQUIPMENT_GUI,
    CURRENCY,
    GUIDE,
    ITEM_PROTECTION,
    ITEM_PROTECTION_ENABLE,
    ITEM_PROTECTION_DISABLE,
    TRASH,
    TRASH_CONFIRM,
    SELL,
    SELL_CONFIRM,
    STORAGE;

    /**
     * アイテム保護の一覧・確認画面を識別します。
     *
     * @return アイテム保護の画面なら true
     */
    public boolean isItemProtection() {
        return this == ITEM_PROTECTION || this == ITEM_PROTECTION_ENABLE || this == ITEM_PROTECTION_DISABLE;
    }
}
