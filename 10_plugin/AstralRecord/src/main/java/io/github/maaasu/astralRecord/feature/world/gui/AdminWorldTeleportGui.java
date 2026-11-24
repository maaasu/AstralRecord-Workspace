package io.github.maaasu.astralRecord.feature.world.gui;

import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgResource;
import io.github.maaasu.astralRecord.feature.world.model.WorldMasterData;
import io.github.maaasu.astralRecord.feature.world.model.WorldType;
import io.github.maaasu.astralRecord.infrastructure.util.ColorCodeUtil;
import io.github.maaasu.astralRecord.infrastructure.util.MaterialNameResolver;
import io.github.maaasu.astralRecord.shared.gui.GuiItems;
import io.github.maaasu.astralRecord.shared.gui.GuiOpenSupport;
import io.github.maaasu.astralRecord.shared.gui.GuiPagination;
import io.github.maaasu.astralRecord.shared.gui.navigation.GuiNavigationDestination;
import io.github.maaasu.astralRecord.shared.gui.paging.PagedGuiView;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 管理者用ワールドテレポート先一覧 GUI を描画します。
 */
public final class AdminWorldTeleportGui {
    public static final int SIZE = PagedGuiView.SIZE;
    public static final int CONTENT_SLOT_COUNT = PagedGuiView.CONTENT_SLOT_COUNT;
    public static final int PREVIOUS_SLOT = PagedGuiView.PREVIOUS_SLOT;
    public static final int BACK_SLOT = PagedGuiView.BACK_SLOT;
    public static final int NEXT_SLOT = PagedGuiView.NEXT_SLOT;

    private static final int DIVIDER_SLOT = 4;
    private static final int CATEGORY_INFO_SLOT = 47;
    private static final int OTHER_SLOT = NEXT_SLOT;

    private final Path worldContainer;
    private final Path worldsRoot;

    /**
     * ワールドフォルダーを分類するための起点を設定します。
     *
     * @param worldContainer Bukkit が相対ワールドパスを解決するディレクトリ
     * @param worldsRoot プラグインの worlds ディレクトリ
     */
    public AdminWorldTeleportGui(@NotNull Path worldContainer, @NotNull Path worldsRoot) {
        this.worldContainer = worldContainer.toAbsolutePath().normalize();
        this.worldsRoot = worldsRoot.toAbsolutePath().normalize();
    }

    /**
     * Bukkit のメインスレッドでワールド一覧の概要を開きます。
     *
     * @param player 表示対象プレイヤー
     * @param worlds 表示対象ワールド
     */
    public void open(@NotNull Player player, @NotNull List<WorldMasterData> worlds) {
        Holder holder = new Holder();
        Inventory inventory = holder.getInventory();
        renderOverview(inventory, holder, worlds);
        GuiOpenSupport.open(player, inventory);
    }

    /**
     * カテゴリ選択、ページ移動、概要へ戻る操作を同じインベントリ内で処理します。
     *
     * @param inventory Bukkit のメインスレッドで更新するインベントリ
     * @param worlds 現在の表示対象ワールド
     * @param rawSlot クリックされたスロット番号
     * @return 画面を再描画した場合は {@code true}
     */
    public boolean navigate(@NotNull Inventory inventory, @NotNull List<WorldMasterData> worlds, int rawSlot) {
        Holder holder = holder(inventory);
        if (holder == null) {
            return false;
        }

        Category selected = holder.category;
        if (selected == null) {
            Category category = holder.categoriesBySlot.get(rawSlot);
            if (category == null) {
                return false;
            }
            renderCategory(inventory, holder, category, worldsIn(category, worlds), 0);
            return true;
        }

        if (rawSlot == BACK_SLOT) {
            renderOverview(inventory, holder, worlds);
            return true;
        }
        if (rawSlot != PREVIOUS_SLOT && rawSlot != NEXT_SLOT) {
            return false;
        }

        List<WorldMasterData> entries = worldsIn(selected, worlds);
        int pageIndex = GuiPagination.normalizePage(holder.pageIndex, entries.size(), CONTENT_SLOT_COUNT);
        if (pageIndex != holder.pageIndex) {
            renderCategory(inventory, holder, selected, entries, pageIndex);
            return true;
        }
        if (rawSlot == PREVIOUS_SLOT && GuiPagination.hasPreviousPage(pageIndex)) {
            renderCategory(inventory, holder, selected, entries, pageIndex - 1);
            return true;
        }
        if (rawSlot == NEXT_SLOT && GuiPagination.hasNextPage(pageIndex, entries.size(), CONTENT_SLOT_COUNT)) {
            renderCategory(inventory, holder, selected, entries, pageIndex + 1);
            return true;
        }
        return false;
    }

