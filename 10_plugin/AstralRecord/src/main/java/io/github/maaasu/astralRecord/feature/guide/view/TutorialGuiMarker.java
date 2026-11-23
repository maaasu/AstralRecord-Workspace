package io.github.maaasu.astralRecord.feature.guide.view;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import io.github.maaasu.astralRecord.feature.guide.service.GuideService;
import io.github.maaasu.astralRecord.feature.item.service.ItemStackFactory;
import io.github.maaasu.astralRecord.feature.mail.gui.MailGuiView;
import io.github.maaasu.astralRecord.feature.menu.service.MenuToolJoinGrantService;
import io.github.maaasu.astralRecord.feature.menu.view.screen.MainMenuScreenView;
import io.github.maaasu.astralRecord.feature.menu.view.MenuInventoryHolder;
import io.github.maaasu.astralRecord.feature.player.AccountModeGuard;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.shop.gui.ShopGui;
import io.github.maaasu.astralRecord.feature.world.gui.OverworldTeleportGui;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/** チュートリアルの対象スロットを、閲覧者に送るアイテムの複製に表示します。 */
public final class TutorialGuiMarker implements Listener {
    private static final int POLL_INTERVAL = 5;
    private static final long EQUIP_SUCCESS_NANOS = TimeUnit.SECONDS.toNanos(3);
    private static final int FIRST_BAG_SLOT = 9;
    private static final int FIRST_HOTBAR_SLOT = 36;
    private static final int STORAGE_SIZE = 36;
    private static final int EQUIPMENT_MAIN_HAND_SLOT = 19;
    private static final int MAIL_PAGE_SIZE = 45;
    private static final String WELCOME_MAIL_ID = "welcome_mail";
    private static final String BUNDLE_ITEM_ID = "50a00001";
    private static final String STARTER_WEAPON_ID = "20a00001";
    private static final String SHOP_ID = "general_goods_shop";
    private static final String SHOP_ENTRY_ID = "nox_weapon";
    private static final String EQUIP_STEP = "equip_starter_weapon";
    private static final String DEFEAT_STEP = "defeat_grassboar";
    private static final Component PREFIX = Component.text("▶ ", NamedTextColor.GOLD)
        .decoration(TextDecoration.ITALIC, false);

    private final JavaPlugin plugin;
    private final GuideService guides;
    private final NamespacedKey mailIdKey;
    private final NamespacedKey shopEntryIdKey;
    private final ConcurrentMap<UUID, Session> sessions = new ConcurrentHashMap<>();

    private @Nullable BukkitTask task;
    private @Nullable PacketAdapter packetListener;

    public TutorialGuiMarker(JavaPlugin plugin, GuideService guides) {
        this.plugin = plugin;
        this.guides = guides;
        this.mailIdKey = new NamespacedKey(plugin, "mail_id");
        this.shopEntryIdKey = new NamespacedKey(plugin, "shop_entry_id");
    }

    public void start() {
        if (packetListener != null) {
            return;
        }

        for (Player player : Bukkit.getOnlinePlayers()) {
            sessions.put(player.getUniqueId(), new Session(player));
        }
        Bukkit.getPluginManager().registerEvents(this, plugin);
        packetListener = new PacketAdapter(
            plugin, ListenerPriority.HIGHEST,
            PacketType.Play.Server.OPEN_WINDOW,
            PacketType.Play.Server.WINDOW_ITEMS,
            PacketType.Play.Server.SET_SLOT,
            PacketType.Play.Server.SET_PLAYER_INVENTORY
        ) {
            @Override
            public void onPacketSending(PacketEvent event) {
                handleOutgoing(event);
            }
        };
        ProtocolLibrary.getProtocolManager().addPacketListener(packetListener);
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::refresh, 1L, POLL_INTERVAL);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        if (packetListener != null) {
            ProtocolLibrary.getProtocolManager().removePacketListener(packetListener);
            packetListener = null;
        }
        HandlerList.unregisterAll(this);

