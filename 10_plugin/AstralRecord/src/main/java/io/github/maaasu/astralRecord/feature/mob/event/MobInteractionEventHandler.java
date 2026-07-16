package io.github.maaasu.astralRecord.feature.mob.event;

import io.github.maaasu.astralRecord.core.event.AbstractEventHandler;
import io.github.maaasu.astralRecord.feature.item.service.EquipmentEnhancementService;
import io.github.maaasu.astralRecord.feature.item.service.EquipmentRepairService;
import io.github.maaasu.astralRecord.feature.menu.service.MenuGuiTransitionService;
import io.github.maaasu.astralRecord.feature.menu.view.MenuView;
import io.github.maaasu.astralRecord.feature.mob.model.MobCategory;
import io.github.maaasu.astralRecord.feature.mob.model.MobInstance;
import io.github.maaasu.astralRecord.feature.mob.model.MobInteractionActionConfig;
import io.github.maaasu.astralRecord.feature.mob.service.MobService;
import io.github.maaasu.astralRecord.feature.player.AccountModeGuard;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.playerclass.PlayerClassService;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.feature.quest.event.QuestGuiEventHandler;
import io.github.maaasu.astralRecord.feature.shop.event.ShopGuiEventHandler;
import io.github.maaasu.astralRecord.feature.storage.service.StorageService;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.util.ColorCodeUtil;
import io.github.maaasu.astralRecord.shared.gui.sound.GuiSound;
import io.github.maaasu.astralRecord.shared.interaction.PlayerInteractionConsumeService;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Locale;

/**
 * NPC クリックアクションを実行するイベントハンドラです。
 */
public final class MobInteractionEventHandler extends AbstractEventHandler {
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

    private final MobService mobService;
    private final ShopGuiEventHandler shopGuiEventHandler;
    private final MenuView menuView;
    private final PlayerClassService playerClassService;
    private final StorageService storageService;
    private final EquipmentEnhancementService equipmentEnhancementService;
    private final EquipmentRepairService equipmentRepairService;
    private final QuestGuiEventHandler questGuiEventHandler;
    private final PlayerInteractionConsumeService interactionConsumeService;

