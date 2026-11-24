package io.github.maaasu.astralRecord.feature.skill.gui;

import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgResource;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.item.model.ItemSigilModifier;
import io.github.maaasu.astralRecord.feature.item.service.ItemService;
import io.github.maaasu.astralRecord.feature.item.service.ItemStackFactory;
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
import io.github.maaasu.astralRecord.feature.skill.model.SkillRequiredItemDefinition;
import io.github.maaasu.astralRecord.feature.skill.model.SkillSigilSlotDefinition;
import io.github.maaasu.astralRecord.feature.skill.service.SkillSynthesisMaterialEligibility.MaterialKind;
import io.github.maaasu.astralRecord.feature.skill.service.SkillIconCatalog;
import io.github.maaasu.astralRecord.feature.skill.service.SkillPresentationUtil;
import io.github.maaasu.astralRecord.feature.skill.service.SkillService;
import io.github.maaasu.astralRecord.feature.status.model.StatusType;
import io.github.maaasu.astralRecord.infrastructure.util.MaterialNameResolver;
import io.github.maaasu.astralRecord.shared.gui.GuiPagination;
import io.github.maaasu.astralRecord.shared.gui.GuiItems;
import io.github.maaasu.astralRecord.shared.gui.GuiResourcePackIcons;
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
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.skill.model.PlayerSkillCaster;
import io.github.maaasu.astralRecord.feature.skill.service.SkillDamagePreview;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.ToLongBiFunction;
import java.util.function.Function;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;

/** 習得済みスキルの表示・バインドを扱うスキルマネージャー GUI です。 */
public final class SkillBindGui {
    private static final UUID PREVIEW_INSTANCE_ID = new UUID(0, 0);
    public static final int SIZE = 54;
    /** スキル一覧は上5行に45件を表示し、最下段をナビゲーションに使用します。 */
    public static final int CONTENT_SLOT_COUNT = 45;
    public static final int PREVIOUS_PAGE_SLOT = 45;
    public static final int BACK_SLOT = 49;
    public static final int PERFORMANCE_BOOK_SLOT = 50;
    public static final int PERFORMANCE_NORMAL_ATTACK_SLOT = 51;
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
    private static final int PERFORMANCE_METRICS_SLOT = 11;
    private static final int PERFORMANCE_CONDITIONS_SLOT = 15;

    public static final int SYNTHESIS_SKILL_SLOT = 20;
    public static final int SYNTHESIS_MATERIAL_SLOT = 22;
    public static final int SYNTHESIS_RESULT_SLOT = 24;
    public static final int SYNTHESIS_BACK_SLOT = 31;

    private static final Material DEFAULT_SKILL_ICON = Material.AMETHYST_SHARD;
    private static final int PERMITTED_SKILL_LORE_LIMIT = 6;
    private final NamespacedKey learnedSkillIdKey;
    private final NamespacedKey unlearnedSkillIdKey;
    private final ItemService itemService;
    private final ItemStackFactory itemStackFactory;
    private final SkillService skillService;
    private final ConfirmDialogView confirmDialogView = new ConfirmDialogView();
    private ToLongBiFunction<UUID, String> requiredItemOwnedAmountProvider = (accountId, itemId) -> 0L;

    private enum UnlearnedAction { LEARN, SELECTED_BIND_SLOT, BUSY, VIEW }
    private enum LearnedDisplay { MANAGE, VIEW, DETAIL }
    private record OwnedMaterialKey(UUID accountId, String itemId) { }

    /**
     * Plugin の namespace を利用してスキルマネージャー GUI を構築します。
     *
     * @param plugin PDC キーの namespace を提供する Plugin
     * @param itemService アイテム表示・検索サービス
     * @param itemStackFactory マスタの外見・光沢を保持する表示生成処理
     * @param skillService スキル定義・解決サービス
     */
    public SkillBindGui(
        @NotNull Plugin plugin,
        @NotNull ItemService itemService,
        @NotNull ItemStackFactory itemStackFactory,
        @NotNull SkillService skillService
    ) {
        learnedSkillIdKey = new NamespacedKey(plugin, "skill_manager_learned_skill_id");
        unlearnedSkillIdKey = new NamespacedKey(plugin, "skill_manager_unlearned_skill_id");
        this.itemService = itemService;
        this.itemStackFactory = itemStackFactory;
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
        var ownedAmounts = memoizedMaterialAmounts();
        UnlearnedAction unlearnedAction = session.processingSkillId() != null ? UnlearnedAction.BUSY
            : session.selectedBindType() == null ? UnlearnedAction.LEARN : UnlearnedAction.SELECTED_BIND_SLOT;
        for (int index = start; index < end; index++) {
            inventory.setItem(index - start, index < entries.size()
                ? createLearnedSkillItem(entries.get(index), LearnedDisplay.MANAGE, permittedSkillIds, session,
                    activePassiveSlots, ownedAmounts)
                : createUnlearnedSkillItem(
                    unlearnedDefinitions.get(index - entries.size()), session.processingSkillId(), permittedSkillIds,
                    session.selectedPreset().getAccountId(), unlearnedAction, ownedAmounts));
        }

        inventory.setItem(PREVIOUS_PAGE_SLOT, createPreviousPageItem(page, pages, page > 0));
        inventory.setItem(BACK_SLOT, GuiItems.backButton(new GuiNavigationDestination(
            Material.PLAYER_HEAD,
            "メニュー",
            GuiItems.MAIN_MENU_HEAD_TEXTURE
        )));
        inventory.setItem(NEXT_PAGE_SLOT, createNextPageItem(page, pages, page + 1 < pages));
        inventory.setItem(PERFORMANCE_BOOK_SLOT, performanceBook("性能内訳", "対象スキルと算出条件を確認"));
        return inventory;
    }

