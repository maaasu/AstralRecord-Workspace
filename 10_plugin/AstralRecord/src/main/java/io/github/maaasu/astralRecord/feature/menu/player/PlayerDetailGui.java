package io.github.maaasu.astralRecord.feature.menu.player;

import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.playerclass.model.ClassProgressViewEntry;
import io.github.maaasu.astralRecord.feature.status.model.StatusSnapshot;
import io.github.maaasu.astralRecord.feature.status.model.StatusType;
import io.github.maaasu.astralRecord.feature.status.model.StatusValue;
import io.github.maaasu.astralRecord.feature.skill.model.LearnedSkillInstance;
import io.github.maaasu.astralRecord.feature.skill.model.ResolvedLearnedSkill;
import io.github.maaasu.astralRecord.feature.skill.model.SkillBindPreset;
import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.feature.skill.model.SkillKind;
import io.github.maaasu.astralRecord.feature.skill.service.LearnedSkillService;
import io.github.maaasu.astralRecord.feature.skill.service.SkillBindPresetService;
import io.github.maaasu.astralRecord.feature.skill.service.SkillPermissionService;
import io.github.maaasu.astralRecord.feature.skill.service.SkillPresentationUtil;
import io.github.maaasu.astralRecord.feature.skill.service.SkillService;
import io.github.maaasu.astralRecord.feature.menu.view.screen.BaseMenuScreenView;
import io.github.maaasu.astralRecord.feature.world.service.WorldService;
import io.github.maaasu.astralRecord.infrastructure.util.ColorCodeUtil;
import io.github.maaasu.astralRecord.infrastructure.util.MaterialNameResolver;
import io.github.maaasu.astralRecord.shared.gui.hotbar.HotbarShortcutGuiHolder;
import io.github.maaasu.astralRecord.shared.gui.paging.PagedGuiView;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Statistic;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * プレイヤー詳細情報を表示する GUI です。
 */
public final class PlayerDetailGui extends BaseMenuScreenView {
    public static final int HEAD_SLOT = 4;
    public static final int RESOURCE_SLOT = 10;
    public static final int PRIMARY_SLOT = 11;
    public static final int OFFENSE_SLOT = 12;
    public static final int DEFENSE_SLOT = 13;
    public static final int ELEMENT_SLOT = 14;
    public static final int CONDITION_SLOT = 15;
    public static final int UTILITY_SLOT = 16;
    public static final int CLASS_SLOT = 30;
    public static final int SKILL_INFO_SLOT = 31;
    public static final int BUFF_SLOT = 32;
    public static final int TRADE_SLOT = 38;
    public static final int PARTY_INVITE_SLOT = 42;

    private static final String SEPARATOR = "◇════════════════◇";
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm");
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();
    private final PagedGuiView pagedGuiView = new PagedGuiView();
    private static final Set<StatusType> ELEMENT_DAMAGE_INCREASES = EnumSet.of(
        StatusType.FIRE_DAMAGE_INCREASE, StatusType.ICE_DAMAGE_INCREASE, StatusType.LIGHTNING_DAMAGE_INCREASE
    );
    private static final Set<StatusType> ELEMENT_RESISTANCES = EnumSet.of(
        StatusType.FIRE_RESISTANCE, StatusType.FIRE_RESISTANCE_CAP,
        StatusType.ICE_RESISTANCE, StatusType.ICE_RESISTANCE_CAP,
        StatusType.LIGHTNING_RESISTANCE, StatusType.LIGHTNING_RESISTANCE_CAP
    );
    private static final Set<StatusType> ELEMENT_PENETRATIONS = EnumSet.of(
        StatusType.FIRE_PENETRATION, StatusType.ICE_PENETRATION, StatusType.LIGHTNING_PENETRATION
    );
    private static final Set<StatusType> CONDITION_INCREASES = EnumSet.of(
        StatusType.BURNING_APPLY_CHANCE, StatusType.BURNING_DAMAGE_INCREASE,
        StatusType.FROZEN_APPLY_CHANCE, StatusType.CHILLED_APPLY_CHANCE,
        StatusType.SHOCKED_APPLY_CHANCE, StatusType.SHOCKED_DAMAGE_INCREASE,
        StatusType.POISONED_APPLY_CHANCE, StatusType.POISONED_DAMAGE_INCREASE,
        StatusType.BLINDNESS_APPLY_CHANCE, StatusType.WEAKNESS_APPLY_CHANCE,
        StatusType.HEALING_INHIBITION_APPLY_CHANCE
    );
    private static final Set<StatusType> CONDITION_RESISTANCES = EnumSet.of(
        StatusType.BURNING_RESISTANCE, StatusType.BURNING_DAMAGE_RESISTANCE,
        StatusType.FROZEN_RESISTANCE, StatusType.CHILLED_RESISTANCE,
        StatusType.SHOCKED_RESISTANCE, StatusType.SHOCKED_DAMAGE_RESISTANCE,
        StatusType.POISONED_RESISTANCE, StatusType.POISONED_DAMAGE_RESISTANCE,
        StatusType.BLINDNESS_RESISTANCE, StatusType.WEAKNESS_RESISTANCE,
        StatusType.HEALING_INHIBITION_RESISTANCE
    );
    private static final Set<StatusType> CONDITION_PENETRATIONS = EnumSet.of(
        StatusType.BURNING_DAMAGE_PENETRATION, StatusType.SHOCKED_DAMAGE_PENETRATION,
        StatusType.POISONED_DAMAGE_PENETRATION
    );

    private final WorldService worldService;
    private SkillService skillService;
    private SkillBindPresetService skillBindPresetService;
    private LearnedSkillService learnedSkillService;
    private SkillPermissionService skillPermissionService;

