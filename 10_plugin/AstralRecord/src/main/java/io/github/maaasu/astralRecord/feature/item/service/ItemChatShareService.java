package io.github.maaasu.astralRecord.feature.item.service;

import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 所持中の AstralRecord アイテムを全体チャットへ共有するサービス。
 */
public final class ItemChatShareService {
    private static final PlainTextComponentSerializer PLAIN_TEXT = PlainTextComponentSerializer.plainText();

    /**
     * 所持品内容からチャット共有可能なアイテム名を、先頭出現順かつ重複なしで取得します。
     *
     * @param contents 所持品内容
     * @return 補完候補に使う表示名一覧
     */
    public @NotNull List<String> getShareableItemNames(@Nullable ItemStack[] contents) {
        if (contents == null || contents.length == 0) {
            return List.of();
        }

        Set<String> names = new LinkedHashSet<>();
        for (ItemStack item : contents) {
            String name = resolvePlainDisplayName(item);
            if (name != null) {
                names.add(name);
            }
        }
        return List.copyOf(names);
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

        String clipboardText = PLAIN_TEXT.serialize(displayName).strip();
        if (clipboardText.isBlank()) {
            return false;
        }

        PlayerMessageService.getInstance().broadcastGlobalItemChat(
            player,
            displayName,
            item.clone(),
            clipboardText
        );
        return true;
    }

    private @Nullable String resolvePlainDisplayName(@Nullable ItemStack item) {
        Component displayName = resolveDisplayName(item);
        if (displayName == null) {
            return null;
        }
        String plainText = PLAIN_TEXT.serialize(displayName).strip();
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
}