    /**
     * 指定インベントリがこの GUI か判定します。
     *
     * @param inventory 判定対象
     * @return この GUI なら {@code true}
     */
    public boolean isInventory(@Nullable Inventory inventory) {
        return holder(inventory) != null;
    }

    /**
     * GUI の Holder を取得します。
     *
     * @param inventory 対象インベントリ
     * @return Holder。対象外なら {@code null}
     */
    public @Nullable Holder holder(@Nullable Inventory inventory) {
        if (inventory != null && inventory.getHolder() instanceof Holder holder) {
            return holder;
        }
        return null;
    }

    private void renderOverview(
            @NotNull Inventory inventory, @NotNull Holder holder, @NotNull List<WorldMasterData> worlds
    ) {
        Map<Category, List<WorldMasterData>> grouped = new EnumMap<>(Category.class);
        for (WorldMasterData world : worlds) {
            grouped.computeIfAbsent(classify(world), ignored -> new ArrayList<>()).add(world);
        }

        ItemStack spacer = resetContents(inventory);
        inventory.setItem(DIVIDER_SLOT, spacer);
        inventory.setItem(BACK_SLOT, GuiItems.closeButton());

        Map<Integer, String> worldIdsBySlot = new LinkedHashMap<>();
        Map<Integer, Category> categoriesBySlot = new LinkedHashMap<>();
        for (Category category : Category.values()) {
            List<WorldMasterData> entries = grouped.getOrDefault(category, List.of());
            if (category == Category.OTHER && entries.isEmpty()) {
                continue;
            }

            int headerSlot = category.overviewSlot;
            inventory.setItem(headerSlot, categoryItem(category, entries.size(), 0, true));
            categoriesBySlot.put(headerSlot, category);
            int previewCount = Math.min(category.previewCount, entries.size());
            for (int index = 0; index < previewCount; index++) {
                placeDestination(inventory, worldIdsBySlot, headerSlot + index + 1, entries.get(index));
            }
        }

        holder.update(null, 0, worldIdsBySlot, categoriesBySlot);
    }

    private void renderCategory(
            @NotNull Inventory inventory,
            @NotNull Holder holder,
            @NotNull Category category,
            @NotNull List<WorldMasterData> entries,
            int pageIndex
    ) {
        resetContents(inventory);

        int start = GuiPagination.pageStart(pageIndex, CONTENT_SLOT_COUNT);
        int end = GuiPagination.pageEnd(pageIndex, entries.size(), CONTENT_SLOT_COUNT);
        Map<Integer, String> worldIdsBySlot = new LinkedHashMap<>();
        for (int index = start; index < end; index++) {
            placeDestination(inventory, worldIdsBySlot, index - start, entries.get(index));
        }

        inventory.setItem(CATEGORY_INFO_SLOT, categoryItem(category, entries.size(), pageIndex, false));
        inventory.setItem(PREVIOUS_SLOT, GuiItems.previousPageButton(
                PlayerMsgResource.getComponent(PlayerMsgId.P_5656.getId()), List.of(),
                GuiPagination.hasPreviousPage(pageIndex)
        ));
        inventory.setItem(BACK_SLOT, GuiItems.backButton(new GuiNavigationDestination(
                Material.COMPASS, ColorCodeUtil.stripColor(PlayerMsgResource.getMessage(PlayerMsgId.P_7161.getId()))
        )));
        inventory.setItem(NEXT_SLOT, GuiItems.nextPageButton(
                PlayerMsgResource.getComponent(PlayerMsgId.P_5657.getId()), List.of(),
                GuiPagination.hasNextPage(pageIndex, entries.size(), CONTENT_SLOT_COUNT)
        ));
        holder.update(category, pageIndex, worldIdsBySlot, Map.of());
    }

