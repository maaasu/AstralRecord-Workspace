package io.github.maaasu.astralRecord.feature.skill.gui;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.item.model.ItemSigilModifier;
import io.github.maaasu.astralRecord.feature.item.service.ItemService;
import io.github.maaasu.astralRecord.feature.skill.model.LearnedSkillSigil;
import io.github.maaasu.astralRecord.feature.skill.model.SkillBindInventoryHolder;
import io.github.maaasu.astralRecord.feature.skill.model.SkillBindPreset;
import io.github.maaasu.astralRecord.feature.skill.model.SkillBindScreen;
import io.github.maaasu.astralRecord.feature.skill.model.SkillBindSession;
import io.github.maaasu.astralRecord.feature.skill.model.SkillBindType;
import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.feature.skill.model.SkillManagerEntry;
import io.github.maaasu.astralRecord.feature.skill.service.SkillPresentationUtil;
import io.github.maaasu.astralRecord.feature.status.model.StatusType;
import io.github.maaasu.astralRecord.infrastructure.util.MaterialNameResolver;
import io.github.maaasu.astralRecord.shared.gui.GuiPagination;
import io.github.maaasu.astralRecord.shared.gui.confirm.ConfirmDialogView;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 習得・強化・シジル合成・バインドを統合したスキルマネージャー GUI です。 */
public final class SkillBindGui {
    public static final int SIZE = 54;
    /** 通常攻撃を slot 0 に常設するため、一覧の 1 ページ容量は 26 件です。 */
    public static final int CONTENT_SLOT_COUNT = 26;
    public static final int PASSIVE_BIND_SLOT_START = 27;
    public static final int NORMAL_ATTACK_SLOT = 0;
    public static final int LEFT_CLICK_BIND_SLOT = 37;
    public static final int ACTION_RING_BIND_SLOT_START = 39;
    public static final int PREVIOUS_PAGE_SLOT = 45;
    public static final int PRESET_SLOT_START = 46;
    public static final int BACK_SLOT = 49;
    public static final int NEXT_PAGE_SLOT = 53;
    public static final int PRESET_COUNT = 6;

    public static final int SYNTHESIS_SKILL_SLOT = 20;
    public static final int SYNTHESIS_MATERIAL_SLOT = 22;
    public static final int SYNTHESIS_RESULT_SLOT = 24;

    private static final Material DEFAULT_SKILL_ICON = Material.AMETHYST_SHARD;
    private final NamespacedKey learnedSkillIdKey;
    private final NamespacedKey dummyKey;
    private final ItemService itemService;
    private final ConfirmDialogView confirmDialogView = new ConfirmDialogView();

    public SkillBindGui(@NotNull AstralRecord plugin, @NotNull ItemService itemService) {
        learnedSkillIdKey = new NamespacedKey(plugin, "skill_manager_learned_skill_id");
        dummyKey = new NamespacedKey(plugin, "skill_manager_dummy");
        this.itemService = itemService;
    }

