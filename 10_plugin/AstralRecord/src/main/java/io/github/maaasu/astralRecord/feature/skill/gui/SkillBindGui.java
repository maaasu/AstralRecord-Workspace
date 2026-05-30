package io.github.maaasu.astralRecord.feature.skill.gui;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.skill.model.SkillBindInventoryHolder;
import io.github.maaasu.astralRecord.feature.skill.model.SkillBindPreset;
import io.github.maaasu.astralRecord.feature.skill.model.SkillBindScreen;
import io.github.maaasu.astralRecord.feature.skill.model.SkillBindSession;
import io.github.maaasu.astralRecord.feature.skill.model.SkillBindType;
import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.infrastructure.util.ColorCodeUtil;
import io.github.maaasu.astralRecord.shared.gui.confirm.ConfirmDialogView;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * スキルバインド GUI を描画します。
 */
public final class SkillBindGui {
    public static final int SIZE = 54;
    public static final int CONTENT_SLOT_COUNT = 45;
    public static final int PREVIOUS_SLOT = 45;
    public static final int BACK_SLOT = 49;
    public static final int NEXT_SLOT = 52;
    public static final int SAVE_SLOT = 8;

    public static final int PRESET_SLOT_START = 9;
    public static final int PRESET_SLOT_END = 17;
    public static final int ACTIVE_BIND_SLOT_START = 0;
    public static final int ACTIVE_CLEAR_SLOT = 26;
    public static final int PASSIVE_BIND_SLOT_START = 27;
    public static final int PASSIVE_CLEAR_SLOT = 35;

    private final NamespacedKey skillIdKey;
    private final NamespacedKey dummyKey;
    private final ConfirmDialogView confirmDialogView = new ConfirmDialogView();

    public SkillBindGui(@NotNull AstralRecord plugin) {
        this.skillIdKey = new NamespacedKey(plugin, "skill_bind_skill_id");
        this.dummyKey = new NamespacedKey(plugin, "skill_bind_dummy");
    }

    /**
     * メイン GUI を開きます。
     */
    public void open(
        @NotNull Player player,
        @NotNull SkillBindSession session,
        @NotNull List<SkillDefinition> skills,
        @NotNull Set<String> ownedSkillIds,
        int pageIndex
    ) {
        int normalizedPage = normalizePage(pageIndex, skills.size());
        int totalPages = totalPages(skills.size());
        Inventory inventory = Bukkit.createInventory(
            new SkillBindInventoryHolder(SkillBindScreen.MAIN, session.selectedPresetIndex(), normalizedPage),
            SIZE,
            Component.text("スキル設定 " + (normalizedPage + 1) + "/" + totalPages, NamedTextColor.AQUA)
        );
        renderTopInventory(inventory, session, skills, ownedSkillIds, normalizedPage);
        player.openInventory(inventory);
        renderPlayerInventoryControls(player.getInventory(), session, ownedSkillIds);
        player.updateInventory();
    }

    /**
     * 確認ダイアログを開きます。プレイヤーインベントリ領域をダミーで埋めます。
     */
    public void openConfirm(
        @NotNull Player player,
        int selectedPresetIndex,
        @NotNull String action,
        int pendingPresetIndex,
        @NotNull Component message
    ) {
        Inventory inventory = Bukkit.createInventory(
            new SkillBindInventoryHolder(SkillBindScreen.CONFIRM, selectedPresetIndex, action, pendingPresetIndex),
            ConfirmDialogView.SIZE,
            Component.text("確認", NamedTextColor.YELLOW)
        );
        confirmDialogView.render(
            inventory,
            message,
            Component.text("確定", NamedTextColor.GREEN),
            Component.text("キャンセル", NamedTextColor.RED)
        );
        player.openInventory(inventory);
        fillPlayerInventoryDummy(player.getInventory());
        player.updateInventory();
    }

    public boolean isInventory(@Nullable Inventory inventory) {
        return inventory != null && inventory.getHolder() instanceof SkillBindInventoryHolder;
    }

    public @Nullable SkillBindInventoryHolder holder(@Nullable Inventory inventory) {
        return inventory != null && inventory.getHolder() instanceof SkillBindInventoryHolder holder ? holder : null;
    }

    public @Nullable String skillId(@Nullable ItemStack itemStack) {
        if (itemStack == null || !itemStack.hasItemMeta()) {
            return null;
        }
        ItemMeta meta = itemStack.getItemMeta();
        return meta == null ? null : meta.getPersistentDataContainer().get(skillIdKey, PersistentDataType.STRING);
    }

    public int normalizePage(int pageIndex, int itemCount) {
        return Math.clamp(pageIndex, 0, totalPages(itemCount) - 1);
    }

    public int totalPages(int itemCount) {
        return Math.max(1, (int) Math.ceil(itemCount / (double) CONTENT_SLOT_COUNT));
    }