    /**
     * プレイヤー詳細 GUI を生成します。
     *
     * @param worldService Bukkit ワールドからプレイヤー向け表示名を解決するサービス
     */
    public PlayerDetailGui(@NotNull WorldService worldService) {
        this(worldService, null, null, null, null);
    }

    /**
     * プレイヤー詳細 GUI を生成します。
     *
     * @param worldService ワールド表示名解決サービス
     * @param skillService スキル定義サービス。null の場合はスキル画面を無効化
     * @param skillBindPresetService バインドプリセットサービス
     * @param learnedSkillService 習得済みスキルサービス
     * @param skillPermissionService 使用許可スキルサービス
     */
    public PlayerDetailGui(
        @NotNull WorldService worldService,
        @Nullable SkillService skillService,
        @Nullable SkillBindPresetService skillBindPresetService,
        @Nullable LearnedSkillService learnedSkillService,
        @Nullable SkillPermissionService skillPermissionService
    ) {
        this.worldService = worldService;
        this.skillService = skillService;
        this.skillBindPresetService = skillBindPresetService;
        this.learnedSkillService = learnedSkillService;
        this.skillPermissionService = skillPermissionService;
    }

    /**
     * スキル情報表示に利用するサービスを設定します。
     *
     * @param skillService スキル定義サービス
     * @param skillBindPresetService バインドプリセットサービス
     * @param learnedSkillService 習得済みスキルサービス
     * @param skillPermissionService 使用許可スキルサービス
     */
    public void setSkillServices(
        @NotNull SkillService skillService,
        @NotNull SkillBindPresetService skillBindPresetService,
        @NotNull LearnedSkillService learnedSkillService,
        @NotNull SkillPermissionService skillPermissionService
    ) {
        this.skillService = skillService;
        this.skillBindPresetService = skillBindPresetService;
        this.learnedSkillService = learnedSkillService;
        this.skillPermissionService = skillPermissionService;
    }

    /**
     * 詳細 GUI を開きます。
     *
     * @param viewer      閲覧者
     * @param target      表示対象
     * @param snapshot    表示用ステータス
     */
    public void open(
        @NotNull Player viewer,
        @NotNull AstPlayer target,
        @NotNull StatusSnapshot snapshot
    ) {
        open(viewer, target, snapshot, 0L, target.getClassId(), List.of());
    }

    /**
     * 詳細 GUI を表示用サマリ付きで開きます。
     *
     * @param viewer                  閲覧者
     * @param target                  表示対象
     * @param snapshot                表示用ステータス
     * @param goldAmount              対象アカウントの所持 Gold
     * @param classDisplayName        対象クラスの表示名
     * @param classProgresses          全クラスの独立した進行度
     */
    public void open(
        @NotNull Player viewer,
        @NotNull AstPlayer target,
        @NotNull StatusSnapshot snapshot,
        long goldAmount,
        @NotNull String classDisplayName,
        @NotNull List<ClassProgressViewEntry> classProgresses
    ) {
        Inventory inventory = Bukkit.createInventory(
            new Holder(target.getBukkit().getUniqueId()),
            SIZE,
            Component.text("プレイヤー情報: " + target.getBukkit().getName(), NamedTextColor.GOLD)
        );
        render(inventory, viewer, target, snapshot, goldAmount, classDisplayName, classProgresses);
        io.github.maaasu.astralRecord.shared.gui.GuiOpenSupport.open(viewer, inventory);
    }

    public boolean isInventory(@Nullable Inventory inventory) {
        return inventory != null && inventory.getHolder() instanceof Holder;
    }

    /**
     * ステータス詳細 GUI かどうかを判定します。
     *
     * @param inventory 判定対象のインベントリ
     * @return ステータス詳細 GUI の場合は {@code true}
     */
    public boolean isStatusDetailInventory(@Nullable Inventory inventory) {
        return inventory != null && inventory.getHolder() instanceof StatusDetailHolder;
    }

    /**
     * スキル情報の一覧選択 GUI か判定します。
     *
     * @param inventory 判定対象のインベントリ
     * @return 一覧選択 GUI なら true
     */
    public boolean isSkillInfoSelectionInventory(@Nullable Inventory inventory) {
        return inventory != null && inventory.getHolder() instanceof SkillInfoSelectionHolder;
    }

    /**
     * スキル一覧 GUI か判定します。
     *
     * @param inventory 判定対象のインベントリ
     * @return スキル一覧 GUI なら true
     */
    public boolean isSkillListInventory(@Nullable Inventory inventory) {
        return inventory != null && inventory.getHolder() instanceof SkillListHolder;
    }

    /**
     * スキル情報 GUI の対象プレイヤーを取得します。
     *
     * @param inventory 判定対象のインベントリ
     * @return 対象プレイヤー ID。対象外なら null
     */
    public @Nullable UUID getSkillInfoTargetId(@Nullable Inventory inventory) {
        if (inventory != null && inventory.getHolder() instanceof SkillInfoHolder holder) {
            return holder.targetId();
        }
        return null;
    }

    /**
     * スキル一覧の種別を取得します。
     *
     * @param inventory 判定対象のインベントリ
     * @return 一覧種別。対象外なら null
     */
    public @Nullable SkillListType getSkillListType(@Nullable Inventory inventory) {
        if (inventory != null && inventory.getHolder() instanceof SkillListHolder holder) {
            return holder.type();
        }
        return null;
    }