    /**
     * 性能を閲覧する対象だけを選ぶ一覧を生成します。
     * @param session 現在の編集セッション
     * @param entries 習得済みスキル
     * @param unlearned 現在許可されている未習得スキル
     * @param permitted 使用許可スキルID
     * @param pageIndex 対象一覧ページ
     * @return スキル編集を行わない一覧
     */
    public @NotNull Inventory createPerformanceList(
        SkillBindSession session, List<SkillManagerEntry> entries, List<SkillDefinition> unlearned,
        Set<String> permitted, int pageIndex
    ) {
        int count = entries.size() + unlearned.size();
        int page = normalizePage(pageIndex, count);
        int pages = totalPages(count);
        Inventory inventory = Bukkit.createInventory(
            new SkillBindInventoryHolder(SkillBindScreen.PERFORMANCE_LIST, session.selectedPresetIndex(), page),
            SIZE, Component.text("性能内訳　対象を選択 " + (page + 1) + "/" + pages, NamedTextColor.AQUA)
        );
        fill(inventory);
        int start = GuiPagination.pageStart(page, CONTENT_SLOT_COUNT);
        int end = GuiPagination.pageEnd(page, count, CONTENT_SLOT_COUNT);
        var ownedAmounts = memoizedMaterialAmounts();
        for (int index = start; index < end; index++) {
            inventory.setItem(index - start, index < entries.size()
                ? createLearnedSkillItem(entries.get(index), LearnedDisplay.VIEW, permitted, null, 0, ownedAmounts)
                : createUnlearnedSkillItem(unlearned.get(index - entries.size()), null, permitted,
                    session.selectedPreset().getAccountId(), UnlearnedAction.VIEW, ownedAmounts));
        }
        inventory.setItem(PREVIOUS_PAGE_SLOT, createPreviousPageItem(page, pages, page > 0));
        inventory.setItem(NEXT_PAGE_SLOT, createNextPageItem(page, pages, page + 1 < pages));
        inventory.setItem(BACK_SLOT, GuiItems.backButton(new GuiNavigationDestination(
            Material.ENCHANTING_TABLE, "スキルマネージャー")));
        ItemStack attack = createItem(Material.STICK, "武器通常攻撃", NamedTextColor.WHITE,
            List.of(SkillLore.note("クリックで性能内訳を表示")));
        inventory.setItem(PERFORMANCE_NORMAL_ATTACK_SLOT,
            SkillIconCatalog.apply(attack, SkillBindPreset.WEAPON_NORMAL_ATTACK_BINDING_ID));
        return inventory;
    }

    /**
     * 対象の性能と算出条件を別々のアイテムで表示する閲覧画面を生成します。
     * @param session 現在の編集セッション
     * @param owner 試算対象のプレイヤー
     * @param skill 閲覧するスキル、通常攻撃の場合は null
     * @param entry 習得済み個体がある場合はその解決結果
     * @param permitted 効果の表示判定に使う使用許可スキルID
     * @param page 対象一覧のページ
     * @return 表示専用の性能詳細画面
     */
    public @NotNull Inventory createPerformanceDetail(
        SkillBindSession session, AstPlayer owner, @Nullable SkillDefinition skill, @Nullable SkillManagerEntry entry,
        Set<String> permitted, int page
    ) {
        Inventory inventory = Bukkit.createInventory(
            new SkillBindInventoryHolder(SkillBindScreen.PERFORMANCE_DETAIL, session.selectedPresetIndex(), page),
            DETAIL_SIZE, Component.text("性能内訳", NamedTextColor.AQUA)
        );
        fill(inventory);
        List<Component> metrics = new ArrayList<>();
        List<Component> conditions = new ArrayList<>();
        ResolvedLearnedSkill resolved = entry == null && skill != null
            ? firstLevel(skill, owner.getAccount().getUuid()) : entry == null ? null : entry.resolved();
        SkillDamagePreview.Report report;
        if (skill == null) {
            report = normalAttackPreview.apply(owner);
            SkillLore.section(metrics, "性能", NamedTextColor.GOLD);
        } else if (resolved != null) {
            report = appendCastMetrics(metrics, resolved);
        } else {
            report = SkillDamagePreview.Report.empty();
            appendBaseCastCostLore(metrics, skill);
        }
        if (!report.summary().isEmpty() || !report.details().isEmpty()) {
            if (skill != null) SkillLore.section(metrics, "試算値", NamedTextColor.GOLD);
            metrics.addAll(report.summary());
            metrics.addAll(report.details());
        }
        if (skill != null) {
            metrics.addAll(resolved == null
                ? SkillPresentationUtil.skillDescriptionAndFlavorLore(skill, permitted, NamedTextColor.GRAY)
                : SkillPresentationUtil.skillDescriptionAndFlavorLore(resolved, permitted, NamedTextColor.GRAY));
        }
        SkillLore.section(conditions, "算出条件", NamedTextColor.AQUA);
        conditions.addAll(report.conditions());
        if (report.conditions().isEmpty()) {
            conditions.add(SkillLore.note("対象の効果と発動条件は性能アイテムに表示"));
        }
        if (skill != null) {
            conditions.add(SkillLore.row("使用許可", permissionText(entry == null || entry.permitted())));
        }
        ItemStack indicator;
        if (skill == null) {
            if (report.summary().isEmpty() && report.details().isEmpty()) {
                metrics.add(SkillLore.note("主手の武器に対応する試算を表示"));
            }
            indicator = SkillIconCatalog.apply(createItem(Material.STICK, "武器通常攻撃", NamedTextColor.WHITE, metrics),
                SkillBindPreset.WEAPON_NORMAL_ATTACK_BINDING_ID);
        } else {
            int level = entry == null ? 1 : entry.learnedSkill().getLevel();
            Component name = SkillPresentationUtil.skillNameComponent(skill, "未登録のスキル", NamedTextColor.WHITE)
                .append(Component.text(" Lv." + level, NamedTextColor.GOLD));
            if (entry == null) name = name.append(Component.text("（習得時）", NamedTextColor.GRAY));
            indicator = createSkillItem(skill, entry == null || entry.permitted(), name, metrics);
        }
        inventory.setItem(PERFORMANCE_METRICS_SLOT, indicator);
        inventory.setItem(PERFORMANCE_CONDITIONS_SLOT, SkillLore.style(
            performanceBook("算出条件", conditions), null));
        inventory.setItem(DETAIL_BACK_SLOT, GuiItems.backButton(
            new GuiNavigationDestination(Material.KNOWLEDGE_BOOK, "性能内訳の対象一覧")));
        return inventory;
    }