    public boolean hasPreviousPage(int pageIndex) {
        return pageIndex > 0;
    }

    public boolean hasNextPage(int pageIndex, int itemCount) {
        return pageIndex + 1 < totalPages(itemCount);
    }

    public @NotNull List<SkillDefinition> sortedSkills(@NotNull Iterable<SkillDefinition> definitions) {
        List<SkillDefinition> skills = new ArrayList<>();
        for (SkillDefinition definition : definitions) {
            skills.add(definition);
        }
        skills.sort(Comparator.comparing(SkillDefinition::getId));
        return skills;
    }

    private void renderTopInventory(
        @NotNull Inventory inventory,
        @NotNull SkillBindSession session,
        @NotNull List<SkillDefinition> skills,
        @NotNull Set<String> ownedSkillIds,
        int pageIndex
    ) {
        for (int slot = 0; slot < SIZE; slot++) {
            inventory.setItem(slot, new ItemStack(Material.AIR));
        }

        int start = pageIndex * CONTENT_SLOT_COUNT;
        int end = Math.min(start + CONTENT_SLOT_COUNT, skills.size());
        for (int i = start; i < end; i++) {
            SkillDefinition skill = skills.get(i);
            inventory.setItem(
                i - start,
                createSkillItem(
                    skill,
                    ownedSkillIds.contains(skill.getId())
                )
            );
        }

        ItemStack spacer = createItem(Material.GRAY_STAINED_GLASS_PANE, Component.text(" "), List.of());
        for (int slot = CONTENT_SLOT_COUNT; slot < SIZE; slot++) {
            inventory.setItem(slot, spacer);
        }
        if (hasPreviousPage(pageIndex)) {
            inventory.setItem(PREVIOUS_SLOT, createItem(
                Material.MAP,
                Component.text("前のページ", NamedTextColor.WHITE),
                List.of()
            ));
        }
        inventory.setItem(BACK_SLOT, createItem(
            Material.ARROW,
            Component.text("戻る", NamedTextColor.WHITE),
            List.of()
        ));
        if (hasNextPage(pageIndex, skills.size())) {
            inventory.setItem(NEXT_SLOT, createItem(
                Material.MAP,
                Component.text("次のページ", NamedTextColor.WHITE),
                List.of()
            ));
        }
    }

    private void renderPlayerInventoryControls(
        @NotNull PlayerInventory inventory,
        @NotNull SkillBindSession session,
        @NotNull Set<String> ownedSkillIds
    ) {
        fillManagedPlayerSlots(inventory);
        for (int index = 0; index < session.presets().size(); index++) {
            SkillBindPreset preset = session.presets().get(index);
            inventory.setItem(
                PRESET_SLOT_START + index,
                createPresetItem(preset, preset.getPresetIndex() == session.selectedPresetIndex())
            );
        }
        for (int index = 0; index < SkillBindPreset.SLOT_COUNT; index++) {
            inventory.setItem(ACTIVE_BIND_SLOT_START + index, createBindSlotItem(
                SkillBindType.ACTIVE,
                index,
                session.activeDraft().get(index),
                ownedSkillIds,
                session.isSelectedBindSlot(SkillBindType.ACTIVE, index)
            ));
            inventory.setItem(PASSIVE_BIND_SLOT_START + index, createBindSlotItem(
                SkillBindType.PASSIVE,
                index,
                session.passiveDraft().get(index),
                ownedSkillIds,
                session.isSelectedBindSlot(SkillBindType.PASSIVE, index)
            ));
        }
        inventory.setItem(
            ACTIVE_CLEAR_SLOT,
            createItem(Material.BARRIER, Component.text("発動系クリア", NamedTextColor.RED), List.of())
        );
        inventory.setItem(
            PASSIVE_CLEAR_SLOT,
            createItem(Material.BARRIER, Component.text("パッシブ系クリア", NamedTextColor.RED), List.of())
        );
        inventory.setItem(SAVE_SLOT, createItem(
            Material.EMERALD,
            Component.text("保存", NamedTextColor.GREEN),
            List.of(Component.text("プリセット " + session.selectedPresetIndex() + " に保存", NamedTextColor.GRAY))
        ));
    }

    private void fillManagedPlayerSlots(@NotNull PlayerInventory inventory) {
        ItemStack dummy = createDummy();
        for (int slot = 0; slot < 36; slot++) {
            inventory.setItem(slot, dummy);
        }
    }

    private void fillPlayerInventoryDummy(@NotNull PlayerInventory inventory) {
        ItemStack dummy = createDummy();
        for (int slot = 0; slot < 36; slot++) {
            inventory.setItem(slot, dummy);
        }
    }

