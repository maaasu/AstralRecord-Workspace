package io.github.maaasu.astralRecord.feature.skill.gui;

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
import io.github.maaasu.astralRecord.feature.skill.model.SkillKind;
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
import io.github.maaasu.astralRecord.shared.gui.navigation.GuiNavigationDestination;
import io.github.maaasu.astralRecord.shared.gui.confirm.ConfirmDialogView;
import io.github.maaasu.astralRecord.shared.display.DisplaySeparators;
import io.github.maaasu.astralRecord.shared.masterdata.tag.MasterTagIds;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
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
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.ToLongBiFunction;

/** 習得済みスキルの表示・バインドを扱うスキルマネージャー GUI です。 */
public final class SkillBindGui {
    public static final int SIZE = 54;
    /** スキル一覧は上5行に45件を表示し、最下段をナビゲーションに使用します。 */
    public static final int CONTENT_SLOT_COUNT = 45;
    public static final int PREVIOUS_PAGE_SLOT = 45;
    public static final int BACK_SLOT = 49;
    public static final int NEXT_PAGE_SLOT = 53;
    /** 下段 PlayerInventory に同時表示する横スクロール枠数です。 */
    public static final int PLAYER_INVENTORY_VISIBLE_BIND_SLOT_COUNT = 7;
    public static final int PLAYER_INVENTORY_LEFT_CLICK_SLOT = 9;
    public static final int PLAYER_INVENTORY_NORMAL_ATTACK_SLOT = 17;
    public static final int PLAYER_INVENTORY_PASSIVE_PREVIOUS_SLOT = 18;
    public static final int PLAYER_INVENTORY_PASSIVE_SLOT_START = 19;
    public static final int PLAYER_INVENTORY_PASSIVE_NEXT_SLOT = 26;
    public static final int PLAYER_INVENTORY_ACTIVE_PREVIOUS_SLOT = 27;
    public static final int PLAYER_INVENTORY_ACTIVE_SLOT_START = 28;
    public static final int PLAYER_INVENTORY_ACTIVE_NEXT_SLOT = 35;

    public static final int DETAIL_SIZE = 27;
    public static final int DETAIL_BIND_SLOT = 11;
    public static final int DETAIL_SKILL_SLOT = 13;
    public static final int DETAIL_LEVEL_UP_SLOT = 15;
    public static final int DETAIL_BACK_SLOT = 22;

    public static final int SYNTHESIS_SKILL_SLOT = 20;
    public static final int SYNTHESIS_MATERIAL_SLOT = 22;
    public static final int SYNTHESIS_RESULT_SLOT = 24;
    public static final int SYNTHESIS_BACK_SLOT = 31;

    private static final Material DEFAULT_SKILL_ICON = Material.AMETHYST_SHARD;
    private static final int PERMITTED_SKILL_LORE_LIMIT = 6;
    private final NamespacedKey learnedSkillIdKey;
    private final NamespacedKey unlearnedSkillIdKey;
    private final NamespacedKey dummyKey;
    private final ItemService itemService;
    private final SkillService skillService;
    private final ConfirmDialogView confirmDialogView = new ConfirmDialogView();
    private ToLongBiFunction<UUID, String> requiredItemOwnedAmountProvider = (accountId, itemId) -> 0L;

    /**
     * Plugin の namespace を利用してスキルマネージャー GUI を構築します。
     *
     * @param plugin PDC キーの namespace を提供する Plugin
     * @param itemService アイテム表示・検索サービス
     * @param skillService スキル定義・解決サービス
     */
    public SkillBindGui(
        @NotNull Plugin plugin,
        @NotNull ItemService itemService,
        @NotNull SkillService skillService
    ) {
        learnedSkillIdKey = new NamespacedKey(plugin, "skill_manager_learned_skill_id");
        unlearnedSkillIdKey = new NamespacedKey(plugin, "skill_manager_unlearned_skill_id");
        dummyKey = new NamespacedKey(plugin, "skill_manager_dummy");
        this.itemService = itemService;
        this.skillService = skillService;
    }

    /**
     * 必要素材の所持数をアカウントごとに解決する関数を初期化時に設定します。
     * @param provider アカウントIDと素材IDから所持数を返す関数
     */
    public void setRequiredItemOwnedAmountProvider(@NotNull ToLongBiFunction<UUID, String> provider) {
        requiredItemOwnedAmountProvider = provider;
    }

    /**
     * スキルマネージャーの一覧画面を生成して開きます。
     *
     * @param player 表示対象プレイヤー
     * @param session 編集中セッション
     * @param entries 表示対象の習得スキル
     * @param entryByBindingId バインド ID ごとの表示対象
     * @param permittedSkillDefinitions 現在の使用許可スキル定義
     * @param activePassiveSlots 現在有効な passive 枠数
     * @param pageIndex 表示するページ番号
     */
    public void open(
        @NotNull Player player,
        @NotNull SkillBindSession session,
        @NotNull List<SkillManagerEntry> entries,
        @NotNull Map<String, SkillManagerEntry> entryByBindingId,
        @NotNull List<SkillDefinition> permittedSkillDefinitions,
        int activePassiveSlots,
        int pageIndex
    ) {
        io.github.maaasu.astralRecord.shared.gui.GuiOpenSupport.open(
            player,
            createMainInventory(
                session,
                entries,
                entryByBindingId,
                permittedSkillDefinitions,
                activePassiveSlots,
                pageIndex
            )
        );
    }