    private @NotNull ItemStack resetContents(@NotNull Inventory inventory) {
        inventory.clear();
        ItemStack spacer = GuiItems.create(Material.GRAY_STAINED_GLASS_PANE, Component.empty(), List.of());
        for (int slot = CONTENT_SLOT_COUNT; slot < SIZE; slot++) {
            inventory.setItem(slot, spacer);
        }
        return spacer;
    }

    private void placeDestination(
            @NotNull Inventory inventory, @NotNull Map<Integer, String> worldIdsBySlot,
            int slot, @NotNull WorldMasterData world
    ) {
        inventory.setItem(slot, destinationItem(world));
        worldIdsBySlot.put(slot, world.id());
    }

    private @NotNull List<WorldMasterData> worldsIn(
            @NotNull Category category, @NotNull List<WorldMasterData> worlds
    ) {
        return worlds.stream().filter(world -> classify(world) == category).toList();
    }

    private @NotNull Category classify(@NotNull WorldMasterData world) {
        if (world.worldType() == WorldType.TEMP) {
            return Category.TEMP;
        }
        try {
            Path source = worldContainer.getFileSystem()
                    .getPath(world.baseWorldPath().trim().replace('\\', '/')).normalize();
            Path resolved = worldContainer.resolve(source).normalize();
            if (!resolved.startsWith(worldsRoot) && !source.isAbsolute()) {
                // WorldService はコンテナーの相対パス全体や末尾名を含むパスも受け付けます。
                resolved = source.toAbsolutePath().normalize();
                if (!resolved.startsWith(worldsRoot) && source.getNameCount() > 1
                        && source.getName(0).equals(worldContainer.getFileName())) {
                    resolved = worldContainer.getParent().resolve(source).normalize();
                }
            }
            if (resolved.startsWith(worldsRoot)) {
                Path relative = worldsRoot.relativize(resolved);
                if (relative.getNameCount() >= 2) {
                    for (Category category : Category.values()) {
                        if (category.folder == null || category == Category.TEMP) {
                            continue;
                        }
                        if (relative.getName(0).equals(relative.getFileSystem().getPath(category.folder))) {
                            return category;
                        }
                    }
                }
            }
        } catch (InvalidPathException ignored) {
            // フォルダーを判定できないワールドも移動先の一覧に残します。
        }
        return Category.OTHER;
    }

    private @NotNull ItemStack categoryItem(@NotNull Category category, int count, int pageIndex, boolean overview) {
        List<Component> lore = new ArrayList<>();
        if (category.folder != null) {
            lore.add(Component.text(category.folder, NamedTextColor.GRAY));
        }
        if (overview) {
            lore.add(PlayerMsgResource.formatPlainComponent(PlayerMsgId.P_7165.getId(), count));
            lore.add(PlayerMsgResource.getComponent(PlayerMsgId.P_7166.getId()));
        } else {
            int pageCount = GuiPagination.totalPages(count, CONTENT_SLOT_COUNT);
            lore.add(PlayerMsgResource.formatPlainComponent(PlayerMsgId.P_5655.getId(), count, pageIndex + 1, pageCount));
        }

        Component name = Component.text(category.displayName(), NamedTextColor.AQUA, TextDecoration.BOLD);
        return GuiItems.create(category.material, name, lore);
    }

    private @NotNull ItemStack destinationItem(@NotNull WorldMasterData world) {
        List<Component> lore = new ArrayList<>();
        if (world.description().isBlank()) {
            lore.add(PlayerMsgResource.getComponent(PlayerMsgId.P_7167.getId()));
        } else {
            for (String line : world.description().split("\\R", -1)) {
                lore.add(ColorCodeUtil.toComponent(line, PlayerMsgResource.getMessage(PlayerMsgId.P_7167.getId()))
                        .decoration(TextDecoration.ITALIC, false));
            }
        }
        lore.add(PlayerMsgResource.formatPlainComponent(
                PlayerMsgId.P_7168.getId(), world.worldType().getRegionDisplayName()));
        lore.add(PlayerMsgResource.getComponent(PlayerMsgId.P_7169.getId()));

        Component displayName = ColorCodeUtil.toComponent(
                world.displayName(),
                world.worldType().getRegionDisplayName()
        ).decorate(TextDecoration.BOLD);
        Material icon = world.worldType() == WorldType.TEMP ? Material.CLOCK : resolveIcon(world.guiIconMaterial());
        return GuiItems.create(icon, displayName, lore);
    }