    private @NotNull ItemStack createPresetItem(@NotNull SkillBindPreset preset, boolean selected) {
        Material material = !preset.isUnlocked()
            ? Material.GRAY_DYE
            : selected ? Material.LIME_DYE : Material.LIGHT_BLUE_DYE;
        ItemStack itemStack = createItem(
            material,
            Component.text(selected ? "選択中プリセット" : "プリセット", selected ? NamedTextColor.GREEN : NamedTextColor.AQUA),
            List.of(
                Component.text("No. " + preset.getPresetIndex(), NamedTextColor.WHITE),
                Component.text(preset.isSaved() ? "保存済み" : "未保存", NamedTextColor.GRAY),
                Component.text(preset.isUnlocked() ? "解放済み" : "未解放", preset.isUnlocked() ? NamedTextColor.GREEN : NamedTextColor.RED)
            )
        );
        itemStack.setAmount(Math.max(1, preset.getPresetIndex()));
        return itemStack;
    }

    private @NotNull ItemStack createBindSlotItem(
        @NotNull SkillBindType type,
        int index,
        @Nullable String skillId,
        @NotNull Set<String> ownedSkillIds,
        boolean selected
    ) {
        boolean empty = skillId == null || skillId.isBlank();
        Material material = empty
            ? Material.LIGHT_GRAY_STAINED_GLASS_PANE
            : ownedSkillIds.contains(skillId) ? Material.ENCHANTED_BOOK : Material.BOOK;
        String label = type == SkillBindType.ACTIVE ? "発動系" : "パッシブ系";
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text(label + "スロット " + (index + 1), NamedTextColor.GRAY));
        if (!empty) {
            lore.add(Component.text(skillId, NamedTextColor.WHITE));
            if (!ownedSkillIds.contains(skillId)) {
                lore.add(Component.text("現在は未所持", NamedTextColor.RED));
            }
        }
        if (selected) {
            lore.add(Component.text("選択中", NamedTextColor.YELLOW));
        }
        ItemStack itemStack = createItem(
            material,
            Component.text(empty ? label + " 未設定" : skillId, empty ? NamedTextColor.GRAY : NamedTextColor.WHITE),
            lore
        );
        return selected ? withSelectionGlow(itemStack) : itemStack;
    }

    private @NotNull ItemStack createSkillItem(@NotNull SkillDefinition skill, boolean owned) {
        Material material = parseMaterial(skill.getIcon(), owned ? Material.BOOK : Material.GRAY_DYE);
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text(skill.getId(), NamedTextColor.DARK_GRAY));
        if (skill.getDescription() != null && !skill.getDescription().isBlank()) {
            lore.add(Component.text(ColorCodeUtil.translateAlternateColorCodes(skill.getDescription()), NamedTextColor.GRAY));
        }
        lore.add(Component.text(owned ? "所持スキル" : "未所持", owned ? NamedTextColor.GREEN : NamedTextColor.RED));
        ItemStack itemStack = createItem(
            material,
            Component.text(
                ColorCodeUtil.translateAlternateColorCodes(skill.getName()),
                owned ? NamedTextColor.WHITE : NamedTextColor.GRAY
            ),
            lore
        );
        ItemMeta meta = itemStack.getItemMeta();
        if (meta != null) {
            meta.getPersistentDataContainer().set(skillIdKey, PersistentDataType.STRING, skill.getId());
            itemStack.setItemMeta(meta);
        }
        return itemStack;
    }

    private @NotNull ItemStack withSelectionGlow(@NotNull ItemStack itemStack) {
        ItemStack glowing = itemStack.clone();
        ItemMeta meta = glowing.getItemMeta();
        if (meta != null) {
            meta.addEnchant(Enchantment.UNBREAKING, 1, true);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
            glowing.setItemMeta(meta);
        }
        return glowing;
    }

    private @NotNull ItemStack createDummy() {
        ItemStack itemStack = createItem(Material.GRAY_STAINED_GLASS_PANE, Component.text(" "), List.of());
        ItemMeta meta = itemStack.getItemMeta();
        if (meta != null) {
            meta.getPersistentDataContainer().set(dummyKey, PersistentDataType.INTEGER, 1);
            itemStack.setItemMeta(meta);
        }
        return itemStack;
    }

    private @NotNull ItemStack createItem(
        @NotNull Material material,
        @NotNull Component name,
        @NotNull List<Component> lore
    ) {
        ItemStack itemStack = new ItemStack(material);
        ItemMeta meta = itemStack.getItemMeta();
        if (meta != null) {
            meta.displayName(name);
            meta.lore(lore);
            meta.addItemFlags(ItemFlag.values());
            itemStack.setItemMeta(meta);
        }
        return itemStack;
    }

    private @NotNull Material parseMaterial(@Nullable String value, @NotNull Material fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        Material material = Material.matchMaterial(value.trim());
        return material == null ? fallback : material;
    }
}