    /**
     * スキルマネージャーの一覧画面 inventory を生成します。
     *
     * @param session 編集中セッション
     * @param entries 表示対象の習得スキル
     * @param entryByBindingId バインド ID ごとの表示対象
     * @param permittedSkillDefinitions 現在の使用許可スキル定義
     * @param activePassiveSlots 現在有効な passive 枠数
     * @param pageIndex 表示するページ番号
     * @return 表示用の一覧画面 inventory
     */
    public @NotNull Inventory createMainInventory(
        @NotNull SkillBindSession session,
        @NotNull List<SkillManagerEntry> entries,
        @NotNull Map<String, SkillManagerEntry> entryByBindingId,
        @NotNull List<SkillDefinition> permittedSkillDefinitions,
        int activePassiveSlots,
        int pageIndex
    ) {
        return createMainInventory(session, entries, List.of(), entryByBindingId, permittedSkillDefinitions, activePassiveSlots, pageIndex);
    }

    public @NotNull Inventory createMainInventory(
        @NotNull SkillBindSession session,
        @NotNull List<SkillManagerEntry> entries,
        @NotNull List<SkillDefinition> unlearnedDefinitions,
        @NotNull Map<String, SkillManagerEntry> entryByBindingId,
        @NotNull List<SkillDefinition> permittedSkillDefinitions,
        int activePassiveSlots,
        int pageIndex
    ) {
        int displayCount = entries.size() + unlearnedDefinitions.size();
        Set<String> permittedSkillIds = permittedSkillDefinitions.stream()
            .map(SkillDefinition::getId)
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
        int page = normalizePage(pageIndex, displayCount);
        int pages = totalPages(displayCount);
        Inventory inventory = Bukkit.createInventory(
            new SkillBindInventoryHolder(SkillBindScreen.MAIN, session.selectedPresetIndex(), page, "", -1, "",
                player -> renderPlayerInventorySettings(player.getInventory(), session, entryByBindingId,
                    permittedSkillDefinitions, activePassiveSlots)),
            SIZE,
            Component.text("スキルマネージャー " + (page + 1) + "/" + pages, NamedTextColor.AQUA)
        );
        fill(inventory);

        int start = GuiPagination.pageStart(page, CONTENT_SLOT_COUNT);
        int end = GuiPagination.pageEnd(page, displayCount, CONTENT_SLOT_COUNT);
        for (int index = start; index < end; index++) {
            inventory.setItem(index - start, index < entries.size()
                ? createLearnedSkillItem(entries.get(index), true, permittedSkillIds)
                : createUnlearnedSkillItem(
                    unlearnedDefinitions.get(index - entries.size()), session.processingSkillId(), permittedSkillIds,
                    session.selectedPreset().getAccountId()));
        }

        inventory.setItem(PREVIOUS_PAGE_SLOT, createPreviousPageItem(page, pages, page > 0));
        inventory.setItem(BACK_SLOT, GuiItems.backButton(new GuiNavigationDestination(
            Material.PLAYER_HEAD,
            "メニュー",
            GuiItems.MAIN_MENU_HEAD_TEXTURE
        )));
        inventory.setItem(NEXT_PAGE_SLOT, createNextPageItem(page, pages, page + 1 < pages));
        return inventory;
    }

    /**
     * スキル詳細画面を生成します。
     *
     * @param session 編集中セッション。選択中バインド枠の表示に使用する
     * @param entry 詳細表示する習得済みスキル
     * @param returnPage スキルマネージャーへ戻るページ番号
     * @return 表示用の詳細画面 inventory
     */
    public @NotNull Inventory createDetailInventory(
        @NotNull SkillBindSession session,
        @NotNull SkillManagerEntry entry,
        int returnPage
    ) {
        return createDetailInventory(session, entry, returnPage, false, Set.of());
    }

    /**
     * スキル詳細画面を、レベルアップ処理中の状態を含めて生成します。
     *
     * @param session 編集中セッション。選択中バインド枠の表示に使用する
     * @param entry 詳細表示する習得済みスキル
     * @param returnPage スキルマネージャーへ戻るページ番号
     * @param mutationInProgress レベルアップなどのスキル更新処理中かどうか
     * @return 表示用の詳細画面 inventory
     */
    public @NotNull Inventory createDetailInventory(
        @NotNull SkillBindSession session,
        @NotNull SkillManagerEntry entry,
        int returnPage,
        boolean mutationInProgress
    ) {
        return createDetailInventory(session, entry, returnPage, mutationInProgress, Set.of());
    }

