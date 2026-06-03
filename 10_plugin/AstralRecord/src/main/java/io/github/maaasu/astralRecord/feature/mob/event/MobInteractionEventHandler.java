package io.github.maaasu.astralRecord.feature.mob.event;

import io.github.maaasu.astralRecord.core.event.AbstractEventHandler;
import io.github.maaasu.astralRecord.feature.menu.service.MenuGuiTransitionService;
import io.github.maaasu.astralRecord.feature.menu.view.MenuView;
import io.github.maaasu.astralRecord.feature.mob.model.MobCategory;
import io.github.maaasu.astralRecord.feature.mob.model.MobInstance;
import io.github.maaasu.astralRecord.feature.mob.model.MobInteractionActionConfig;
import io.github.maaasu.astralRecord.feature.mob.service.MobService;
import io.github.maaasu.astralRecord.feature.shop.event.ShopGuiEventHandler;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.util.ColorCodeUtil;
import io.github.maaasu.astralRecord.shared.gui.sound.GuiSound;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
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

    /**
     * ハンドラを生成します。
     *
     * @param mobService          Mob 管理サービス
     * @param shopGuiEventHandler ショップ GUI ハンドラ
     * @param menuView            メニュー GUI ビュー
     */
    public MobInteractionEventHandler(
            @NotNull MobService mobService,
            @NotNull ShopGuiEventHandler shopGuiEventHandler,
            @NotNull MenuView menuView) {
        this.mobService = mobService;
        this.shopGuiEventHandler = shopGuiEventHandler;
        this.menuView = menuView;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onPlayerInteractEntity(@NotNull PlayerInteractEntityEvent event) {
        runSafely(() -> {
            if (event.getHand() != EquipmentSlot.HAND) {
                return;
            }
            MobInstance instance = findNpc(event.getRightClicked());
            if (instance == null) {
                return;
            }
            event.setCancelled(true);
            execute(event.getPlayer(), instance.template().interactions().rightClick());
        }, LogId.E_5702, event.getPlayer().getName());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onEntityDamageByEntity(@NotNull EntityDamageByEntityEvent event) {
        runSafely(() -> {
            if (!(event.getDamager() instanceof Player player)) {
                return;
            }
            MobInstance instance = findNpc(event.getEntity());
            if (instance == null) {
                return;
            }
            event.setCancelled(true);
            execute(player, instance.template().interactions().leftClick());
        }, LogId.E_5702, event.getDamager().getName());
    }

    private MobInstance findNpc(@NotNull Entity entity) {
        MobInstance instance = mobService.getInstanceByEntity(entity.getUniqueId());
        if (instance == null || instance.template().category() != MobCategory.NPC) {
            return null;
        }
        return instance;
    }

    private void execute(@NotNull Player player, @NotNull List<MobInteractionActionConfig> actions) {
        if (actions.isEmpty()) {
            GuiSound.DENY.play(player);
            return;
        }
        for (MobInteractionActionConfig action : actions) {
            execute(player, action);
        }
    }

    private void execute(@NotNull Player player, @NotNull MobInteractionActionConfig action) {
        switch (action.id().toLowerCase(Locale.ROOT)) {
            case "message" -> sendMessage(player, action);
            case "gui" -> openGui(player, action);
            default -> GuiSound.DENY.play(player);
        }
    }

    private void sendMessage(@NotNull Player player, @NotNull MobInteractionActionConfig action) {
        String message = action.params().get("message");
        if (message == null || message.isBlank()) {
            GuiSound.DENY.play(player);
            return;
        }
        player.sendMessage(LEGACY.deserialize(ColorCodeUtil.translateAlternateColorCodes(message)));
    }

    private void openGui(@NotNull Player player, @NotNull MobInteractionActionConfig action) {
        String rawType = action.params().get("type");
        String type = rawType == null ? "" : rawType.trim().toUpperCase(Locale.ROOT);
        switch (type) {
            case "SHOP" -> openShop(player, action);
            case "SELL" -> {
                MenuGuiTransitionService.suppressNextCloseSound(player);
                menuView.openSell(player, List.of(), 0);
                GuiSound.OPEN.play(player);
            }
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
        shopGuiEventHandler.open(player, shopId);
    }
}
