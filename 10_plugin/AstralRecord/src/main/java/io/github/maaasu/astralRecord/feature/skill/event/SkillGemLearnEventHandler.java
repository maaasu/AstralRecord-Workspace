package io.github.maaasu.astralRecord.feature.skill.event;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.core.event.AbstractEventHandler;
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryEntryModel;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.feature.skill.model.SkillGemLearnConfirmHolder;
import io.github.maaasu.astralRecord.feature.skill.service.LearnedSkillService;
import io.github.maaasu.astralRecord.feature.skill.service.PassiveSkillService;
import io.github.maaasu.astralRecord.feature.skill.service.SkillService;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.shared.gui.confirm.ConfirmDialogView;
import io.github.maaasu.astralRecord.shared.gui.sound.GuiSound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** インベントリ左クリックによるスキルジェムの新規個体習得を処理します。 */
public final class SkillGemLearnEventHandler extends AbstractEventHandler {
    private final AstralRecord plugin;
    private final InventoryService inventoryService;
    private final LearnedSkillService learnedSkillService;
    private final SkillService skillService;
    private final PassiveSkillService passiveSkillService;
    private final ConfirmDialogView confirmDialogView = new ConfirmDialogView();
    private final Map<UUID, UUID> inFlight = new ConcurrentHashMap<>();

    public SkillGemLearnEventHandler(
        @NotNull AstralRecord plugin,
        @NotNull InventoryService inventoryService,
        @NotNull LearnedSkillService learnedSkillService,
        @NotNull SkillService skillService,
        @NotNull PassiveSkillService passiveSkillService
    ) {
        this.plugin = plugin;
        this.inventoryService = inventoryService;
        this.learnedSkillService = learnedSkillService;
        this.skillService = skillService;
        this.passiveSkillService = passiveSkillService;
    }

    /**
     * 通常プレイヤーインベントリのクリックをジェム操作として処理できた場合 true を返します。
     * ジェムの右クリックは消費も画面遷移も行わず、通常処理だけを抑止します。
     */
    public boolean handleInventoryItemClick(
        @NotNull InventoryClickEvent event,
        @NotNull AstPlayer astPlayer,
        int bukkitSlot
    ) {
        InventoryEntryModel entry = inventoryService.getOwnedEntryAtBukkitSlot(astPlayer, bukkitSlot);
        ItemModel item = inventoryService.getOwnedItemModelAtBukkitSlot(astPlayer, bukkitSlot);
        if (entry == null || item == null || item.getSkillGem() == null) return false;

        event.setCancelled(true);
        if (event.getClick() != ClickType.LEFT) return true;
        Player player = astPlayer.getBukkit();
        if (inFlight.containsKey(player.getUniqueId())) return true;
        openConfirm(player, astPlayer, entry, item);
        return true;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onConfirmClick(InventoryClickEvent event) {
        runSafely(() -> {
            if (!(event.getWhoClicked() instanceof Player player)) return;
            if (!(event.getView().getTopInventory().getHolder() instanceof SkillGemLearnConfirmHolder holder)) return;
            event.setCancelled(true);
            if (event.getRawSlot() == ConfirmDialogView.CANCEL_SLOT) {
                GuiSound.SELECT.play(player);
                player.closeInventory();
                restoreInventory(player);
                return;
            }
            if (event.getRawSlot() != ConfirmDialogView.CONFIRM_SLOT) return;
            UUID playerId = player.getUniqueId();
            UUID operationToken = UUID.randomUUID();
            if (inFlight.putIfAbsent(playerId, operationToken) != null) return;
            AstPlayer astPlayer = AstPlayerCache.get(player);
            if (astPlayer == null) {
                inFlight.remove(playerId, operationToken);
                return;
            }
            UUID accountId = astPlayer.getAccount().getUuid();
            boolean scheduled = learnedSkillService.learnAsync(
                accountId,
                holder.skillId(),
                holder.inventoryEntryId(),
                accountId,
                learned -> {
                    if (!inFlight.remove(playerId, operationToken)) return;
                    AstPlayer current = AstPlayerCache.get(player);
                    if (current == null) return;
                    inventoryService.applyInventoriesToGui(current);
                    passiveSkillService.markDirty(current);
                    player.closeInventory();
                    restoreInventory(player);
                    GuiSound.SELECT.play(player);
                    SkillDefinition definition = skillService.registry().getDefinition(learned.getSkillId());
                    String name = definition == null ? learned.getSkillId() : definition.getName();
                    PlayerMessageService.getInstance().send(player, PlayerMsgId.P_5856, name);
                },
                error -> {
                    if (!inFlight.remove(playerId, operationToken)) return;
                    GuiSound.DENY.play(player);
                    PlayerMessageService.getInstance().send(player, PlayerMsgId.P_5857);
                }
            );
            if (!scheduled) {
                inFlight.remove(playerId, operationToken);
                GuiSound.DENY.play(player);
            }
        }, LogId.E_5601, event.getWhoClicked().getName(), "skill_gem_learn_confirm");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onConfirmDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof SkillGemLearnConfirmHolder) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        inFlight.remove(event.getPlayer().getUniqueId());
    }

    private void openConfirm(Player player, AstPlayer astPlayer, InventoryEntryModel entry, ItemModel item) {
        String skillId = item.getSkillGem().getSkillId();
        SkillDefinition definition = skillService.registry().getDefinition(skillId);
        String name = definition == null ? skillId : definition.getName();
        boolean duplicate = learnedSkillService.ownsSkill(astPlayer.getAccount().getUuid(), skillId);
        Component message = Component.text(name + "を習得します。ジェムを1個消費します。", NamedTextColor.YELLOW);
        if (duplicate) {
            message = message.append(Component.newline()).append(Component.text(
                "同じスキルを所持済みですが、上書きやレベルアップは行わず別個体として習得します。",
                NamedTextColor.LIGHT_PURPLE
            ));
        }
        Inventory inventory = Bukkit.createInventory(
            new SkillGemLearnConfirmHolder(entry.getInventoryEntryId(), skillId),
            ConfirmDialogView.SIZE,
            Component.text("スキル習得確認", NamedTextColor.YELLOW)
        );
        confirmDialogView.render(
            inventory,
            message,
            Component.text("習得する", NamedTextColor.GREEN),
            Component.text("キャンセル", NamedTextColor.RED)
        );
        io.github.maaasu.astralRecord.shared.gui.GuiOpenSupport.open(player, inventory);
    }

    private void restoreInventory(Player player) {
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            AstPlayer astPlayer = AstPlayerCache.get(player);
            if (astPlayer != null) inventoryService.applyInventoriesToGui(astPlayer);
            player.updateInventory();
        });
    }
}
