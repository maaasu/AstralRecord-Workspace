package io.github.maaasu.astralRecord.feature.menu.model;

import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.List;

/**
 * プレイヤー別の 2x2 クラフトスロットショートカット設定です。
 */
public final class MenuShortcutSettings {
    /** 2x2 クラフトスロット数。 */
    public static final int SLOT_COUNT = 4;

    private final MenuShortcutAction[] actions;

    /**
     * 指定されたショートカット配列から設定を生成します。
     *
     * @param actions ショートカット項目配列
     */
    public MenuShortcutSettings(@NotNull MenuShortcutAction[] actions) {
        this.actions = new MenuShortcutAction[SLOT_COUNT];
        for (int i = 0; i < SLOT_COUNT; i++) {
            this.actions[i] = i < actions.length && actions[i] != null
                ? actions[i]
                : MenuShortcutAction.defaultForSlot(i);
        }
    }

    /**
     * 初期ショートカット設定を生成します。
     *
     * @return 初期設定
     */
    public static @NotNull MenuShortcutSettings defaults() {
        MenuShortcutAction[] defaults = new MenuShortcutAction[SLOT_COUNT];
        for (int i = 0; i < SLOT_COUNT; i++) {
            defaults[i] = MenuShortcutAction.defaultForSlot(i);
        }
        return new MenuShortcutSettings(defaults);
    }

    /**
     * 指定スロットのショートカット項目を返します。
     *
     * @param slotIndex 0始まりのショートカットスロット番号
     * @return ショートカット項目
     */
    public @NotNull MenuShortcutAction getAction(int slotIndex) {
        if (slotIndex < 0 || slotIndex >= SLOT_COUNT) {
            return MenuShortcutAction.NONE;
        }
        return actions[slotIndex];
    }

    /**
     * 設定内容を List として返します。
     *
     * @return ショートカット項目一覧
     */
    public @NotNull List<MenuShortcutAction> asList() {
        return List.copyOf(Arrays.asList(actions));
    }

    /**
     * 指定スロットだけを差し替えた新しい設定を返します。
     *
     * @param slotIndex 0始まりのショートカットスロット番号
     * @param action 割り当てる項目
     * @return 更新後の設定
     */
    public @NotNull MenuShortcutSettings withAction(int slotIndex, @NotNull MenuShortcutAction action) {
        MenuShortcutAction[] copy = Arrays.copyOf(actions, actions.length);
        if (slotIndex >= 0 && slotIndex < SLOT_COUNT) {
            copy[slotIndex] = action;
        }
        return new MenuShortcutSettings(copy);
    }
}
