package io.github.maaasu.astralRecord.feature.skill.gui;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.skill.model.SkillBindInventoryHolder;
import io.github.maaasu.astralRecord.feature.skill.model.SkillBindPreset;
import io.github.maaasu.astralRecord.feature.skill.model.SkillBindScreen;
import io.github.maaasu.astralRecord.feature.skill.model.SkillBindSession;
import io.github.maaasu.astralRecord.feature.skill.model.SkillBindType;
import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.feature.skill.service.SkillPresentationUtil;
import io.github.maaasu.astralRecord.infrastructure.util.ColorCodeUtil;
import io.github.maaasu.astralRecord.shared.gui.confirm.ConfirmDialogView;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
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
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * スキルバインド GUI を描画します。
 */
public final class SkillBindGui {
    public static final int SIZE = 54;
    public static final int CONTENT_SLOT_COUNT = 45;
    public static final int PREVIOUS_SLOT = 45;
    public static final int BACK_SLOT = 49;
    public static final int CLOSE_SLOT = 50;
    public static final int NEXT_SLOT = 53;
    public static final int PLAYER_CLOSE_SLOT = 4;
    public static final int SAVE_SLOT = 8;

    public static final int PRESET_SLOT_START = 9;
    public static final int PRESET_SLOT_END = 17;
    public static final int ACTIVE_BIND_SLOT_START = 18;
    public static final int ACTIVE_CLEAR_SLOT = 26;
    public static final int PASSIVE_BIND_SLOT_START = 27;
    public static final int PASSIVE_CLEAR_SLOT = 35;

    private static final Material DEFAULT_SKILL_ICON = Material.AMETHYST_SHARD;
    private static final String DUMMY_KEY_VALUE = "skill_bind_dummy";
    private static final String SKILL_ID_KEY_VALUE = "skill_bind_skill_id";

    private final NamespacedKey skillIdKey;
    private final NamespacedKey dummyKey;
    private final ConfirmDialogView confirmDialogView = new ConfirmDialogView();

    public SkillBindGui(@NotNull AstralRecord plugin) {
        this.skillIdKey = new NamespacedKey(plugin, SKILL_ID_KEY_VALUE);
        this.dummyKey = new NamespacedKey(plugin, DUMMY_KEY_VALUE);
    }

    /**
     * メイン GUI を開きます。
     */
    public void open(
        @NotNull Player player,
        @NotNull SkillBindSession session,
        @NotNull List<SkillDefinition> skills,
        @NotNull Map<String, SkillDefinition> bindSkillMap,
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

        renderTopInventory(inventory, skills, ownedSkillIds, normalizedPage);
        player.openInventory(inventory);

        renderPlayerInventoryControls(player.getInventory(), session, ownedSkillIds, bindSkillMap);
        player.updateInventory();
    }