    /**
     * スキル詳細画面を、閲覧者の使用許可を反映して生成します。
     *
     * @param session 編集中セッション
     * @param entry 詳細表示する習得済みスキル
     * @param returnPage スキルマネージャーへ戻るページ番号
     * @param mutationInProgress レベルアップなどのスキル更新処理中かどうか
     * @param permittedSkillIds 閲覧者に許可されたスキル ID 一覧
     * @return 表示用の詳細画面 inventory
     */
    public @NotNull Inventory createDetailInventory(
        @NotNull SkillBindSession session,
        @NotNull SkillManagerEntry entry,
        int returnPage,
        boolean mutationInProgress,
        @NotNull Set<String> permittedSkillIds
    ) {
        Inventory inventory = Bukkit.createInventory(
            new SkillBindInventoryHolder(
                SkillBindScreen.DETAIL,
                session.selectedPresetIndex(),
                returnPage,
                entry.bindingId()
            ),
            DETAIL_SIZE,
            Component.text("スキル詳細", NamedTextColor.AQUA)
        );
        fill(inventory);
        inventory.setItem(DETAIL_SKILL_SLOT, createLearnedSkillItem(entry, false, permittedSkillIds));
        if (mutationInProgress) {
            ItemStack processing = GuiItems.processingItem();
            inventory.setItem(DETAIL_BIND_SLOT, processing.clone());
            inventory.setItem(DETAIL_LEVEL_UP_SLOT, processing);
        } else {
            inventory.setItem(
                DETAIL_BIND_SLOT,
                createItem(
                    Material.NAME_TAG,
                    "このスキルをバインド",
                    NamedTextColor.GREEN,
                    List.of(
                        Component.text(bindTargetText(session), NamedTextColor.YELLOW),
                        Component.text("クリックで設定", NamedTextColor.GRAY)
                    )
                )
            );
            inventory.setItem(
                DETAIL_LEVEL_UP_SLOT,
                createItem(
                    Material.ENCHANTED_BOOK,
                    "レベルアップ",
                    NamedTextColor.LIGHT_PURPLE,
                    List.of(Component.text("クリックでレベルアップ", NamedTextColor.YELLOW))
                )
            );
        }
        inventory.setItem(DETAIL_BACK_SLOT, GuiItems.backButton(
            new GuiNavigationDestination(Material.ENCHANTING_TABLE, "スキルマネージャー")
        ));
        return inventory;
    }

    /**
     * スキル合成画面を生成して開きます。
     *
     * @param player 表示対象プレイヤー
     * @param selectedPresetIndex 選択中プリセット番号
     * @param returnPage 一覧へ戻るページ番号
     * @param entry 合成対象スキル
     * @param material 選択済みまたは拒否表示する素材
     * @param materialKind 素材の適合結果
     * @param materialSelected 素材を消費予約している場合は {@code true}
     */
    public void openSynthesis(
        @NotNull Player player,
        int selectedPresetIndex,
        int returnPage,
        @NotNull SkillManagerEntry entry,
        @Nullable ItemModel material,
        @NotNull MaterialKind materialKind,
        boolean materialSelected
    ) {
        io.github.maaasu.astralRecord.shared.gui.GuiOpenSupport.open(
            player,
            createSynthesisInventory(
                selectedPresetIndex,
                returnPage,
                entry,
                material,
                materialKind,
                materialSelected
            )
        );
    }

    /**
     * スキル合成画面 inventory を生成します。
     *
     * @param selectedPresetIndex 選択中プリセット番号
     * @param returnPage 一覧へ戻るページ番号
     * @param entry 合成対象スキル
     * @param material 選択済みまたは拒否表示する素材
     * @param materialKind 素材の適合結果
     * @param materialSelected 素材を消費予約している場合は {@code true}
     * @return 表示用の合成画面 inventory
     */
    public @NotNull Inventory createSynthesisInventory(
        int selectedPresetIndex,
        int returnPage,
        @NotNull SkillManagerEntry entry,
        @Nullable ItemModel material,
        @NotNull MaterialKind materialKind,
        boolean materialSelected
    ) {
        return createSynthesisInventory(selectedPresetIndex, returnPage, entry, material,
            materialKind, materialSelected, Set.of());
    }

    /**
     * 閲覧者の使用許可を反映したスキル合成画面を生成します。
     * @param selectedPresetIndex 選択中プリセット番号
     * @param returnPage 一覧へ戻るページ番号
     * @param entry 合成対象個体
     * @param material 素材
     * @param materialKind 素材の適合結果
     * @param materialSelected 素材を消費予約しているか
     * @param permittedSkillIds 閲覧者の使用許可
     * @return 合成画面
     */
    public @NotNull Inventory createSynthesisInventory(
        int selectedPresetIndex, int returnPage, @NotNull SkillManagerEntry entry,
        @Nullable ItemModel material, @NotNull MaterialKind materialKind, boolean materialSelected,
        @NotNull Set<String> permittedSkillIds
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
        inventory.setItem(SYNTHESIS_SKILL_SLOT, createLearnedSkillItem(entry, false, permittedSkillIds));
        inventory.setItem(
            SYNTHESIS_MATERIAL_SLOT,
            material == null
                ? createItem(Material.LIGHT_GRAY_STAINED_GLASS_PANE, "素材を選択", NamedTextColor.GRAY,
                    List.of(Component.text("下のインベントリから対応シジルをクリック", NamedTextColor.YELLOW)))
                : materialSelected ? createMaterialItem(material) : createRejectedMaterialItem(material, materialKind)
        );
        inventory.setItem(
            SYNTHESIS_RESULT_SLOT,
            createSynthesisResult(entry, material, materialKind, permittedSkillIds)
        );
        inventory.setItem(SYNTHESIS_BACK_SLOT, GuiItems.backButton(
            new GuiNavigationDestination(Material.ENCHANTING_TABLE, "スキルマネージャー")
        ));
        return inventory;
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
        Inventory inventory = createConfirmInventory(
            selectedPresetIndex,
            pageIndex,
            action,
            pendingPresetIndex,
            message
        );
        io.github.maaasu.astralRecord.shared.gui.GuiOpenSupport.open(player, inventory);
    }

