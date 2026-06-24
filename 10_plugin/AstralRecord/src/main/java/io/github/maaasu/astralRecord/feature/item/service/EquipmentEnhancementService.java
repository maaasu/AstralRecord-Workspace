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
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentTranscendence;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.item.model.ItemReference;
import io.github.maaasu.astralRecord.feature.menu.model.MenuScreen;
import io.github.maaasu.astralRecord.feature.menu.view.MenuInventoryHolder;
import io.github.maaasu.astralRecord.feature.menu.view.MenuView;
import io.github.maaasu.astralRecord.feature.menu.view.screen.BaseMenuScreenView;
import io.github.maaasu.astralRecord.feature.menu.view.screen.EquipmentEnhancementMenuScreenView;
import io.github.maaasu.astralRecord.feature.player.AccountModeGuard;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.infrastructure.util.ColorCodeUtil;
import io.github.maaasu.astralRecord.shared.gui.GuiItems;
import io.github.maaasu.astralRecord.shared.gui.sound.GuiSound;
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
import org.bukkit.inventory.ItemStack;
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
        if (!AccountModeGuard.isGameplayPlayer(astPlayer)) {
            GuiSound.DENY.play(player);
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
        if (!AccountModeGuard.isGameplayPlayer(astPlayer)) {
            player.closeInventory();
            return;
        }
        EnhancementSession session = sessions.computeIfAbsent(
            player.getUniqueId(),
            ignored -> new EnhancementSession(inventoryService.getDisplayedInventoryType(astPlayer.getAccount().getUuid()))
        );

        if (rawSlot == EquipmentEnhancementMenuScreenView.TARGET_SLOT) {
            if (!returnSelectedEquipment(astPlayer, session)) {
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
        if (!AccountModeGuard.isGameplayPlayer(astPlayer)) {
            player.closeInventory();
            GuiSound.DENY.play(player);
            return;
        }
        EnhancementSession session = sessions.computeIfAbsent(
            player.getUniqueId(),
            ignored -> new EnhancementSession(inventoryService.getDisplayedInventoryType(astPlayer.getAccount().getUuid()))
        );

        ItemModel clickedModel = inventoryService.getDisplayedItemModelAtBukkitSlot(astPlayer, bukkitSlot);
        if (!isEquipmentModel(clickedModel)) {
            GuiSound.DENY.play(player);
            return;
        }

        ItemStack selected = inventoryService.takeDisplayedItem(astPlayer, bukkitSlot);
        if (selected == null || selected.getType() == Material.AIR) {
            GuiSound.DENY.play(player);
            return;
        }

        SelectionResult selection = resolveSelection(selected);
        if (selection.state() == SelectionState.INVALID_TARGET) {
            inventoryService.returnItemToOwnedInventory(astPlayer, selected);
            GuiSound.DENY.play(player);
            return;
        }

        ItemStack previous = session.selectedEquipment;
        session.selectedEquipment = selected.clone();
        if (previous != null && previous.getType() != Material.AIR) {
            if (inventoryService.returnItemToOwnedInventory(astPlayer, previous.clone()) == null) {
                inventoryService.returnItemToOwnedInventory(astPlayer, selected.clone());
                session.selectedEquipment = previous;
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
        SelectionResult selection = resolveSelection(session.selectedEquipment);
        EnhancementContext context = selection.context();
        if (context == null) {
            if (selection.state() == SelectionState.NONE_SELECTED) {
                PlayerMessageService.getInstance().send(player, PlayerMsgId.P_5261);
            }
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
                session.selectedEquipment = itemStackFactory.create(context.model, Objects.requireNonNull(result.updatedInstance), 1);
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
                    session.selectedEquipment = itemStackFactory.create(context.model, result.updatedInstance, 1);
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
                session.selectedEquipment = null;
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
        SelectionResult selection = resolveSelection(session.selectedEquipment);
        EnhancementContext context = selection.context();
        List<MaterialRequirement> requirements = context == null
            ? List.of()
            : collectMaterialRequirements(Objects.requireNonNull(AstPlayerCache.get(player)), context.nextLevel);
        view.render(
            inventory,
            session.selectedEquipment == null ? null : session.selectedEquipment.clone(),
            createMaterialSummaryItem(selection.state(), requirements),
            createGuideItem(),
            createInfoItem(player, selection),
            createExecuteItem(player, selection, requirements)
        );
    }

    private @NotNull ItemStack createMaterialSummaryItem(
        @NotNull SelectionState state,
        @NotNull List<MaterialRequirement> requirements
    ) {
        List<Component> lore = new ArrayList<>();
        if (requirements.isEmpty()) {
            if (state == SelectionState.READY) {
                lore.add(Component.text("この強化段階で消費するアイテムはありません。", NamedTextColor.GRAY));
                lore.add(Component.text("必要ゴールドは強化情報を確認してください。", NamedTextColor.GRAY));
            } else {
                lore.add(Component.text("装備をセットすると消費アイテムを一覧表示します。", NamedTextColor.GRAY));
                lore.add(Component.text("必要ゴールドは強化情報に表示されます。", NamedTextColor.GRAY));
            }
        } else {
            lore.add(Component.text("強化実行時に消費されるアイテムです。", NamedTextColor.GRAY));
            lore.add(Component.empty());
            for (MaterialRequirement requirement : requirements) {
                lore.add(Component.text(
                    materialRequirementLine(requirement),
                    requirement.enough() ? NamedTextColor.GREEN : NamedTextColor.RED
                ));
            }
        }
        return createItem(
            Material.CHEST,
            Component.text("消費アイテム", NamedTextColor.YELLOW, TextDecoration.BOLD),
            lore
        );
    }

    private @NotNull String materialRequirementLine(@NotNull MaterialRequirement requirement) {
        String name = requirement.model == null ? requirement.itemId : displayName(requirement.model);
        return name + ": " + requirement.amount + " / 所持 " + requirement.ownedAmount;
    }

    private @NotNull ItemStack createGuideItem() {
        return createItem(
            Material.ANVIL,
            Component.text("強化ガイド", NamedTextColor.GOLD, TextDecoration.BOLD),
            List.of(
                Component.text("1. 下の装備インベントリから装備をクリックしてセットします。", NamedTextColor.GRAY),
                Component.text("2. 必要素材とゴールドが揃うと強化ボタンが実行可能になります。", NamedTextColor.GRAY),
                Component.text("3. 実行すると成功率と失敗時挙動に従って強化されます。", NamedTextColor.GRAY)
            )
        );
    }

    private @NotNull ItemStack createInfoItem(@NotNull Player player, @NotNull SelectionResult selection) {
        AstPlayer astPlayer = AstPlayerCache.get(player);
        if (!AccountModeGuard.isGameplayPlayer(astPlayer)) {
            return createItem(
                Material.BOOK,
                Component.text("強化情報", NamedTextColor.YELLOW),
                List.of(Component.text("強化情報を取得できません。", NamedTextColor.RED))
            );
        }

        if (selection.state() == SelectionState.NONE_SELECTED) {
            return createItem(
                Material.BOOK,
                Component.text("強化情報", NamedTextColor.YELLOW),
                List.of(
                    Component.text("装備をセットすると次の強化情報を表示します。", NamedTextColor.GRAY),
                    Component.text("強化値 / 成功率 / 失敗時挙動 / 必要ゴールド", NamedTextColor.GRAY)
                )
            );
        }

        EnhancementContext context = selection.context();
        if (context == null) {
            List<Component> lore = new ArrayList<>();
            if (selection.instance() != null) {
                lore.add(Component.text("現在強化値: +" + selection.instance().getEnhanceLevel(), NamedTextColor.GRAY));
            }
            lore.add(Component.text(selection.state().message(), NamedTextColor.RED));
            return createItem(
                Material.BOOK,
                Component.text("強化情報", NamedTextColor.YELLOW),
                lore
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
        @NotNull SelectionResult selection,
        @NotNull List<MaterialRequirement> requirements
    ) {
        AstPlayer astPlayer = AstPlayerCache.get(player);
        if (!AccountModeGuard.isGameplayPlayer(astPlayer)) {
            return createItem(
                Material.BARRIER,
                Component.text("強化実行", NamedTextColor.RED, TextDecoration.BOLD),
                List.of(Component.text("強化情報を取得できません。", NamedTextColor.RED))
            );
        }

        EnhancementContext context = selection.context();
        if (selection.state() != SelectionState.READY || context == null) {
            return createItem(
                Material.BARRIER,
                Component.text("強化実行", NamedTextColor.RED, TextDecoration.BOLD),
                List.of(
                    Component.text("クリックしてもこの装備はまだ強化できません。", NamedTextColor.GRAY),
                    Component.text(selection.state().message(), NamedTextColor.RED)
                )
            );
        }

        boolean executable = hasEnoughRequirements(astPlayer, requirements, context.nextLevel.getRequiredCurrency());
        return createItem(
            executable ? Material.ANVIL : Material.BARRIER,
            Component.text("強化実行", executable ? NamedTextColor.GREEN : NamedTextColor.RED, TextDecoration.BOLD),
            List.of(
                Component.text("クリックするとこの装備の強化を実行します。", NamedTextColor.GRAY),
                Component.text(
                    executable ? "必要素材とゴールドが揃っています。" : "必要素材またはゴールドが不足しています。",
                    executable ? NamedTextColor.GREEN : NamedTextColor.RED
                )
            )
        );
    }

    private @NotNull SelectionResult resolveSelection(@Nullable ItemStack selectedEquipment) {
        if (selectedEquipment == null || selectedEquipment.getType() == Material.AIR) {
            return new SelectionResult(SelectionState.NONE_SELECTED, null, null, null);
        }

        ItemReference reference = itemReferenceResolver.resolve(selectedEquipment);
        if (reference == null || ItemCategory.fromApiValue(reference.category()) != ItemCategory.EQUIPMENT) {
            return new SelectionResult(SelectionState.INVALID_TARGET, null, null, null);
        }

        ItemModel model = itemReferenceResolver.resolveItemModel(reference);
        EquipmentInstance instance = itemReferenceResolver.resolveEquipmentInstance(reference);
        if (!isEquipmentModel(model) || instance == null) {
            return new SelectionResult(SelectionState.INVALID_TARGET, model, instance, null);
        }

        ItemEquipment equipment = Objects.requireNonNull(model.getEquipment());
        ItemEquipmentEnhance enhance = equipment.getEnhance();
        if (enhance == null) {
            return new SelectionResult(SelectionState.NO_ENHANCE_DATA, model, instance, null);
        }

        int effectiveMaxLevel = resolveEffectiveMaxLevel(equipment, instance);
        if (instance.getEnhanceLevel() >= effectiveMaxLevel) {
            return new SelectionResult(SelectionState.MAX_LEVEL, model, instance, null);
        }

        ItemEquipmentEnhanceLevel nextLevel = enhance.getLevels().stream()
            .filter(level -> level.getLevel() == instance.getEnhanceLevel() + 1)
            .min(Comparator.comparingInt(ItemEquipmentEnhanceLevel::getLevel))
            .orElse(null);
        if (nextLevel == null) {
            return new SelectionResult(SelectionState.NEXT_LEVEL_MISSING, model, instance, null);
        }

        return new SelectionResult(
            SelectionState.READY,
            model,
            instance,
            new EnhancementContext(model, instance, nextLevel)
        );
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

    private boolean isEquipmentModel(@Nullable ItemModel model) {
        return model != null && model.getEquipment() != null;
    }

    private boolean returnSelectedEquipment(@NotNull AstPlayer astPlayer, @NotNull EnhancementSession session) {
        if (session.selectedEquipment == null || session.selectedEquipment.getType() == Material.AIR) {
            return false;
        }
        if (inventoryService.returnItemToOwnedInventory(astPlayer, session.selectedEquipment.clone()) == null) {
            astPlayer.getBukkit().getWorld().dropItemNaturally(astPlayer.getBukkit().getLocation(), session.selectedEquipment.clone());
        }
        session.selectedEquipment = null;
        return true;
    }

    private void releaseSession(@NotNull AstPlayer astPlayer, boolean restoreDisplayedInventory) {
        EnhancementSession session = sessions.remove(astPlayer.getBukkit().getUniqueId());
        if (session == null) {
            return;
        }
        if (session.selectedEquipment != null && session.selectedEquipment.getType() != Material.AIR) {
            if (inventoryService.returnItemToOwnedInventory(astPlayer, session.selectedEquipment.clone()) == null) {
                astPlayer.getBukkit().getWorld().dropItemNaturally(astPlayer.getBukkit().getLocation(), session.selectedEquipment.clone());
            }
            session.selectedEquipment = null;
        }
        if (restoreDisplayedInventory && session.previousDisplayedType != null) {
            inventoryService.applyInventoryToGui(astPlayer, session.previousDisplayedType);
        }
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
        return ColorCodeUtil.toPlainText(model.getName(), model.getId());
    }

    private @NotNull ItemStack createItem(
        @NotNull Material material,
        @NotNull Component name,
        @NotNull List<Component> lore
    ) {
        return GuiItems.create(material, name, lore);
    }

    private static final class EnhancementSession {
        private final InventoryType previousDisplayedType;
        private ItemStack selectedEquipment;

        private EnhancementSession(@Nullable InventoryType previousDisplayedType) {
            this.previousDisplayedType = previousDisplayedType;
        }
    }

    private record SelectionResult(
        @NotNull SelectionState state,
        @Nullable ItemModel model,
        @Nullable EquipmentInstance instance,
        @Nullable EnhancementContext context
    ) {
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

    private enum SelectionState {
        NONE_SELECTED("強化する装備をセットしてください。"),
        INVALID_TARGET("選択した装備の情報を取得できません。"),
        NO_ENHANCE_DATA("この装備には強化データが定義されていません。"),
        MAX_LEVEL("この装備は現在の強化上限に達しています。"),
        NEXT_LEVEL_MISSING("次の強化レベル定義が見つかりません。"),
        READY("");

        private final String message;

        SelectionState(@NotNull String message) {
            this.message = message;
        }

        private @NotNull String message() {
            return message;
        }
    }
}
