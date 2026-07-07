package io.github.maaasu.astralRecord.feature.item.view;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import io.github.maaasu.astralRecord.feature.item.service.ItemStackFactory;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import io.github.maaasu.astralRecord.infrastructure.util.CustomModelDataComponentUtil;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ProtocolLib パケットアダプタ。
 * <p>
 * サーバ側 {@link Material#PAPER} の ItemStack に埋め込まれた icon 情報を読み取り、
 * クライアントへ送信するパケット内の Material を icon に差し替えます。
 * <p>
 * 対象パケット:
 * <ul>
 *   <li>{@code SET_SLOT} — 単一スロットの更新</li>
 *   <li>{@code WINDOW_ITEMS} — インベントリ全体の一括送信</li>
 * </ul>
 * <p>
 * <b>性能上の考慮:</b>
 * <ul>
 *   <li>Material 名 → Material の変換結果を {@link ConcurrentHashMap} でキャッシュし、
 *       毎パケットでの {@link Material#matchMaterial} 呼び出しを回避します。</li>
 *   <li>AstralRecord アイテムでない ItemStack は PDC チェック 1 回で即スキップします。</li>
 *   <li>パケット内の ItemStack を直接 clone → 書き換えすることで、
 *       不要なオブジェクト生成を最小限にしています。</li>
 * </ul>
 */
public class ItemStackPacketAdapter {

    /** Material 名キャッシュ (大文字名 → Material) */
    private static final Map<String, Material> MATERIAL_CACHE = new ConcurrentHashMap<>();

    private final Plugin plugin;
    private boolean registered = false;

    /**
     * アダプタを初期化します。
     *
     * @param plugin プラグインインスタンス
     */
    public ItemStackPacketAdapter(@NotNull Plugin plugin) {
        this.plugin = plugin;
    }

    /**
     * ProtocolLib にパケットリスナーを登録します。
     * 複数回呼び出しても二重登録されません。
     */
    public void register() {
        if (registered) {
            return;
        }

        ProtocolManager manager = ProtocolLibrary.getProtocolManager();

        manager.addPacketListener(new PacketAdapter(
                plugin,
                ListenerPriority.NORMAL,
                PacketType.Play.Server.SET_SLOT,
                PacketType.Play.Server.WINDOW_ITEMS
        ) {
            @Override
            public void onPacketSending(PacketEvent event) {
                if (event.isCancelled()) {
                    return;
                }

                PacketContainer packet = event.getPacket();
                PacketType type = packet.getType();

                if (type == PacketType.Play.Server.SET_SLOT) {
                    handleSetSlot(packet);
                } else if (type == PacketType.Play.Server.WINDOW_ITEMS) {
                    handleWindowItems(packet);
                }
            }
        });

        registered = true;
        Logger.log(LogId.I_5210);
    }

    // region --- パケットハンドラ ---

    /**
     * SET_SLOT パケット内の単一 ItemStack を書き換えます。
     */
    private void handleSetSlot(@NotNull PacketContainer packet) {
        var original = packet.getItemModifier().readSafely(0);
        if (original == null || original.getType() == Material.AIR) {
            return;
        }

        var replaced = replaceIcon(original);
        if (replaced != null) {
            packet.getItemModifier().writeSafely(0, replaced);
        }
    }

    /**
     * WINDOW_ITEMS パケット内の ItemStack リストを書き換えます。
     */
    private void handleWindowItems(@NotNull PacketContainer packet) {
        var items = packet.getItemListModifier().readSafely(0);
        if (items == null || items.isEmpty()) {
            return;
        }

        var modified = false;
        for (int i = 0; i < items.size(); i++) {
            var original = items.get(i);
            if (original == null || original.getType() == Material.AIR) {
                continue;
            }

            var replaced = replaceIcon(original);
            if (replaced != null) {
                items.set(i, replaced);
                modified = true;
            }
        }

        if (modified) {
            packet.getItemListModifier().writeSafely(0, items);
        }
    }

    // endregion

    // region --- Material 差し替えロジック ---

    /**
     * AstralRecord アイテムであれば、clone して Material を icon に差し替えた ItemStack を返します。
     * AstralRecord アイテムでなければ {@code null} を返します。
     *
     * @param original 元の ItemStack
     * @return icon 適用済み ItemStack、または {@code null}
     */
    private ItemStack replaceIcon(@NotNull ItemStack original) {
        var iconName = ItemStackFactory.getIconName(original);
        var customModelData = ItemStackFactory.getCustomModelData(original);
        var appearanceColor = ItemStackFactory.getAppearanceColor(original);
        var potionType = ItemStackFactory.getPotionType(original);

        if (iconName == null && customModelData == null && appearanceColor == null && potionType == null) {
            return null;
        }

        ItemStack replaced = original;
        boolean modified = false;

        if (iconName != null) {
            var iconMaterial = resolveMaterial(iconName);
            if (iconMaterial != null && iconMaterial != original.getType()) {
                replaced = original.withType(iconMaterial);
                modified = true;
            }
        }

        if (customModelData != null) {
            var meta = replaced.getItemMeta();
            if (meta != null) {
                Integer currentCustomModelData = CustomModelDataComponentUtil.readAsInt(meta);
                if (needsCustomModelDataUpdate(customModelData, currentCustomModelData)) {
                    CustomModelDataComponentUtil.writeFromInt(meta, customModelData);
                    replaced.setItemMeta(meta);
                    modified = true;
                }
            }
        }

        if (ItemStackFactory.applyAppearance(replaced)) {
            modified = true;
        }

        return modified ? replaced : null;
    }

    static boolean needsCustomModelDataUpdate(int desiredCustomModelData, @Nullable Integer currentCustomModelData) {
        return currentCustomModelData == null || currentCustomModelData != desiredCustomModelData;
    }



    /**
     * Material 名を解決します。キャッシュにヒットしない場合のみ {@link Material#matchMaterial} を呼び出します。
     *
     * @param name Material 名（大文字）
     * @return 解決された Material。不正な名前なら {@code null}
     */
    private static Material resolveMaterial(@NotNull String name) {
        var upper = name.toUpperCase(Locale.ROOT);
        return MATERIAL_CACHE.computeIfAbsent(upper, k -> {
            var mat = Material.matchMaterial(k);
            if (mat == null) {
                Logger.log(LogId.W_5210, k);
            }
            return mat;
        });
    }

    // endregion
}
