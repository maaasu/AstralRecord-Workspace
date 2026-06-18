package io.github.maaasu.astralRecord.feature.waystone.service;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;

import java.util.Map;

/**
 * ウェイストーン選択GUIのクリック対象IDを保持する holder です。
 */
public final class WaystoneGuiHolder implements InventoryHolder {
    private final Map<Integer, String> waystoneIdsBySlot;

    /**
     * holder を初期化します。
     *
     * @param waystoneIdsBySlot slot とウェイストーンIDの対応
     */
    public WaystoneGuiHolder(@NotNull Map<Integer, String> waystoneIdsBySlot) {
        this.waystoneIdsBySlot = waystoneIdsBySlot;
    }

    /**
     * slot に対応するウェイストーンIDを返します。
     *
     * @param slot クリックされたslot
     * @return ウェイストーンID。対象外slotの場合はnull
     */
    public String waystoneIdAt(int slot) {
        return waystoneIdsBySlot.get(slot);
    }

    @Override
    public Inventory getInventory() {
        return null;
    }
}
