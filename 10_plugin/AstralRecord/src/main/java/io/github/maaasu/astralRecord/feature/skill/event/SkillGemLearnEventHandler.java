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
import io.github.maaasu.astralRecord.feature.skill.service.SkillPresentationUtil;
import io.github.maaasu.astralRecord.feature.skill.service.SkillService;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.shared.gui.confirm.ConfirmDialogView;
import io.github.maaasu.astralRecord.shared.gui.hotbar.HotbarShortcutClickSupport;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/** インベントリ左クリックによるスキルジェムの新規個体習得を処理します。 */
public final class SkillGemLearnEventHandler extends AbstractEventHandler {
    private final AstralRecord plugin;
    private final InventoryService inventoryService;
    private final LearnedSkillService learnedSkillService;
    private final SkillService skillService;
    private final PassiveSkillService passiveSkillService;
    private final ConfirmDialogView confirmDialogView = new ConfirmDialogView();
    private final Map<UUID, UUID> inFlight = new ConcurrentHashMap<>();
    private BiConsumer<AstPlayer, String> skillLearnedListener = (player, skillId) -> { };

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
     * スキルジェムによる習得成功を外部機能へ通知する listener を設定します。
     *
     * @param listener 習得したプレイヤーとスキル ID を受け取る通知先
     */
    public void setSkillLearnedListener(@NotNull BiConsumer<AstPlayer, String> listener) {
        this.skillLearnedListener = listener;
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
            int topSize = event.getView().getTopInventory().getSize();
            if (event.getRawSlot() >= topSize) {
                HotbarShortcutClickSupport.handle(event, player, inventoryService);
                return;
            }
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
                    skillLearnedListener.accept(current, learned.getSkillId());
                    player.closeInventory();
                    restoreInventory(player);
                    GuiSound.SKILL_LEARN.play(player);
                    SkillDefinition definition = skillService.registry().getDefinition(learned.getSkillId());
                    String name = SkillPresentationUtil.plainName(definition, learned.getSkillId());
                    PlayerMessageService.getInstance().send(player, PlayerMsgId.P_5856, name);
                },
                error -> {
                    if (!inFlight.remove(playerId, operationToken)) return;
                    // mutateAsync は失敗時にも API 正本で状態を再同期する。確認画面を開いた
                    // ままにするため、下部の managed inventory 表示もその状態へ追随させる。
                    restoreInventory(player);
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
        if (event.getView().getTopInventory().getHolder() instanceof SkillGemLearnConfirmHolder
            && event.getRawSlots().stream().anyMatch(slot -> slot < event.getView().getTopInventory().getSize())) {
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
        boolean duplicate = learnedSkillService.ownsSkill(astPlayer.getAccount().getUuid(), skillId);
        List<Component> description = new ArrayList<>();
        description.add(SkillPresentationUtil.skillNameComponent(definition, skillId, NamedTextColor.WHITE));
        description.add(Component.text("スキルジェムを1個消費して習得します。", NamedTextColor.GRAY));
        if (duplicate) {
            description.add(Component.text("このスキルはすでに習得済みです。", NamedTextColor.RED));
        }
        Component title = duplicate
            ? Component.text("このスキルの習得は非推奨です。", NamedTextColor.RED)
            : Component.text("スキル習得確認", NamedTextColor.YELLOW);
        Inventory inventory = Bukkit.createInventory(
            new SkillGemLearnConfirmHolder(entry.getInventoryEntryId(), skillId),
            ConfirmDialogView.SIZE,
            title
        );
        if (duplicate) {
            confirmDialogView.render(
                inventory,
                Component.text("スキル習得の確認", NamedTextColor.YELLOW),
                description,
                Component.text("習得する", NamedTextColor.GREEN),
                Component.text("キャンセル", NamedTextColor.RED),
                List.of(
                    Component.text("すでに習得済みのスキルなので、", NamedTextColor.RED),
                    Component.text("同じスキルを別個体として習得します。", NamedTextColor.RED)
                )
            );
        } else {
            confirmDialogView.render(
                inventory,
                Component.text("スキル習得の確認", NamedTextColor.YELLOW),
                description,
                Component.text("習得する", NamedTextColor.GREEN),
                Component.text("キャンセル", NamedTextColor.RED)
            );
        }
        io.github.maaasu.astralRecord.shared.gui.GuiOpenSupport.open(player, inventory);
        GuiSound.SKILL_CONFIRM.play(player);
    }

    private void restoreInventory(Player player) {
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            AstPlayer astPlayer = AstPlayerCache.get(player);
            if (astPlayer != null) inventoryService.applyInventoriesToGui(astPlayer);
            player.updateInventory();
        });
    }
}