    /**
     * 確認ダイアログを開きます。
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
        return holder(inventory) != null;
    }

    public @Nullable SkillBindInventoryHolder holder(@Nullable Inventory inventory) {
        if (inventory == null) {
            return null;
        }

        InventoryHolder holder = inventory.getHolder();
        if (!(holder instanceof SkillBindInventoryHolder)) {
            return null;
        }

        return (SkillBindInventoryHolder) holder;
    }

    public @Nullable String skillId(@Nullable ItemStack itemStack) {
        if (itemStack == null || !itemStack.hasItemMeta()) {
            return null;
        }

        ItemMeta meta = itemStack.getItemMeta();
        if (meta == null) {
            return null;
        }

        return meta.getPersistentDataContainer().get(skillIdKey, PersistentDataType.STRING);
    }

    public int normalizePage(int pageIndex, int itemCount) {
        int maxPage = totalPages(itemCount) - 1;
        if (pageIndex < 0) {
            return 0;
        }
        if (pageIndex > maxPage) {
            return maxPage;
        }
        return pageIndex;
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
        @NotNull List<SkillDefinition> skills,
        @NotNull Set<String> ownedSkillIds,
        int pageIndex
    ) {
        clearInventory(inventory, SIZE);

        int start = pageIndex * CONTENT_SLOT_COUNT;
        int end = Math.min(start + CONTENT_SLOT_COUNT, skills.size());
        for (int index = start; index < end; index++) {
            SkillDefinition skill = skills.get(index);
            boolean owned = ownedSkillIds.contains(skill.getId());
            inventory.setItem(index - start, createSkillItem(skill, owned));
        }

        ItemStack spacer = createItem(Material.GRAY_STAINED_GLASS_PANE, Component.text(" "), List.of());
        for (int slot = CONTENT_SLOT_COUNT; slot < SIZE; slot++) {
            inventory.setItem(slot, spacer);
        }

        if (hasPreviousPage(pageIndex)) {
            inventory.setItem(
                PREVIOUS_SLOT,
                createItem(Material.MAP, Component.text("前のページ", NamedTextColor.WHITE), List.of())
            );
        }

        inventory.setItem(
            BACK_SLOT,
            createItem(Material.SPECTRAL_ARROW, Component.text("戻る", NamedTextColor.WHITE), List.of())
        );

        if (hasNextPage(pageIndex, skills.size())) {
            inventory.setItem(
                NEXT_SLOT,
                createItem(Material.MAP, Component.text("次のページ", NamedTextColor.WHITE), List.of())
            );
        }
    }

    private void renderPlayerInventoryControls(
        @NotNull PlayerInventory inventory,
        @NotNull SkillBindSession session,
        @NotNull Set<String> ownedSkillIds,
        @NotNull Map<String, SkillDefinition> skillMap
    ) {
        fillManagedPlayerSlots(inventory);

        for (int index = 0; index < session.presets().size(); index++) {
            SkillBindPreset preset = session.presets().get(index);
            boolean selected = preset.getPresetIndex() == session.selectedPresetIndex();
            inventory.setItem(PRESET_SLOT_START + index, createPresetItem(preset, selected));
        }

        for (int index = 0; index < SkillBindPreset.SLOT_COUNT; index++) {
            inventory.setItem(
                ACTIVE_BIND_SLOT_START + index,
                createBindSlotItem(
                    SkillBindType.ACTIVE,
                    index,
                    session.activeDraft().get(index),
                    ownedSkillIds,
                    session.isSelectedBindSlot(SkillBindType.ACTIVE, index),
                    skillMap
                )
            );
            inventory.setItem(
                PASSIVE_BIND_SLOT_START + index,
                createBindSlotItem(
                    SkillBindType.PASSIVE,
                    index,
                    session.passiveDraft().get(index),
                    ownedSkillIds,
                    session.isSelectedBindSlot(SkillBindType.PASSIVE, index),
                    skillMap
                )
            );
        }

        inventory.setItem(
            ACTIVE_CLEAR_SLOT,
            createItem(Material.BARRIER, Component.text("アクティブ解除", NamedTextColor.RED), List.of())
        );
        inventory.setItem(
            PASSIVE_CLEAR_SLOT,
            createItem(Material.BARRIER, Component.text("パッシブ解除", NamedTextColor.RED), List.of())
        );
        inventory.setItem(
            SAVE_SLOT,
            createItem(
                Material.EMERALD,
                Component.text("保存", NamedTextColor.GREEN),
                List.of(
                    Component.text(
                        "プリセット " + session.selectedPresetIndex() + " に保存",
                        NamedTextColor.GRAY
                    )
                )
            )
        );
        inventory.setItem(
            PLAYER_CLOSE_SLOT,
            createItem(Material.BARRIER, Component.text("閉じる", NamedTextColor.RED), List.of())
        );
    }

    private void clearInventory(@NotNull Inventory inventory, int size) {
        for (int slot = 0; slot < size; slot++) {
            inventory.setItem(slot, new ItemStack(Material.AIR));
        }
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
        Material material;
        if (!preset.isUnlocked()) {
            material = Material.GRAY_DYE;
        } else if (selected) {
            material = Material.LIME_DYE;
        } else {
            material = Material.LIGHT_BLUE_DYE;
        }

        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("No. " + preset.getPresetIndex(), NamedTextColor.WHITE));
        lore.add(Component.text(preset.isSaved() ? "保存済み" : "未保存", NamedTextColor.GRAY));
        lore.add(
            Component.text(
                preset.isUnlocked() ? "解放済み" : "未解放",
                preset.isUnlocked() ? NamedTextColor.GREEN : NamedTextColor.RED
            )
        );

        Component name = Component.text(
            selected ? "選択中プリセット" : "プリセット",
            selected ? NamedTextColor.GREEN : NamedTextColor.AQUA
        );

        ItemStack itemStack = createItem(material, name, lore);
        itemStack.setAmount(Math.max(1, preset.getPresetIndex()));
        return itemStack;
    }

    private @NotNull ItemStack createBindSlotItem(
        @NotNull SkillBindType type,
        int index,
        @Nullable String skillId,
        @NotNull Set<String> ownedSkillIds,
        boolean selected,
        @NotNull Map<String, SkillDefinition> skillMap
    ) {
        boolean empty = skillId == null || skillId.isBlank();
        boolean owned = !empty && ownedSkillIds.contains(skillId);
        SkillDefinition skill = empty ? null : skillMap.get(skillId);
        boolean kindMatches = skill == null || matchesBindType(type, skill);

        Material material = empty
            ? Material.LIGHT_GRAY_STAINED_GLASS_PANE
            : kindMatches ? resolveSkillMaterial(skill, owned) : Material.BARRIER;
        String label = type == SkillBindType.ACTIVE ? "アクティブ" : "パッシブ";

        List<Component> lore = new ArrayList<>();
        lore.add(Component.text(label + "スロット " + (index + 1), NamedTextColor.GOLD));

        if (empty) {
            lore.add(Component.text("スキル一覧から選択するとここに設定されます。", NamedTextColor.GRAY));
            lore.add(Component.text("クリックでこの枠を選択できます。", NamedTextColor.YELLOW));
        } else if (skill == null) {
            lore.add(Component.text("未読込スキル", NamedTextColor.RED));
            lore.add(Component.text("スキル定義が見つかりません。", NamedTextColor.DARK_GRAY));
        } else {
            addSkillLore(lore, skill, owned);
            if (!kindMatches) {
                lore.add(Component.empty());
                lore.add(Component.text("このスロット種別には設定できません。", NamedTextColor.RED));
            }
            lore.add(Component.empty());
            lore.add(Component.text("クリックでこのスロットから外せます。", NamedTextColor.YELLOW));
        }

        if (selected) {
            lore.add(Component.empty());
            lore.add(Component.text("選択中: スキル一覧から選ぶとここに設定されます。", NamedTextColor.YELLOW));
        }

        Component name;
        if (empty) {
            name = Component.text(label + " 未設定", NamedTextColor.GRAY);
        } else {
            name = skillName(skill, skillId, owned);
        }

        ItemStack itemStack = createItem(material, name, lore);
        return selected ? withSelectionGlow(itemStack) : itemStack;
    }

    private @NotNull ItemStack createSkillItem(@NotNull SkillDefinition skill, boolean owned) {
        Material material = resolveSkillMaterial(skill, owned);

        List<Component> lore = new ArrayList<>();
        addSkillLore(lore, skill, owned);
        lore.add(Component.empty());
        lore.add(
            Component.text(
                owned ? "クリックで選択中スロットに設定します。" : "未習得のため設定できません。",
                owned ? NamedTextColor.YELLOW : NamedTextColor.RED
            )
        );

        ItemStack itemStack = createItem(material, skillName(skill, skill.getId(), owned), lore);
        ItemMeta meta = itemStack.getItemMeta();
        if (meta != null) {
            meta.getPersistentDataContainer().set(skillIdKey, PersistentDataType.STRING, skill.getId());
            itemStack.setItemMeta(meta);
        }
        return itemStack;
    }

    private @NotNull Material resolveSkillMaterial(@Nullable SkillDefinition skill, boolean owned) {
        if (skill != null) {
            return parseMaterial(skill.getIcon(), DEFAULT_SKILL_ICON);
        }
        return owned ? DEFAULT_SKILL_ICON : Material.GRAY_DYE;
    }

    private @NotNull Component skillName(@Nullable SkillDefinition skill, @NotNull String fallback, boolean owned) {
        if (skill == null || skill.getName().isBlank()) {
            return Component.text("未定義スキル", owned ? NamedTextColor.WHITE : NamedTextColor.GRAY);
        }
        return legacyComponent(skill.getName());
    }

    private void addSkillLore(@NotNull List<Component> lore, @NotNull SkillDefinition skill, boolean owned) {
        lore.add(Component.text("スキル名: " + SkillPresentationUtil.plainName(skill, "未定義スキル"), NamedTextColor.DARK_GRAY));
        lore.add(Component.text("種別: " + (skill.getKind().isPassive() ? "パッシブ" : "発動スキル"), NamedTextColor.GOLD));
        if (skill.getKind().isPassive()) {
            lore.add(Component.text(
                "発動条件: " + (skill.getPassiveBindRequired() ? "パッシブ枠への設定が必要" : "所持のみで常時発動"),
                NamedTextColor.GRAY
            ));
        }
        lore.add(
            Component.text(
                owned ? "習得済みスキル" : "未所持スキル",
                owned ? NamedTextColor.GREEN : NamedTextColor.RED
            )
        );
        if (!owned) {
            lore.add(Component.text("現在未所持のため、発動や新規設定はできません。", NamedTextColor.RED));
        }

        if (skill.getDescription() != null && !skill.getDescription().isBlank()) {
            lore.add(Component.empty());
            lore.add(Component.text("説明", NamedTextColor.GOLD));
            lore.add(legacyComponent(skill.getDescription()));
        }

        if (!skill.getLore().isEmpty()) {
            lore.add(Component.empty());
            lore.add(Component.text("詳細", NamedTextColor.GOLD));
            for (String line : skill.getLore()) {
                if (line != null && !line.isBlank()) {
                    lore.add(legacyComponent(line));
                }
            }
        }

        lore.add(Component.empty());
        lore.add(Component.text("性能", NamedTextColor.GOLD));
        lore.add(Component.text("クールダウン: " + formatTicks(skill.getCooldownTicks()), NamedTextColor.GRAY));
        lore.add(Component.text("詠唱: " + formatTicks(skill.getCastTimeTicks()), NamedTextColor.GRAY));
        lore.add(Component.text("消費MP: " + formatDecimal(skill.getManaCost()), NamedTextColor.AQUA));
        lore.add(Component.text("必要Lv: " + skill.getRequiredLevel(), NamedTextColor.GRAY));

        if (!skill.getTags().isEmpty()) {
            lore.add(Component.text("タグ: " + String.join(", ", skill.getTags()), NamedTextColor.DARK_GRAY));
        }
    }

    private boolean matchesBindType(@NotNull SkillBindType type, @NotNull SkillDefinition skill) {
        return type == SkillBindType.PASSIVE ? skill.getKind().isPassive() : !skill.getKind().isPassive();
    }

    private @NotNull String formatTicks(long ticks) {
        if (ticks <= 0L) {
            return "なし";
        }
        return ticks + " tick (" + String.format(Locale.ROOT, "%.1f", ticks / 20.0D) + "秒)";
    }

    private @NotNull String formatDecimal(double value) {
        if (value == Math.rint(value)) {
            return String.format(Locale.ROOT, "%.0f", value);
        }
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private @NotNull Component legacyComponent(@NotNull String text) {
        return LegacyComponentSerializer.legacySection().deserialize(
            ColorCodeUtil.translateAlternateColorCodes(text)
        );
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