    /**
     * 変更確認画面 inventory を生成します。
     *
     * @param selectedPresetIndex 選択中プリセット番号
     * @param pageIndex 確認前に表示していた一覧ページ。キャンセル・切替後の復帰に引き継ぐ
     * @param action 確認後の操作
     * @param pendingPresetIndex 切替対象プリセット番号。切替以外では {@code -1}
     * @param message 確認メッセージ
     * @return 表示用の変更確認画面 inventory
     */
    public @NotNull Inventory createConfirmInventory(
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
        return inventory;
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

    public @Nullable String unlearnedSkillId(@Nullable ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        return item.getItemMeta().getPersistentDataContainer().get(unlearnedSkillIdKey, PersistentDataType.STRING);
    }

    public int normalizePage(int pageIndex, int count) {
        return GuiPagination.normalizePage(pageIndex, count, CONTENT_SLOT_COUNT);
    }

    public int totalPages(int count) {
        return GuiPagination.totalPages(count, CONTENT_SLOT_COUNT);
    }


    /**
     * スキル名に続くレベル表示を生成します。
     *
     * @param level 現在のレベル
     * @param maxLevel 最大レベル
     * @param normalColor 最大レベル未満のレベル表示に使う色。null は不可
     * @return 最大レベルなら赤色太字の {@code max}、それ以外は {@code Lv.現在/最大}
     */
    static @NotNull Component skillLevelDisplay(
        int level,
        int maxLevel,
        @NotNull NamedTextColor normalColor
    ) {
        if (level == maxLevel) {
            return Component.text(" ", normalColor)
                .append(Component.text("max", NamedTextColor.RED, TextDecoration.BOLD));
        }
        return Component.text(" Lv." + level + "/" + maxLevel, normalColor);
    }

    private ItemStack createLearnedSkillItem(
        SkillManagerEntry entry,
        boolean listDisplay,
        @NotNull Set<String> permittedSkillIds
    ) {
        List<Component> lore = new ArrayList<>();
        appendLearnedSkillDetails(lore, entry, permittedSkillIds);
        if (listDisplay) {
            lore.add(separator());
            lore.add(Component.text(
                entry.learnedSkill().getLevel() >= entry.definition().getMaxLevel()
                    ? "クリック: バインド"
                    : "クリック: 詳細画面を開く（素材所持時）／素材不足時はバインド",
                NamedTextColor.YELLOW
            ));
        }
        SkillDefinition skill = entry.definition();
        ItemStack item = createItem(
            listDisplay && !entry.permitted()
                ? Material.LIGHT_GRAY_WOOL
                : parseMaterial(skill.getIcon(), DEFAULT_SKILL_ICON),
            SkillPresentationUtil.skillNameComponent(skill, skill.getId(), NamedTextColor.WHITE)
                .append(skillLevelDisplay(entry.learnedSkill().getLevel(), skill.getMaxLevel(), NamedTextColor.GOLD)),
            lore,
            skill.getIconTexture()
        );
        return withBindingId(item, entry.bindingId());
    }

    /**
     * プレイヤー情報から参照する読み取り専用のスキル情報アイテムを生成します。
     *
     * @param definition 表示するスキル定義
     * @param resolved 習得済み個体の解決結果。未習得の場合は {@code null}
     * @param permitted 表示対象プレイヤーが現在使用を許可されている場合は {@code true}
     * @param permittedSkillIds 条件付き説明の表示判定に使う、対象プレイヤーの使用許可スキル ID
     * @return プレイヤー状態とマスター情報を含む読み取り専用アイテム
     */
    public @NotNull ItemStack createReadOnlySkillInformationItem(
        @NotNull SkillDefinition definition,
        @Nullable ResolvedLearnedSkill resolved,
        boolean permitted,
        @NotNull Set<String> permittedSkillIds
    ) {
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text(
            permitted ? "使用許可: あり" : "使用許可: なし",
            permitted ? NamedTextColor.GREEN : NamedTextColor.RED
        ));
        lore.add(Component.text(
            resolved == null ? "習得状態: 未習得" : "習得状態: 習得済み",
            resolved == null ? NamedTextColor.RED : NamedTextColor.LIGHT_PURPLE
        ));
        if (resolved != null) {
            lore.add(Component.text(
                "現在レベル: Lv." + resolved.learnedSkill().getLevel()
                    + " / 最大 Lv." + definition.getMaxLevel(),
                NamedTextColor.GOLD
            ));
        }
        lore.add(separator());

        if (resolved == null) {
            lore.addAll(SkillPresentationUtil.skillDescriptionAndFlavorLore(
                definition, permittedSkillIds, NamedTextColor.GRAY
            ));
            if (!lore.isEmpty()) {
                lore.add(separator());
            }
            appendBaseCastCostLore(lore, definition);
            lore.add(separator());
            String tagNames = SkillPresentationUtil.skillTagDisplayNames(definition,
                Set.of(MasterTagIds.Activity.ACTIVE, MasterTagIds.Activity.PASSIVE));
            if (!tagNames.isBlank()) {
                lore.add(Component.text("タグ: " + tagNames, NamedTextColor.DARK_AQUA));
            }
            lore.add(Component.text(
                "種別: " + (definition.getKind().isPassive() ? "パッシブ" : "アクティブ"),
                NamedTextColor.GRAY
            ));
            lore.add(Component.text("現在レベル: なし / 最大 Lv." + definition.getMaxLevel(), NamedTextColor.DARK_GRAY));
            lore.add(Component.text("シジル: 未習得のため装着なし", NamedTextColor.DARK_GRAY));
        } else {
            appendLearnedSkillDetails(
                lore,
                new SkillManagerEntry(resolved.learnedSkill(), definition, permitted, resolved),
                permittedSkillIds
            );
        }

        Component name = SkillPresentationUtil.skillNameComponent(
            definition, "未登録のスキル", NamedTextColor.WHITE
        );
        if (resolved != null) {
            name = name.append(skillLevelDisplay(
                resolved.learnedSkill().getLevel(), definition.getMaxLevel(), NamedTextColor.GOLD
            ));
        }
        return createItem(
            permitted ? parseMaterial(definition.getIcon(), DEFAULT_SKILL_ICON) : Material.LIGHT_GRAY_WOOL,
            name,
            lore,
            permitted ? definition.getIconTexture() : null
        );
    }

