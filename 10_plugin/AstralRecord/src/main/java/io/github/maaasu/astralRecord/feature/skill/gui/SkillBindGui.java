package io.github.maaasu.astralRecord.feature.skill.gui;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.item.model.ItemSigilModifier;
import io.github.maaasu.astralRecord.feature.item.service.ItemService;
import io.github.maaasu.astralRecord.feature.skill.model.LearnedSkillSigil;
import io.github.maaasu.astralRecord.feature.skill.model.LearnedSkillInstance;
import io.github.maaasu.astralRecord.feature.skill.model.ResolvedLearnedSkill;
import io.github.maaasu.astralRecord.feature.skill.model.SkillBindInventoryHolder;
import io.github.maaasu.astralRecord.feature.skill.model.SkillBindPreset;
import io.github.maaasu.astralRecord.feature.skill.model.SkillBindScreen;
import io.github.maaasu.astralRecord.feature.skill.model.SkillBindSession;
import io.github.maaasu.astralRecord.feature.skill.model.SkillBindType;
import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.feature.skill.model.SkillManagerEntry;
import io.github.maaasu.astralRecord.feature.skill.model.SkillResourceType;
import io.github.maaasu.astralRecord.feature.skill.model.SkillSigilSlotDefinition;
import io.github.maaasu.astralRecord.feature.skill.service.SkillSynthesisMaterialEligibility.MaterialKind;
import io.github.maaasu.astralRecord.feature.skill.service.SkillPresentationUtil;
import io.github.maaasu.astralRecord.feature.skill.service.SkillService;
import io.github.maaasu.astralRecord.feature.status.model.StatusType;
import io.github.maaasu.astralRecord.infrastructure.util.MaterialNameResolver;
import io.github.maaasu.astralRecord.shared.gui.GuiPagination;
import io.github.maaasu.astralRecord.shared.gui.GuiItems;
import io.github.maaasu.astralRecord.shared.gui.confirm.ConfirmDialogView;
import io.github.maaasu.astralRecord.shared.display.DisplaySeparators;
import io.github.maaasu.astralRecord.shared.masterdata.tag.MasterTagIds;
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
import java.util.Set;
import java.util.UUID;

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
    private final SkillService skillService;
    private final ConfirmDialogView confirmDialogView = new ConfirmDialogView();

    public SkillBindGui(
        @NotNull AstralRecord plugin,
        @NotNull ItemService itemService,
        @NotNull SkillService skillService
    ) {
        learnedSkillIdKey = new NamespacedKey(plugin, "skill_manager_learned_skill_id");
        dummyKey = new NamespacedKey(plugin, "skill_manager_dummy");
        this.itemService = itemService;
        this.skillService = skillService;
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
            inventory.setItem(index - start + 1, createLearnedSkillItem(entry, true));
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
        if (shouldShowNormalAttack(page, session.selectedBindType())) {
            inventory.setItem(NORMAL_ATTACK_SLOT, createNormalAttackItem());
        }
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
        inventory.setItem(BACK_SLOT, GuiItems.backButton());
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
        @NotNull MaterialKind materialKind,
        boolean materialSelected
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
        inventory.setItem(SYNTHESIS_SKILL_SLOT, createLearnedSkillItem(entry, false));
        inventory.setItem(
            SYNTHESIS_MATERIAL_SLOT,
            material == null
                ? createItem(Material.LIGHT_GRAY_STAINED_GLASS_PANE, "素材を選択", NamedTextColor.GRAY,
                    List.of(Component.text("下のインベントリから同スキルのジェム、または対応シジルをクリック", NamedTextColor.YELLOW)))
                : materialSelected ? createMaterialItem(material) : createRejectedMaterialItem(material, materialKind)
        );
        inventory.setItem(
            SYNTHESIS_RESULT_SLOT,
            createSynthesisResult(entry, material, materialKind)
        );
        inventory.setItem(BACK_SLOT, createItem(
            Material.SPECTRAL_ARROW,
            "スキルマネージャーへ戻る",
            NamedTextColor.WHITE,
            List.of(Component.text("選択中の素材は消費せずに戻します。", NamedTextColor.GRAY))
        ));
        io.github.maaasu.astralRecord.shared.gui.GuiOpenSupport.open(player, inventory);
    }

    /**
     * 変更確認画面を開きます。
     *
     * @param player 表示対象プレイヤー
     * @param selectedPresetIndex 選択中プリセット番号
     * @param pageIndex 確認前に表示していた一覧ページ。キャンセル・切替後の復帰に引き継ぐ
     * @param action 確認後の操作
     * @param pendingPresetIndex 切替対象プリセット番号。切替以外では {@code -1}
     * @param message 確認メッセージ
     */
    public void openConfirm(
        @NotNull Player player,
        int selectedPresetIndex,
        int pageIndex,
        @NotNull String action,
        int pendingPresetIndex,
        @NotNull Component message
    ) {
        Inventory inventory = Bukkit.createInventory(
            new SkillBindInventoryHolder(
                SkillBindScreen.CONFIRM, selectedPresetIndex, pageIndex, action, pendingPresetIndex
            ),
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

    /** パッシブ枠の設定中は、設定不能な通常攻撃を一覧から除外します。 */
    public static boolean shouldShowNormalAttack(@Nullable SkillBindType selectedBindType) {
        return selectedBindType != SkillBindType.PASSIVE;
    }

    /**
     * 通常攻撃を現在の一覧ページへ表示するか判定します。
     *
     * @param pageIndex 0 始まりの一覧ページ番号
     * @param selectedBindType 選択中のバインド種別
     * @return 1ページ目で、通常攻撃を設定可能な場合は {@code true}
     */
    public static boolean shouldShowNormalAttack(int pageIndex, @Nullable SkillBindType selectedBindType) {
        return pageIndex == 0 && shouldShowNormalAttack(selectedBindType);
    }

    private ItemStack createLearnedSkillItem(SkillManagerEntry entry, boolean listDisplay) {
        List<Component> lore = new ArrayList<>();
        appendLearnedSkillDetails(lore, entry);
        if (listDisplay) {
            lore.add(separator());
            lore.add(Component.text("左クリック: 空き枠へ自動設定", NamedTextColor.YELLOW));
            lore.add(Component.text("右クリック: スキル合成を開く", NamedTextColor.LIGHT_PURPLE));
        }
        SkillDefinition skill = entry.definition();
        ItemStack item = createItem(
            listDisplay && !entry.permitted()
                ? Material.LIGHT_GRAY_WOOL
                : parseMaterial(skill.getIcon(), DEFAULT_SKILL_ICON),
            SkillPresentationUtil.skillNameComponent(skill, skill.getId(), NamedTextColor.WHITE)
                .append(Component.text(" Lv." + entry.learnedSkill().getLevel() + "/" + skill.getMaxLevel(), NamedTextColor.GOLD)),
            lore
        );
        return withBindingId(item, entry.bindingId());
    }

    /** 一覧・設定済みスロットで共通に表示する、習得済みスキルのプレイヤー向け詳細です。 */
    private void appendLearnedSkillDetails(@NotNull List<Component> lore, @NotNull SkillManagerEntry entry) {
        SkillDefinition skill = entry.definition();
        lore.addAll(SkillPresentationUtil.skillDescriptionAndFlavorLore(entry.resolved(), NamedTextColor.GRAY));
        if (!lore.isEmpty()) {
            lore.add(separator());
        }
        appendCastCostLore(lore, entry.resolved());
        lore.add(separator());
        String tagNames = SkillPresentationUtil.skillTagDisplayNames(skill,
            Set.of(MasterTagIds.Activity.ACTIVE, MasterTagIds.Activity.PASSIVE));
        if (!tagNames.isBlank()) {
            lore.add(Component.text("タグ: " + tagNames, NamedTextColor.DARK_AQUA));
        }
        lore.add(Component.text(
            "種別: " + (skill.getKind().isPassive() ? "パッシブ" : "アクティブ"),
            NamedTextColor.GRAY
        ));
        lore.add(Component.text(
            entry.permitted() ? "現在のクラス／スキルツリーで使用可能" : "現在のクラス／スキルツリーでは使用不可",
            entry.permitted() ? NamedTextColor.GREEN : NamedTextColor.RED
        ));
        if (skill.getKind().isPassive() && !skill.getPassiveBindRequired()) {
            lore.add(Component.text("所持中はバインド不要で発動", NamedTextColor.AQUA));
        }
        lore.add(separator());
        appendSigilSlotLore(lore, entry, entry.learnedSkill().getLevel(), null);
    }

    /** 消費とクールダウンを、解決済みレベル・シジル補正込みで表示します。 */
    private void appendCastCostLore(
        @NotNull List<Component> lore,
        @NotNull ResolvedLearnedSkill resolved
    ) {
        SkillDefinition skill = resolved.definition();
        double resourceCost = skill.getResourceCost() == null ? skill.getManaCost() : skill.getResourceCost();
        SkillResourceType resourceType = skill.getResourceType() == null
            ? SkillResourceType.MANA
            : skill.getResourceType();
        String resourceName = resourceType == SkillResourceType.ENERGY ? "EN" : "MP";
        String cost = BigDecimal.valueOf(resourceCost).stripTrailingZeros().toPlainString();
        lore.add(Component.text("消費リソース: " + resourceName + " " + cost, NamedTextColor.AQUA));
        if (!skill.getKind().isPassive()) {
            double reduction = resolved.statusBonuses().getOrDefault(StatusType.COOLDOWN_REDUCTION, 0.0D);
            long cooldownTicks = io.github.maaasu.astralRecord.feature.combat.service.CombatTimingCalculator
                .resolveCooldownTicks(skill.getCooldownTicks(), reduction);
            String cooldownSeconds = BigDecimal.valueOf(cooldownTicks / 20.0D)
                .stripTrailingZeros().toPlainString();
            lore.add(Component.text("クールダウン: " + cooldownSeconds + "秒", NamedTextColor.YELLOW));
        }
    }

    private @NotNull Component separator() {
        return Component.text(DisplaySeparators.SECTION, NamedTextColor.DARK_GRAY);
    }

    private void appendSigilEffectLore(List<Component> lore, int slotIndex, LearnedSkillSigil attached) {
        ItemModel item = itemService.findLoadedById(attached.getSigilId());
        if (item == null || item.getSigil() == null) {
            lore.add(Component.text("  スロット " + (slotIndex + 1) + ": " + attached.getSigilId(), NamedTextColor.GRAY));
            return;
        }
        lore.add(Component.text("  スロット " + (slotIndex + 1) + ": ", NamedTextColor.GRAY).append(
            SkillPresentationUtil.itemNameComponent(item, attached.getSigilId(), NamedTextColor.WHITE)
        ));
        for (ItemSigilModifier modifier : item.getSigil().getModifiers()) {
            StatusType status = StatusType.fromId(modifier.getStatus());
            String statusName = status == null ? "未定義ステータス" : status.getDisplayName();
            String suffix = status == null ? "" : status.getSuffix();
            String value = BigDecimal.valueOf(modifier.getValue()).stripTrailingZeros().toPlainString();
            if (modifier.getValue() > 0.0D) value = "+" + value;
            lore.add(Component.text("    効果: " + statusName + " " + value + suffix, NamedTextColor.AQUA));
        }
        for (Component description : SkillPresentationUtil.itemLoreComponents(item, NamedTextColor.GRAY)) {
            lore.add(Component.text("    説明: ", NamedTextColor.DARK_GRAY).append(description));
        }
    }

    private void appendSigilSlotLore(
        List<Component> lore,
        SkillManagerEntry entry,
        int level,
        @Nullable ItemModel pendingSigil
    ) {
        int slotCount = sigilSlotCount(entry.definition(), level);
        int equippedCount = entry.learnedSkill().getSigils().size()
            + (pendingSigil != null && pendingSigil.getSigil() != null ? 1 : 0);
        lore.add(Component.text("シジル合成枠: " + equippedCount + " / " + slotCount, NamedTextColor.LIGHT_PURPLE));
        if (slotCount <= 0) {
            entry.definition().getSigilSlotsByLevel().stream()
                .filter(slot -> slot.getLevel() > level && slot.getSlots() > 0)
                .mapToInt(SkillSigilSlotDefinition::getLevel)
                .min()
                .ifPresent(nextLevel -> lore.add(Component.text(
                    "Lv." + nextLevel + " でシジル枠を解放", NamedTextColor.GRAY
                )));
            return;
        }
        for (int slotIndex = 0; slotIndex < slotCount; slotIndex++) {
            final int targetSlotIndex = slotIndex;
            LearnedSkillSigil attached = entry.learnedSkill().getSigils().stream()
                .filter(sigil -> sigil.getSlotIndex() == targetSlotIndex)
                .findFirst()
                .orElse(null);
            if (attached != null) {
                appendSigilEffectLore(lore, slotIndex, attached);
                continue;
            }
            if (pendingSigil != null && pendingSigil.getSigil() != null) {
                appendPendingSigilLore(lore, slotIndex, pendingSigil);
                pendingSigil = null;
                continue;
            }
            lore.add(Component.text("  スロット " + (slotIndex + 1) + ": 空き", NamedTextColor.GRAY));
        }
    }

    private void appendPendingSigilLore(List<Component> lore, int slotIndex, ItemModel sigil) {
        lore.add(Component.text("  スロット " + (slotIndex + 1) + ": ", NamedTextColor.GREEN).append(
            SkillPresentationUtil.itemNameComponent(sigil, sigil.getId(), NamedTextColor.WHITE)
        ).append(Component.text("（今回装着）", NamedTextColor.GREEN)));
        appendSigilModifierLore(lore, sigil);
        for (Component description : SkillPresentationUtil.itemLoreComponents(sigil, NamedTextColor.GRAY)) {
            lore.add(Component.text("    説明: ", NamedTextColor.DARK_GRAY).append(description));
        }
    }

    private int sigilSlotCount(SkillDefinition skill, int level) {
        return skill.getSigilSlotsByLevel().stream()
            .filter(slot -> slot.getLevel() <= level)
            .mapToInt(SkillSigilSlotDefinition::getSlots)
            .max()
            .orElse(0);
    }

    /**
     * バインド枠の状態と習得個体を GUI 表示用 ItemStack へ変換します。
     *
     * @param type バインド種別
     * @param index 種別内の枠番号
     * @param bindingId バインドされた個体IDまたは通常攻撃予約ID
     * @param entries 表示対象の習得個体一覧
     * @param selected 選択中の枠かどうか
     * @param enabled 現在設定可能な枠かどうか
     * @return バインド枠の表示アイテム
     */
    ItemStack createBindSlot(
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
        if (entry != null) {
            lore.add(separator());
            appendLearnedSkillDetails(lore, entry);
        } else if (bindingId != null && !normalAttack) {
            lore.add(separator());
            lore.add(Component.text("未習得スキルです。発動できません。", NamedTextColor.RED));
            lore.add(Component.text("この枠をクリックしてバインドを解除してください。", NamedTextColor.YELLOW));
        }
        Material material = bindSlotMaterial(enabled, bindingId, normalAttack, entry);
        Component name = bindingId == null
            ? Component.text(label + "（未設定）", enabled ? NamedTextColor.WHITE : NamedTextColor.DARK_GRAY)
            : normalAttack
                ? Component.text(label + ": 武器通常攻撃", enabled ? NamedTextColor.WHITE : NamedTextColor.DARK_GRAY)
                : entry == null
                    ? Component.text(label + ": 未習得スキル", enabled ? NamedTextColor.RED : NamedTextColor.DARK_GRAY)
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

    /**
     * 習得個体の使用許可に応じたスキルアイコン素材を解決します。
     *
     * @param entry 表示対象の習得個体
     * @return 許可時は定義アイコン、未許可時は薄灰色の羊毛
     */
    static @NotNull Material skillIconMaterial(@NotNull SkillManagerEntry entry) {
        return entry.permitted()
            ? parseMaterial(entry.definition().getIcon(), DEFAULT_SKILL_ICON)
            : Material.LIGHT_GRAY_WOOL;
    }

    /**
     * バインド枠の状態に応じて表示素材を選択します。
     *
     * @param enabled 現在設定可能な枠かどうか
     * @param bindingId バインドID。未設定時は null
     * @param normalAttack 通常攻撃予約IDの枠かどうか
     * @param entry バインド対象の習得個体。解決できない場合は null
     * @return バインド枠の表示素材
     */
    static @NotNull Material bindSlotMaterial(
        boolean enabled,
        @Nullable String bindingId,
        boolean normalAttack,
        @Nullable SkillManagerEntry entry
    ) {
        if (!enabled) {
            return Material.IRON_BARS;
        }
        if (bindingId == null) {
            return Material.LIGHT_GRAY_STAINED_GLASS_PANE;
        }
        if (normalAttack) {
            return Material.IRON_SWORD;
        }
        if (entry == null) {
            return Material.BARRIER;
        }
        return skillIconMaterial(entry);
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
        lore.add(Component.text("クリック: 素材の選択を解除", NamedTextColor.YELLOW));
        lore.addAll(SkillPresentationUtil.itemLoreComponents(material, NamedTextColor.GRAY));
        return createItem(
            parseMaterial(material.getIcon(), Material.PRISMARINE_CRYSTALS),
            SkillPresentationUtil.itemNameComponent(material, material.getId(), NamedTextColor.WHITE),
            lore
        );
    }

    private ItemStack createRejectedMaterialItem(@NotNull ItemModel material, @NotNull MaterialKind kind) {
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text(materialFailureText(kind), NamedTextColor.RED));
        lore.add(Component.text("素材は選択・消費されていません。", NamedTextColor.GRAY));
        lore.add(Component.text("クリック: 表示を戻す", NamedTextColor.YELLOW));
        lore.addAll(SkillPresentationUtil.itemLoreComponents(material, NamedTextColor.GRAY));
        return createItem(
            parseMaterial(material.getIcon(), Material.PRISMARINE_CRYSTALS),
            SkillPresentationUtil.itemNameComponent(material, material.getId(), NamedTextColor.WHITE),
            lore
        );
    }

    private ItemStack createSynthesisResult(
        @NotNull SkillManagerEntry entry,
        @Nullable ItemModel material,
        @NotNull MaterialKind materialKind
    ) {
        if (material == null) {
            return createItem(
                Material.LIGHT_GRAY_STAINED_GLASS_PANE,
                "合成結果",
                NamedTextColor.GRAY,
                List.of(Component.text("素材をセットすると合成後の内容を表示します。", NamedTextColor.GRAY))
            );
        }
        if (!materialKind.usable()) {
            return createItem(
                Material.BARRIER,
                "合成できません",
                NamedTextColor.RED,
                List.of(
                    Component.text(materialFailureText(materialKind), NamedTextColor.RED),
                    Component.text("素材は消費されません。", NamedTextColor.GRAY)
                )
            );
        }
        SkillDefinition skill = entry.definition();
        int currentLevel = entry.learnedSkill().getLevel();
        boolean levelUp = materialKind == MaterialKind.GEM;
        ItemModel pendingSigil = materialKind == MaterialKind.SIGIL ? material : null;
        int resultingLevel = levelUp ? Math.min(skill.getMaxLevel(), currentLevel + 1) : currentLevel;
        ResolvedLearnedSkill preview = resolvedPreview(entry, resultingLevel, pendingSigil);
        List<Component> lore = new ArrayList<>();
        lore.addAll(SkillPresentationUtil.skillDescriptionAndFlavorLore(preview, NamedTextColor.GRAY));
        if (!lore.isEmpty()) {
            lore.add(separator());
        }
        appendCastCostLore(lore, preview);
        lore.add(separator());
        lore.add(Component.text(
            "種別: " + (skill.getKind().isPassive() ? "パッシブ" : "アクティブ"),
            NamedTextColor.GRAY
        ));
        if (materialKind == MaterialKind.GEM) {
            lore.add(Component.text("レベル: Lv." + currentLevel + " → Lv." + resultingLevel, NamedTextColor.GREEN));
        } else if (materialKind == MaterialKind.SIGIL) {
            lore.add(Component.text("シジルを装着します（取り外し不可）", NamedTextColor.LIGHT_PURPLE));
        }
        lore.add(separator());
        appendSigilSlotLore(lore, entry, resultingLevel, pendingSigil);
        lore.add(separator());
        lore.add(Component.text(
            levelUp ? "クリックでジェムを消費してレベルアップ" : "クリックでシジルを消費して装着",
            NamedTextColor.YELLOW
        ));
        return createItem(
            parseMaterial(preview.definition().getIcon(), DEFAULT_SKILL_ICON),
                SkillPresentationUtil.skillNameComponent(preview.definition(), skill.getId(), NamedTextColor.WHITE)
                    .append(Component.text(" Lv." + resultingLevel + "/" + skill.getMaxLevel(),
                    NamedTextColor.GREEN)),
            lore
        );
    }

    /** 合成後の仮想レベル・シジルを実行時と同じResolverで解決します。 */
    private @NotNull ResolvedLearnedSkill resolvedPreview(
        @NotNull SkillManagerEntry entry,
        int resultingLevel,
        @Nullable ItemModel pendingSigil
    ) {
        LearnedSkillInstance learned = entry.learnedSkill();
        List<LearnedSkillSigil> sigils = new ArrayList<>(learned.getSigils());
        if (pendingSigil != null && pendingSigil.getSigil() != null) {
            int slotCandidate = 0;
            while (true) {
                int candidate = slotCandidate;
                if (sigils.stream().noneMatch(sigil -> sigil.getSlotIndex() == candidate)) break;
                slotCandidate++;
            }
            int slotIndex = slotCandidate;
            sigils.add(new LearnedSkillSigil(
                UUID.randomUUID(),
                pendingSigil.getId(),
                pendingSigil.getSigil().getEquipGroupId(),
                slotIndex
            ));
        }
        LearnedSkillInstance projected = new LearnedSkillInstance(
            learned.getLearnedSkillId(),
            learned.getAccountId(),
            learned.getSkillId(),
            resultingLevel,
            sigils,
            learned.getVersion(),
            learned.getCreatedAt(),
            learned.getUpdatedAt()
        );
        ResolvedLearnedSkill resolved = skillService.resolveLearnedSkill(projected);
        return resolved == null ? entry.resolved() : resolved;
    }

    private @NotNull String materialFailureText(@NotNull MaterialKind kind) {
        return switch (kind) {
            case SIGIL_NOT_ALLOWED -> "このシジルはこのスキルに装着できません。";
            case NO_SIGIL_SLOT -> "シジル合成枠が空いていません。";
            case DUPLICATE_SIGIL_GROUP -> "同系統のシジルは重ねて装着できません。";
            case INVALID_GEM -> "このジェムではレベルアップできません。";
            case NONE -> "このアイテムは合成素材にできません。";
            case GEM, SIGIL -> "";
        };
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
        return GuiItems.create(material, name, lore);
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
            lore.add(Component.text("    効果: " + statusName + " " + value + suffix, NamedTextColor.AQUA));
        }
    }

    private ItemStack glow(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        meta.addEnchant(Enchantment.UNBREAKING, 1, true);
        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        item.setItemMeta(meta);
        return item;
    }

    /**
     * Material 名を解決し、未指定または不明な場合はフォールバックを返します。
     *
     * @param raw 解決する Material 名
     * @param fallback 解決に失敗した場合の素材
     * @return 解決した素材またはフォールバック
     */
    private static @NotNull Material parseMaterial(@Nullable String raw, @NotNull Material fallback) {
        Material resolved = MaterialNameResolver.match(raw);
        return resolved == null ? fallback : resolved;
    }
}