    /**
     * スキル一覧のページ番号を取得します。
     *
     * @param inventory 判定対象のインベントリ
     * @return ページ番号。対象外なら 0
     */
    public int getSkillListPageIndex(@Nullable Inventory inventory) {
        if (inventory != null && inventory.getHolder() instanceof SkillListHolder holder) {
            return holder.pageIndex();
        }
        return 0;
    }

    /**
     * 指定スキル一覧に次ページが存在するか判定します。
     *
     * @param target 表示対象プレイヤー
     * @param type 一覧種別
     * @param pageIndex 現在のページ番号
     * @return 次ページがあれば true
     */
    public boolean hasNextSkillListPage(@NotNull AstPlayer target, @NotNull SkillListType type, int pageIndex) {
        return pagedGuiView.hasNextPage(pageIndex, skillListSize(target, type));
    }

    private int skillListSize(@NotNull AstPlayer target, @NotNull SkillListType type) {
        return type == SkillListType.PERMITTED
            ? permittedSkillItems(target).size()
            : learnedSkillItems(target).size();
    }

    /**
     * 使用許可スキル一覧と習得済みスキル一覧の選択画面を開きます。
     *
     * @param viewer 閲覧者
     * @param target 表示対象プレイヤー
     */
    public void openSkillInfoSelection(@NotNull Player viewer, @NotNull AstPlayer target) {
        Inventory inventory = Bukkit.createInventory(
            new SkillInfoSelectionHolder(target.getBukkit().getUniqueId()),
            SIZE,
            Component.text("スキル情報", NamedTextColor.AQUA)
        );
        fill(inventory);
        inventory.setItem(BACK_SLOT, backItem());
        inventory.setItem(20, createItem(
            Material.ENCHANTED_BOOK,
            noItalic(Component.text("使用許可スキル", NamedTextColor.GREEN, TextDecoration.BOLD)),
            List.of(
                noItalic(Component.text("現在のクラス・スキルツリーで", NamedTextColor.GRAY)),
                noItalic(Component.text("使用を許可されているスキル", NamedTextColor.GRAY)),
                noItalic(Component.text("クリックで一覧を表示", NamedTextColor.YELLOW))
            )
        ));
        inventory.setItem(24, createItem(
            Material.BOOK,
            noItalic(Component.text("習得済みスキル", NamedTextColor.LIGHT_PURPLE, TextDecoration.BOLD)),
            List.of(
                noItalic(Component.text("スキルジェムから習得した", NamedTextColor.GRAY)),
                noItalic(Component.text("スキル個体の一覧", NamedTextColor.GRAY)),
                noItalic(Component.text("クリックで一覧を表示", NamedTextColor.YELLOW))
            )
        ));
        io.github.maaasu.astralRecord.shared.gui.GuiOpenSupport.open(viewer, inventory);
    }

    /** スキル一覧をページ付きで開きます。 */
    public void openSkillList(
        @NotNull Player viewer,
        @NotNull AstPlayer target,
        @NotNull SkillListType type,
        int pageIndex
    ) {
        List<ItemStack> items = type == SkillListType.PERMITTED
            ? permittedSkillItems(target)
            : learnedSkillItems(target);
        int normalizedPage = pagedGuiView.normalizePage(pageIndex, items.size());
        Inventory inventory = Bukkit.createInventory(
            new SkillListHolder(target.getBukkit().getUniqueId(), type, normalizedPage),
            PagedGuiView.SIZE,
            Component.text(type.title(), type.color())
        );
        pagedGuiView.render(inventory, items, normalizedPage);
        io.github.maaasu.astralRecord.shared.gui.GuiOpenSupport.open(viewer, inventory);
    }

    /**
     * ステータス詳細 GUI のカテゴリを取得します。
     *
     * @param inventory 判定対象のインベントリ
     * @return カテゴリ、対象外の場合は {@code null}
     */
    public @Nullable StatusType.Category getStatusDetailCategory(@Nullable Inventory inventory) {
        if (inventory != null && inventory.getHolder() instanceof StatusDetailHolder holder) {
            return holder.category();
        }
        return null;
    }

    /**
     * ステータス詳細 GUI のページ番号を取得します。
     *
     * @param inventory 判定対象のインベントリ
     * @return ページ番号、対象外の場合は {@code 0}
     */
    public int getStatusDetailPageIndex(@Nullable Inventory inventory) {
        if (inventory != null && inventory.getHolder() instanceof StatusDetailHolder holder) {
            return holder.pageIndex();
        }
        return 0;
    }

    /**
     * 指定カテゴリのステータス詳細 GUI を開きます。
     *
     * @param viewer 閲覧者
     * @param target 表示対象プレイヤー
     * @param category 表示カテゴリ
     * @param snapshot 表示用ステータススナップショット
     * @param pageIndex 開くページ番号
     */
    public void openStatusDetail(
        @NotNull Player viewer,
        @NotNull AstPlayer target,
        @NotNull StatusType.Category category,
        @NotNull StatusSnapshot snapshot,
        int pageIndex
    ) {
        List<StatusType> statuses = statusesInCategory(category, snapshot);
        int normalizedPage = pagedGuiView.normalizePage(pageIndex, statuses.size());
        Inventory inventory = Bukkit.createInventory(
            new StatusDetailHolder(target.getBukkit().getUniqueId(), category, normalizedPage),
            PagedGuiView.SIZE,
            Component.text(
                "ステータス詳細: " + category.getDisplayName(),
                NamedTextColor.GOLD
            )
        );
        List<ItemStack> items = statuses.stream()
            .map(type -> statusDetailItem(type, snapshot.getValue(type)))
            .toList();
        pagedGuiView.render(inventory, items, normalizedPage);
        io.github.maaasu.astralRecord.shared.gui.GuiOpenSupport.open(viewer, inventory);
    }