    public void open(
        @NotNull Player player,
        @NotNull SkillBindSession session,
        @NotNull List<SkillManagerEntry> entries,
        @NotNull Map<String, SkillManagerEntry> entryByBindingId,
        int activePassiveSlots,
        int pageIndex
    ) {
        int page = normalizePage(pageIndex, entries.size());
        int pages = totalPages(entries.size());
        Inventory inventory = Bukkit.createInventory(
            new SkillBindInventoryHolder(SkillBindScreen.MAIN, session.selectedPresetIndex(), page),
            SIZE,
            Component.text("スキルマネージャー " + (page + 1) + "/" + pages, NamedTextColor.AQUA)
        );
        fill(inventory);

        int start = GuiPagination.pageStart(page, CONTENT_SLOT_COUNT);
        int end = GuiPagination.pageEnd(page, entries.size(), CONTENT_SLOT_COUNT);
        for (int index = start; index < end; index++) {
            SkillManagerEntry entry = entries.get(index);
            inventory.setItem(index - start + 1, createLearnedSkillItem(entry));
        }

        for (int index = 0; index < SkillBindPreset.PASSIVE_SLOT_COUNT; index++) {
            inventory.setItem(
                PASSIVE_BIND_SLOT_START + index,
                createBindSlot(
                    SkillBindType.PASSIVE,
                    index,
                    session.passiveDraft().get(index),
                    entryByBindingId,
                    session.isSelectedBindSlot(SkillBindType.PASSIVE, index),
                    index < activePassiveSlots
                )
            );
        }
        inventory.setItem(NORMAL_ATTACK_SLOT, createNormalAttackItem());
        inventory.setItem(
            LEFT_CLICK_BIND_SLOT,
            createBindSlot(
                SkillBindType.LEFT_CLICK, 0, session.leftClickDraft(), entryByBindingId,
                session.isSelectedBindSlot(SkillBindType.LEFT_CLICK, 0), true
            )
        );
        for (int index = 0; index < SkillBindPreset.ACTION_RING_SLOT_COUNT; index++) {
            inventory.setItem(
                ACTION_RING_BIND_SLOT_START + index,
                createBindSlot(
                    SkillBindType.ACTIVE, index, session.activeDraft().get(index), entryByBindingId,
                    session.isSelectedBindSlot(SkillBindType.ACTIVE, index), true
                )
            );
        }

        if (page > 0) {
            inventory.setItem(PREVIOUS_PAGE_SLOT, createPreviousPageItem(page, pages));
        }
        for (int presetIndex = 1; presetIndex <= PRESET_COUNT; presetIndex++) {
            SkillBindPreset preset = session.presets().get(presetIndex - 1);
            inventory.setItem(presetSlot(presetIndex), createPresetItem(preset, presetIndex == session.selectedPresetIndex()));
        }
        inventory.setItem(BACK_SLOT, createItem(Material.SPECTRAL_ARROW, "戻る", NamedTextColor.WHITE, List.of()));
        if (page + 1 < pages) {
            inventory.setItem(NEXT_PAGE_SLOT, createNextPageItem(page, pages));
        }
        io.github.maaasu.astralRecord.shared.gui.GuiOpenSupport.open(player, inventory);
    }