    /**
     * ハンドラを生成します。
     *
     * @param mobService          Mob 管理サービス
     * @param shopGuiEventHandler ショップ GUI ハンドラ
     * @param menuView            メニュー GUI ビュー
     * @param playerClassService  職業表示用サービス
     * @param storageService      ストレージ GUI サービス
     * @param equipmentEnhancementService 装備強化 GUI サービス
     * @param equipmentRepairService 装備修理 GUI サービス
     * @param questGuiEventHandler クエストボード GUI ハンドラ
     * @param interactionConsumeService コンテンツが消費したインタラクトの共有サービス
     */
    public MobInteractionEventHandler(
            @NotNull MobService mobService,
            @NotNull ShopGuiEventHandler shopGuiEventHandler,
            @NotNull MenuView menuView,
            @NotNull PlayerClassService playerClassService,
            @NotNull StorageService storageService,
            @NotNull EquipmentEnhancementService equipmentEnhancementService,
            @NotNull EquipmentRepairService equipmentRepairService,
            @NotNull QuestGuiEventHandler questGuiEventHandler,
            @NotNull PlayerInteractionConsumeService interactionConsumeService) {
        this.mobService = mobService;
        this.shopGuiEventHandler = shopGuiEventHandler;
        this.menuView = menuView;
        this.playerClassService = playerClassService;
        this.storageService = storageService;
        this.equipmentEnhancementService = equipmentEnhancementService;
        this.equipmentRepairService = equipmentRepairService;
        this.questGuiEventHandler = questGuiEventHandler;
        this.interactionConsumeService = interactionConsumeService;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onPlayerInteractEntity(@NotNull PlayerInteractEntityEvent event) {
        runSafely(() -> {
            if (event.getHand() != EquipmentSlot.HAND) {
                return;
            }
            MobInstance instance = mobService.getNpcInstanceByEntity(event.getRightClicked().getUniqueId());
            if (instance == null) {
                return;
            }
            event.setCancelled(true);
            if (!AccountModeGuard.isGameplayPlayer(event.getPlayer())) {
                return;
            }
            if (!isWithinInteractionDistance(event.getPlayer(), event.getRightClicked())) {
                return;
            }
            execute(event.getPlayer(), instance, instance.template().interactions().rightClick());
        }, LogId.E_5702, event.getPlayer().getName());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onPlayerInteract(@NotNull PlayerInteractEvent event) {
        runSafely(() -> {
            if (event.getHand() != null && event.getHand() != EquipmentSlot.HAND) {
                return;
            }

            Action action = event.getAction();
            boolean leftClick = action == Action.LEFT_CLICK_AIR || action == Action.LEFT_CLICK_BLOCK;
            boolean rightClick = action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK;
            if (!leftClick && !rightClick) {
                return;
            }

            MobInstance instance = mobService.findTargetedNpc(
                    event.getPlayer(),
                    MobService.NPC_INTERACTION_DISTANCE,
                    MobService.NPC_INTERACTION_RAY_SIZE
            );
            if (instance == null) {
                return;
            }

            interactionConsumeService.consume(event);
            if (!AccountModeGuard.isGameplayPlayer(event.getPlayer())) {
                return;
            }
            execute(event.getPlayer(), instance, leftClick
                    ? instance.template().interactions().leftClick()
                    : instance.template().interactions().rightClick());
        }, LogId.E_5702, event.getPlayer().getName());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onEntityDamageByEntity(@NotNull EntityDamageByEntityEvent event) {
        runSafely(() -> {
            if (!(event.getDamager() instanceof Player player)) {
                return;
            }
            MobInstance instance = mobService.getNpcInstanceByEntity(event.getEntity().getUniqueId());
            if (instance == null) {
                return;
            }
            event.setCancelled(true);
            if (!AccountModeGuard.isGameplayPlayer(player)) {
                return;
            }
            if (!isWithinInteractionDistance(player, event.getEntity())) {
                return;
            }
            execute(player, instance, instance.template().interactions().leftClick());
        }, LogId.E_5702, event.getDamager().getName());
    }

    private boolean isWithinInteractionDistance(@NotNull Player player, @NotNull Entity entity) {
        double distanceSq = player.getLocation().distanceSquared(entity.getLocation());
        return distanceSq <= MobService.NPC_INTERACTION_DISTANCE * MobService.NPC_INTERACTION_DISTANCE;
    }

    private void execute(@NotNull Player player, @NotNull MobInstance instance, @NotNull List<MobInteractionActionConfig> actions) {
        if (!AccountModeGuard.isGameplayPlayer(player)) {
            return;
        }
        if (actions.isEmpty()) {
            GuiSound.DENY.play(player);
            return;
        }
        for (MobInteractionActionConfig action : actions) {
            execute(player, instance, action);
        }
    }

    private void execute(@NotNull Player player, @NotNull MobInstance instance, @NotNull MobInteractionActionConfig action) {
        switch (action.id().toLowerCase(Locale.ROOT)) {
            case "message" -> sendMessage(player, action);
            case "gui" -> openGui(player, instance, action);
            case "command" -> executeCommand(player, action);
            default -> GuiSound.DENY.play(player);
        }
    }

    private void sendMessage(@NotNull Player player, @NotNull MobInteractionActionConfig action) {
        String message = action.params().get("message");
        if (message == null || message.isBlank()) {
            GuiSound.DENY.play(player);
            return;
        }
        PlayerMessageService.getInstance().sendComponent(
            player,
            LEGACY.deserialize(ColorCodeUtil.translateAlternateColorCodes(message))
        );
    }

    private void openGui(@NotNull Player player, @NotNull MobInstance instance, @NotNull MobInteractionActionConfig action) {
        String rawType = action.params().get("type");
        String type = rawType == null ? "" : rawType.trim().toUpperCase(Locale.ROOT);
        switch (type) {
            case "SHOP" -> openShop(player, action);
            case "QUEST", "QUEST_BOARD" -> openQuestBoard(player, instance, action);
            case "SELL" -> {
                MenuGuiTransitionService.suppressNextCloseSound(player);
                menuView.openSell(player, List.of(), 0);
                GuiSound.OPEN.play(player);
            }
            case "CLASS" -> openClass(player);
            case "STORAGE" -> openStorage(player);
            case "EQUIPMENT_ENHANCE", "ENHANCE" -> openEquipmentEnhance(player);
            case "EQUIPMENT_REPAIR", "REPAIR" -> openEquipmentRepair(player);
            default -> GuiSound.DENY.play(player);
        }
    }

    private void openShop(@NotNull Player player, @NotNull MobInteractionActionConfig action) {
        String shopId = action.params().get("shopId");
        if (shopId == null || shopId.isBlank()) {
            GuiSound.DENY.play(player);
            return;
        }
        MenuGuiTransitionService.suppressNextCloseSound(player);
        shopGuiEventHandler.openFromNpc(player, shopId);
    }

    private void openQuestBoard(@NotNull Player player, @NotNull MobInstance instance, @NotNull MobInteractionActionConfig action) {
        String boardId = action.params().get("boardId");
        if (boardId == null || boardId.isBlank()) {
            boardId = action.params().get("questBoardId");
        }
        if (boardId == null || boardId.isBlank()) {
            GuiSound.DENY.play(player);
            return;
        }
        MenuGuiTransitionService.suppressNextCloseSound(player);
        questGuiEventHandler.openBoard(player, boardId, instance.template().id());
    }

    private void openClass(@NotNull Player player) {
        AstPlayer astPlayer = AstPlayerCache.get(player);
        if (astPlayer == null) {
            GuiSound.DENY.play(player);
            return;
        }
        MenuGuiTransitionService.suppressNextCloseSound(player);
        menuView.openClass(player, astPlayer, playerClassService.getClassViewEntries(astPlayer));
        GuiSound.OPEN.play(player);
    }

    private void openStorage(@NotNull Player player) {
        MenuGuiTransitionService.suppressNextCloseSound(player);
        storageService.open(player);
        GuiSound.OPEN.play(player);
    }

    private void openEquipmentEnhance(@NotNull Player player) {
        MenuGuiTransitionService.suppressNextCloseSound(player);
        equipmentEnhancementService.open(player);
        GuiSound.OPEN.play(player);
    }

    private void openEquipmentRepair(@NotNull Player player) {
        MenuGuiTransitionService.suppressNextCloseSound(player);
        equipmentRepairService.open(player);
        GuiSound.OPEN.play(player);
    }

    private void executeCommand(@NotNull Player player, @NotNull MobInteractionActionConfig action) {
        String command = action.params().get("command");
        if (command == null || command.isBlank()) {
            GuiSound.DENY.play(player);
            return;
        }

        String normalized = command.trim();
        if (normalized.startsWith("/")) {
            normalized = normalized.substring(1).trim();
        }
        if (normalized.isEmpty() || !player.performCommand(normalized)) {
            GuiSound.DENY.play(player);
        }
    }
}
