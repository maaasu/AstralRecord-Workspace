package io.github.maaasu.astralRecord.shared.gui;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.utility.MinecraftReflection;
import com.comphenix.protocol.wrappers.WrappedChatComponent;
import io.github.maaasu.astralRecord.feature.resourcepack.service.ResourcePackService;
import io.github.maaasu.astralRecord.shared.gui.session.GuiSessionTransitionService;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 対応パックを適用した Java 接続にだけ、チェスト GUI の背景をタイトルとして重ねます。
 * インベントリ、holder、スロット、クリック処理には触れません。
 */
public final class GuiResourcePackTitle {
    private static final Key FONT = Key.key("astralrecord", "gui");
    private static final char MOVE_LEFT = '\uea00';
    private static final char RETURN_TO_TITLE = '\uea01';
    private static final int FIRST_PANEL = 0xea10;
    private static final TextColor TITLE_COLOR = TextColor.color(0xf7dea0);
    private static @Nullable ThemeContext context;

    private GuiResourcePackTitle() {
    }

    /**
     * メインスレッドから、起動済みのパック適用状態サービスを関連付けます。
     * @param plugin ProtocolLib リスナーの所有プラグイン
     * @param resourcePacks 管理パックの適用成功と GUI 形式を確認するサービス
     */
    public static void configure(@NotNull Plugin plugin, @NotNull ResourcePackService resourcePacks) {
        context = new ThemeContext(plugin, resourcePacks);
    }

    /** メインスレッドから、終了するプラグインへの参照を解除します。 */
    public static void clear() {
        context = null;
    }

    /**
     * インベントリを元の API と同じ条件で開き、対応したチェストだけに専用背景を送ります。
     * メインスレッドから呼び出します。背景を使えない接続は通常のタイトルを保持します。
     * タイトル変更が非推奨の InventoryView#setTitle は使用せず、この open の送信だけを装飾します。
     * @param player 閲覧者
     * @param inventory 元の holder と内容を維持して開くインベントリ
     * @return 表示されたビュー。元の openInventory が拒否された場合は null
     */
    public static @Nullable InventoryView open(@NotNull Player player, @NotNull Inventory inventory) {
        ThemeContext active = context;
        int rows = inventory.getSize() / 9;
        if (active == null || !active.plugin().isEnabled()
            || inventory.getType() != InventoryType.CHEST || inventory.getSize() % 9 != 0
            || rows < 1 || rows > 6 || !GuiSessionTransitionService.isPluginManagedGui(inventory)
            || active.resourcePacks().isBedrock(player.getName())
            || !active.resourcePacks().hasGuiTheme(player.getUniqueId())) {
            return player.openInventory(inventory);
        }

        Class<?> menuTypeClass;
        Object expectedMenuType;
        try {
            menuTypeClass = MinecraftReflection.getMinecraftClass("world.inventory.MenuType");
            expectedMenuType = menuTypeClass.getField("GENERIC_9x" + rows).get(null);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // 将来のサーバーで構造が変わった場合も、元の GUI と操作を維持します。
            return player.openInventory(inventory);
        }
        ProtocolManager protocol = ProtocolLibrary.getProtocolManager();
        PacketAdapter listener = new PacketAdapter(active.plugin(), ListenerPriority.HIGH,
            PacketType.Play.Server.OPEN_WINDOW) {
            @Override
            public void onPacketSending(PacketEvent event) {
                if (event.isCancelled() || event.getPlayer() != player
                    || event.getPacket().getChatComponents().size() == 0) {
                    return;
                }
                Object menuType = event.getPacket().getSpecificModifier(menuTypeClass).readSafely(0);
                if (menuType != expectedMenuType) {
                    return;
                }
                WrappedChatComponent original = event.getPacket().getChatComponents().read(0);
                if (original == null) {
                    return;
                }
                Component title = GsonComponentSerializer.gson().deserialize(original.getJson());
                var decorated = event.getPacket().shallowClone();
                decorated.getChatComponents().write(0, WrappedChatComponent.fromJson(
                    GsonComponentSerializer.gson().serialize(decorate(title, rows))
                ));
                event.setPacket(decorated);
            }
        };
        protocol.addPacketListener(listener);
        try {
            return player.openInventory(inventory);
        } finally {
            protocol.removePacketListener(listener);
        }
    }

    /**
     * 176px の背景を左端に描き、元のタイトルの開始位置へカーソルを戻します。
     * @param original サーバーが作ったタイトル
     * @param rows 1～6 のチェスト行数
     * @return 背景と、文字内容・装飾・座標を維持し暗い帯で読める色に揃えたタイトル
     */
    private static Component decorate(Component original, int rows) {
        return Component.empty()
            .append(Component.text("" + MOVE_LEFT + (char) (FIRST_PANEL + rows - 1) + RETURN_TO_TITLE,
                    NamedTextColor.WHITE)
                .font(FONT)
                .decoration(TextDecoration.BOLD, false)
                .decoration(TextDecoration.ITALIC, false))
            .append(readableTitle(original));
    }

    /** タイトルの子要素も含め、内容・装飾を保持して暗い帯用の文字色に揃えます。 */
    private static Component readableTitle(Component original) {
        return original.color(TITLE_COLOR).children(
            original.children().stream().map(GuiResourcePackTitle::readableTitle).toList()
        );
    }

    private record ThemeContext(@NotNull Plugin plugin, @NotNull ResourcePackService resourcePacks) {
    }

}
