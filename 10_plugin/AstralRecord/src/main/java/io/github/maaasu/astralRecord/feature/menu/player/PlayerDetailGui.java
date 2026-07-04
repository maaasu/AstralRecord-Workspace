package io.github.maaasu.astralRecord.feature.menu.player;

import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.status.model.StatusSnapshot;
import io.github.maaasu.astralRecord.feature.status.model.StatusType;
import io.github.maaasu.astralRecord.feature.menu.view.screen.BaseMenuScreenView;
import io.github.maaasu.astralRecord.infrastructure.util.ColorCodeUtil;
import io.github.maaasu.astralRecord.shared.gui.hotbar.HotbarShortcutGuiHolder;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * プレイヤー詳細情報を表示する GUI です。
 */
public final class PlayerDetailGui extends BaseMenuScreenView {
    public static final int HEAD_SLOT = 13;
    public static final int USER_SLOT = 20;
    public static final int ACCOUNT_SLOT = 22;
    public static final int STATUS_SLOT = 24;
    public static final int CLASS_SLOT = 31;
    public static final int BUFF_SLOT = 33;

    /**
     * 詳細 GUI を開きます。
     *
     * @param viewer      閲覧者
     * @param target      表示対象
     * @param snapshot    表示用ステータス
     * @param backTarget  戻り先
     * @param returnPage  戻り先一覧ページ
     */
    public void open(
        @NotNull Player viewer,
        @NotNull AstPlayer target,
        @NotNull StatusSnapshot snapshot,
        @NotNull PlayerListBackTarget backTarget,
        int returnPage
    ) {
        Inventory inventory = Bukkit.createInventory(
            new Holder(target.getBukkit().getUniqueId(), backTarget, Math.max(0, returnPage)),
            SIZE,
            Component.text(target.getBukkit().getName() + " の情報", NamedTextColor.GOLD)
        );
        render(inventory, target, snapshot);
        viewer.openInventory(inventory);
    }

    public boolean isInventory(@Nullable Inventory inventory) {
        return inventory != null && inventory.getHolder() instanceof Holder;
    }

    public @Nullable UUID getTargetId(@Nullable Inventory inventory) {
        if (inventory != null && inventory.getHolder() instanceof Holder holder) {
            return holder.targetId();
        }
        return null;
    }

    public @Nullable PlayerListBackTarget getBackTarget(@Nullable Inventory inventory) {
        if (inventory != null && inventory.getHolder() instanceof Holder holder) {
            return holder.backTarget();
        }
        return null;
    }

    public int getReturnPage(@Nullable Inventory inventory) {
        if (inventory != null && inventory.getHolder() instanceof Holder holder) {
            return holder.returnPage();
        }
        return 0;
    }