    public @Nullable UUID getTargetId(@Nullable Inventory inventory) {
        if (inventory != null && inventory.getHolder() instanceof Holder holder) {
            return holder.targetId();
        }
        return null;
    }

    /**
     * ステータス詳細 GUI の対象プレイヤー ID を取得します。
     *
     * @param inventory 判定対象のインベントリ
     * @return 対象プレイヤー ID、対象外の場合は {@code null}
     */
    public @Nullable UUID getStatusDetailTargetId(@Nullable Inventory inventory) {
        if (inventory != null && inventory.getHolder() instanceof StatusDetailHolder holder) {
            return holder.targetId();
        }
        return null;
    }

    private void render(
        @NotNull Inventory inventory,
        @NotNull Player viewer,
        @NotNull AstPlayer target,
        @NotNull StatusSnapshot snapshot,
        long goldAmount,
        @NotNull String classDisplayName,
        @NotNull List<ClassProgressViewEntry> classProgresses
    ) {
        boolean self = viewer.getUniqueId().equals(target.getBukkit().getUniqueId());
        fill(inventory);
        inventory.setItem(BACK_SLOT, backItem());
        inventory.setItem(HEAD_SLOT, playerHead(
            target,
            goldAmount,
            classDisplayName
        ));
        inventory.setItem(RESOURCE_SLOT, categoryItem(Material.GOLDEN_APPLE, "◆", StatusType.Category.RESOURCE, NamedTextColor.GOLD, snapshot));
        inventory.setItem(PRIMARY_SLOT, categoryItem(Material.DIAMOND, "◇", StatusType.Category.PRIMARY, NamedTextColor.YELLOW, snapshot));
        inventory.setItem(OFFENSE_SLOT, categoryItem(Material.NETHERITE_SWORD, "⚔", StatusType.Category.OFFENSE, NamedTextColor.RED, snapshot));
        inventory.setItem(DEFENSE_SLOT, categoryItem(Material.SHIELD, "✚", StatusType.Category.DEFENSE, NamedTextColor.BLUE, snapshot));
        inventory.setItem(ELEMENT_SLOT, categoryItem(Material.PRISMARINE_CRYSTALS, "✧", StatusType.Category.ELEMENT, NamedTextColor.LIGHT_PURPLE, snapshot));
        inventory.setItem(CONDITION_SLOT, categoryItem(Material.FERMENTED_SPIDER_EYE, "☣", StatusType.Category.CONDITION, NamedTextColor.DARK_PURPLE, snapshot));
        inventory.setItem(UTILITY_SLOT, categoryItem(Material.FEATHER, "✦", StatusType.Category.UTILITY, NamedTextColor.GREEN, snapshot));
        inventory.setItem(CLASS_SLOT, classProgressItem(classProgresses));
        inventory.setItem(SKILL_INFO_SLOT, skillInfoItem(target));
        inventory.setItem(BUFF_SLOT, createItem(
            Material.POTION,
            noItalic(Component.text("現在のバフ", NamedTextColor.AQUA)),
            buildBuffLore(target)
        ));
        inventory.setItem(TRADE_SLOT, actionItem(
            Material.EMERALD,
            "トレード申請",
            self,
            "対象プレイヤーへトレード申請を送ります",
            "自分自身へは申請できません"
        ));
        inventory.setItem(PARTY_INVITE_SLOT, actionItem(
            Material.WRITABLE_BOOK,
            "パーティー招待",
            self,
            "対象プレイヤーをパーティーへ招待します",
            "自分自身へは招待できません"
        ));
    }

    private @NotNull ItemStack skillInfoItem(@NotNull AstPlayer target) {
        List<Component> lore = new ArrayList<>();
        lore.add(separatorLine());
        appendBoundSkillLore(lore, target, SkillKind.ACTIVE, "アクティブスキル", NamedTextColor.AQUA);
        lore.add(separatorLine());
        appendBoundSkillLore(lore, target, SkillKind.PASSIVE, "パッシブスキル", NamedTextColor.LIGHT_PURPLE);
        lore.add(separatorLine());
        lore.add(noItalic(Component.text("クリックでスキル一覧を選択", NamedTextColor.YELLOW)));
        return createItem(Material.ENCHANTED_BOOK,
            noItalic(Component.text("スキル情報", NamedTextColor.AQUA, TextDecoration.BOLD)), lore);
    }

    private void appendBoundSkillLore(
        @NotNull List<Component> lore,
        @NotNull AstPlayer target,
        @NotNull SkillKind kind,
        @NotNull String heading,
        @NotNull TextColor color
    ) {
        lore.add(noItalic(Component.text(heading, color, TextDecoration.BOLD)));
        List<String> bindings = boundSkillIds(target, kind);
        if (bindings.isEmpty()) {
            lore.add(noItalic(Component.text("  なし", NamedTextColor.DARK_GRAY)));
            return;
        }
        for (String binding : bindings) {
            LearnedSkillInstance learned = learnedSkillService == null
                ? null
                : learnedSkillService.findInstance(target.getAccount().getUuid(), binding);
            ResolvedLearnedSkill resolved = learned == null || skillService == null
                ? null
                : skillService.resolveLearnedSkill(learned);
            SkillDefinition definition = resolved == null && skillService != null
                ? skillService.registry().getDefinition(learned == null ? binding : learned.getSkillId())
                : resolved == null ? null : resolved.definition();
            lore.add(noItalic(Component.text(" ▸ ", color)
                .append(SkillPresentationUtil.skillNameComponent(definition, binding, NamedTextColor.WHITE))));
        }
    }

