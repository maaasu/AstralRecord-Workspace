package io.github.maaasu.astralRecord.feature.menu.model;

/**
 * メニュー GUI の表示画面種別を表します。
 */
public enum MenuScreen {
    /** メインメニュー。 */
    MAIN,
    /** 表示するインベントリを選択する画面。 */
    INVENTORY_SELECTOR,
    /** 装備スロットを編集する画面。 */
    EQUIPMENT_GUI,
    /** 通貨を表示するページング画面。 */
    CURRENCY,
    /** ショートカットを設定するクラフトスロットを選択する画面。 */
    SHORTCUT_SLOT_SELECTOR,
    /** 選択したクラフトスロットへ割り当てる項目を選ぶ画面。 */
    SHORTCUT_ACTION_SELECTOR
}