        List<Session> stopped = List.copyOf(sessions.values());
        sessions.clear();
        for (Session session : stopped) {
            Presentation previous = session.presentation.get();
            if (previous.needsRestore() && session.player.isOnline()) {
                session.player.updateInventory();
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        sessions.put(player.getUniqueId(), new Session(player));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        Session session = sessionFor(player);
        if (session != null) {
            sessions.remove(player.getUniqueId(), session);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        if (event.getPlayer() instanceof Player player) {
            Session session = sessionFor(player);
            if (session != null) {
                session.resetWindow(null);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGameModeChange(PlayerGameModeChangeEvent event) {
        if (event.getNewGameMode() != GameMode.CREATIVE) {
            return;
        }
        Session session = sessionFor(event.getPlayer());
        if (session != null) {
            Presentation previous = session.presentation.getAndUpdate(
                current -> new Presentation(current.windowId(), null, false)
            );
            if (previous.needsRestore()) {
                event.getPlayer().updateInventory();
            }
        }
    }

    private @Nullable Session sessionFor(Player player) {
        Session session = sessions.get(player.getUniqueId());
        return session != null && session.player == player ? session : null;
    }

    private void refresh() {
        long now = System.nanoTime();
        for (Player player : Bukkit.getOnlinePlayers()) {
            Session session = sessionFor(player);
            if (session == null) {
                continue;
            }

            UUID accountId = null;
            var astPlayer = AstPlayerCache.get(player);
            if (AccountModeGuard.isGameplayPlayer(astPlayer)) {
                accountId = astPlayer.getAccount().getUuid();
            }
            String step = accountId == null ? null : guides.tutorialStep(accountId);
            boolean recentEquipSuccess = session.observeStep(accountId, step, now);

            Presentation previous = session.presentation.get();
            Mark next = step == null || player.getGameMode() == GameMode.CREATIVE ? null : selectMark(
                player.getOpenInventory(), previous.windowId(), player.getUniqueId(),
                step, recentEquipSuccess, mailIdKey, shopEntryIdKey
            );
            if (!previous.restorePending() && Objects.equals(previous.mark(), next)) {
                continue;
            }
            if (session.presentation.compareAndSet(previous, new Presentation(previous.windowId(), next, false))) {
                player.updateInventory();
            }
        }
    }

    private void handleOutgoing(PacketEvent event) {
        if (event.isCancelled()) {
            return;
        }
        Player viewer = event.getPlayer();
        Session session = sessionFor(viewer);
        if (session == null) {
            return;
        }

        PacketContainer packet = event.getPacket();
        PacketType type = packet.getType();
        if (type == PacketType.Play.Server.OPEN_WINDOW) {
            session.resetWindow(packet.getIntegers().readSafely(0));
            return;
        }

        Presentation snapshot = session.presentation.get();
        Mark mark = snapshot.mark();
        if (mark == null) {
            return;
        }

        PacketContainer replacement;
        if (type == PacketType.Play.Server.WINDOW_ITEMS) {
            Integer windowId = packet.getIntegers().readSafely(0);
            if (windowId == null || !mark.targetsWindowSlot(windowId, mark.rawSlot())) {
                return;
            }
            List<ItemStack> items = decorateWindowItems(mark, windowId, packet.getItemListModifier().readSafely(0));
            if (items == null) {
                return;
            }
            replacement = packet.shallowClone();
            replacement.getItemListModifier().write(0, items);
        } else {
            replacement = decorateSingleSlot(packet, mark);
            if (replacement == null) {
                return;
            }
        }

        // GUI の切替や退出で選択が更新された場合は、新しいウィンドウの同期に委ねます。
        if (session.presentation.get() == snapshot && sessionFor(viewer) == session) {
            event.setPacket(replacement);
        }
    }

    private static @Nullable PacketContainer decorateSingleSlot(PacketContainer packet, Mark mark) {
        Integer first = packet.getIntegers().readSafely(0);
        if (first == null) {
            return null;
        }
        boolean playerInventory = packet.getType() == PacketType.Play.Server.SET_PLAYER_INVENTORY;
        int slot = first;
        if (!playerInventory) {
            if (packet.getType() != PacketType.Play.Server.SET_SLOT) {
                return null;
            }
            Integer rawSlot = packet.getIntegers().readSafely(2);
            if (rawSlot == null) {
                Short shortSlot = packet.getShorts().readSafely(0);
                rawSlot = shortSlot == null ? -1 : shortSlot.intValue();
            }
            slot = rawSlot;
            playerInventory = first == -2;
        }
        if (!(playerInventory ? mark.targetsPlayerSlot(slot) : mark.acceptsWindowSlot(first, slot))) {
            return null;
        }
        ItemStack original = packet.getItemModifier().readSafely(0);
        ItemStack decorated = playerInventory
            ? decorate(mark, original) : decorateWindowSlot(mark, first, slot, original);
        if (decorated == original) {
            return null;
        }
        PacketContainer replacement = packet.shallowClone();
        replacement.getItemModifier().write(0, decorated);
        return replacement;
    }

    static @Nullable List<ItemStack> decorateWindowItems(Mark mark, int windowId, @Nullable List<ItemStack> items) {
        int rawSlot = mark.rawSlot();
        if (items == null || !mark.targetsWindowSlot(windowId, rawSlot) || rawSlot >= items.size()) {
            return null;
        }
        ItemStack original = items.get(rawSlot);
        if (original == null || !mark.guard().matches(original)) {
            return null;
        }
        ItemStack decorated = decorateMatchedItem(original);
        List<ItemStack> copy = null;
        if (decorated != original) {
            copy = new ArrayList<>(items);
            copy.set(rawSlot, decorated);
        }
        MailPage page = mark.mailPage();
        if (page != null) {
            for (int slot = 0; slot < Math.min(page.size(), items.size()); slot++) {
                if (slot == rawSlot) {
                    continue;
                }
                ItemStack card = items.get(slot);
                ItemStack muted = suppressMailGlint(page, card);
                if (muted != card) {
                    if (copy == null) {
                        copy = new ArrayList<>(items);
                    }
                    copy.set(slot, muted);
                }
            }
        }
        return copy;
    }

    static @Nullable ItemStack decorateWindowSlot(Mark mark, int windowId, int slot, @Nullable ItemStack original) {
        if (mark.targetsWindowSlot(windowId, slot)) {
            return decorate(mark, original);
        }
        return mark.acceptsWindowSlot(windowId, slot) ? suppressMailGlint(mark.mailPage(), original) : original;
    }

    private static @Nullable ItemStack suppressMailGlint(MailPage page, @Nullable ItemStack original) {
        if (!present(original)) {
            return original;
        }
        String id = original.getPersistentDataContainer().get(page.key(), PersistentDataType.STRING);
        if (id == null || WELCOME_MAIL_ID.equals(id) || !page.unreadIds().contains(id)) {
            return original;
        }
        ItemMeta meta = original.getItemMeta();
        if (meta == null || !(meta.hasEnchantmentGlintOverride()
            ? meta.getEnchantmentGlintOverride() : meta.hasEnchants())) {
            return original;
        }
        ItemStack copy = original.clone();
        ItemMeta copyMeta = copy.getItemMeta();
        if (copyMeta == null) {
            return original;
        }
        copyMeta.setEnchantmentGlintOverride(false);
        return copy.setItemMeta(copyMeta) ? copy : original;
    }

    static @Nullable ItemStack decorate(Mark mark, @Nullable ItemStack original) {
        if (original == null || !mark.guard().matches(original)) {
            return original;
        }
        return decorateMatchedItem(original);
    }

    private static ItemStack decorateMatchedItem(ItemStack original) {
        ItemStack copy = original.clone();
        ItemMeta meta = copy.getItemMeta();
        if (meta == null) {
            return original;
        }
        Component name = meta.displayName();
        if (name == null) {
            name = copy.effectiveName().decoration(TextDecoration.ITALIC, false);
        }
        meta.displayName(Component.empty().append(PREFIX).append(name));
        meta.setEnchantmentGlintOverride(true);
        return copy.setItemMeta(meta) ? copy : original;
    }

    static @Nullable Mark selectMark(
        InventoryView view, @Nullable Integer windowId, UUID playerId, @Nullable String step,
        boolean recentEquipSuccess, NamespacedKey mailIdKey, NamespacedKey shopEntryIdKey
    ) {
        if (step == null) {
            return null;
        }
        if (isPlayerCraftingView(view)) {
            String itemId = switch (step) {
                case "open_menu" -> MenuToolJoinGrantService.MENU_ITEM_ID;
                case "open_initial_bundle" -> BUNDLE_ITEM_ID;
                default -> null;
            };
            if (itemId == null) {
                return null;
            }
            Guard target = new AstralItem(itemId);
            // 使用可能なホットバーの実枠を優先し、未割当なら割当元のバッグを案内します。
            Mark hotbar = firstBagItem(view, 0, FIRST_HOTBAR_SLOT, FIRST_BAG_SLOT + STORAGE_SIZE, target);
            return hotbar != null ? hotbar : firstBagItem(view, 0, FIRST_BAG_SLOT, FIRST_HOTBAR_SLOT, target);
        }
        if (windowId == null || windowId <= 0) {
            return null;
        }

        InventoryHolder holder = view.getTopInventory().getHolder();
        if (holder instanceof MenuInventoryHolder menu) {
            return switch (menu.screen()) {
                case MAIN -> switch (step) {
                    case "receive_welcome_mail" -> menuButton(view, windowId, MainMenuScreenView.MAIL_SLOT);
                    case EQUIP_STEP -> menuButton(view, windowId, MainMenuScreenView.EQUIPMENT_GUI_SLOT);
                    default -> null;
                };
                case EQUIPMENT_GUI -> equipmentMark(view, windowId, menu, playerId, step, recentEquipSuccess);
                default -> null;
            };
        }
        if (holder instanceof MailGuiView.Holder mail && "receive_welcome_mail".equals(step)) {
            int start = mail.pageIndex() * MAIL_PAGE_SIZE;
            if (start < 0 || start >= mail.mails().size()) {
                return null;
            }
            var visible = mail.mails().subList(start, Math.min(start + MAIL_PAGE_SIZE, mail.mails().size()));
            Set<String> unreadIds = visible.stream()
                .filter(entry -> !entry.read())
                .map(entry -> entry.id())
                .collect(Collectors.toUnmodifiableSet());
            if (unreadIds.contains(WELCOME_MAIL_ID)) {
                // 表示中のページにある実アイテムの ID でスロットを確定します。
                Mark target = firstTopItem(view, windowId, MAIL_PAGE_SIZE, new TaggedItem(mailIdKey, WELCOME_MAIL_ID));
                if (target != null) {
                    MailPage page = new MailPage(mailIdKey, unreadIds,
                        Math.min(MAIL_PAGE_SIZE, view.getTopInventory().getSize()));
                    return new Mark(windowId, target.rawSlot(), -1, target.guard(), page);
                }
            }
        }
        if (DEFEAT_STEP.equals(step) && holder instanceof OverworldTeleportGui.Holder teleport) {
            for (var entry : teleport.worldIdsBySlot().entrySet()) {
                if ("midgard_greenfields".equals(entry.getValue())) {
                    return menuButton(view, windowId, entry.getKey());
                }
            }
        }
        if ("buy_starter_weapon".equals(step)) {
            if (holder instanceof ShopGui.ListHolder shop && SHOP_ID.equals(shop.shopId())) {
                return firstTopItem(
                    view, windowId, view.getTopInventory().getSize(), new TaggedItem(shopEntryIdKey, SHOP_ENTRY_ID)
                );
            }
            if (holder instanceof ShopGui.ConfirmHolder confirm
                && SHOP_ID.equals(confirm.shopId()) && SHOP_ENTRY_ID.equals(confirm.entryId())) {
                return topMark(view, windowId, ShopGui.BUY_SLOT, PurchaseButton.INSTANCE);
            }
        }
        return null;
    }

    private static @Nullable Mark equipmentMark(
        InventoryView view, int windowId, MenuInventoryHolder menu, UUID playerId, String step,
        boolean recentEquipSuccess
    ) {
        if (menu.equipmentReadOnly()
            || (menu.equipmentTargetId() != null && !playerId.equals(menu.equipmentTargetId()))) {
            return null;
        }

        Guard weapon = new AstralItem(STARTER_WEAPON_ID);
        if (EQUIP_STEP.equals(step)) {
            int lowerStart = view.getTopInventory().getSize();
            Mark bag = firstBagItem(view, windowId, lowerStart, lowerStart + STORAGE_SIZE, weapon);
            if (bag != null) {
                return bag;
            }
        } else if (!DEFEAT_STEP.equals(step) || !recentEquipSuccess) {
            return null;
        }
        return topMark(view, windowId, EQUIPMENT_MAIN_HAND_SLOT, weapon);
    }

    private static @Nullable Mark firstBagItem(InventoryView view, int windowId, int from, int to, Guard guard) {
        for (int rawSlot = from; rawSlot < Math.min(to, view.countSlots()); rawSlot++) {
            int playerSlot = playerSlot(view, rawSlot);
            if (playerSlot >= 0 && guard.matches(view.getItem(rawSlot))) {
                return new Mark(windowId, rawSlot, playerSlot, guard);
            }
        }
        return null;
    }

    /** プレイヤーの2×2クラフト画面は結果を含む5枠。作業台の下段は対象外。 */
    private static boolean isPlayerCraftingView(InventoryView view) {
        return view.getTopInventory() instanceof CraftingInventory crafting && crafting.getSize() == 5;
    }

    static int playerSlot(InventoryView view, int rawSlot) {
        int first = isPlayerCraftingView(view) ? FIRST_BAG_SLOT : view.getTopInventory().getSize();
        if (rawSlot < first || rawSlot >= first + STORAGE_SIZE || rawSlot >= view.countSlots()
            || !(view.getBottomInventory() instanceof PlayerInventory bottom)
            || !bottom.equals(view.getInventory(rawSlot))) {
            return -1;
        }
        int slot = view.convertSlot(rawSlot);
        return slot >= 0 && slot < STORAGE_SIZE ? slot : -1;
    }

    private static @Nullable Mark firstTopItem(InventoryView view, int windowId, int limit, Guard guard) {
        int end = Math.min(limit, view.getTopInventory().getSize());
        for (int rawSlot = 0; rawSlot < end; rawSlot++) {
            if (guard.matches(view.getItem(rawSlot))) {
                return new Mark(windowId, rawSlot, -1, guard);
            }
        }
        return null;
    }

    private static @Nullable Mark topMark(InventoryView view, int windowId, int rawSlot, Guard guard) {
        if (rawSlot >= view.getTopInventory().getSize() || !guard.matches(view.getItem(rawSlot))) {
            return null;
        }
        return new Mark(windowId, rawSlot, -1, guard);
    }

    private static @Nullable Mark menuButton(InventoryView view, int windowId, int rawSlot) {
        if (rawSlot >= view.getTopInventory().getSize()) {
            return null;
        }
        ItemStack item = view.getItem(rawSlot);
        if (!present(item)) {
            return null;
        }
        ItemMeta meta = item.getItemMeta();
        return new Mark(windowId, rawSlot, -1, new MenuButton(item.getType(), meta == null ? null : meta.displayName()));
    }

    private static boolean present(@Nullable ItemStack item) {
        return item != null && item.getType() != Material.AIR;
    }

    private record MailPage(NamespacedKey key, Set<String> unreadIds, int size) {
    }

    record Mark(int windowId, int rawSlot, int playerSlot, Guard guard, @Nullable MailPage mailPage) {
        Mark(int windowId, int rawSlot, int playerSlot, Guard guard) {
            this(windowId, rawSlot, playerSlot, guard, null);
        }

        boolean acceptsWindowSlot(int id, int slot) {
            return targetsWindowSlot(id, slot)
                || (mailPage != null && id > 0 && windowId == id
                    && slot >= 0 && slot < mailPage.size());
        }

        boolean targetsWindowSlot(int id, int slot) {
            return id >= 0 && slot >= 0 && windowId == id && rawSlot == slot;
        }

        boolean targetsPlayerSlot(int slot) {
            return slot >= 0 && slot < STORAGE_SIZE && playerSlot == slot;
        }
    }

    interface Guard {
        boolean matches(@Nullable ItemStack item);
    }

    record AstralItem(String id) implements Guard {
        @Override
        public boolean matches(@Nullable ItemStack item) {
            return present(item) && id.equals(ItemStackFactory.getAstralItemId(item));
        }
    }

    record TaggedItem(NamespacedKey key, String id) implements Guard {
        @Override
        public boolean matches(@Nullable ItemStack item) {
            ItemMeta meta = present(item) ? item.getItemMeta() : null;
            return meta != null && id.equals(meta.getPersistentDataContainer().get(key, PersistentDataType.STRING));
        }
    }

    private record MenuButton(Material material, @Nullable Component name) implements Guard {
        @Override
        public boolean matches(@Nullable ItemStack item) {
            if (item == null || item.getType() != material) {
                return false;
            }
            ItemMeta meta = item.getItemMeta();
            return Objects.equals(name, meta == null ? null : meta.displayName());
        }
    }

    private enum PurchaseButton implements Guard {
        INSTANCE;

        private static final Component CONFIRM_LORE =
            Component.text("◆ 購入確認 ◆", NamedTextColor.GOLD, TextDecoration.BOLD)
                .decoration(TextDecoration.ITALIC, false);

        @Override
        public boolean matches(@Nullable ItemStack item) {
            if (item == null
                || (item.getType() != Material.GREEN_TERRACOTTA && item.getType() != Material.RED_TERRACOTTA)) {
                return false;
            }
            // 購入確認の先頭行を照合し、共通素材の販売停止ボタンや処理中の差し替えを除外します。
            ItemMeta meta = item.getItemMeta();
            List<Component> lore = meta == null ? null : meta.lore();
            return lore != null && !lore.isEmpty() && CONFIRM_LORE.equals(lore.get(0));
        }
    }

    private record Presentation(@Nullable Integer windowId, @Nullable Mark mark, boolean restorePending) {
        boolean needsRestore() {
            return mark != null || restorePending;
        }
    }

    private static final class Session {
        private final Player player;
        private final AtomicReference<Presentation> presentation =
            new AtomicReference<>(new Presentation(null, null, false));

        // 手順の履歴はメインスレッドで、この接続の同一アカウントについてだけ記録します。
        private @Nullable UUID accountId;
        private @Nullable String step;
        private long equipSuccessUntil;

        private Session(Player player) {
            this.player = player;
        }

        private void resetWindow(@Nullable Integer windowId) {
            // 閉じた時点で送信対象を無効にし、旧表示の同期は次の画面が確定した後に行います。
            presentation.updateAndGet(previous -> new Presentation(windowId, null, previous.needsRestore()));
        }

        private boolean observeStep(@Nullable UUID nextAccount, @Nullable String nextStep, long now) {
            if (nextAccount == null || !nextAccount.equals(accountId) || !DEFEAT_STEP.equals(nextStep)) {
                equipSuccessUntil = 0;
            } else if (EQUIP_STEP.equals(step)) {
                equipSuccessUntil = now + EQUIP_SUCCESS_NANOS;
            }
            accountId = nextAccount;
            step = nextStep;
            return equipSuccessUntil != 0 && equipSuccessUntil - now > 0;
        }
    }
}