    private ItemStack createUnlearnedSkillItem(
        @NotNull SkillDefinition skill,
        @Nullable String processingSkillId,
        @NotNull Set<String> permittedSkillIds,
        @NotNull UUID accountId
    ) {
        if (processingSkillId != null && processingSkillId.equalsIgnoreCase(skill.getId())) {
            return GuiItems.processingItem();
        }
        List<Component> lore = new ArrayList<>();
        lore.addAll(SkillPresentationUtil.skillDescriptionAndFlavorLore(
            skill, permittedSkillIds, NamedTextColor.GRAY
        ));
        lore.add(separator());
        lore.add(Component.text("未習得", NamedTextColor.RED));
        appendRequiredItemLore(lore, skill.getLearnRequiredItems(), "習得に必要な素材", accountId);
        lore.add(Component.text("左クリック: 習得", NamedTextColor.YELLOW));
        ItemStack item = createItem(parseMaterial(skill.getIcon(), DEFAULT_SKILL_ICON),
            SkillPresentationUtil.skillNameComponent(skill, skill.getId(), NamedTextColor.WHITE), lore,
            skill.getIconTexture());
        ItemMeta meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(unlearnedSkillIdKey, PersistentDataType.STRING, skill.getId());
        item.setItemMeta(meta);
        return item;
    }

    private void appendRequiredItemLore(
        @NotNull List<Component> lore,
        @NotNull List<io.github.maaasu.astralRecord.feature.skill.model.SkillRequiredItemDefinition> costs,
        @NotNull String label,
        @NotNull UUID accountId
    ) {
        if (costs.isEmpty()) {
            lore.add(Component.text(label + ":", NamedTextColor.AQUA));
            lore.add(Component.text("• なし", NamedTextColor.DARK_GRAY));
            return;
        }
        lore.add(Component.text(label + ":", NamedTextColor.AQUA));
        for (var cost : costs) {
            ItemModel item = itemService.findLoadedById(cost.getItemId());
            Component name = item == null ? Component.text("未登録の素材", NamedTextColor.RED)
                : SkillPresentationUtil.itemNameComponent(item, item.getId(), NamedTextColor.WHITE);
            lore.add(Component.text("• ", NamedTextColor.AQUA).append(name)
                .append(Component.text(" ×" + cost.getAmount(), NamedTextColor.AQUA))
                .append(Component.text(" （所持: " + Math.max(0L,
                    requiredItemOwnedAmountProvider.applyAsLong(accountId, cost.getItemId())) + "）", NamedTextColor.GRAY)));
        }
    }

