package io.github.maaasu.astralRecord.shared.packetdisplay;

import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

/**
 * プレイヤーへパケット送信した仮想 Display Entity を操作するハンドルです。
 */
public final class PacketDisplayHandle {
    private final PacketDisplayService service;
    private final int entityId;
    private final PacketDisplayType type;
    private boolean destroyed;

    PacketDisplayHandle(@NotNull PacketDisplayService service, int entityId, @NotNull PacketDisplayType type) {
        this.service = service;
        this.entityId = entityId;
        this.type = type;
    }

    /**
     * 仮想 Display Entity の位置を更新します。
     *
     * @param location 新しい表示位置
     */
    public void teleport(@NotNull Location location) {
        if (!destroyed) {
            service.teleport(entityId, location);
        }
    }

    /**
     * TextDisplay の表示文字列を更新します。
     *
     * @param text 新しい表示文字列
     */
    public void updateText(@NotNull Component text) {
        if (!destroyed && type == PacketDisplayType.TEXT) {
            service.updateText(entityId, text);
        }
    }

    /**
     * ItemDisplay の表示アイテムを更新します。
     *
     * @param itemStack 新しい表示アイテム
     */
    public void updateItem(@NotNull ItemStack itemStack) {
        if (!destroyed && type == PacketDisplayType.ITEM) {
            service.updateItem(entityId, itemStack);
        }
    }

    /**
     * 仮想 Display Entity を viewer 側から破棄します。
     */
    public void destroy() {
        if (destroyed) {
            return;
        }
        destroyed = true;
        service.destroy(entityId);
    }
}