    private void render(@NotNull Inventory inventory, @NotNull AstPlayer target, @NotNull StatusSnapshot snapshot) {
        fill(inventory);
        inventory.setItem(BACK_SLOT, backItem());
        inventory.setItem(HEAD_SLOT, playerHead(target));
        inventory.setItem(USER_SLOT, createItem(
            Material.NAME_TAG,
            noItalic(Component.text("基本情報", NamedTextColor.AQUA)),
            List.of(
                noItalic(Component.text("名前: " + target.getBukkit().getName(), NamedTextColor.WHITE)),
                noItalic(Component.text("UUID: " + target.getBukkit().getUniqueId(), NamedTextColor.GRAY)),
                noItalic(Component.text("ワールド: " + displayWorldName(target.getBukkit()), NamedTextColor.GRAY)),
                noItalic(Component.text("権限: " + target.getUser().getPermission(), NamedTextColor.YELLOW))
            )
        ));
        inventory.setItem(ACCOUNT_SLOT, createItem(
            Material.BOOK,
            noItalic(Component.text("アカウント", NamedTextColor.GREEN)),
            List.of(
                noItalic(Component.text("名前: " + target.getAccount().getAccountName(), NamedTextColor.WHITE)),
                noItalic(Component.text("スロット: " + target.getAccount().getSlotIndex(), NamedTextColor.GRAY)),
                noItalic(Component.text("モード: " + target.getAccount().getMode().getDisplayName(), NamedTextColor.GRAY)),
                noItalic(Component.text("Lv: " + target.getAccount().getLevel(), NamedTextColor.YELLOW)),
                noItalic(Component.text("累計経験値: " + target.getAccount().getTotalExperience(), NamedTextColor.YELLOW))
            )
        ));
        inventory.setItem(STATUS_SLOT, createItem(
            Material.NETHER_STAR,
            noItalic(Component.text("ステータス", NamedTextColor.GOLD)),
            List.of(
                noItalic(Component.text("HP: " + snapshot.getCurrentHp() + "/" + snapshot.getMaxValue(StatusType.MAX_HEALTH), NamedTextColor.RED)),
                noItalic(Component.text("MP: " + snapshot.getCurrentMp() + "/" + snapshot.getMaxValue(StatusType.MAX_MANA), NamedTextColor.BLUE)),
                noItalic(Component.text("EN: " + snapshot.getCurrentEnergy() + "/" + snapshot.getMaxValue(StatusType.MAX_ENERGY), NamedTextColor.GREEN)),
                noItalic(Component.text(StatusType.ATTACK.getDisplayName() + ": " + totalValue(snapshot, StatusType.ATTACK), StatusType.ATTACK.namedColor())),
                noItalic(Component.text(StatusType.DEFENSE.getDisplayName() + ": " + totalValue(snapshot, StatusType.DEFENSE), StatusType.DEFENSE.namedColor()))
            )
        ));
        inventory.setItem(CLASS_SLOT, createItem(
            Material.COMPASS,
            noItalic(Component.text("クラス", NamedTextColor.LIGHT_PURPLE)),
            List.of(
                noItalic(Component.text("classId: " + target.getClassId(), NamedTextColor.WHITE)),
                noItalic(Component.text("classLevel: " + target.getClassLevel(), NamedTextColor.YELLOW))
            )
        ));
        inventory.setItem(BUFF_SLOT, createItem(
            Material.POTION,
            noItalic(Component.text("現在のバフ", NamedTextColor.AQUA)),
            buildBuffLore(target)
        ));
    }

    private @NotNull ItemStack playerHead(@NotNull AstPlayer target) {
        ItemStack itemStack = new ItemStack(Material.PLAYER_HEAD);
        ItemMeta meta = itemStack.getItemMeta();
        if (meta instanceof SkullMeta skullMeta) {
            OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(target.getBukkit().getUniqueId());
            skullMeta.setOwningPlayer(offlinePlayer);
            skullMeta.displayName(noItalic(Component.text(target.getBukkit().getName(), NamedTextColor.WHITE, TextDecoration.BOLD)));
            skullMeta.lore(List.of(
                noItalic(Component.text("クリック操作はありません", NamedTextColor.GRAY))
            ));
            skullMeta.addItemFlags(ItemFlag.values());
            itemStack.setItemMeta(skullMeta);
        }
        return itemStack;
    }

    private @NotNull List<Component> buildBuffLore(@NotNull AstPlayer target) {
        if (target.getActiveBuffs().isEmpty()) {
            return List.of(noItalic(Component.text("現在有効なバフはありません", NamedTextColor.GRAY)));
        }
        List<Component> lore = new ArrayList<>();
        for (var activeBuff : target.getActiveBuffs()) {
            String displayName = ColorCodeUtil.toPlainText(
                activeBuff.getType().getDisplayName(),
                activeBuff.getType().getId()
            );
            lore.add(noItalic(Component.text("- " + displayName, NamedTextColor.WHITE)));
        }
        return lore;
    }

    protected @NotNull Component noItalic(@NotNull Component component) {
        return component.decoration(TextDecoration.ITALIC, false);
    }

    private @NotNull String displayWorldName(@NotNull Player target) {
        String normalizedName = target.getWorld().getName()
            .replace('\\', '/')
            .replaceAll("/{2,}", "/");
        String leafName = new File(normalizedName).getName();
        return leafName.isBlank() ? normalizedName : leafName;
    }

    private double totalValue(@NotNull StatusSnapshot snapshot, @NotNull StatusType type) {
        var value = snapshot.getValue(type);
        return value == null ? 0.0 : value.getTotalValue();
    }

    private record Holder(
        @NotNull UUID targetId,
        @NotNull PlayerListBackTarget backTarget,
        int returnPage
    ) implements HotbarShortcutGuiHolder {
        @Override
        public @NotNull Inventory getInventory() {
            return Bukkit.createInventory(this, SIZE);
        }
    }
}
