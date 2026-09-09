package io.github.maaasu.astralRecord.feature.item.service;

import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 所持中の AstralRecord アイテムを全体チャットへ共有するサービス。
 */
public final class ItemChatShareService {
    private static final PlainTextComponentSerializer PLAIN_TEXT = PlainTextComponentSerializer.plainText();
    private static final Pattern SLOT_SELECTION = Pattern.compile("^\\[(\\d+)]\\s+(.+)$");
    private static final NamespacedKey BAG_SLOT_NUMBER_KEY = new NamespacedKey("astralrecord", "bag_slot_number");

    /**
     * 表示中 BAG の内容から、論理スロット番号とともにチャット共有可能なアイテムを取得します。
     *
     * @param contents 所持品内容
     * @return 補完候補に使う BAG スロット・表示名一覧
     */
    public @NotNull List<ShareableItem> getShareableItems(@Nullable ItemStack[] contents) {
        if (contents == null || contents.length == 0) {
            return List.of();
        }

        List<ShareableItem> items = new ArrayList<>();
        for (ItemStack item : contents) {
            String name = resolvePlainDisplayName(item);
            Integer bagSlotNumber = resolveBagSlotNumber(item);
            if (name != null && bagSlotNumber != null) {
                items.add(new ShareableItem(bagSlotNumber, name, item));
            }
        }
        return List.copyOf(items);
    }

    /**
     * 所持品内容から、指定表示名と一致する共有可能なアイテムを取得します。
     *
     * @param contents 所持品内容
     * @param requestedName コマンドで指定された表示名
     * @return 一致した ItemStack。見つからない場合は {@code null}
     */
    public @Nullable ItemStack findShareableItem(@Nullable ItemStack[] contents, @NotNull String requestedName) {
        if (contents == null || requestedName.isBlank()) {
            return null;
        }

        String normalizedName = requestedName.strip();
        Matcher slotSelection = SLOT_SELECTION.matcher(normalizedName);
        if (slotSelection.matches()) {
            int slotNumber;
            try {
                slotNumber = Integer.parseInt(slotSelection.group(1));
            } catch (NumberFormatException ignored) {
                return null;
            }
            return getShareableItems(contents).stream()
                .filter(item -> item.slotNumber() == slotNumber)
                .filter(item -> item.displayName().equalsIgnoreCase(slotSelection.group(2).strip()))
                .map(ShareableItem::item)
                .findFirst()
                .orElse(null);
        }
        for (ItemStack item : contents) {
            String displayName = resolvePlainDisplayName(item);
            if (displayName != null && displayName.equalsIgnoreCase(normalizedName)) {
                return item;
            }
        }
        return null;
    }

    /**
     * AstralRecord アイテム名を、ホバー・クリック操作付きの全体チャットとして配信します。
     *
     * @param player 共有したプレイヤー
     * @param item 共有する所持アイテム
     * @return 共有できた場合は {@code true}
     */
    public boolean share(@NotNull Player player, @Nullable ItemStack item) {
        Component displayName = resolveDisplayName(item);
        if (displayName == null || item == null) {
            return false;
        }

        String shareName = resolvePlainDisplayName(item);
        if (shareName == null) {
            return false;
        }

        PlayerMessageService.getInstance().broadcastGlobalItemChat(
            player,
            shareName,
            ItemTransferSupport.stripExactDisplayLore(
                item,
                "クリックでホットバースロットに設定",
                "クリックで使用"
            )
        );
        return true;
    }

    private @NotNull String removeShareDecoration(@NotNull String displayName) {
        return displayName.replaceFirst("◆\\s*", "").strip();
    }

    private @Nullable String resolvePlainDisplayName(@Nullable ItemStack item) {
        Component displayName = resolveDisplayName(item);
        if (displayName == null) {
            return null;
        }
        String plainText = PLAIN_TEXT.serialize(displayName).strip();
        plainText = removeShareDecoration(plainText);
        return plainText.isBlank() ? null : plainText;
    }

    private @Nullable Component resolveDisplayName(@Nullable ItemStack item) {
        if (item == null
            || item.getType() == Material.AIR
            || ItemStackFactory.getAstralItemId(item) == null) {
            return null;
        }

        ItemMeta meta = item.getItemMeta();
        if (meta == null || !meta.hasDisplayName() || meta.displayName() == null) {
            return null;
        }
        return meta.displayName();
    }

    private @Nullable Integer resolveBagSlotNumber(@Nullable ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return null;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return null;
        }
        Integer slotNumber = meta.getPersistentDataContainer().get(BAG_SLOT_NUMBER_KEY, PersistentDataType.INTEGER);
        return slotNumber == null || slotNumber < 1 ? null : slotNumber;
    }

    /**
     * {@code /showitem} の補完と選択に使用する、所持品内の共有可能アイテムです。
     *
     * @param slotNumber BAG の論理スロット番号（左上を1とする）
     * @param displayName 装飾を除いた表示名
     * @param item 対象 ItemStack
     */
    public record ShareableItem(int slotNumber, @NotNull String displayName, @NotNull ItemStack item) {
        /** @return コマンドへ入力する候補文字列 */
        public @NotNull String commandSelection() {
            return "[" + slotNumber + "] " + displayName;
        }
    }
}