    private @NotNull List<String> boundSkillIds(@NotNull AstPlayer target, @NotNull SkillKind kind) {
        if (skillBindPresetService == null || skillService == null) return List.of();
        SkillBindPreset preset = skillBindPresetService.getPresets(target.getAccount().getUuid()).stream()
            .filter(candidate -> candidate.isUnlocked()
                && candidate.getPresetIndex() == skillBindPresetService.selectedPresetIndex(target.getAccount().getUuid()))
            .findFirst().orElse(null);
        if (preset == null) return List.of();
        List<String> source = kind == SkillKind.ACTIVE ? preset.getActiveSkillSlots() : preset.getPassiveSkillSlots();
        return source.stream()
            .filter(id -> id != null && !id.isBlank())
            .filter(id -> !SkillBindPreset.WEAPON_NORMAL_ATTACK_BINDING_ID.equals(id))
            .filter(id -> {
                LearnedSkillInstance learned = learnedSkillService == null ? null
                    : learnedSkillService.findInstance(target.getAccount().getUuid(), id);
                SkillDefinition definition = learned == null ? skillService.registry().getDefinition(id)
                    : skillService.registry().getDefinition(learned.getSkillId());
                return definition != null && definition.getKind() == kind;
            })
            .distinct()
            .toList();
    }

    private @NotNull List<ItemStack> permittedSkillItems(@NotNull AstPlayer target) {
        if (skillPermissionService == null || skillService == null) return List.of();
        return skillPermissionService.permittedSkillIds(target).stream()
            .map(skillService.registry()::getDefinition)
            .filter(definition -> definition != null)
            .sorted(Comparator.comparing(definition -> SkillPresentationUtil.plainName(definition, definition.getId())))
            .map(definition -> skillItem(definition, null, "使用許可済み"))
            .toList();
    }

    private @NotNull List<ItemStack> learnedSkillItems(@NotNull AstPlayer target) {
        if (learnedSkillService == null || skillService == null) return List.of();
        return learnedSkillService.getLearnedSkills(target.getAccount().getUuid()).stream()
            .map(skillService::resolveLearnedSkill)
            .filter(resolved -> resolved != null)
            .sorted(Comparator.comparing(resolved -> SkillPresentationUtil.plainName(resolved.definition(), resolved.learnedSkill().getSkillId())))
            .map(resolved -> skillItem(resolved.definition(), resolved, "習得済み"))
            .toList();
    }

    private @NotNull ItemStack skillItem(
        @NotNull SkillDefinition definition,
        @Nullable ResolvedLearnedSkill resolved,
        @NotNull String state
    ) {
        Material material = MaterialNameResolver.match(definition.getIcon());
        List<Component> lore = new ArrayList<>();
        lore.add(noItalic(Component.text(state + " / "
            + (definition.getKind() == SkillKind.ACTIVE ? "アクティブ" : "パッシブ"), NamedTextColor.GRAY)));
        if (resolved != null) {
            lore.add(noItalic(Component.text("レベル: " + resolved.learnedSkill().getLevel(), NamedTextColor.YELLOW)));
            lore.addAll(SkillPresentationUtil.skillDescriptionAndFlavorLore(resolved, NamedTextColor.WHITE));
        } else {
            lore.addAll(SkillPresentationUtil.skillDescriptionAndLore(definition, NamedTextColor.WHITE));
        }
        lore.add(Component.empty());
        lore.add(noItalic(Component.text("スキルID: " + definition.getId(), NamedTextColor.DARK_GRAY)));
        return createItem(material == null ? Material.BOOK : material,
            SkillPresentationUtil.skillNameComponent(definition, definition.getId(), NamedTextColor.WHITE), lore);
    }

    private @NotNull ItemStack playerHead(
        @NotNull AstPlayer target,
        long goldAmount,
        @NotNull String classDisplayName
    ) {
        ItemStack itemStack = new ItemStack(Material.PLAYER_HEAD);
        ItemMeta meta = itemStack.getItemMeta();
        if (meta instanceof SkullMeta skullMeta) {
            OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(target.getBukkit().getUniqueId());
            skullMeta.setOwningPlayer(offlinePlayer);
            skullMeta.displayName(noItalic(Component.text(target.getBukkit().getName(), NamedTextColor.WHITE, TextDecoration.BOLD)));
            List<Component> lore = new ArrayList<>();
            lore.add(noItalic(Component.text("プレイヤー Lv." + target.getAccount().getLevel(), NamedTextColor.YELLOW)));
            lore.add(noItalic(Component.text("現在のクラス: ", NamedTextColor.GRAY).append(legacy(classDisplayName))));
            lore.add(noItalic(Component.text("アカウント: " + target.getAccount().getAccountName(), NamedTextColor.WHITE)));
            lore.add(noItalic(Component.text("モード: " + target.getAccount().getMode().getDisplayName(), NamedTextColor.GRAY)));
            lore.add(noItalic(Component.text("累計 EXP: " + formatInt(target.getAccount().getTotalExperience()), NamedTextColor.YELLOW)));
            lore.add(Component.empty());
            lore.add(noItalic(Component.text("所持ゴールド: " + formatInt(goldAmount), NamedTextColor.GOLD, TextDecoration.BOLD)));
            lore.add(noItalic(Component.text("プレイ時間: " + formatPlayTime(target), NamedTextColor.YELLOW)));
            lore.add(noItalic(Component.text("現在地: " + displayWorldName(target.getBukkit()), NamedTextColor.GRAY)));
            lore.add(noItalic(Component.text("初ログイン: " + formatDateTime(target.getUser().getJoinDate()), NamedTextColor.GRAY)));
            lore.add(noItalic(Component.text("最終ログイン: " + formatDateTime(target.getUser().getLastJoinDate()), NamedTextColor.GRAY)));
            skullMeta.lore(lore);
            skullMeta.addItemFlags(ItemFlag.values());
            itemStack.setItemMeta(skullMeta);
        }
        return itemStack;
    }