    /** 配布パックと同じガイドの書を使う操作アイコンを生成します。 */
    private ItemStack performanceBook(String name, String instruction) {
        return performanceBook(name, List.of(SkillLore.note(instruction)));
    }

    /** 配布パックと同じガイドの書を使う説明アイコンを生成します。 */
    private ItemStack performanceBook(String name, List<Component> lore) {
        return GuiResourcePackIcons.apply(createItem(Material.KNOWLEDGE_BOOK, name, NamedTextColor.AQUA, lore), "guide");
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
        return createDetailInventory(session, entry, returnPage, mutationInProgress, permittedSkillIds,
            SkillBindPreset.PASSIVE_SLOT_COUNT);
    }

    /**
     * 強化比較と実際に利用できるバインド枠を含む詳細画面を生成します。
     * @param session 編集中セッション
     * @param entry 習得スキルと解決結果
     * @param returnPage 一覧に戻るページ
     * @param mutationInProgress 更新処理中かどうか
     * @param permittedSkillIds 現在の使用許可スキル
     * @param activePassiveSlots 利用可能なパッシブ枠数
     * @return スキル詳細画面
     */
    public @NotNull Inventory createDetailInventory(
        @NotNull SkillBindSession session,
        @NotNull SkillManagerEntry entry,
        int returnPage,
        boolean mutationInProgress,
        @NotNull Set<String> permittedSkillIds,
        int activePassiveSlots
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
        var ownedAmounts = memoizedMaterialAmounts();
        inventory.setItem(DETAIL_SKILL_SLOT,
            createLearnedSkillItem(entry, LearnedDisplay.DETAIL, permittedSkillIds, null, 0, ownedAmounts));
        if (mutationInProgress) {
            ItemStack processing = GuiItems.processingItem();
            inventory.setItem(DETAIL_BIND_SLOT, processing.clone());
            inventory.setItem(DETAIL_LEVEL_UP_SLOT, processing);
        } else {
            List<Component> bindLore = List.of(
                SkillLore.row("設定先", SkillLore.value(bindTargetText(session))),
                bindAction(session, entry, activePassiveSlots)
            );
            inventory.setItem(DETAIL_BIND_SLOT, SkillLore.style(createItem(
                Material.NAME_TAG, "バインド", NamedTextColor.GREEN, bindLore), null));
            inventory.setItem(DETAIL_LEVEL_UP_SLOT, createLevelUpItem(entry, permittedSkillIds, ownedAmounts));
        }
        inventory.setItem(DETAIL_BACK_SLOT, GuiItems.backButton(
            new GuiNavigationDestination(Material.ENCHANTING_TABLE, "スキルマネージャー")
        ));
        return inventory;
    }

    /** 現在の選択枠と空き枠に応じたバインド操作の案内を返します。 */
    private Component bindAction(SkillBindSession session, SkillManagerEntry entry, int activePassiveSlots) {
        SkillDefinition skill = entry.definition();
        if (skill.getKind().isPassive() && !skill.getPassiveBindRequired()) {
            return SkillLore.note("このスキルはバインド不要");
        }
        if (skill.getKind().isPassive() && session.isBound(SkillBindType.PASSIVE, entry.bindingId())) {
            return SkillLore.note("パッシブ枠に設定済み");
        }
        if (!session.canAssign(entry.bindingId(), skill.getKind(), activePassiveSlots, skill.getPassiveBindRequired())) {
            return Component.text(session.selectedBindType() == null
                ? "設定できる空き枠がありません" : "選択中の枠には設定できません", NamedTextColor.RED);
        }
        return SkillLore.note(session.selectedBindType() == null ? "クリックで空き枠に設定" : "クリックで選択中の枠に設定");
    }