    /** 一覧・設定済みスロットで共通に表示する、習得済みスキルのプレイヤー向け詳細です。 */
    private void appendLearnedSkillDetails(
        @NotNull List<Component> lore,
        @NotNull SkillManagerEntry entry,
        @NotNull Set<String> permittedSkillIds
    ) {
        SkillDefinition skill = entry.definition();
        lore.addAll(SkillPresentationUtil.skillDescriptionAndFlavorLore(
            entry.resolved(), permittedSkillIds, NamedTextColor.GRAY
        ));
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
        int currentLevel = entry.learnedSkill().getLevel();
        if (currentLevel >= skill.getMaxLevel()) {
            lore.add(Component.text("レベル: ", NamedTextColor.GRAY)
                .append(Component.text("MAX", NamedTextColor.RED, TextDecoration.BOLD)));
        } else {
            lore.add(Component.text(
                "次のレベル: Lv." + currentLevel + " → Lv." + (currentLevel + 1),
                NamedTextColor.AQUA
            ));
            appendRequiredItemLore(lore, skill.getLevelUpRequiredItems(), "レベルアップに必要な素材", entry.learnedSkill().getAccountId());
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
        double reduction = resolved.statusBonuses().getOrDefault(StatusType.COOLDOWN_REDUCTION, 0.0D);
        appendCastCostLore(lore, skill, reduction);
    }

    /**
     * 未習得スキルの基礎消費リソースとクールダウンを表示します。
     *
     * @param lore 追記先
     * @param skill 基礎スキル定義
     */
    private void appendBaseCastCostLore(
        @NotNull List<Component> lore,
        @NotNull SkillDefinition skill
    ) {
        appendCastCostLore(lore, skill, 0.0D);
    }

    /**
     * スキル定義とクールダウン短縮率から消費・クールダウン表示を生成します。
     *
     * @param lore 追記先
     * @param skill 表示対象スキル
     * @param cooldownReduction クールダウン短縮率
     */
    private void appendCastCostLore(
        @NotNull List<Component> lore,
        @NotNull SkillDefinition skill,
        double cooldownReduction
    ) {
        double resourceCost = skill.getResourceCost() == null ? skill.getManaCost() : skill.getResourceCost();
        SkillResourceType resourceType = skill.getResourceType() == null
            ? SkillResourceType.MANA
            : skill.getResourceType();
        String resourceName = switch (resourceType) {
            case MANA -> "MP";
            case ENERGY -> "ENG";
            case GUARD -> "ガード";
        };
        String cost = BigDecimal.valueOf(resourceCost).stripTrailingZeros().toPlainString();
        lore.add(Component.text("消費リソース: " + resourceName + " " + cost, NamedTextColor.AQUA));
        if (resourceType == SkillResourceType.ENERGY && skill.getManaCost() > 0.0D) {
            String manaCost = BigDecimal.valueOf(skill.getManaCost()).stripTrailingZeros().toPlainString();
            lore.add(Component.text("消費リソース: MP " + manaCost, NamedTextColor.AQUA));
        }
        if (!skill.getKind().isPassive()) {
            long cooldownTicks = io.github.maaasu.astralRecord.feature.combat.service.CombatTimingCalculator
                .resolveCooldownTicks(skill.getCooldownTicks(), cooldownReduction);
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
            lore.add(Component.text("  スロット " + (slotIndex + 1) + ": 未登録のシジル", NamedTextColor.RED));
            return;
        }
        lore.add(Component.text("  スロット " + (slotIndex + 1) + ": ", NamedTextColor.GRAY).append(
            SkillPresentationUtil.itemNameComponent(item, "未登録のシジル", NamedTextColor.WHITE)
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
     * @param permittedSkillDefinitions 現在の使用許可スキル定義
     * @param selected 選択中の枠かどうか
     * @param enabled 現在設定可能な枠かどうか
     * @return バインド枠の表示アイテム
     */
    ItemStack createBindSlot(
        SkillBindType type,
        int index,
        String bindingId,
        Map<String, SkillManagerEntry> entries,
        List<SkillDefinition> permittedSkillDefinitions,
        boolean selected,
        boolean enabled
    ) {
        return createBindSlot(type, index, bindingId, entries, permittedSkillDefinitions, selected, enabled, true);
    }

    /**
     * プレイヤー情報から参照する読み取り専用のバインド枠を生成します。
     *
     * @param type バインド種別
     * @param index 種別内の枠番号
     * @param bindingId バインドされた個体 ID または通常攻撃予約 ID
     * @param entries バインド ID ごとの習得個体
     * @param permittedSkillDefinitions 現在の使用許可スキル定義
     * @param enabled 現在有効な枠なら {@code true}
     * @return 操作案内を含まない読み取り専用の表示アイテム
     */
    public @NotNull ItemStack createReadOnlyBindSlot(
        @NotNull SkillBindType type,
        int index,
        @Nullable String bindingId,
        @NotNull Map<String, SkillManagerEntry> entries,
        @NotNull List<SkillDefinition> permittedSkillDefinitions,
        boolean enabled
    ) {
        return createBindSlot(
            type, index, bindingId, entries, permittedSkillDefinitions, false, enabled, false
        );
    }

    /**
     * 操作可否を含めて共通バインド枠アイテムを生成します。
     *
     * @param type バインド種別
     * @param index 種別内の枠番号
     * @param bindingId バインド ID
     * @param entries バインド ID ごとの習得個体
     * @param permittedSkillDefinitions 使用許可スキル定義
     * @param selected 編集画面で選択中なら {@code true}
     * @param enabled 現在有効な枠なら {@code true}
     * @param interactive 操作用案内を表示する場合は {@code true}
     * @return バインド枠アイテム
     */
    private @NotNull ItemStack createBindSlot(
        @NotNull SkillBindType type,
        int index,
        @Nullable String bindingId,
        @NotNull Map<String, SkillManagerEntry> entries,
        @NotNull List<SkillDefinition> permittedSkillDefinitions,
        boolean selected,
        boolean enabled,
        boolean interactive
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
        if (type == SkillBindType.ACTIVE) {
            lore.add(Component.text(index < SkillBindPreset.ACTION_RING_SLOT_COUNT
                ? "アクションリング: 表示対象" : "アクションリング: 非表示（キャストディスク等で使用）", NamedTextColor.GRAY));
        }
        if (!enabled && bindingId != null) {
            lore.add(Component.text("設定は保持されますが機能しません。解除のみ可能です。", NamedTextColor.RED));
        } else if (bindingId == null) {
            lore.add(Component.text(
                interactive
                    ? (enabled ? "クリックして設定先に選択" : "枠数を増やすまで設定不可")
                    : (enabled ? "未設定" : "現在は設定できません"),
                NamedTextColor.GRAY
            ));
            appendPermittedSkillLore(lore, type, permittedSkillDefinitions);
        } else {
            lore.add(Component.text(interactive ? "クリックで解除" : "設定済み", NamedTextColor.YELLOW));
        }
        if (entry != null) {
            lore.add(separator());
            appendLearnedSkillDetails(lore, entry, permittedSkillDefinitions.stream()
                .map(SkillDefinition::getId).collect(java.util.stream.Collectors.toSet()));
        } else if (bindingId != null && !normalAttack) {
            lore.add(separator());
            lore.add(Component.text("未習得スキルです。発動できません。", NamedTextColor.RED));
            if (interactive) {
                lore.add(Component.text("この枠をクリックしてバインドを解除してください。", NamedTextColor.YELLOW));
            }
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
                            entry.definition(), "未登録のスキル", NamedTextColor.WHITE
                        ))
                        .append(Component.text(" Lv." + entry.learnedSkill().getLevel(), NamedTextColor.GOLD));
        ItemStack item = createItem(material, name, lore);
        if (bindingId == null && (type == SkillBindType.PASSIVE || type == SkillBindType.ACTIVE)) {
            item.setAmount(index + 1);
        }
        return selected ? glow(item) : item;
    }

    /**
     * 空のバインド枠へ、現在の枠種別に対応する使用許可スキルを表示します。
     *
     * @param lore 追記対象の lore
     * @param type バインド種別
     * @param permittedSkillDefinitions 現在の使用許可スキル定義
     */
    private void appendPermittedSkillLore(
        @NotNull List<Component> lore,
        @NotNull SkillBindType type,
        @NotNull List<SkillDefinition> permittedSkillDefinitions
    ) {
        List<SkillDefinition> compatible = permittedSkillDefinitions.stream()
            .filter(definition -> isCompatibleSkill(type, definition))
            .toList();
        NamedTextColor headingColor = type == SkillBindType.PASSIVE
            ? NamedTextColor.LIGHT_PURPLE
            : NamedTextColor.AQUA;
        lore.add(separator());
        lore.add(Component.text("現在の使用許可スキル", headingColor, TextDecoration.BOLD));
        if (compatible.isEmpty()) {
            lore.add(Component.text("  なし", NamedTextColor.DARK_GRAY));
            return;
        }
        int displayedCount = Math.min(compatible.size(), PERMITTED_SKILL_LORE_LIMIT);
        for (int index = 0; index < displayedCount; index++) {
            SkillDefinition definition = compatible.get(index);
            lore.add(Component.text("  ▸ ", NamedTextColor.GRAY).append(
                SkillPresentationUtil.skillNameComponent(definition, "未登録のスキル", NamedTextColor.WHITE)
            ));
        }
        if (compatible.size() > displayedCount) {
            lore.add(Component.text("  ... +" + (compatible.size() - displayedCount), NamedTextColor.DARK_GRAY));
        }
    }

    private @NotNull String bindTargetText(@NotNull SkillBindSession session) {
        SkillBindType type = session.selectedBindType();
        if (type == null) {
            return "種別に合う空き枠へ自動設定します";
        }
        return switch (type) {
            case PASSIVE -> "選択中: パッシブスロット " + (session.selectedBindSlotIndex() + 1);
            case LEFT_CLICK -> "選択中: 左クリック";
            case ACTIVE -> "選択中: アクションスロット " + (session.selectedBindSlotIndex() + 1);
        };
    }

    /**
     * スキル定義が指定バインド枠へ設定できる種別か判定します。
     *
     * @param type バインド種別
     * @param definition 判定対象スキル定義
     * @return 指定枠へ設定できる場合は {@code true}
     */
    private boolean isCompatibleSkill(@NotNull SkillBindType type, @NotNull SkillDefinition definition) {
        if (type == SkillBindType.PASSIVE) {
            return definition.getKind() == SkillKind.PASSIVE && definition.getPassiveBindRequired();
        }
        return definition.getKind() != SkillKind.PASSIVE;
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
            return Material.STICK;
        }
        if (entry == null) {
            return Material.BARRIER;
        }
        return skillIconMaterial(entry);
    }

    /** 下段右端へ表示する武器通常攻撃の設定アイテムを生成します。 */
    public ItemStack createNormalAttackItem() {
        return withBindingId(createItem(
            Material.STICK,
            "武器通常攻撃",
            NamedTextColor.WHITE,
            List.of(
                Component.text("武器タグ SWORD / BOW / STAFF から自動決定", NamedTextColor.GRAY),
                Component.text("左クリックまたはアクション枠を選択してからクリック", NamedTextColor.YELLOW)
            )
        ), SkillBindPreset.WEAPON_NORMAL_ATTACK_BINDING_ID);
    }

    private ItemStack createPreviousPageItem(int page, int pages, boolean enabled) {
        return GuiItems.previousPageButton(
            Component.text("前のページ " + page + " / " + pages, NamedTextColor.AQUA),
            List.of(Component.text("クリック: 前のページ", NamedTextColor.YELLOW)),
            enabled
        );
    }

    private ItemStack createNextPageItem(int page, int pages, boolean enabled) {
        return GuiItems.nextPageButton(
            Component.text("次のページ " + (page + 2) + " / " + pages, NamedTextColor.AQUA),
            List.of(Component.text("クリック: 次のページ", NamedTextColor.YELLOW)),
            enabled
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

    /**
     * スキルマネージャー用に、開いている PlayerInventory の管理領域を設定枠へ置き換えます。
     * 正本の BAG/HOTBAR は変更せず、閉じる際は InventoryService の通常描画で復元します。
     */
    public void renderPlayerInventorySettings(
        @NotNull PlayerInventory inventory,
        @NotNull SkillBindSession session,
        @NotNull Map<String, SkillManagerEntry> entries,
        @NotNull List<SkillDefinition> permittedSkillDefinitions,
        int activePassiveSlots
    ) {
        ItemStack background = createItem(Material.GRAY_STAINED_GLASS_PANE, " ", NamedTextColor.DARK_GRAY, List.of());
        for (int slot = 0; slot <= PLAYER_INVENTORY_ACTIVE_NEXT_SLOT; slot++) {
            inventory.setItem(slot, background.clone());
        }
        for (int presetIndex = 1; presetIndex <= SkillBindPreset.PRESET_COUNT; presetIndex++) {
            SkillBindPreset preset = session.presets().get(presetIndex - 1);
            inventory.setItem(presetIndex - 1, createPresetItem(preset, presetIndex == session.selectedPresetIndex()));
        }
        inventory.setItem(PLAYER_INVENTORY_LEFT_CLICK_SLOT, createBindSlot(
            SkillBindType.LEFT_CLICK, 0, session.leftClickDraft(), entries, permittedSkillDefinitions,
            session.isSelectedBindSlot(SkillBindType.LEFT_CLICK, 0), true
        ));
        inventory.setItem(PLAYER_INVENTORY_NORMAL_ATTACK_SLOT, createNormalAttackItem());
        renderScrolledBindSlots(inventory, PLAYER_INVENTORY_PASSIVE_PREVIOUS_SLOT,
            PLAYER_INVENTORY_PASSIVE_SLOT_START, PLAYER_INVENTORY_PASSIVE_NEXT_SLOT, SkillBindType.PASSIVE,
            session.passiveSlotOffset(), SkillBindPreset.PASSIVE_SLOT_COUNT, activePassiveSlots,
            session.passiveDraft(), session, entries, permittedSkillDefinitions, "パッシブ");
        renderScrolledBindSlots(inventory, PLAYER_INVENTORY_ACTIVE_PREVIOUS_SLOT,
            PLAYER_INVENTORY_ACTIVE_SLOT_START, PLAYER_INVENTORY_ACTIVE_NEXT_SLOT, SkillBindType.ACTIVE,
            session.activeSlotOffset(), SkillBindPreset.ACTIVE_SLOT_COUNT, SkillBindPreset.DEFAULT_ACTIVE_SLOT_COUNT,
            session.activeDraft(), session, entries, permittedSkillDefinitions, "アクション");
    }

    private void renderScrolledBindSlots(
        @NotNull PlayerInventory inventory, int previousSlot, int firstBindSlot, int nextSlot,
        @NotNull SkillBindType type, int offset, int totalSlotCount, int enabledSlotCount,
        @NotNull List<String> bindings, @NotNull SkillBindSession session,
        @NotNull Map<String, SkillManagerEntry> entries, @NotNull List<SkillDefinition> permittedDefinitions,
        @NotNull String label
    ) {
        boolean previousEnabled = offset > 0;
        boolean nextEnabled = offset + PLAYER_INVENTORY_VISIBLE_BIND_SLOT_COUNT < totalSlotCount;
        inventory.setItem(previousSlot, scrollButton(label + "を左へ", false, previousEnabled));
        inventory.setItem(nextSlot, scrollButton(label + "を右へ", true, nextEnabled));
        for (int displayIndex = 0; displayIndex < PLAYER_INVENTORY_VISIBLE_BIND_SLOT_COUNT; displayIndex++) {
            int index = offset + displayIndex;
            inventory.setItem(firstBindSlot + displayIndex, createBindSlot(type, index, bindings.get(index), entries,
                permittedDefinitions, session.isSelectedBindSlot(type, index), index < enabledSlotCount));
        }
    }

    /**
     * 共通ページングと同じ左右ヘッドで、設定枠の横スクロールを表示します。
     * @param name 操作名
     * @param right 右向きならtrue
     * @param enabled 移動できる場合true
     * @return 移動不能時は共通の操作不可ヘッド
     */
    private ItemStack scrollButton(@NotNull String name, boolean right, boolean enabled) {
        Component label = Component.text(name, NamedTextColor.AQUA);
        List<Component> lore = List.of(Component.text("クリックで1枠移動", NamedTextColor.YELLOW));
        return right ? GuiItems.nextPageButton(label, lore, enabled)
            : GuiItems.previousPageButton(label, lore, enabled);
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
            lore,
            material.getIconTexture()
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
            lore,
            material.getIconTexture()
        );
    }

    /** 合成後の性能と閲覧者の使用許可を反映したプレビューを作成します。 */
    private ItemStack createSynthesisResult(
        @NotNull SkillManagerEntry entry,
        @Nullable ItemModel material,
        @NotNull MaterialKind materialKind,
        @NotNull Set<String> permittedSkillIds
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
        ItemModel pendingSigil = material;
        int resultingLevel = currentLevel;
        ResolvedLearnedSkill preview = resolvedPreview(entry, resultingLevel, pendingSigil);
        List<Component> lore = new ArrayList<>();
        lore.addAll(SkillPresentationUtil.skillDescriptionAndFlavorLore(preview, permittedSkillIds, NamedTextColor.GRAY));
        if (!lore.isEmpty()) {
            lore.add(separator());
        }
        appendCastCostLore(lore, preview);
        lore.add(separator());
        lore.add(Component.text(
            "種別: " + (skill.getKind().isPassive() ? "パッシブ" : "アクティブ"),
            NamedTextColor.GRAY
        ));
        lore.add(Component.text("シジルを装着します（取り外し不可）", NamedTextColor.LIGHT_PURPLE));
        lore.add(separator());
        appendSigilSlotLore(lore, entry, resultingLevel, pendingSigil);
        lore.add(separator());
        lore.add(Component.text(
            "クリックでシジルを消費して装着",
            NamedTextColor.YELLOW
        ));
        return createItem(
            parseMaterial(preview.definition().getIcon(), DEFAULT_SKILL_ICON),
                SkillPresentationUtil.skillNameComponent(preview.definition(), skill.getId(), NamedTextColor.WHITE)
                    .append(skillLevelDisplay(resultingLevel, skill.getMaxLevel(), NamedTextColor.GREEN)),
            lore,
            preview.definition().getIconTexture()
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
            case NONE -> "このアイテムは合成素材にできません。";
            case SIGIL -> "";
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

    private ItemStack createItem(Material material, Component name, List<Component> lore, @Nullable String iconTexture) {
        return GuiItems.create(material, name, lore, iconTexture);
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
