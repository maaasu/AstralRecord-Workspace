package io.github.maaasu.astralRecord.feature.item.service;

import io.github.maaasu.astralRecord.feature.inventory.model.InventoryType;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.item.model.EquipmentInstance;
import io.github.maaasu.astralRecord.feature.item.model.ItemCategory;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipment;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentEnhance;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentEnhanceFailAction;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentEnhanceLevel;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentEnhanceMaterial;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentSlot;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentTranscendence;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.item.model.ItemReference;
import io.github.maaasu.astralRecord.feature.menu.event.MenuOpenEventHandler;
import io.github.maaasu.astralRecord.feature.menu.model.MenuScreen;
import io.github.maaasu.astralRecord.feature.menu.view.MenuInventoryHolder;
import io.github.maaasu.astralRecord.feature.menu.view.MenuView;
import io.github.maaasu.astralRecord.feature.menu.view.screen.BaseMenuScreenView;
import io.github.maaasu.astralRecord.feature.menu.view.screen.EquipmentEnhancementMenuScreenView;
import io.github.maaasu.astralRecord.shared.gui.sound.GuiSound;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.infrastructure.util.ColorCodeUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class EquipmentEnhancementService {
    private static final Component TITLE = Component.text("装備強化", NamedTextColor.GOLD);

    private final MenuView menuView;
    private final InventoryService inventoryService;
    private final ItemService itemService;
    private final ItemStackFactory itemStackFactory;
    private final ItemReferenceResolver itemReferenceResolver;
    private final EquipmentEnhancementMenuScreenView view = new EquipmentEnhancementMenuScreenView();
    private final Map<UUID, EnhancementSession> sessions = new ConcurrentHashMap<>();

    public EquipmentEnhancementService(
        @NotNull MenuView menuView,
        @NotNull InventoryService inventoryService,
        @NotNull ItemService itemService,
        @NotNull ItemStackFactory itemStackFactory
    ) {
        this.menuView = menuView;
        this.inventoryService = inventoryService;
        this.itemService = itemService;
        this.itemStackFactory = itemStackFactory;
        this.itemReferenceResolver = new ItemReferenceResolver(itemService);
    }

    public boolean isEnhancementMenu(@Nullable Inventory inventory) {
        return menuView.isMenuInventory(inventory)
            && menuView.getMenuScreen(inventory) == MenuScreen.EQUIPMENT_ENHANCE;
    }

    public void open(@NotNull Player player) {
        AstPlayer astPlayer = AstPlayerCache.get(player);
        if (astPlayer == null) {
            return;
        }

        EnhancementSession session = sessions.computeIfAbsent(
            player.getUniqueId(),
            ignored -> new EnhancementSession(inventoryService.getDisplayedInventoryType(astPlayer.getAccount().getUuid()))
        );

        if (inventoryService.canSwitchToInventory(astPlayer.getAccount().getUuid(), InventoryType.EQUIPMENT)) {
            inventoryService.applyInventoryToGui(astPlayer, InventoryType.EQUIPMENT);
        }

        Inventory inventory = Bukkit.createInventory(
            new MenuInventoryHolder(MenuScreen.EQUIPMENT_ENHANCE, -1, 0),
            BaseMenuScreenView.SIZE,
            TITLE
        );
        render(player, inventory, session);
        player.openInventory(inventory);
    }

    public void handleTopClick(@NotNull Player player, int rawSlot) {
        AstPlayer astPlayer = AstPlayerCache.get(player);
        if (astPlayer == null) {
            return;
        }
        EnhancementSession session = sessions.computeIfAbsent(
            player.getUniqueId(),
            ignored -> new EnhancementSession(inventoryService.getDisplayedInventoryType(astPlayer.getAccount().getUuid()))
        );

        if (rawSlot == BaseMenuScreenView.BACK_SLOT) {
            releaseSession(astPlayer, true);
            GuiSound.SELECT.play(player);
            MenuOpenEventHandler.suppressNextCloseSound(player);
            openEquipmentMenu(player, astPlayer);
            return;
        }
        if (rawSlot == BaseMenuScreenView.CLOSE_SLOT) {
            releaseSession(astPlayer, true);
            MenuOpenEventHandler.suppressNextCloseSound(player);
            player.closeInventory();
            return;
        }
        if (rawSlot == EquipmentEnhancementMenuScreenView.TARGET_SLOT) {
            if (!returnSelectedWeapon(astPlayer, session)) {
                GuiSound.DENY.play(player);
                return;
            }
            render(player, player.getOpenInventory().getTopInventory(), session);
            GuiSound.SELECT.play(player);
            return;
        }
        if (rawSlot == EquipmentEnhancementMenuScreenView.EXECUTE_SLOT) {
            executeEnhancement(player, astPlayer, session);
            return;
        }
        GuiSound.DENY.play(player);
    }

    public void handlePlayerInventoryClick(@NotNull Player player, int bukkitSlot) {
        AstPlayer astPlayer = AstPlayerCache.get(player);
        if (astPlayer == null) {
            GuiSound.DENY.play(player);
            return;
        }
        EnhancementSession session = sessions.computeIfAbsent(
            player.getUniqueId(),
            ignored -> new EnhancementSession(inventoryService.getDisplayedInventoryType(astPlayer.getAccount().getUuid()))
        );

        ItemModel clickedModel = inventoryService.getDisplayedItemModelAtBukkitSlot(astPlayer, bukkitSlot);
        if (!isWeaponModel(clickedModel)) {
            PlayerMessageService.getInstance().send(player, PlayerMsgId.P_5256);
            GuiSound.DENY.play(player);
            return;
        }

        ItemStack selected = inventoryService.takeDisplayedItem(astPlayer, bukkitSlot);
        if (selected == null || selected.getType() == Material.AIR) {
            GuiSound.DENY.play(player);
            return;
        }
        if (resolveContext(selected) == null) {
            inventoryService.returnItemToOwnedInventory(astPlayer, selected);
            PlayerMessageService.getInstance().send(player, PlayerMsgId.P_5255);
            GuiSound.DENY.play(player);
            return;
        }

        ItemStack previous = session.selectedWeapon;
        session.selectedWeapon = selected.clone();
        if (previous != null && previous.getType() != Material.AIR) {
            if (inventoryService.returnItemToOwnedInventory(astPlayer, previous.clone()) == null) {
                inventoryService.returnItemToOwnedInventory(astPlayer, selected.clone());
                session.selectedWeapon = previous;
                GuiSound.DENY.play(player);
                return;
            }
        }

        render(player, player.getOpenInventory().getTopInventory(), session);
        GuiSound.SELECT.play(player);
    }

    public void handleClose(@NotNull Player player) {
        AstPlayer astPlayer = AstPlayerCache.get(player);
        if (astPlayer == null) {
            sessions.remove(player.getUniqueId());
            return;
        }
        releaseSession(astPlayer, true);
    }

    private void executeEnhancement(
        @NotNull Player player,
        @NotNull AstPlayer astPlayer,
        @NotNull EnhancementSession session
    ) {
        EnhancementContext context = resolveContext(session.selectedWeapon);
        if (context == null) {
            PlayerMessageService.getInstance().send(player, PlayerMsgId.P_5261);
            GuiSound.DENY.play(player);
            return;
        }

        List<MaterialRequirement> requirements = collectMaterialRequirements(astPlayer, context.nextLevel);
        if (!hasEnoughRequirements(astPlayer, requirements, context.nextLevel.getRequiredCurrency())) {
            PlayerMessageService.getInstance().send(player, PlayerMsgId.P_5254);
            GuiSound.DENY.play(player);
            return;
        }

        double successRate = normalizeSuccessRate(context.nextLevel.getSuccessRate());
        boolean success = Math.random() < successRate;
        EnhancementResult result = applyEnhancementResult(astPlayer, context, success);
        if (result == null) {
            PlayerMessageService.getInstance().send(player, PlayerMsgId.P_5262);
            GuiSound.DENY.play(player);
            return;
        }

        if (!consumeRequirements(astPlayer, requirements, context.nextLevel.getRequiredCurrency())) {
            PlayerMessageService.getInstance().send(player, PlayerMsgId.P_5262);
            GuiSound.DENY.play(player);
            return;
        }
        inventoryService.saveNow(astPlayer.getAccount().getUuid());

        switch (result.type) {
            case SUCCESS -> {
                session.selectedWeapon = itemStackFactory.create(context.model, Objects.requireNonNull(result.updatedInstance), 1);
                PlayerMessageService.getInstance().send(
                    player,
                    PlayerMsgId.P_5257,
                    displayName(context.model),
                    result.updatedInstance.getEnhanceLevel(),
                    formatPercent(successRate)
                );
                playSuccessEffects(player);
            }
            case FAIL_NONE -> {
                PlayerMessageService.getInstance().send(player, PlayerMsgId.P_5258, displayName(context.model));
                playFailureEffects(player);
            }
            case FAIL_DOWNGRADE -> {
                if (result.updatedInstance != null) {
                    session.selectedWeapon = itemStackFactory.create(context.model, result.updatedInstance, 1);
                }
                int downgradedLevel = result.updatedInstance == null
                    ? Math.max(0, context.instance.getEnhanceLevel() - 1)
                    : result.updatedInstance.getEnhanceLevel();
                PlayerMessageService.getInstance().send(
                    player,
                    PlayerMsgId.P_5259,
                    displayName(context.model),
                    downgradedLevel
                );
                playFailureEffects(player);
            }
            case FAIL_DESTROY -> {
                session.selectedWeapon = null;
                PlayerMessageService.getInstance().send(player, PlayerMsgId.P_5260, displayName(context.model));
                playDestroyEffects(player);
            }
        }

        render(player, player.getOpenInventory().getTopInventory(), session);
    }

    private @Nullable EnhancementResult applyEnhancementResult(
        @NotNull AstPlayer astPlayer,
        @NotNull EnhancementContext context,
        boolean success
    ) {
        String updatedBy = astPlayer.getAccount().getUuid().toString();
        if (success) {
            EquipmentInstance updated = itemService.enhanceEquipmentInstance(
                context.instance.getEquipmentInstanceId(),
                context.nextLevel.getLevel(),
                updatedBy
            );
            return updated == null ? null : new EnhancementResult(EnhancementResultType.SUCCESS, updated);
        }

        return switch (context.nextLevel.getFailAction()) {
            case NONE -> new EnhancementResult(EnhancementResultType.FAIL_NONE, null);
            case DOWNGRADE -> {
                if (context.instance.getEnhanceLevel() <= 0) {
                    yield new EnhancementResult(EnhancementResultType.FAIL_NONE, null);
                }
                EquipmentInstance downgraded = itemService.enhanceEquipmentInstance(
                    context.instance.getEquipmentInstanceId(),
                    context.instance.getEnhanceLevel() - 1,
                    updatedBy
                );
                yield downgraded == null ? null : new EnhancementResult(EnhancementResultType.FAIL_DOWNGRADE, downgraded);
            }
            case DESTROY -> itemService.deleteEquipmentInstance(context.instance.getEquipmentInstanceId())
                ? new EnhancementResult(EnhancementResultType.FAIL_DESTROY, null)
                : null;
        };
    }

    private boolean consumeRequirements(
        @NotNull AstPlayer astPlayer,
        @NotNull List<MaterialRequirement> requirements,
        int requiredCurrency
    ) {
        UUID accountId = astPlayer.getAccount().getUuid();
        for (MaterialRequirement requirement : requirements) {
            if (!inventoryService.consumeNormalItem(accountId, requirement.itemId, requirement.amount)) {
                return false;
            }
        }
        return inventoryService.consumeGold(accountId, requiredCurrency);
    }

    private boolean hasEnoughRequirements(
        @NotNull AstPlayer astPlayer,
        @NotNull List<MaterialRequirement> requirements,
        int requiredCurrency
    ) {
        UUID accountId = astPlayer.getAccount().getUuid();
        long ownedGold = inventoryService.getCurrencyAmount(accountId, ItemService.DEFAULT_CURRENCY_ITEM_ID)
            + inventoryService.getCurrencyAmount(accountId, ItemService.LEGACY_DEFAULT_CURRENCY_ITEM_ID);
        if (ownedGold < requiredCurrency) {
            return false;
        }
        return requirements.stream().allMatch(MaterialRequirement::enough);
    }

    private void render(
        @NotNull Player player,
        @NotNull Inventory inventory,
        @NotNull EnhancementSession session
    ) {
        EnhancementContext context = resolveContext(session.selectedWeapon);
        List<MaterialRequirement> requirements = context == null
            ? List.of()
            : collectMaterialRequirements(Objects.requireNonNull(AstPlayerCache.get(player)), context.nextLevel);
        view.render(
            inventory,
            session.selectedWeapon == null ? null : session.selectedWeapon.clone(),
            createMaterialItems(requirements),
            createGuideItem(),
            createInfoItem(player, context),
            createExecuteItem(player, context, requirements)
        );
    }

    private @NotNull List<ItemStack> createMaterialItems(@NotNull List<MaterialRequirement> requirements) {
        List<ItemStack> items = new ArrayList<>();
        for (MaterialRequirement requirement : requirements) {
            ItemStack item = requirement.model != null
                ? itemStackFactory.createDisplay(requirement.model, requirement.amount)
                : new ItemStack(Material.BARRIER);
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                List<Component> lore = meta.hasLore() && meta.lore() != null
                    ? new ArrayList<>(meta.lore())
                    : new ArrayList<>();
                lore.add(Component.empty());
                lore.add(Component.text("必要数: " + requirement.amount, NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));
                lore.add(Component.text(
                    "所持数: " + requirement.ownedAmount,
                    requirement.enough() ? NamedTextColor.GREEN : NamedTextColor.RED
                ).decoration(TextDecoration.ITALIC, false));
                meta.lore(lore);
                meta.addItemFlags(ItemFlag.values());
                item.setItemMeta(meta);
            }
            items.add(item);
        }
        return items;
    }

    private @NotNull ItemStack createGuideItem() {
        return createItem(
            Material.ANVIL,
            Component.text("強化ガイド", NamedTextColor.GOLD, TextDecoration.BOLD),
            List.of(
                Component.text("1. 下の装備インベントリから武器をクリックしてセットします。", NamedTextColor.GRAY),
                Component.text("2. 必要素材とゴールドが揃うと実行ボタンが有効になります。", NamedTextColor.GRAY),
                Component.text("3. 実行すると成功率と失敗時挙動に従って強化されます。", NamedTextColor.GRAY)
            )
        );
    }

    private @NotNull ItemStack createInfoItem(@NotNull Player player, @Nullable EnhancementContext context) {
        AstPlayer astPlayer = AstPlayerCache.get(player);
        if (context == null || astPlayer == null) {
            return createItem(
                Material.BOOK,
                Component.text("強化情報", NamedTextColor.YELLOW),
                List.of(
                    Component.text("武器をセットすると次の強化情報を表示します。", NamedTextColor.GRAY),
                    Component.text("強化値 / 成功率 / 失敗時挙動 / 必要ゴールド", NamedTextColor.GRAY)
                )
            );
        }

        long ownedGold = inventoryService.getCurrencyAmount(astPlayer.getAccount().getUuid(), ItemService.DEFAULT_CURRENCY_ITEM_ID)
            + inventoryService.getCurrencyAmount(astPlayer.getAccount().getUuid(), ItemService.LEGACY_DEFAULT_CURRENCY_ITEM_ID);
        return createItem(
            Material.KNOWLEDGE_BOOK,
            Component.text("次の強化情報", NamedTextColor.AQUA, TextDecoration.BOLD),
            List.of(
                Component.text("現在強化値: +" + context.instance.getEnhanceLevel(), NamedTextColor.GRAY),
                Component.text("次の強化値: +" + context.nextLevel.getLevel(), NamedTextColor.GRAY),
                Component.text("成功率: " + formatPercent(normalizeSuccessRate(context.nextLevel.getSuccessRate())) + "%", NamedTextColor.GRAY),
                Component.text("失敗時: " + failActionLabel(context.nextLevel.getFailAction()), NamedTextColor.GRAY),
                Component.text(
                    "必要ゴールド: " + context.nextLevel.getRequiredCurrency() + " / 所持: " + ownedGold,
                    ownedGold >= context.nextLevel.getRequiredCurrency() ? NamedTextColor.GREEN : NamedTextColor.RED
                )
            )
        );
    }

    private @NotNull ItemStack createExecuteItem(
        @NotNull Player player,
        @Nullable EnhancementContext context,
        @NotNull List<MaterialRequirement> requirements
    ) {
        AstPlayer astPlayer = AstPlayerCache.get(player);
        if (context == null || astPlayer == null) {
            return createItem(
                Material.BARRIER,
                Component.text("強化実行", NamedTextColor.RED, TextDecoration.BOLD),
                List.of(Component.text("強化する武器をセットしてください。", NamedTextColor.GRAY))
            );
        }

        boolean executable = hasEnoughRequirements(astPlayer, requirements, context.nextLevel.getRequiredCurrency());
        return createItem(
            executable ? Material.ANVIL : Material.BARRIER,
            Component.text("強化実行", executable ? NamedTextColor.GREEN : NamedTextColor.RED, TextDecoration.BOLD),
            List.of(
                Component.text("クリックするとこの武器の強化を実行します。", NamedTextColor.GRAY),
                Component.text(
                    executable ? "必要素材とゴールドが揃っています。" : "必要素材またはゴールドが不足しています。",
                    executable ? NamedTextColor.GREEN : NamedTextColor.RED
                )
            )
        );
    }

    private @Nullable EnhancementContext resolveContext(@Nullable ItemStack selectedWeapon) {
        ItemReference reference = itemReferenceResolver.resolve(selectedWeapon);
        if (reference == null || ItemCategory.fromApiValue(reference.category()) != ItemCategory.EQUIPMENT) {
            return null;
        }
        ItemModel model = itemReferenceResolver.resolveItemModel(reference);
        EquipmentInstance instance = itemReferenceResolver.resolveEquipmentInstance(reference);
        if (!isWeaponModel(model) || instance == null || model == null || model.getEquipment() == null) {
            return null;
        }

        int effectiveMaxLevel = resolveEffectiveMaxLevel(model.getEquipment(), instance);
        if (instance.getEnhanceLevel() >= effectiveMaxLevel) {
            return null;
        }

        ItemEquipmentEnhance enhance = model.getEquipment().getEnhance();
        if (enhance == null) {
            return null;
        }

        ItemEquipmentEnhanceLevel nextLevel = enhance.getLevels().stream()
            .filter(level -> level.getLevel() == instance.getEnhanceLevel() + 1)
            .min(Comparator.comparingInt(ItemEquipmentEnhanceLevel::getLevel))
            .orElse(null);
        if (nextLevel == null) {
            return null;
        }
        return new EnhancementContext(model, instance, nextLevel);
    }

    private @NotNull List<MaterialRequirement> collectMaterialRequirements(
        @NotNull AstPlayer astPlayer,
        @NotNull ItemEquipmentEnhanceLevel nextLevel
    ) {
        Map<String, Integer> merged = new LinkedHashMap<>();
        for (ItemEquipmentEnhanceMaterial material : nextLevel.getRequiredMaterials()) {
            if (material.getItemId() == null || material.getItemId().isBlank() || material.getAmount() <= 0) {
                continue;
            }
            merged.merge(material.getItemId(), material.getAmount(), Integer::sum);
        }

        List<MaterialRequirement> requirements = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : merged.entrySet()) {
            String itemId = entry.getKey();
            int amount = entry.getValue();
            ItemModel model = itemService.findLoadedById(itemId);
            if (model == null) {
                model = itemService.loadItem(itemId);
            }
            long ownedAmount = inventoryService.getNormalItemAmount(astPlayer.getAccount().getUuid(), itemId);
            requirements.add(new MaterialRequirement(itemId, amount, ownedAmount, model));
        }
        return requirements;
    }

    private int resolveEffectiveMaxLevel(@NotNull ItemEquipment equipment, @NotNull EquipmentInstance instance) {
        int maxLevel = equipment.getEnhance() == null ? 0 : equipment.getEnhance().getMaxLevel();
        for (ItemEquipmentTranscendence transcendence : equipment.getTranscendence().stream()
            .sorted(Comparator.comparingInt(ItemEquipmentTranscendence::getRank))
            .toList()) {
            if (transcendence.getRank() > instance.getTranscendenceRank()) {
                break;
            }
            if (transcendence.getOverridesEnhanceMaxLevel() != null) {
                maxLevel = transcendence.getOverridesEnhanceMaxLevel();
            }
        }
        return maxLevel;
    }

    private boolean isWeaponModel(@Nullable ItemModel model) {
        return model != null
            && model.getEquipment() != null
            && model.getEquipment().getSlot() == ItemEquipmentSlot.WEAPON;
    }

    private boolean returnSelectedWeapon(@NotNull AstPlayer astPlayer, @NotNull EnhancementSession session) {
        if (session.selectedWeapon == null || session.selectedWeapon.getType() == Material.AIR) {
            return false;
        }
        if (inventoryService.returnItemToOwnedInventory(astPlayer, session.selectedWeapon.clone()) == null) {
            astPlayer.getBukkit().getWorld().dropItemNaturally(astPlayer.getBukkit().getLocation(), session.selectedWeapon.clone());
        }
        session.selectedWeapon = null;
        return true;
    }

    private void releaseSession(@NotNull AstPlayer astPlayer, boolean restoreDisplayedInventory) {
        EnhancementSession session = sessions.remove(astPlayer.getBukkit().getUniqueId());
        if (session == null) {
            return;
        }
        if (session.selectedWeapon != null && session.selectedWeapon.getType() != Material.AIR) {
            if (inventoryService.returnItemToOwnedInventory(astPlayer, session.selectedWeapon.clone()) == null) {
                astPlayer.getBukkit().getWorld().dropItemNaturally(astPlayer.getBukkit().getLocation(), session.selectedWeapon.clone());
            }
            session.selectedWeapon = null;
        }
        if (restoreDisplayedInventory && session.previousDisplayedType != null) {
            inventoryService.applyInventoryToGui(astPlayer, session.previousDisplayedType);
        }
    }

    private void openEquipmentMenu(@NotNull Player player, @NotNull AstPlayer astPlayer) {
        menuView.openEquipmentGui(
            player,
            new ItemStack[] {
                null,
                inventoryService.getAccessorySnapshotItem(astPlayer, 1),
                inventoryService.getAccessorySnapshotItem(astPlayer, 2),
                inventoryService.getAccessorySnapshotItem(astPlayer, 3),
                inventoryService.getAccessorySnapshotItem(astPlayer, 4),
                inventoryService.getAccessorySnapshotItem(astPlayer, 5),
                inventoryService.getAccessorySnapshotItem(astPlayer, 6),
                inventoryService.getAccessorySnapshotItem(astPlayer, 7)
            }
        );
    }

    private void playSuccessEffects(@NotNull Player player) {
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, SoundCategory.PLAYERS, 0.8f, 1.1f);
        player.spawnParticle(Particle.ENCHANT, player.getLocation().add(0.0, 1.0, 0.0), 30, 0.35, 0.45, 0.35, 0.0);
    }

    private void playFailureEffects(@NotNull Player player) {
        player.playSound(player.getLocation(), Sound.BLOCK_ANVIL_LAND, SoundCategory.PLAYERS, 0.7f, 0.9f);
        player.spawnParticle(Particle.SMOKE, player.getLocation().add(0.0, 1.0, 0.0), 18, 0.25, 0.35, 0.25, 0.02);
    }

    private void playDestroyEffects(@NotNull Player player) {
        player.playSound(player.getLocation(), Sound.ENTITY_ITEM_BREAK, SoundCategory.PLAYERS, 0.85f, 0.8f);
        player.spawnParticle(Particle.LARGE_SMOKE, player.getLocation().add(0.0, 1.0, 0.0), 22, 0.3, 0.35, 0.3, 0.02);
    }

    private double normalizeSuccessRate(double rawRate) {
        double normalized = rawRate > 1.0 ? rawRate / 100.0 : rawRate;
        return Math.clamp(normalized, 0.0, 1.0);
    }

    private @NotNull String failActionLabel(@NotNull ItemEquipmentEnhanceFailAction failAction) {
        return switch (failAction) {
            case NONE -> "変化なし";
            case DOWNGRADE -> "強化値低下";
            case DESTROY -> "装備破壊";
        };
    }

    private @NotNull String formatPercent(double rate) {
        return BigDecimal.valueOf(rate * 100.0).stripTrailingZeros().toPlainString();
    }

    private @NotNull String displayName(@NotNull ItemModel model) {
        String translated = ColorCodeUtil.translateAlternateColorCodes(model.getName());
        String stripped = ColorCodeUtil.stripColor(translated);
        return stripped == null || stripped.isBlank() ? model.getId() : stripped;
    }

    private @NotNull ItemStack createItem(
        @NotNull Material material,
        @NotNull Component name,
        @NotNull List<Component> lore
    ) {
        ItemStack itemStack = new ItemStack(material);
        ItemMeta meta = itemStack.getItemMeta();
        if (meta != null) {
            meta.displayName(name.decoration(TextDecoration.ITALIC, false));
            meta.lore(lore.stream().map(component -> component.decoration(TextDecoration.ITALIC, false)).toList());
            meta.addItemFlags(ItemFlag.values());
            itemStack.setItemMeta(meta);
        }
        return itemStack;
    }

    private static final class EnhancementSession {
        private final InventoryType previousDisplayedType;
        private ItemStack selectedWeapon;

        private EnhancementSession(@Nullable InventoryType previousDisplayedType) {
            this.previousDisplayedType = previousDisplayedType;
        }
    }

    private record EnhancementContext(
        @NotNull ItemModel model,
        @NotNull EquipmentInstance instance,
        @NotNull ItemEquipmentEnhanceLevel nextLevel
    ) {
    }

    private record MaterialRequirement(
        @NotNull String itemId,
        int amount,
        long ownedAmount,
        @Nullable ItemModel model
    ) {
        private boolean enough() {
            return ownedAmount >= amount;
        }
    }

    private record EnhancementResult(
        @NotNull EnhancementResultType type,
        @Nullable EquipmentInstance updatedInstance
    ) {
    }

    private enum EnhancementResultType {
        SUCCESS,
        FAIL_NONE,
        FAIL_DOWNGRADE,
        FAIL_DESTROY
    }
}