    private @NotNull ItemStack classProgressItem(@NotNull List<ClassProgressViewEntry> classProgresses) {
        ClassProgressViewEntry current = classProgresses.stream()
            .filter(ClassProgressViewEntry::getCurrent)
            .findFirst()
            .orElse(classProgresses.isEmpty() ? null : classProgresses.get(0));
        Material material = current == null
            ? Material.EXPERIENCE_BOTTLE
            : parseMaterial(current.getIcon(), Material.EXPERIENCE_BOTTLE);
        List<Component> lore = new ArrayList<>();

        if (current == null) {
            lore.add(noItalic(Component.text("クラス情報を取得できません", NamedTextColor.GRAY)));
        } else {
            lore.add(noItalic(Component.text("現在: ", NamedTextColor.GRAY)
                .append(legacy(current.getName()))
                .append(Component.text(" Lv." + current.getLevel(), NamedTextColor.AQUA, TextDecoration.BOLD))));
            lore.add(noItalic(Component.text("累計 CEXP: " + formatInt(current.getExperience()), NamedTextColor.YELLOW)));
            lore.add(classExperienceBar(current.getExperienceProgress()));
            lore.add(noItalic(Component.text(
                current.getExperienceRemaining() <= 0
                    ? "次のクラス Lv: 最大レベル"
                    : "次のクラス Lv まで: " + formatInt(current.getExperienceRemaining()) + " CEXP",
                NamedTextColor.AQUA
            )));
            lore.add(Component.empty());
            lore.add(noItalic(Component.text("全クラスレベル", NamedTextColor.GOLD, TextDecoration.BOLD)));
            for (ClassProgressViewEntry progress : classProgresses) {
                Component marker = Component.text(
                    progress.getCurrent() ? "● " : "・ ",
                    progress.getCurrent() ? NamedTextColor.GREEN : NamedTextColor.DARK_GRAY
                );
                lore.add(noItalic(marker
                    .append(legacy(progress.getName()))
                    .append(Component.text(" Lv." + progress.getLevel(), NamedTextColor.WHITE))));
            }
        }
        return createItem(
            material,
            noItalic(Component.text("クラス情報", NamedTextColor.AQUA, TextDecoration.BOLD)),
            lore
        );
    }

    private @NotNull List<StatusType> statusesInCategory(
        @NotNull StatusType.Category category,
        @NotNull StatusSnapshot snapshot
    ) {
        return StatusType.byCategory(category).stream()
            .filter(type -> snapshot.getValue(type) != null)
            .sorted(Comparator.comparingInt(PlayerDetailGui::statusGroupOrder))
            .toList();
    }

    private @NotNull ItemStack statusDetailItem(
        @NotNull StatusType type,
        @Nullable StatusValue value
    ) {
        List<Component> lore = new ArrayList<>();
        lore.add(noItalic(Component.text(type.getDescription(), NamedTextColor.GRAY)));
        lore.add(Component.empty());
        if (value == null) {
            lore.add(noItalic(Component.text("現在値: 未計算", NamedTextColor.DARK_GRAY)));
        } else {
            lore.add(noItalic(Component.text(
                "現在値: " + type.formatRange(value.getMinValue(), value.getMaxValue()),
                NamedTextColor.WHITE,
                TextDecoration.BOLD
            )));
            lore.add(noItalic(Component.text(
                "基礎値: " + type.formatRange(value.getBaseMinValue(), value.getBaseMaxValue()),
                NamedTextColor.GRAY
            )));
            lore.add(noItalic(Component.text(
                "合計補正: " + type.formatSignedRange(value.getBonusMinValue(), value.getBonusMaxValue()),
                value.getBonusMinValue() >= 0.0D && value.getBonusMaxValue() >= 0.0D
                    ? NamedTextColor.GREEN
                    : NamedTextColor.RED
            )));
        }
        return createItem(
            statusMaterial(type),
            noItalic(Component.text(type.getDisplayName(), type.namedColor(), TextDecoration.BOLD)),
            lore
        );
    }

    private @NotNull Material statusMaterial(@NotNull StatusType type) {
        return switch (type.getCategory()) {
            case RESOURCE -> Material.GOLDEN_APPLE;
            case PRIMARY -> Material.DIAMOND;
            case OFFENSE -> Material.NETHERITE_SWORD;
            case DEFENSE -> Material.SHIELD;
            case ELEMENT -> Material.PRISMARINE_CRYSTALS;
            case CONDITION -> Material.FERMENTED_SPIDER_EYE;
            case UTILITY -> Material.FEATHER;
        };
    }

    private @NotNull Component classExperienceBar(double progress) {
        int width = 20;
        double normalized = Math.max(0.0, Math.min(1.0, progress));
        int completed = (int) Math.floor(normalized * width);
        return noItalic(Component.text("CEXP ", NamedTextColor.GRAY)
            .append(Component.text("|".repeat(completed), NamedTextColor.GREEN))
            .append(Component.text("|".repeat(width - completed), NamedTextColor.DARK_GRAY))
            .append(Component.text(" " + formatPercent(normalized), NamedTextColor.WHITE)));
    }

    private @NotNull Material parseMaterial(@Nullable String value, @NotNull Material fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        Material material = Material.matchMaterial(value.trim().toUpperCase(Locale.ROOT));
        return material == null ? fallback : material;
    }