    /** 次レベルをシジル込みで解決し、変化する効果、消費と試算値を表示します。 */
    private ItemStack createLevelUpItem(
        SkillManagerEntry entry, Set<String> permittedSkillIds, ToLongBiFunction<UUID, String> ownedAmounts
    ) {
        SkillDefinition skill = entry.definition();
        int level = entry.learnedSkill().getLevel();
        List<Component> lore = new ArrayList<>();
        SkillLore.section(lore, "強化", NamedTextColor.LIGHT_PURPLE);
        if (level >= skill.getMaxLevel()) {
            lore.add(SkillLore.row("レベル", Component.text("MAX", NamedTextColor.RED, TextDecoration.BOLD)));
            return SkillLore.style(createItem(Material.ENCHANTED_BOOK, "レベルアップ", NamedTextColor.GRAY, lore), null);
        }
        lore.add(SkillLore.row("レベル", SkillLore.value("Lv." + level + " → Lv." + (level + 1))));
        ResolvedLearnedSkill next = resolvedPreview(entry, level + 1, null);
        if (next != entry.resolved()) {
            var currentEffect = SkillPresentationUtil.skillDescriptionAndFlavorLore(entry.resolved(), permittedSkillIds, NamedTextColor.GRAY);
            var nextEffect = SkillPresentationUtil.skillDescriptionAndFlavorLore(next, permittedSkillIds, NamedTextColor.GRAY);
            if (!currentEffect.equals(nextEffect)) {
                lore.add(SkillLore.note("Lv." + (level + 1) + " の効果"));
                lore.addAll(nextEffect);
            }
            List<Component> currentMetrics = new ArrayList<>();
            currentMetrics.addAll(appendCastMetrics(currentMetrics, entry.resolved()).summary());
            List<Component> nextMetrics = new ArrayList<>();
            nextMetrics.addAll(appendCastMetrics(nextMetrics, next).summary());
            if (!currentMetrics.equals(nextMetrics)) {
                lore.add(SkillLore.note("Lv." + (level + 1) + " の性能"));
                lore.addAll(nextMetrics);
            }
        }
        int currentSlots = sigilSlotCount(skill, level);
        int nextSlots = sigilSlotCount(skill, level + 1);
        if (nextSlots > currentSlots) {
            lore.add(SkillLore.row("シジル枠", SkillLore.value(currentSlots + " → " + nextSlots + "枠")));
        }
        UUID accountId = entry.learnedSkill().getAccountId();
        SkillLore.section(lore, "必要素材", NamedTextColor.AQUA);
        appendRequiredItemLore(lore, skill.getLevelUpRequiredItems(), accountId, ownedAmounts);
        appendAction(lore, hasRequiredItems(skill.getLevelUpRequiredItems(), accountId, ownedAmounts)
            ? SkillLore.note("クリックでレベルアップ")
            : Component.text("素材がそろうと強化できます", NamedTextColor.RED));
        return SkillLore.style(createItem(Material.ENCHANTED_BOOK, "レベルアップ", NamedTextColor.LIGHT_PURPLE, lore), null);
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
        inventory.setItem(SYNTHESIS_SKILL_SLOT,
            createLearnedSkillItem(entry, LearnedDisplay.DETAIL, permittedSkillIds, null, 0,
                requiredItemOwnedAmountProvider));
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
     * @return {@code Lv.現在/最大} に最大レベル到達時の表示を付けた値
     */
    static @NotNull Component skillLevelDisplay(
        int level,
        int maxLevel,
        @NotNull NamedTextColor normalColor
    ) {
        Component rank = Component.text(" Lv." + level + "/" + maxLevel, normalColor);
        return level >= maxLevel ? rank.append(Component.text(" 最大", NamedTextColor.GOLD, TextDecoration.BOLD)) : rank;
    }

    /** 解決後の性能と、一覧の場合は現在のクリック結果を表示します。 */
    private ItemStack createLearnedSkillItem(
        SkillManagerEntry entry,
        LearnedDisplay display,
        @NotNull Set<String> permittedSkillIds,
        @Nullable SkillBindSession session,
        int activePassiveSlots,
        ToLongBiFunction<UUID, String> ownedAmounts
    ) {
        List<Component> lore = new ArrayList<>();
        lore.add(skillMeta(entry.definition(), entry.permitted()));
        appendLearnedSkillDetails(lore, entry, permittedSkillIds, ownedAmounts);
        if (display == LearnedDisplay.VIEW) {
            appendAction(lore, SkillLore.note("クリックで性能内訳を表示"));
        } else if (display == LearnedDisplay.MANAGE && session != null) {
            List<String> slots = new ArrayList<>();
            for (int index = 0; index < session.activeDraft().size(); index++) {
                if (entry.bindingId().equals(session.activeDraft().get(index))) slots.add("アクション " + (index + 1));
            }
            for (int index = 0; index < session.passiveDraft().size(); index++) {
                if (entry.bindingId().equals(session.passiveDraft().get(index))) slots.add("パッシブ " + (index + 1));
            }
            if (entry.bindingId().equals(session.leftClickDraft())) slots.add("左クリック");
            if (!slots.isEmpty()) {
                lore.add(SkillLore.row("設定先", Component.text(String.join("、", slots), NamedTextColor.AQUA)));
            }
            if (session.processingSkillId() != null) {
                appendAction(lore, SkillLore.note("スキル更新の完了をお待ちください"));
            } else if (entry.learnedSkill().getLevel() < entry.definition().getMaxLevel()
                && hasRequiredItems(entry.definition().getLevelUpRequiredItems(), entry.learnedSkill().getAccountId(), ownedAmounts)) {
                appendAction(lore, SkillLore.note("クリックで強化・バインドの詳細を表示"));
            } else {
                appendAction(lore, bindAction(session, entry, activePassiveSlots));
            }
        }
        SkillDefinition skill = entry.definition();
        ItemStack item = createSkillItem(
            skill,
            entry.permitted(),
            SkillPresentationUtil.skillNameComponent(skill, "未登録のスキル", NamedTextColor.WHITE)
                .append(skillLevelDisplay(entry.learnedSkill().getLevel(), skill.getMaxLevel(), NamedTextColor.GOLD)),
            lore
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
        lore.add(skillMeta(definition, permitted));
        if (resolved == null) {
            lore.addAll(SkillPresentationUtil.skillDescriptionAndFlavorLore(
                definition, permittedSkillIds, NamedTextColor.GRAY
            ));
            appendBaseCastCostLore(lore, definition);
            SkillLore.section(lore, "習得条件", NamedTextColor.AQUA);
            appendRequiredItemLore(lore, definition.getLearnRequiredItems(), null, requiredItemOwnedAmountProvider);
            lore.add(SkillLore.row("最大レベル", SkillLore.value("Lv." + definition.getMaxLevel())));
        } else {
            appendLearnedSkillDetails(
                lore,
                new SkillManagerEntry(resolved.learnedSkill(), definition, permitted, resolved),
                permittedSkillIds,
                requiredItemOwnedAmountProvider
            );
        }

        Component name = SkillPresentationUtil.skillNameComponent(
            definition, "未登録のスキル", NamedTextColor.WHITE
        );
        if (resolved != null) {
            name = name.append(skillLevelDisplay(
                resolved.learnedSkill().getLevel(), definition.getMaxLevel(), NamedTextColor.GOLD
            ));
        } else {
            name = name.append(Component.text(" 未習得", NamedTextColor.GRAY));
        }
        return createSkillItem(definition, permitted, name, lore);
    }

    /** 一覧へ渡された使用許可済みの未習得スキルを表示します。 */
    private ItemStack createUnlearnedSkillItem(
        @NotNull SkillDefinition skill,
        @Nullable String processingSkillId,
        @NotNull Set<String> permittedSkillIds,
        @NotNull UUID accountId,
        @NotNull UnlearnedAction action,
        ToLongBiFunction<UUID, String> ownedAmounts
    ) {
        if (processingSkillId != null && processingSkillId.equalsIgnoreCase(skill.getId())) {
            return GuiItems.processingItem();
        }
        List<Component> lore = new ArrayList<>();
        lore.add(skillMeta(skill, true));
        lore.addAll(SkillPresentationUtil.skillDescriptionAndFlavorLore(
            skill, permittedSkillIds, NamedTextColor.GRAY
        ));
        var firstLevel = firstLevel(skill, accountId);
        if (firstLevel == null) appendBaseCastCostLore(lore, skill);
        else appendCastCostLore(lore, firstLevel);
        SkillLore.section(lore, "習得条件", NamedTextColor.AQUA);
        appendRequiredItemLore(lore, skill.getLearnRequiredItems(), accountId, ownedAmounts);
        appendAction(lore, switch (action) {
            case VIEW -> SkillLore.note("クリックで性能内訳を表示");
            case SELECTED_BIND_SLOT -> SkillLore.note("バインド枠の選択中は習得できません");
            case BUSY -> SkillLore.note("スキル更新の完了をお待ちください");
            case LEARN -> hasRequiredItems(skill.getLearnRequiredItems(), accountId, ownedAmounts)
                ? SkillLore.note("左クリックで習得")
                : Component.text("素材がそろうと習得できます", NamedTextColor.RED);
        });
        ItemStack item = createSkillItem(skill, true,
            SkillPresentationUtil.skillNameComponent(skill, "未登録のスキル", NamedTextColor.WHITE)
                .append(Component.text(" 未習得", NamedTextColor.GRAY)), lore);
        ItemMeta meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(unlearnedSkillIdKey, PersistentDataType.STRING, skill.getId());
        item.setItemMeta(meta);
        return item;
    }

    /** 未習得の初期性能を、実行時と同じ解決処理で取得します。 */
    private @Nullable ResolvedLearnedSkill firstLevel(SkillDefinition skill, UUID accountId) {
        return skillService.resolveLearnedSkill(new LearnedSkillInstance(
            PREVIEW_INSTANCE_ID, accountId, skill.getId(), 1, List.of(), 0, null, null));
    }

    /** 同一素材への複数の要求を、支払い処理と同じく合算します。 */
    private Map<String, Long> materialAmounts(List<SkillRequiredItemDefinition> costs) {
        Map<String, Long> amounts = new LinkedHashMap<>();
        for (var cost : costs) {
            String id = cost.getItemId();
            ItemModel item = id == null || id.isBlank() ? null : itemService.findLoadedById(id);
            String key = item == null ? id : item.getId().trim().toLowerCase(Locale.ROOT);
            amounts.merge(key, (long) cost.getAmount(), Long::sum);
        }
        return amounts;
    }

    /** 要求素材がすべて支払えるか表示用に確認します。実際の消費は操作時に再検証されます。 */
    private boolean hasRequiredItems(
        List<SkillRequiredItemDefinition> costs, UUID accountId, ToLongBiFunction<UUID, String> ownedAmounts
    ) {
        for (var cost : costs) {
            if (cost.getAmount() <= 0 || cost.getItemId() == null || cost.getItemId().isBlank()
                || itemService.findLoadedById(cost.getItemId()) == null) return false;
        }
        return materialAmounts(costs).entrySet().stream().allMatch(requirement ->
            ownedAmounts.applyAsLong(accountId, requirement.getKey()) >= requirement.getValue());
    }

    /**
     * 同一描画で同じアカウントの同じ素材を複数回集計しない関数を作ります。
     * @return 次回の描画やクリック処理には引き継がない所持数取得関数
     */
    private ToLongBiFunction<UUID, String> memoizedMaterialAmounts() {
        Map<OwnedMaterialKey, Long> amounts = new HashMap<>();
        return (accountId, itemId) -> amounts.computeIfAbsent(new OwnedMaterialKey(accountId, itemId),
            key -> requiredItemOwnedAmountProvider.applyAsLong(key.accountId(), key.itemId()));
    }

    /** 素材名・必要数・所持数と、足りない数量を表示します。 */
    private void appendRequiredItemLore(
        @NotNull List<Component> lore,
        @NotNull List<SkillRequiredItemDefinition> costs,
        @Nullable UUID accountId,
        ToLongBiFunction<UUID, String> ownedAmounts
    ) {
        if (costs.isEmpty()) {
            lore.add(SkillLore.row("必要素材", SkillLore.value("なし")));
            return;
        }
        materialAmounts(costs).forEach((id, amount) -> {
            ItemModel item = id == null || id.isBlank() ? null : itemService.findLoadedById(id);
            Component name = item == null ? Component.text("未登録の素材", NamedTextColor.RED)
                : SkillPresentationUtil.itemNameComponent(item, "未登録の素材", NamedTextColor.WHITE);
            lore.add(SkillLore.materialRow(item == null ? null : item.getId(), "", name));
            Component quantity = SkillLore.note("   必要 ").append(SkillLore.value(Long.toString(amount)));
            if (accountId != null && item != null) {
                long owned = Math.max(0, ownedAmounts.applyAsLong(accountId, id));
                quantity = quantity.append(SkillLore.note(" / 所持 "))
                    .append(Component.text(owned, owned >= amount ? NamedTextColor.GREEN : NamedTextColor.RED));
                if (owned < amount) {
                    quantity = quantity.append(Component.text(" 不足 " + (amount - owned), NamedTextColor.RED));
                }
            }
            lore.add(quantity);
        });
    }

    /** 一覧・設定済みスロットで共通に表示する、習得済みスキルのプレイヤー向け詳細です。 */
    private void appendLearnedSkillDetails(
        @NotNull List<Component> lore,
        @NotNull SkillManagerEntry entry,
        @NotNull Set<String> permittedSkillIds,
        ToLongBiFunction<UUID, String> ownedAmounts
    ) {
        SkillDefinition skill = entry.definition();
        lore.addAll(SkillPresentationUtil.skillDescriptionAndFlavorLore(
            entry.resolved(), permittedSkillIds, NamedTextColor.GRAY
        ));
        List<Component> performance = new ArrayList<>();
        var currentReport = appendCastMetrics(performance, entry.resolved());
        performance.addAll(currentReport.summary());
        lore.addAll(performance);
        int currentLevel = entry.learnedSkill().getLevel();
        if (currentLevel < skill.getMaxLevel()) {
            SkillLore.section(lore, "レベルアップ　Lv." + currentLevel + " → " + (currentLevel + 1), NamedTextColor.LIGHT_PURPLE);
            appendRequiredItemLore(lore, skill.getLevelUpRequiredItems(), entry.learnedSkill().getAccountId(), ownedAmounts);
        }
        appendSigilSlotLore(lore, entry, currentLevel, null);
    }

    /** 種別、タグと使用許可をタイトル直下の短い行へまとめます。 */
    private Component skillMeta(SkillDefinition skill, boolean permitted) {
        String kind = skill.getKind().isPassive() ? "パッシブ" : "アクティブ";
        if (skill.getKind().isPassive() && !skill.getPassiveBindRequired()) kind += "（バインド不要）";
        Component meta = SkillLore.note(kind + " / ").append(permissionText(permitted));
        String tagNames = SkillPresentationUtil.skillTagDisplayNames(skill,
            Set.of(MasterTagIds.Activity.ACTIVE, MasterTagIds.Activity.PASSIVE));
        return tagNames.isBlank() ? meta : meta.append(SkillLore.note(" / " + tagNames));
    }

    /** 区切りと短い操作案内を追加します。 */
    private void appendAction(List<Component> lore, Component action) {
        lore.add(Component.text(DisplaySeparators.SECTION, NamedTextColor.DARK_GRAY));
        lore.add(action);
    }

    /** 現在のクラスとスキルツリーでの使用可否を返します。 */
    private Component permissionText(boolean permitted) {
        return Component.text(permitted ? "使用可能" : "使用不可",
            permitted ? NamedTextColor.GREEN : NamedTextColor.RED);
    }

    /** 実効消費・詠唱時間・再使用時間を追記し、解決済みの性能試算を返します。 */
    private SkillDamagePreview.Report appendCastMetrics(List<Component> lore, ResolvedLearnedSkill resolved) {
        SkillDefinition skill = resolved.definition();
        var owner = AstPlayerCache.getByAccount(resolved.learnedSkill().getAccountId());
        if (owner == null || owner.getStatusSnapshot().getValues().isEmpty()) {
            appendBaseCastCostLore(lore, skill);
            lore.add(SkillLore.note("装備の情報が読み込まれていません"));
            return SkillDamagePreview.Report.empty();
        }
        var preview = skillService.previewCast(new PlayerSkillCaster(owner), resolved);
        var report = SkillDamagePreview.describe(preview.status(), skill, preview.cooldownTicks(), preview.castTicks(),
            preview.circleCount());
        boolean showTiming = !skill.getKind().isPassive();
        if (!report.summary().isEmpty() || preview.costs().values().stream().anyMatch(cost -> cost > 0) || showTiming) {
            SkillLore.section(lore, "性能", NamedTextColor.GOLD);
        }
        preview.costs().forEach((type, cost) -> {
            if (cost <= 0) return;
            String name = switch (type) {
                case MANA -> "MP";
                case ENERGY -> "ENG";
                case GUARD -> "ガード";
            };
            lore.add(SkillLore.row(name + "消費", SkillLore.value(SkillDamagePreview.number(cost))));
        });
        if (showTiming) {
            lore.add(castTimingRow(preview.castTicks(), preview.cooldownTicks()));
        }
        return report;
    }

    /** 通常ロアに実効値の要約を表示します。 */
    private void appendCastCostLore(List<Component> lore, ResolvedLearnedSkill resolved) {
        var report = appendCastMetrics(lore, resolved);
        lore.addAll(report.summary());
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
        appendBaseCastCostLore(lore, skill, 0.0D);
    }

    /**
     * スキル定義とクールダウン短縮率から消費・クールダウン表示を生成します。
     *
     * @param lore 追記先
     * @param skill 表示対象スキル
     * @param cooldownReduction クールダウン短縮率
     */
    private void appendBaseCastCostLore(
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
        boolean supplementaryMana = resourceType == SkillResourceType.ENERGY && skill.getManaCost() > 0.0D;
        long cooldownTicks = skill.getKind().isPassive() ? 0
            : io.github.maaasu.astralRecord.feature.combat.service.CombatTimingCalculator
                .resolveCooldownTicks(skill.getCooldownTicks(), cooldownReduction);
        if (!skill.getKind().isPassive() || resourceCost > 0 || supplementaryMana) {
            SkillLore.section(lore, "基礎性能", NamedTextColor.GOLD);
        }
        if (resourceCost > 0) lore.add(SkillLore.row(resourceName + "消費", SkillLore.value(cost)));
        if (supplementaryMana) {
            String manaCost = BigDecimal.valueOf(skill.getManaCost()).stripTrailingZeros().toPlainString();
            lore.add(SkillLore.row("MP消費", SkillLore.value(manaCost)));
        }
        if (!skill.getKind().isPassive()) {
            lore.add(castTimingRow(skill.getCastTimeTicks(), cooldownTicks));
        }
    }

    /**
     * 発動方法と通常時クールダウンを同じ行へまとめます。
     * @param castTicks 基礎または実効詠唱時間
     * @param cooldownTicks 基礎または実効クールダウン
     * @return アクティブスキルの発動情報
     */
    private Component castTimingRow(long castTicks, long cooldownTicks) {
        Component timing = SkillLore.row("発動", SkillLore.value(castTicks > 0
            ? "詠唱" + SkillDamagePreview.number(castTicks / 20.0D) + "秒" : "即時"));
        if (cooldownTicks <= 0) return timing;
        return timing.append(SkillLore.note("  通常時CT "))
            .append(SkillLore.value(SkillDamagePreview.number(cooldownTicks / 20.0D) + "秒"));
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
        var nextSlot = entry.definition().getSigilSlotsByLevel().stream()
            .filter(slot -> slot.getLevel() > level && slot.getSlots() > slotCount)
            .min(java.util.Comparator.comparingInt(SkillSigilSlotDefinition::getLevel));
        if (slotCount == 0 && equippedCount == 0 && nextSlot.isEmpty()) return;
        SkillLore.section(lore, "シジル", NamedTextColor.LIGHT_PURPLE);
        lore.add(SkillLore.row("装着枠", SkillLore.value(equippedCount + " / " + slotCount)));
        nextSlot.ifPresent(next -> lore.add(SkillLore.row("次の枠",
            SkillLore.value("Lv." + next.getLevel() + " で " + next.getSlots() + "枠"))));
        if (slotCount <= 0) return;
        lore.add(PlayerMsgResource.getComponent(PlayerMsgId.P_5879.getId()));
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
        }
    }

    private void appendPendingSigilLore(List<Component> lore, int slotIndex, ItemModel sigil) {
        lore.add(Component.text("  スロット " + (slotIndex + 1) + ": ", NamedTextColor.GREEN).append(
            SkillPresentationUtil.itemNameComponent(sigil, "未登録のシジル", NamedTextColor.WHITE)
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
     * PLAYER_HEAD の場合は定義済み textures 値も適用します。
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
        SkillLore.section(lore, "バインド枠", NamedTextColor.AQUA);
        lore.add(SkillLore.row("状態", Component.text(enabled ? "有効" : "無効",
            enabled ? NamedTextColor.GREEN : NamedTextColor.RED)));
        if (type == SkillBindType.ACTIVE) {
            lore.add(SkillLore.row("アクションリング", SkillLore.value(index < SkillBindPreset.ACTION_RING_SLOT_COUNT
                ? "表示" : "非表示（キャストディスク等で使用）")));
        }
        if (!enabled && bindingId != null) {
            lore.add(SkillLore.note(interactive ? "現在は解除のみ可能" : "設定を保持中"));
        } else if (bindingId == null) {
            lore.add(Component.text(
                interactive
                    ? (enabled ? "クリックして設定先に選択" : "枠数を増やすまで設定不可")
                    : (enabled ? "未設定" : "現在は設定できません"),
                NamedTextColor.GRAY
            ));
            appendPermittedSkillLore(lore, type, permittedSkillDefinitions);
        } else {
            lore.add(SkillLore.note(interactive ? "クリックで解除" : "設定済み"));
        }
        if (entry != null) {
            appendLearnedSkillDetails(lore, entry, permittedSkillDefinitions.stream()
                .map(SkillDefinition::getId).collect(java.util.stream.Collectors.toSet()), requiredItemOwnedAmountProvider);
        } else if (bindingId != null && !normalAttack) {
            lore.add(SkillLore.row("習得", Component.text("未習得・発動不可", NamedTextColor.RED)));
        }
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
        ItemStack item = enabled && !normalAttack && entry != null
            ? createSkillItem(entry.definition(), entry.permitted(), name, lore)
            : createItem(bindSlotMaterial(enabled, bindingId, normalAttack), name, lore, null,
                enabled && normalAttack ? SkillBindPreset.WEAPON_NORMAL_ATTACK_BINDING_ID : null);
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
        SkillLore.section(lore, "使用許可スキル", NamedTextColor.AQUA);
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
     * 有効な習得個体を表示しないバインド枠の素材を選びます。
     *
     * @param enabled 現在設定可能な枠かどうか
     * @param bindingId バインドID。未設定時は null
     * @param normalAttack 通常攻撃予約IDの枠かどうか
     * @return バインド枠の表示素材
     */
    private static @NotNull Material bindSlotMaterial(
        boolean enabled,
        @Nullable String bindingId,
        boolean normalAttack
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
        return Material.BARRIER;
    }

    private Function<AstPlayer, SkillDamagePreview.Report> normalAttackPreview = player -> SkillDamagePreview.Report.empty();

    /**
     * 主手武器の読み取り専用プレビューを接続します。
     * @param preview 所有者の通常攻撃について性能と算出条件を返す関数
     */
    public void setNormalAttackPreview(Function<AstPlayer, SkillDamagePreview.Report> preview) {
        normalAttackPreview = preview;
    }

    /** 下段右端へ表示する武器通常攻撃の設定アイテムを生成します。 */
    public ItemStack createNormalAttackItem() {
        return createNormalAttackItem(SkillDamagePreview.Report.empty(), false);
    }

    /** 武器通常攻撃の要約と、現在選択している枠に応じた設定方法を表示します。 */
    private ItemStack createNormalAttackItem(SkillDamagePreview.Report preview, boolean bindTargetSelected) {
        List<Component> lore = new ArrayList<>();
        if (!preview.summary().isEmpty()) {
            SkillLore.section(lore, "性能", NamedTextColor.GOLD);
            lore.addAll(preview.summary());
        }
        appendAction(lore, SkillLore.note(bindTargetSelected ? "クリックで選択中の枠に設定"
            : "左クリック枠かアクション枠を選択してから設定"));
        ItemStack item = createItem(Material.STICK, "武器通常攻撃", NamedTextColor.WHITE, lore);
        String bindingId = SkillBindPreset.WEAPON_NORMAL_ATTACK_BINDING_ID;
        return withBindingId(SkillIconCatalog.apply(item, bindingId), bindingId);
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
        var owner = inventory.getHolder() instanceof Player player ? AstPlayerCache.get(player) : null;
        inventory.setItem(PLAYER_INVENTORY_NORMAL_ATTACK_SLOT,
                createNormalAttackItem(owner == null ? SkillDamagePreview.Report.empty() : normalAttackPreview.apply(owner),
                    session.selectedBindType() == SkillBindType.LEFT_CLICK || session.selectedBindType() == SkillBindType.ACTIVE));
        renderScrolledBindSlots(inventory, PLAYER_INVENTORY_PASSIVE_PREVIOUS_SLOT,
            PLAYER_INVENTORY_PASSIVE_SLOT_START, PLAYER_INVENTORY_PASSIVE_NEXT_SLOT, SkillBindType.PASSIVE,
            session.passiveSlotOffset(), SkillBindPreset.PASSIVE_SLOT_COUNT, activePassiveSlots,
            session.passiveDraft(), session, entries, permittedSkillDefinitions, "パッシブ");
        renderScrolledBindSlots(inventory, PLAYER_INVENTORY_ACTIVE_PREVIOUS_SLOT,
            PLAYER_INVENTORY_ACTIVE_SLOT_START, PLAYER_INVENTORY_ACTIVE_NEXT_SLOT, SkillBindType.ACTIVE,
            session.activeSlotOffset(), SkillBindPreset.ACTIVE_SLOT_COUNT, session.activeSlotCount(),
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

    /**
     * 選択素材の外見を維持し、選択解除の操作説明を表示します。
     * @param material 選択中の素材マスタ
     * @return 合成素材欄の表示専用スタック
     */
    private ItemStack createMaterialItem(ItemModel material) {
        List<Component> lore = new ArrayList<>();
        lore.addAll(SkillPresentationUtil.itemLoreComponents(material, NamedTextColor.GRAY));
        SkillLore.section(lore, "合成素材", NamedTextColor.AQUA);
        lore.add(SkillLore.row("状態", Component.text("選択中", NamedTextColor.GREEN)));
        lore.add(SkillLore.row("消費", SkillLore.value("装着時に1個")));
        appendAction(lore, SkillLore.note("クリックで素材選択を解除"));
        return createMaterialPreview(
            material,
            SkillPresentationUtil.itemNameComponent(material, "未登録の素材", NamedTextColor.WHITE),
            lore
        );
    }

    /**
     * 不適合素材の外見を維持し、装着できない理由を表示します。
     * @param material 不適合の素材マスタ
     * @param kind 素材判定の失敗理由
     * @return 素材の所持状態を変更しない表示専用スタック
     */
    private ItemStack createRejectedMaterialItem(@NotNull ItemModel material, @NotNull MaterialKind kind) {
        List<Component> lore = new ArrayList<>();
        lore.addAll(SkillPresentationUtil.itemLoreComponents(material, NamedTextColor.GRAY));
        SkillLore.section(lore, "合成条件", NamedTextColor.AQUA);
        lore.add(SkillLore.row("装着不可", Component.text(materialFailureText(kind), NamedTextColor.RED)));
        lore.add(SkillLore.row("消費", SkillLore.value("なし")));
        appendAction(lore, SkillLore.note("クリックで表示を戻す"));
        return createMaterialPreview(
            material,
            SkillPresentationUtil.itemNameComponent(material, "未登録の素材", NamedTextColor.WHITE),
            lore
        );
    }

    /**
     * 共通のアイテム工場で色・光沢を反映し、GUI専用の表示名と説明へ置き換えます。
     * @param material 表示する素材マスタ
     * @param name GUIの表示名
     * @param lore GUIの説明行
     * @return 色・光沢を保持した表示専用スタック。実所持品は変更しない
     */
    private ItemStack createMaterialPreview(ItemModel material, Component name, List<Component> lore) {
        ItemStack item = itemStackFactory.createDisplay(material, 1);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(name.decoration(TextDecoration.ITALIC, false));
        meta.lore(SkillLore.finish(lore));
        item.setItemMeta(meta);
        return SkillLore.style(item, null);
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
        lore.add(skillMeta(skill, entry.permitted()));
        lore.addAll(SkillPresentationUtil.skillDescriptionAndFlavorLore(preview, permittedSkillIds, NamedTextColor.GRAY));
        appendCastCostLore(lore, preview);
        appendSigilSlotLore(lore, entry, resultingLevel, pendingSigil);
        appendAction(lore, SkillLore.note("クリックで1個消費して装着（取り外し不可）"));
        return createItem(
            parseMaterial(preview.definition().getIcon(), DEFAULT_SKILL_ICON),
            SkillPresentationUtil.skillNameComponent(preview.definition(), "未登録のスキル", NamedTextColor.WHITE)
                .append(skillLevelDisplay(resultingLevel, skill.getMaxLevel(), NamedTextColor.GREEN)),
            lore,
            preview.definition().getIconTexture(),
            preview.definition().getId()
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
                PREVIEW_INSTANCE_ID,
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

    /** 装飾専用の空白パネルで未使用スロットを埋めます。 */
    private void fill(Inventory inventory) {
        ItemStack dummy = createItem(Material.BLACK_STAINED_GLASS_PANE, " ", NamedTextColor.BLACK, List.of());
        for (int slot = 0; slot < inventory.getSize(); slot++) inventory.setItem(slot, dummy.clone());
    }

    private ItemStack createItem(Material material, String name, NamedTextColor color, List<Component> lore) {
        return createItem(material, Component.text(name, color), lore);
    }

    private ItemStack createItem(Material material, Component name, List<Component> lore) {
        return GuiItems.create(material, name, SkillLore.finish(lore));
    }

    /**
     * 使用許可に応じてスキル固有の絵柄か、バリアを代替素材にした南京錠アイコンを使います。
     * @param skill 表示対象のスキル定義
     * @param permitted 現在の使用許可
     * @param name 表示名
     * @param lore 説明行
     * @return スキルカードの表示専用アイテム
     */
    private ItemStack createSkillItem(SkillDefinition skill, boolean permitted, Component name, List<Component> lore) {
        if (!permitted) {
            return SkillLore.style(GuiResourcePackIcons.apply(createItem(Material.BARRIER, name, lore), "skill_unavailable"), skill.getId());
        }
        return createItem(parseMaterial(skill.getIcon(), DEFAULT_SKILL_ICON), name, lore,
            skill.getIconTexture(), skill.getId());
    }

    private ItemStack createItem(
        Material material, Component name, List<Component> lore,
        @Nullable String iconTexture, @Nullable String skillId
    ) {
        return SkillIconCatalog.apply(GuiItems.create(material, name, SkillLore.finish(lore), iconTexture), skillId);
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