    public void openSynthesis(
        @NotNull Player player,
        int selectedPresetIndex,
        int returnPage,
        @NotNull SkillManagerEntry entry,
        @Nullable ItemModel material,
        boolean validMaterial
    ) {
        Inventory inventory = Bukkit.createInventory(
            new SkillBindInventoryHolder(
                SkillBindScreen.SYNTHESIS,
                selectedPresetIndex,
                returnPage,
                entry.bindingId()
            ),
            SIZE,
            Component.text("スキル合成", NamedTextColor.LIGHT_PURPLE)
        );
        fill(inventory);
        inventory.setItem(SYNTHESIS_SKILL_SLOT, createLearnedSkillItem(entry));
        inventory.setItem(
            SYNTHESIS_MATERIAL_SLOT,
            material == null
                ? createItem(Material.LIGHT_GRAY_STAINED_GLASS_PANE, "素材を選択", NamedTextColor.GRAY,
                    List.of(Component.text("下のインベントリから同スキルのジェム、または対応シジルをクリック", NamedTextColor.YELLOW)))
                : createMaterialItem(material)
        );
        inventory.setItem(
            SYNTHESIS_RESULT_SLOT,
            createSynthesisResult(entry, material, validMaterial)
        );
        inventory.setItem(BACK_SLOT, createItem(
            Material.SPECTRAL_ARROW,
            "スキルマネージャーへ戻る",
            NamedTextColor.WHITE,
            List.of(Component.text("選択中の素材は消費せずに戻します。", NamedTextColor.GRAY))
        ));
        io.github.maaasu.astralRecord.shared.gui.GuiOpenSupport.open(player, inventory);
    }

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
        io.github.maaasu.astralRecord.shared.gui.GuiOpenSupport.open(player, inventory);
    }

    public @Nullable SkillBindInventoryHolder holder(@Nullable Inventory inventory) {
        if (inventory == null) return null;
        InventoryHolder holder = inventory.getHolder();
        return holder instanceof SkillBindInventoryHolder skillHolder ? skillHolder : null;
    }

    public boolean isInventory(@Nullable Inventory inventory) { return holder(inventory) != null; }

    public @Nullable String learnedSkillId(@Nullable ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        return item.getItemMeta().getPersistentDataContainer().get(learnedSkillIdKey, PersistentDataType.STRING);
    }

    public int normalizePage(int pageIndex, int count) {
        return GuiPagination.normalizePage(pageIndex, count, CONTENT_SLOT_COUNT);
    }

    public int totalPages(int count) {
        return GuiPagination.totalPages(count, CONTENT_SLOT_COUNT);
    }

    public static int presetSlot(int presetIndex) {
        return switch (presetIndex) {
            case 1, 2, 3 -> PRESET_SLOT_START + presetIndex - 1;
            case 4, 5, 6 -> PRESET_SLOT_START + presetIndex;
            default -> -1;
        };
    }

    public static int presetIndexAtSlot(int slot) {
        return switch (slot) {
            case 46, 47, 48 -> slot - PRESET_SLOT_START + 1;
            case 50, 51, 52 -> slot - PRESET_SLOT_START;
            default -> -1;
        };
    }

    private ItemStack createLearnedSkillItem(SkillManagerEntry entry) {
        SkillDefinition skill = entry.definition();
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("個体ID: " + entry.learnedSkill().getLearnedSkillId(), NamedTextColor.DARK_GRAY));
        lore.addAll(SkillPresentationUtil.skillDescriptionAndLore(skill, NamedTextColor.GRAY));
        if (!lore.isEmpty()) {
            lore.add(Component.empty());
        }
        lore.add(Component.text(
            "種別: " + (skill.getKind().isPassive() ? "パッシブ" : "アクティブ"),
            NamedTextColor.GRAY
        ));
        lore.add(Component.text(
            entry.permitted() ? "現在のクラス／ツリーで使用可能" : "現在のクラスでは使用不可",
            entry.permitted() ? NamedTextColor.GREEN : NamedTextColor.RED
        ));
        if (skill.getKind().isPassive() && !skill.getPassiveBindRequired()) {
            lore.add(Component.text("所持中はバインド不要で発動", NamedTextColor.AQUA));
        }
        if (!entry.learnedSkill().getSigils().isEmpty()) {
            lore.add(Component.empty());
            lore.add(Component.text("装着済みシジル", NamedTextColor.LIGHT_PURPLE));
            for (LearnedSkillSigil sigil : entry.learnedSkill().getSigils()) {
                appendSigilEffectLore(lore, sigil);
            }
        }
        lore.add(Component.empty());
        lore.add(Component.text("左クリック: 選択中のバインド枠へ設定", NamedTextColor.YELLOW));
        lore.add(Component.text("右クリック: スキル合成を開く", NamedTextColor.LIGHT_PURPLE));
        ItemStack item = createItem(
            parseMaterial(skill.getIcon(), DEFAULT_SKILL_ICON),
            SkillPresentationUtil.skillNameComponent(skill, skill.getId(), NamedTextColor.WHITE)
                .append(Component.text(" Lv." + entry.learnedSkill().getLevel() + "/" + skill.getMaxLevel(), NamedTextColor.GOLD)),
            lore
        );
        return withBindingId(item, entry.bindingId());
    }

    private void appendSigilEffectLore(List<Component> lore, LearnedSkillSigil attached) {
        ItemModel item = itemService.findLoadedById(attached.getSigilId());
        if (item == null || item.getSigil() == null) {
            lore.add(Component.text("  - " + attached.getSigilId(), NamedTextColor.GRAY));
            return;
        }
        lore.add(Component.text("  - ", NamedTextColor.GRAY).append(
            SkillPresentationUtil.itemNameComponent(item, attached.getSigilId(), NamedTextColor.WHITE)
        ));
        for (ItemSigilModifier modifier : item.getSigil().getModifiers()) {
            StatusType status = StatusType.fromId(modifier.getStatus());
            String statusName = status == null ? "未定義ステータス" : status.getDisplayName();
            String suffix = status == null ? "" : status.getSuffix();
            String value = BigDecimal.valueOf(modifier.getValue()).stripTrailingZeros().toPlainString();
            if (modifier.getValue() > 0.0D) value = "+" + value;
            lore.add(Component.text("    " + statusName + " " + value + suffix, NamedTextColor.AQUA));
        }
        for (Component description : SkillPresentationUtil.itemLoreComponents(item, NamedTextColor.GRAY)) {
            lore.add(Component.text("    ", NamedTextColor.DARK_GRAY).append(description));
        }
    }

    private ItemStack createBindSlot(
        SkillBindType type,
        int index,
        String bindingId,
        Map<String, SkillManagerEntry> entries,
        boolean selected,
        boolean enabled
    ) {
        SkillManagerEntry entry = bindingId == null ? null : entries.get(bindingId);
        boolean normalAttack = SkillBindPreset.WEAPON_NORMAL_ATTACK_BINDING_ID.equals(bindingId);
        String label = switch (type) {
            case PASSIVE -> "パッシブスロット " + (index + 1);
            case LEFT_CLICK -> "左クリック";
            case ACTIVE -> "アクションスロット " + (index + 1);
        };
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text(enabled ? "有効枠" : "現在は無効な枠", enabled ? NamedTextColor.GREEN : NamedTextColor.RED));
        if (!enabled && bindingId != null) {
            lore.add(Component.text("設定は保持されますが機能しません。解除のみ可能です。", NamedTextColor.RED));
        } else if (bindingId == null) {
            lore.add(Component.text(enabled ? "クリックして設定先に選択" : "枠数を増やすまで設定不可", NamedTextColor.GRAY));
        } else {
            lore.add(Component.text("クリックで解除", NamedTextColor.YELLOW));
        }
        Material material = !enabled ? Material.IRON_BARS
            : bindingId == null ? Material.LIGHT_GRAY_STAINED_GLASS_PANE
            : normalAttack ? Material.IRON_SWORD
            : entry == null ? Material.BARRIER
            : parseMaterial(entry.definition().getIcon(), DEFAULT_SKILL_ICON);
        Component name = bindingId == null
            ? Component.text(label + "（未設定）", enabled ? NamedTextColor.WHITE : NamedTextColor.DARK_GRAY)
            : normalAttack
                ? Component.text(label + ": 武器通常攻撃", enabled ? NamedTextColor.WHITE : NamedTextColor.DARK_GRAY)
                : entry == null
                    ? Component.text(label + ": 不明な個体", enabled ? NamedTextColor.WHITE : NamedTextColor.DARK_GRAY)
                    : Component.text(label + ": ", enabled ? NamedTextColor.WHITE : NamedTextColor.DARK_GRAY)
                        .append(SkillPresentationUtil.skillNameComponent(
                            entry.definition(), entry.definition().getId(), NamedTextColor.WHITE
                        ))
                        .append(Component.text(" Lv." + entry.learnedSkill().getLevel(), NamedTextColor.GOLD));
        ItemStack item = createItem(material, name, lore);
        if (bindingId == null && (type == SkillBindType.PASSIVE || type == SkillBindType.ACTIVE)) {
            item.setAmount(index + 1);
        }
        return selected ? glow(item) : item;
    }

    private ItemStack createNormalAttackItem() {
        return withBindingId(createItem(
            Material.IRON_SWORD,
            "武器通常攻撃",
            NamedTextColor.WHITE,
            List.of(
                Component.text("武器タグ SWORD / BOW / STAFF から自動決定", NamedTextColor.GRAY),
                Component.text("左クリックまたはアクション枠を選択してからクリック", NamedTextColor.YELLOW)
            )
        ), SkillBindPreset.WEAPON_NORMAL_ATTACK_BINDING_ID);
    }

    private ItemStack createPreviousPageItem(int page, int pages) {
        return createItem(
            Material.ARROW,
            "前のページ " + page + " / " + pages,
            NamedTextColor.AQUA,
            List.of(Component.text("クリック: 前のページ", NamedTextColor.YELLOW))
        );
    }

    private ItemStack createNextPageItem(int page, int pages) {
        return createItem(
            Material.ARROW,
            "次のページ " + (page + 2) + " / " + pages,
            NamedTextColor.AQUA,
            List.of(Component.text("クリック: 次のページ", NamedTextColor.YELLOW))
        );
    }

    private ItemStack createPresetItem(SkillBindPreset preset, boolean selected) {
        Material material = !preset.isUnlocked() ? Material.GRAY_DYE : selected ? Material.LIME_DYE : Material.LIGHT_BLUE_DYE;
        ItemStack item = createItem(
            material,
            "プリセット " + preset.getPresetIndex(),
            selected ? NamedTextColor.GREEN : NamedTextColor.AQUA,
            List.of(Component.text(preset.isUnlocked() ? "解放済み" : "未解放", preset.isUnlocked() ? NamedTextColor.GREEN : NamedTextColor.RED))
        );
        item.setAmount(preset.getPresetIndex());
        return selected ? glow(item) : item;
    }

    private ItemStack createMaterialItem(ItemModel material) {
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("選択中の合成素材", NamedTextColor.GREEN));
        lore.add(Component.text("合成時に1個消費します。", NamedTextColor.RED));
        lore.addAll(SkillPresentationUtil.itemLoreComponents(material, NamedTextColor.GRAY));
        return createItem(
            parseMaterial(material.getIcon(), Material.PRISMARINE_CRYSTALS),
            SkillPresentationUtil.itemNameComponent(material, material.getId(), NamedTextColor.WHITE),
            lore
        );
    }

    private ItemStack createSynthesisResult(SkillManagerEntry entry, ItemModel material, boolean valid) {
        List<Component> lore = new ArrayList<>();
        if (material == null) {
            lore.add(Component.text("素材を選択してください。", NamedTextColor.GRAY));
        } else if (!valid) {
            lore.add(Component.text("この素材は合成できません。", NamedTextColor.RED));
        } else if (material.getSkillGem() != null) {
            lore.add(Component.text("Lv. " + entry.learnedSkill().getLevel() + " → " + (entry.learnedSkill().getLevel() + 1), NamedTextColor.GREEN));
            lore.add(Component.text("クリックでジェムを消費してレベルアップ", NamedTextColor.YELLOW));
        } else if (material.getSigil() != null) {
            lore.add(Component.text("装着: ", NamedTextColor.LIGHT_PURPLE).append(
                SkillPresentationUtil.itemNameComponent(material, material.getId(), NamedTextColor.WHITE)
            ));
            appendSigilModifierLore(lore, material);
            for (Component description : SkillPresentationUtil.itemLoreComponents(material, NamedTextColor.GRAY)) {
                lore.add(Component.text("  ", NamedTextColor.DARK_GRAY).append(description));
            }
            lore.add(Component.text("クリックでシジルを消費して装着（取り外し不可）", NamedTextColor.YELLOW));
        }
        return createItem(
            valid ? Material.NETHER_STAR : Material.BARRIER,
            valid ? "合成後のスキル" : "合成不可",
            valid ? NamedTextColor.GREEN : NamedTextColor.RED,
            lore
        );
    }

    private void fill(Inventory inventory) {
        ItemStack dummy = createItem(Material.BLACK_STAINED_GLASS_PANE, " ", NamedTextColor.BLACK, List.of());
        ItemMeta meta = dummy.getItemMeta();
        meta.getPersistentDataContainer().set(dummyKey, PersistentDataType.BYTE, (byte) 1);
        dummy.setItemMeta(meta);
        for (int slot = 0; slot < inventory.getSize(); slot++) inventory.setItem(slot, dummy.clone());
    }

    private ItemStack createItem(Material material, String name, NamedTextColor color, List<Component> lore) {
        return createItem(material, Component.text(name, color), lore);
    }

    private ItemStack createItem(Material material, Component name, List<Component> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(name);
        meta.lore(lore);
        meta.addItemFlags(ItemFlag.values());
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack withBindingId(ItemStack item, String bindingId) {
        ItemMeta meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(learnedSkillIdKey, PersistentDataType.STRING, bindingId);
        item.setItemMeta(meta);
        return item;
    }

    private void appendSigilModifierLore(List<Component> lore, ItemModel material) {
        for (ItemSigilModifier modifier : material.getSigil().getModifiers()) {
            StatusType status = StatusType.fromId(modifier.getStatus());
            String statusName = status == null ? "未定義ステータス" : status.getDisplayName();
            String suffix = status == null ? "" : status.getSuffix();
            String value = BigDecimal.valueOf(modifier.getValue()).stripTrailingZeros().toPlainString();
            if (modifier.getValue() > 0.0D) {
                value = "+" + value;
            }
            lore.add(Component.text("  " + statusName + " " + value + suffix, NamedTextColor.AQUA));
        }
    }

    private ItemStack glow(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        meta.addEnchant(Enchantment.UNBREAKING, 1, true);
        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        item.setItemMeta(meta);
        return item;
    }

    private Material parseMaterial(String raw, Material fallback) {
        Material resolved = MaterialNameResolver.match(raw);
        return resolved == null ? fallback : resolved;
    }
}