    private @NotNull ItemStack categoryItem(
        @NotNull Material material,
        @NotNull String icon,
        @NotNull StatusType.Category category,
        @NotNull TextColor color,
        @NotNull StatusSnapshot snapshot
    ) {
        Component name = noItalic(Component.empty()
            .append(Component.text(icon + " ", color))
            .append(Component.text(category.getDisplayName(), color, TextDecoration.BOLD))
            .append(Component.text(" " + icon, color)));

        List<Component> lore = new ArrayList<>();
        lore.add(separatorLine());
        for (StatusType type : StatusType.byCategory(category).stream()
            .sorted(Comparator.comparingInt(PlayerDetailGui::statusGroupOrder))
            .toList()) {
            StatusValue value = snapshot.getValue(type);
            if (value != null) {
                lore.add(statLine(type, value, color));
            }
        }
        if (lore.size() == 1) {
            lore.add(noItalic(Component.text("算出済みステータスがありません", NamedTextColor.DARK_GRAY)));
        }
        lore.add(separatorLine());
        return createItem(material, name, lore);
    }

    private @NotNull Component statLine(
        @NotNull StatusType type,
        @NotNull StatusValue value,
        @NotNull TextColor accent
    ) {
        TextColor typeColor = statusColor(type, accent);
        Component line = Component.empty()
            .append(Component.text(" ▸ ", typeColor))
            .append(Component.text(type.getDisplayName(), typeColor))
            .append(Component.text("  "))
            .append(Component.text(
                type.formatRange(value.getMinValue(), value.getMaxValue()),
                NamedTextColor.WHITE,
                TextDecoration.BOLD
            ));
        double bonusMin = value.getBonusMinValue();
        double bonusMax = value.getBonusMaxValue();
        NamedTextColor bonusColor = bonusMin >= 0.0 && bonusMax >= 0.0
            ? NamedTextColor.GREEN
            : NamedTextColor.RED;
        line = line
            .append(Component.text("  (", NamedTextColor.DARK_GRAY))
            .append(Component.text(
                type.formatRange(value.getBaseMinValue(), value.getBaseMaxValue()),
                NamedTextColor.GRAY
            ))
            .append(Component.text(" ", NamedTextColor.DARK_GRAY))
            .append(Component.text(type.formatSignedRange(bonusMin, bonusMax), bonusColor))
            .append(Component.text(")", NamedTextColor.DARK_GRAY));
        return noItalic(line);
    }

    private static int statusGroupOrder(@NotNull StatusType type) {
        if (ELEMENT_DAMAGE_INCREASES.contains(type) || CONDITION_INCREASES.contains(type)) return 0;
        if (ELEMENT_RESISTANCES.contains(type) || CONDITION_RESISTANCES.contains(type)) return 1;
        if (ELEMENT_PENETRATIONS.contains(type) || CONDITION_PENETRATIONS.contains(type)) return 2;
        return 3;
    }

    private static @NotNull TextColor statusColor(@NotNull StatusType type, @NotNull TextColor fallback) {
        return switch (type) {
            case FIRE_DAMAGE_INCREASE, FIRE_RESISTANCE, FIRE_RESISTANCE_CAP, FIRE_PENETRATION,
                BURNING_APPLY_CHANCE, BURNING_RESISTANCE, BURNING_DAMAGE_INCREASE,
                BURNING_DAMAGE_RESISTANCE, BURNING_DAMAGE_PENETRATION -> NamedTextColor.RED;
            case ICE_DAMAGE_INCREASE, ICE_RESISTANCE, ICE_RESISTANCE_CAP, ICE_PENETRATION,
                FROZEN_APPLY_CHANCE, FROZEN_RESISTANCE -> NamedTextColor.AQUA;
            case LIGHTNING_DAMAGE_INCREASE, LIGHTNING_RESISTANCE, LIGHTNING_RESISTANCE_CAP,
                LIGHTNING_PENETRATION, SHOCKED_APPLY_CHANCE, SHOCKED_RESISTANCE,
                SHOCKED_DAMAGE_INCREASE, SHOCKED_DAMAGE_RESISTANCE, SHOCKED_DAMAGE_PENETRATION -> NamedTextColor.YELLOW;
            case CHILLED_APPLY_CHANCE, CHILLED_RESISTANCE -> NamedTextColor.BLUE;
            case POISONED_APPLY_CHANCE, POISONED_RESISTANCE, POISONED_DAMAGE_INCREASE,
                POISONED_DAMAGE_RESISTANCE, POISONED_DAMAGE_PENETRATION -> NamedTextColor.GREEN;
            case BLINDNESS_APPLY_CHANCE, BLINDNESS_RESISTANCE -> NamedTextColor.DARK_GRAY;
            case WEAKNESS_APPLY_CHANCE, WEAKNESS_RESISTANCE -> NamedTextColor.DARK_PURPLE;
            case HEALING_INHIBITION_APPLY_CHANCE, HEALING_INHIBITION_RESISTANCE -> NamedTextColor.LIGHT_PURPLE;
            default -> fallback;
        };
    }

    private @NotNull ItemStack actionItem(
        @NotNull Material material,
        @NotNull String title,
        boolean disabled,
        @NotNull String enabledLine,
        @NotNull String disabledLine
    ) {
        NamedTextColor color = disabled ? NamedTextColor.DARK_GRAY : NamedTextColor.GREEN;
        List<Component> lore = new ArrayList<>();
        lore.add(separatorLine());
        lore.add(noItalic(Component.text(disabled ? disabledLine : enabledLine, disabled ? NamedTextColor.GRAY : NamedTextColor.WHITE)));
        lore.add(noItalic(Component.text(disabled ? "利用不可" : "クリックで実行", color, TextDecoration.BOLD)));
        lore.add(separatorLine());
        return createItem(material, noItalic(Component.text(title, color, TextDecoration.BOLD)), lore);
    }

