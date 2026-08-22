package io.github.maaasu.astralRecord.shared.challenge;

import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgResource;
import io.github.maaasu.astralRecord.shared.gui.GuiOpenSupport;
import io.github.maaasu.astralRecord.shared.gui.confirm.ConfirmDialogView;
import io.github.maaasu.astralRecord.shared.gui.sound.GuiSound;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;

/**
 * 挑戦待機列から離脱する前に、再度最後尾へ並び直すことを確認する GUI です。
 */
public final class ChallengeWaitingLeaveConfirmGui {
    /** 確認対象の挑戦種別です。 */
    public enum ChallengeType {
        BOSS,
        DUNGEON
    }

    /** 確認 GUI を識別する holder です。 */
    public record Holder(
            @NotNull UUID playerId,
            @NotNull ChallengeType type
    ) implements InventoryHolder {
        @Override
        public @NotNull Inventory getInventory() {
            return Bukkit.createInventory(this, ConfirmDialogView.SIZE);
        }
    }

    /**
     * 待機列離脱確認 GUI を開きます。
     *
     * @param player 対象プレイヤー
     * @param type 挑戦種別
     */
    public void open(@NotNull Player player, @NotNull ChallengeType type) {
        Inventory inventory = Bukkit.createInventory(
                new Holder(player.getUniqueId(), type),
                ConfirmDialogView.SIZE,
                PlayerMsgResource.getComponent(PlayerMsgId.P_6709.getId())
        );
        new ConfirmDialogView().render(
                inventory,
                PlayerMsgResource.getComponent(PlayerMsgId.P_6709.getId()),
                List.of(PlayerMsgResource.getComponent(PlayerMsgId.P_6710.getId())),
                PlayerMsgResource.getComponent(PlayerMsgId.P_6711.getId()),
                PlayerMsgResource.getComponent(PlayerMsgId.P_6712.getId())
        );
        GuiOpenSupport.open(player, inventory);
        GuiSound.SELECT.play(player);
    }

    /**
     * 指定 inventory が待機列離脱確認 GUI か判定します。
     *
     * @param inventory 判定対象 inventory
     * @return 対象 GUI なら {@code true}
     */
    public boolean isInventory(@Nullable Inventory inventory) {
        return inventory != null && inventory.getHolder() instanceof Holder;
    }

    /**
     * inventory から確認 holder を取得します。
     *
     * @param inventory 判定対象 inventory
     * @return holder。対象外なら {@code null}
     */
    public @Nullable Holder holder(@Nullable Inventory inventory) {
        return inventory != null && inventory.getHolder() instanceof Holder holder ? holder : null;
    }
}