    private @NotNull Material resolveIcon(@Nullable String materialName) {
        if (materialName != null && !materialName.isBlank()) {
            Material resolved = MaterialNameResolver.match(materialName);
            if (resolved != null && resolved.isItem()) {
                return resolved;
            }
        }
        return Material.GRASS_BLOCK;
    }

    private enum Category {
        BASE("base", Material.OAK_DOOR, 0, 3, WorldType.BASE),
        HUB("hub", Material.BEACON, 5, 3, WorldType.HUB),
        OVERWORLD("overworld", Material.MAP, 9, 8, WorldType.OVERWORLD),
        BOSS_FIELD("boss_field", Material.GOLDEN_SWORD, 18, 8, WorldType.BOSS_FIELD),
        SYSTEM("system", Material.COMMAND_BLOCK, 27, 8, PlayerMsgId.P_7162),
        TEMP("system/_temp", Material.RECOVERY_COMPASS, 36, 8, PlayerMsgId.P_7163),
        OTHER(null, Material.CHEST, OTHER_SLOT, 0, PlayerMsgId.P_7164);

        private final @Nullable String folder;
        private final Material material;
        private final int overviewSlot;
        private final int previewCount;
        private final Supplier<String> displayName;

        Category(
                @Nullable String folder, Material material, int overviewSlot, int previewCount, WorldType labelType
        ) {
            this(folder, material, overviewSlot, previewCount, labelType::getRegionDisplayName);
        }

        Category(
                @Nullable String folder, Material material, int overviewSlot, int previewCount, PlayerMsgId labelId
        ) {
            this(folder, material, overviewSlot, previewCount, () -> PlayerMsgResource.getMessage(labelId.getId()));
        }

        Category(
                @Nullable String folder, Material material, int overviewSlot, int previewCount,
                Supplier<String> displayName
        ) {
            this.folder = folder;
            this.material = material;
            this.overviewSlot = overviewSlot;
            this.previewCount = previewCount;
            this.displayName = displayName;
        }

        private @NotNull String displayName() {
            return displayName.get();
        }
    }

    /**
     * GUI のカテゴリ、ページ、スロット別ワールド ID を保持します。
     */
    public static final class Holder implements InventoryHolder {
        private final Inventory inventory = Bukkit.createInventory(
                this, SIZE, PlayerMsgResource.getComponent(PlayerMsgId.P_7161.getId()));
        private @Nullable Category category;
        private int pageIndex;
        private Map<Integer, String> worldIdsBySlot = Map.of();
        private Map<Integer, Category> categoriesBySlot = Map.of();

        private void update(
                @Nullable Category category,
                int pageIndex,
                @NotNull Map<Integer, String> worldIdsBySlot,
                @NotNull Map<Integer, Category> categoriesBySlot
        ) {
            this.category = category;
            this.pageIndex = pageIndex;
            this.worldIdsBySlot = Map.copyOf(worldIdsBySlot);
            this.categoriesBySlot = Map.copyOf(categoriesBySlot);
        }

        /**
         * 概要を表示しているか判定します。
         *
         * @return 概要を表示している場合は {@code true}
         */
        public boolean isOverview() {
            return category == null;
        }

        /**
         * 現在画面の GUI スロットと内部ワールド ID の対応を返します。
         *
         * @return スロット番号から内部ワールド ID への不変対応表
         */
        public @NotNull Map<Integer, String> worldIdsBySlot() {
            return worldIdsBySlot;
        }

        /**
         * この Holder が所有する GUI インベントリを返します。
         *
         * @return GUI 用インベントリ
         */
        @Override
        public @NotNull Inventory getInventory() {
            return inventory;
        }
    }
}