    private @NotNull List<Component> buildBuffLore(@NotNull AstPlayer target) {
        List<Component> lore = new ArrayList<>();
        if (target.getActiveBuffs().isEmpty()) {
            lore.add(noItalic(Component.text("現在有効なバフはありません", NamedTextColor.GRAY)));
        } else {
            for (var activeBuff : target.getActiveBuffs()) {
                String displayName = ColorCodeUtil.toPlainText(
                    activeBuff.getType().getDisplayName(),
                    activeBuff.getType().getId()
                );
                lore.add(noItalic(Component.text("- " + displayName, NamedTextColor.WHITE)));
            }
        }
        lore.add(Component.empty());
        lore.add(noItalic(Component.text("クリックで詳細を表示", NamedTextColor.YELLOW)));
        return lore;
    }

    protected @NotNull Component noItalic(@NotNull Component component) {
        return component.decoration(TextDecoration.ITALIC, false);
    }

    private @NotNull Component legacy(@NotNull String text) {
        return noItalic(LEGACY.deserialize(ColorCodeUtil.translateAlternateColorCodes(text)));
    }

    private @NotNull Component separatorLine() {
        return noItalic(Component.text(SEPARATOR, NamedTextColor.DARK_GRAY));
    }

    private @NotNull String displayWorldName(@NotNull Player target) {
        var world = worldService.findByBukkitWorld(target.getWorld());
        if (world == null) {
            return "名称未設定";
        }
        return ColorCodeUtil.toPlainText(world.displayName(), "名称未設定");
    }

    private @NotNull String formatDateTime(@NotNull LocalDateTime value) {
        return value.format(DATE_TIME_FORMATTER);
    }

    private @NotNull String formatPlayTime(@NotNull AstPlayer target) {
        long totalTicks = Math.max(0L, (long) target.getBukkit().getStatistic(Statistic.PLAY_ONE_MINUTE));
        long totalSeconds = totalTicks / 20L;
        long hours = totalSeconds / 3600L;
        long minutes = totalSeconds % 3600L / 60L;
        long seconds = totalSeconds % 60L;
        if (hours > 0L) {
            return String.format(Locale.US, "%d時間%02d分", hours, minutes);
        }
        if (minutes > 0L) {
            return String.format(Locale.US, "%d分%02d秒", minutes, seconds);
        }
        return String.format(Locale.US, "%d秒", seconds);
    }

    private @NotNull String formatPercent(double ratio) {
        return String.format(Locale.US, "%.1f%%", Math.max(0.0, Math.min(1.0, ratio)) * 100.0);
    }

    private @NotNull String formatInt(long value) {
        return String.format(Locale.US, "%,d", value);
    }

    private record Holder(@NotNull UUID targetId) implements HotbarShortcutGuiHolder {
        @Override
        public @NotNull String getNavigationId() {
            return "player-detail:" + targetId;
        }

        @Override
        public int getBackSlot() {
            return BACK_SLOT;
        }

        @Override
        public @NotNull Inventory getInventory() {
            return Bukkit.createInventory(this, SIZE);
        }
    }

    private record StatusDetailHolder(
        @NotNull UUID targetId,
        @NotNull StatusType.Category category,
        int pageIndex
    ) implements HotbarShortcutGuiHolder {
        @Override
        public @NotNull String getNavigationId() {
            return "player-status-detail:" + targetId + ":" + category.name();
        }

        @Override
        public int getBackSlot() {
            return PagedGuiView.BACK_SLOT;
        }

        @Override
        public @NotNull Inventory getInventory() {
            return Bukkit.createInventory(this, PagedGuiView.SIZE);
        }
    }

    /** スキル一覧の種別です。 */
    public enum SkillListType {
        PERMITTED("使用許可スキル一覧", NamedTextColor.GREEN),
        LEARNED("習得済みスキル一覧", NamedTextColor.LIGHT_PURPLE);

        private final String title;
        private final NamedTextColor color;

        SkillListType(@NotNull String title, @NotNull NamedTextColor color) {
            this.title = title;
            this.color = color;
        }

        @NotNull String title() {
            return title;
        }

        @NotNull NamedTextColor color() {
            return color;
        }
    }

    private interface SkillInfoHolder extends org.bukkit.inventory.InventoryHolder {
        @NotNull UUID targetId();
    }

    private record SkillInfoSelectionHolder(@NotNull UUID targetId)
        implements SkillInfoHolder, HotbarShortcutGuiHolder {
        @Override
        public @NotNull String getNavigationId() {
            return "player-skill-info-selection:" + targetId;
        }

        @Override
        public int getBackSlot() {
            return BACK_SLOT;
        }

        @Override
        public @NotNull Inventory getInventory() {
            return Bukkit.createInventory(this, SIZE);
        }
    }

    private record SkillListHolder(
        @NotNull UUID targetId,
        @NotNull SkillListType type,
        int pageIndex
    ) implements SkillInfoHolder, HotbarShortcutGuiHolder {
        @Override
        public @NotNull String getNavigationId() {
            return "player-skill-info-list:" + targetId + ":" + type.name();
        }

        @Override
        public int getBackSlot() {
            return PagedGuiView.BACK_SLOT;
        }

        @Override
        public @NotNull Inventory getInventory() {
            return Bukkit.createInventory(this, PagedGuiView.SIZE);
        }
    }
}
