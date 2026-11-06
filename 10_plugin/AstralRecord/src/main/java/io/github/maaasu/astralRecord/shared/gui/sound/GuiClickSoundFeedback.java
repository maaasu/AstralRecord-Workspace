package io.github.maaasu.astralRecord.shared.gui.sound;

import io.github.maaasu.astralRecord.shared.gui.GuiResourcePackIcons;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/** 装飾枠クリックの操作音を、共通クリックの開始から終了までの間だけ調整します。 */
public final class GuiClickSoundFeedback {
    private static final ThreadLocal<DummyClick> DUMMY_CLICK = new ThreadLocal<>();

    private GuiClickSoundFeedback() {
    }

    /**
     * 通常の左・右クリックが上段GUIの装飾枠を対象とするときだけ記録します。
     *
     * @param event クールタイムを通過したメインスレッド上のクリック
     * @param player 操作したプレイヤー
     */
    public static void begin(@NotNull InventoryClickEvent event, @NotNull Player player) {
        if (DUMMY_CLICK.get() == null && event.getClickedInventory() == event.getView().getTopInventory()
                && (event.getClick() == ClickType.LEFT || event.getClick() == ClickType.RIGHT)
                && GuiResourcePackIcons.isDecorativePane(event.getCurrentItem())) {
            DUMMY_CLICK.set(new DummyClick(event, player.getUniqueId()));
        }
    }

    /**
     * 装飾枠への拒否音を専用音へ置き換えます。操作側が同じ拒否音を再度要求しても重ねません。
     *
     * @param player 再生対象
     * @param cue 操作側が選んだ音
     * @return 再生する音。すでに装飾枠音を再生した場合は null
     */
    static @Nullable GuiSound resolve(@NotNull Player player, @NotNull GuiSound cue) {
        DummyClick click = DUMMY_CLICK.get();
        if (click == null || !click.playerId.equals(player.getUniqueId())) {
            return cue;
        }
        if (cue == GuiSound.DENY && !click.played) {
            click.played = true;
            click.playedDummy = true;
            return GuiSound.DUMMY;
        }
        click.played = true;
        return cue == GuiSound.DENY && click.playedDummy ? null : cue;
    }

    /**
     * クリック結果を変更せず、他の操作音がない装飾枠へのキャンセル済みクリックに音を付けます。
     *
     * @param event 処理完了時のメインスレッド上のクリック
     * @param player 操作したプレイヤー
     */
    public static void finish(@NotNull InventoryClickEvent event, @NotNull Player player) {
        DummyClick click = DUMMY_CLICK.get();
        if (click == null || click.event != event) {
            return;
        }
        DUMMY_CLICK.remove();
        if (event.isCancelled() && !click.played) {
            GuiSound.DUMMY.play(player);
        }
    }

    private static final class DummyClick {
        private final InventoryClickEvent event;
        private final UUID playerId;
        private boolean played;
        private boolean playedDummy;

        private DummyClick(InventoryClickEvent event, UUID playerId) {
            this.event = event;
            this.playerId = playerId;
        }
    }
}
