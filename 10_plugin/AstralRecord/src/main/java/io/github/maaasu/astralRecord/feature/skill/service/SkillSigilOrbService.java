package io.github.maaasu.astralRecord.feature.skill.service;

import io.github.maaasu.astralRecord.feature.inventory.model.InventoryEntryModel;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.item.model.ItemCategory;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.item.model.ItemOrbEffectType;
import io.github.maaasu.astralRecord.feature.item.service.ItemService;
import io.github.maaasu.astralRecord.feature.item.service.ItemStackFactory;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.feature.skill.gui.SkillSigilOrbGuiHolder;
import io.github.maaasu.astralRecord.feature.skill.model.LearnedSkillInstance;
import io.github.maaasu.astralRecord.feature.skill.model.LearnedSkillMutationException;
import io.github.maaasu.astralRecord.feature.skill.model.LearnedSkillMutationFailure;
import io.github.maaasu.astralRecord.feature.skill.model.LearnedSkillSigil;
import io.github.maaasu.astralRecord.feature.skill.model.ResolvedLearnedSkill;
import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.feature.skill.service.SkillSynthesisMaterialEligibility.MaterialKind;
import io.github.maaasu.astralRecord.infrastructure.util.MaterialNameResolver;
import io.github.maaasu.astralRecord.shared.gui.GuiItems;
import io.github.maaasu.astralRecord.shared.gui.GuiOpenSupport;
import io.github.maaasu.astralRecord.shared.gui.hotbar.HotbarShortcutClickSupport;
import io.github.maaasu.astralRecord.shared.gui.sound.GuiSound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * シジル用オーブから、習得済みスキル一覧・装着・脱着 GUI を提供します。
 *
 * <p>装着・脱着の成立時は、起点オーブを1個消費します。装着時は選択した SIGIL entry も API が消費し、
 * 脱着時は API が返却した entry を {@link LearnedSkillService} がローカル所持品へ同期します。</p>
 */
public final class SkillSigilOrbService {
    private static final int LIST_CONTENT_SIZE = 45;
    private static final int PREVIOUS_PAGE_SLOT = 45;
    private static final int INFO_SLOT = 49;
    private static final int NEXT_PAGE_SLOT = 53;
    private static final int TARGET_SLOT = 10;
    private static final int SELECTION_SLOT = 13;
    private static final int RESULT_SLOT = 16;
    private static final int BACK_SLOT = 22;
    private static final int RETURN_SLOT = 25;
    private static final int DETACH_SELECTION_SIZE = 18;
    private static final int DETACH_PREVIOUS_SLOT = 18;
    private static final int DETACH_NEXT_SLOT = 26;
    private static final Material DEFAULT_SKILL_ICON = Material.BOOK;

    private final Plugin plugin;
    private final InventoryService inventoryService;
    private final ItemService itemService;
    private final ItemStackFactory itemStackFactory;
    private final SkillService skillService;
    private final LearnedSkillService learnedSkillService;
    private final PassiveSkillService passiveSkillService;
    private final InventoryOpener inventoryOpener;
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();

    public SkillSigilOrbService(
        @NotNull Plugin plugin,
        @NotNull InventoryService inventoryService,
        @NotNull ItemService itemService,
        @NotNull ItemStackFactory itemStackFactory,
        @NotNull SkillService skillService,
        @NotNull LearnedSkillService learnedSkillService,
        @NotNull PassiveSkillService passiveSkillService
    ) {
        this(
            plugin,
            inventoryService,
            itemService,
            itemStackFactory,
            skillService,
            learnedSkillService,
            passiveSkillService,
            GuiOpenSupport::open
        );
    }

    SkillSigilOrbService(
        @NotNull Plugin plugin,
        @NotNull InventoryService inventoryService,
        @NotNull ItemService itemService,
        @NotNull ItemStackFactory itemStackFactory,
        @NotNull SkillService skillService,
        @NotNull LearnedSkillService learnedSkillService,
        @NotNull PassiveSkillService passiveSkillService,
        @NotNull InventoryOpener inventoryOpener
    ) {
        this.plugin = plugin;
        this.inventoryService = inventoryService;
        this.itemService = itemService;
        this.itemStackFactory = itemStackFactory;
        this.skillService = skillService;
        this.learnedSkillService = learnedSkillService;
        this.passiveSkillService = passiveSkillService;
        this.inventoryOpener = inventoryOpener;
    }

    /**
     * シジル用オーブ操作を開始します。
     *
     * @param player 操作プレイヤー
     * @param astPlayer ロード済みプレイヤー状態
     * @param orbInventoryEntryId 操作を開始したオーブentry ID
     * @param orbModel 起点オーブのマスタ
     * @param returnToOrbListOnFailure 対象がない場合にオーブ一覧へ戻すか
     * @param openOrbList オーブ一覧へ戻る処理
     */
    public void start(
        @NotNull Player player,
        @NotNull AstPlayer astPlayer,
        @NotNull UUID orbInventoryEntryId,
        @NotNull ItemModel orbModel,
        boolean returnToOrbListOnFailure,
        @NotNull Runnable openOrbList
    ) {
        ItemOrbEffectType type = effectType(orbModel);
        if (type != ItemOrbEffectType.SIGIL_ATTACH && type != ItemOrbEffectType.SIGIL_DETACH) {
            GuiSound.DENY.play(player);
            return;
        }
        UUID accountId = astPlayer.getAccount().getUuid();
        if (!ownsOrb(accountId, orbInventoryEntryId, orbModel.getId(), type)) {
            GuiSound.DENY.play(player);
            return;
        }
        Session previous = sessions.get(player.getUniqueId());
        if (previous != null && previous.locked) {
            GuiSound.DENY.play(player);
            return;
        }
        removeSession(player.getUniqueId());
        Session session = new Session(
            player,
            astPlayer,
            accountId,
            UUID.randomUUID(),
            orbInventoryEntryId,
            orbModel.getId(),
            type,
            openOrbList
        );
        sessions.put(player.getUniqueId(), session);
        List<SkillTarget> candidates = collectCandidates(session);
        if (candidates.isEmpty()) {
            sessions.remove(player.getUniqueId(), session);
            PlayerMessageService.getInstance().send(player, PlayerMsgId.P_5872);
            GuiSound.DENY.play(player);
            if (returnToOrbListOnFailure) openOrbList.run();
            return;
        }
        openList(session, candidates, false);
    }

    /**
     * 指定インベントリがシジルオーブ専用GUIかを判定します。
     *
     * @param inventory 判定対象。未表示の場合は {@code null}
     * @return 専用holderを持つ場合は {@code true}
     */
    public boolean isSkillSigilInventory(@Nullable Inventory inventory) {
        return inventory != null && inventory.getHolder() instanceof SkillSigilOrbGuiHolder;
    }

    /**
     * プレイヤーのシジル操作がAPI処理中かを判定します。
     *
     * @param player 判定対象プレイヤー
     * @return 操作をロックしている場合は {@code true}
     */
    public boolean isLocked(@NotNull Player player) {
        Session session = sessions.get(player.getUniqueId());
        return session != null && session.locked;
    }

    /**
     * シジルオーブGUIのクリックを処理し、対象・素材選択またはAPI操作へ進めます。
     *
     * @param event 対象GUIのクリックイベント
     */
    public void handleGuiClick(@NotNull InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            event.setCancelled(true);
            return;
        }
        Session session = currentSession(player, event.getView().getTopInventory());
        if (session == null) {
            event.setCancelled(true);
            return;
        }
        if (event.getClickedInventory() instanceof PlayerInventory) {
            if (session.locked || session.transitioning) {
                event.setCancelled(true);
                GuiSound.DENY.play(player);
                return;
            }
            if (HotbarShortcutClickSupport.handleInventoryControlClick(event, player, inventoryService)) return;
            if (event.getSlot() >= 0 && event.getSlot() <= 8
                && HotbarShortcutClickSupport.handle(event, player, inventoryService)) return;
            event.setCancelled(true);
            if (session.screen == SkillSigilOrbGuiHolder.Screen.ATTACH && event.getClick() == ClickType.LEFT) {
                selectAttachMaterial(event, session);
            } else {
                GuiSound.DENY.play(player);
            }
            return;
        }
        event.setCancelled(true);
        if (session.locked || session.transitioning || event.getClick() != ClickType.LEFT) {
            GuiSound.DENY.play(player);
            return;
        }
        switch (session.screen) {
            case LIST -> handleListClick(event.getRawSlot(), session);
            case ATTACH, DETACH -> handleOperationClick(event.getRawSlot(), session);
            case DETACH_SELECT -> handleDetachSelectionClick(event.getRawSlot(), session);
        }
    }

    /**
     * シジルオーブGUI上のドラッグを拒否します。
     *
     * @param event 対象GUIのドラッグイベント
     */
    public void handleGuiDrag(@NotNull InventoryDragEvent event) {
        if (isSkillSigilInventory(event.getView().getTopInventory())) event.setCancelled(true);
    }

    /**
     * API処理中のhotbar持ち替えを拒否します。
     *
     * @param event hotbar持ち替えイベント
     * @return ロック中として拒否した場合は {@code true}
     */
    public boolean handleHeldChange(@NotNull PlayerItemHeldEvent event) {
        if (!isLocked(event.getPlayer())) return false;
        event.setCancelled(true);
        return true;
    }

    /**
     * GUI終了時にセッションを破棄し、API処理中の手動closeだけ再表示します。
     *
     * @param player 操作プレイヤー
     * @param closedInventory 閉じられたトップインベントリ
     */
    public void handleClose(@NotNull Player player, @Nullable Inventory closedInventory) {
        Session session = sessions.get(player.getUniqueId());
        if (session == null || session.player != player || session.transitioning) return;
        if (closedInventory != null && closedInventory != session.inventory) return;
        if (session.locked && player.isOnline()) {
            scheduleLockedReopen(session);
            return;
        }
        sessions.remove(player.getUniqueId(), session);
    }

    /**
     * プレイヤー保存・ログアウト前に対象セッションを破棄します。
     *
     * @param player 対象プレイヤー
     */
    public void prepareForPlayerSave(@NotNull Player player) {
        removeSession(player.getUniqueId());
    }

    /** Plugin停止前に全シジルオーブセッションを破棄します。 */
    public void prepareAllForShutdown() {
        for (UUID playerId : List.copyOf(sessions.keySet())) removeSession(playerId);
    }

    private void handleListClick(int slot, @NotNull Session session) {
        List<SkillTarget> candidates = collectCandidates(session);
        if (candidates.isEmpty()) {
            closeAndRemove(session);
            PlayerMessageService.getInstance().send(session.player, PlayerMsgId.P_5872);
            GuiSound.DENY.play(session.player);
            return;
        }
        int pages = pageCount(candidates.size(), LIST_CONTENT_SIZE);
        if (slot == PREVIOUS_PAGE_SLOT) {
            if (session.page > 0) {
                session.page--;
                renderList(session, candidates);
                GuiSound.PAGE.play(session.player);
            } else GuiSound.DENY.play(session.player);
            return;
        }
        if (slot == NEXT_PAGE_SLOT) {
            if (session.page + 1 < pages) {
                session.page++;
                renderList(session, candidates);
                GuiSound.PAGE.play(session.player);
            } else GuiSound.DENY.play(session.player);
            return;
        }
        if (slot == INFO_SLOT) {
            sessions.remove(session.player.getUniqueId(), session);
            session.openOrbList.run();
            return;
        }
        UUID learnedSkillId = session.displayedTargets.get(slot);
        SkillTarget target = learnedSkillId == null ? null : findCandidate(session, learnedSkillId);
        if (target == null) {
            renderList(session, candidates);
            GuiSound.DENY.play(session.player);
            return;
        }
        openOperation(session, target);
    }

    private void handleOperationClick(int slot, @NotNull Session session) {
        SkillTarget target = currentTarget(session);
        if (target == null || !ownsOrb(
            session.accountId,
            session.orbInventoryEntryId,
            session.orbItemId,
            session.type
        )) {
            closeAndRemove(session);
            PlayerMessageService.getInstance().send(session.player, PlayerMsgId.P_5873);
            GuiSound.DENY.play(session.player);
            return;
        }
        if (slot == BACK_SLOT) {
            session.selectedSigilItemId = null;
            session.selectedInventoryEntryId = null;
            session.selectedLearnedSkillSigilId = null;
            openList(session, collectCandidates(session), true);
            return;
        }
        if (session.screen == SkillSigilOrbGuiHolder.Screen.ATTACH
            && slot == SELECTION_SLOT && session.selectedSigilItemId != null) {
            session.selectedSigilItemId = null;
            session.selectedInventoryEntryId = null;
            renderOperation(session, target);
            GuiSound.SELECT.play(session.player);
            return;
        }
        if (session.screen == SkillSigilOrbGuiHolder.Screen.DETACH && slot == SELECTION_SLOT) {
            openDetachSelection(session, target);
            return;
        }
        if (slot != RESULT_SLOT || !isOperationReady(session, target)) {
            GuiSound.DENY.play(session.player);
            return;
        }
        execute(session, target);
    }

    private void selectAttachMaterial(@NotNull InventoryClickEvent event, @NotNull Session session) {
        SkillTarget target = currentTarget(session);
        InventoryEntryModel entry = inventoryService.getOwnedEntryAtBukkitSlot(session.astPlayer, event.getSlot());
        ItemModel sigil = entry == null || entry.getItemId() == null ? null : itemService.findLoadedById(entry.getItemId());
        if (target == null || sigil == null || entry.getQuantity() <= 0L
            || ItemCategory.fromApiValue(entry.getItemCategory()) != ItemCategory.SIGIL) {
            GuiSound.DENY.play(session.player);
            return;
        }
        MaterialKind eligibility = SkillSynthesisMaterialEligibility.resolve(target.learnedSkill, target.definition, sigil);
        if (eligibility != MaterialKind.SIGIL) {
            sendEligibilityFailure(session.player, eligibility);
            GuiSound.DENY.play(session.player);
            return;
        }
        session.selectedSigilItemId = sigil.getId();
        session.selectedInventoryEntryId = entry.getInventoryEntryId();
        renderOperation(session, target);
        GuiSound.SELECT.play(session.player);
    }

    private void handleDetachSelectionClick(int slot, @NotNull Session session) {
        SkillTarget target = currentTarget(session);
        if (target == null) {
            closeAndRemove(session);
            return;
        }
        List<LearnedSkillSigil> sigils = target.learnedSkill.getSigils().stream()
            .sorted(Comparator.comparingInt(LearnedSkillSigil::getSlotIndex))
            .toList();
        if (slot == BACK_SLOT) {
            showDetachOperation(session, target);
            return;
        }
        if (slot == DETACH_PREVIOUS_SLOT) {
            if (session.sigilPage > 0) {
                session.sigilPage--;
                renderDetachSelection(session, target);
                GuiSound.PAGE.play(session.player);
            } else GuiSound.DENY.play(session.player);
            return;
        }
        if (slot == DETACH_NEXT_SLOT) {
            if ((session.sigilPage + 1) * DETACH_SELECTION_SIZE < sigils.size()) {
                session.sigilPage++;
                renderDetachSelection(session, target);
                GuiSound.PAGE.play(session.player);
            } else GuiSound.DENY.play(session.player);
            return;
        }
        int index = session.sigilPage * DETACH_SELECTION_SIZE + slot;
        if (slot < 0 || slot >= DETACH_SELECTION_SIZE || index >= sigils.size()) {
            GuiSound.DENY.play(session.player);
            return;
        }
        LearnedSkillSigil selected = sigils.get(index);
        session.selectedLearnedSkillSigilId = selected.getLearnedSkillSigilId();
        session.selectedSigilItemId = selected.getSigilId();
        showDetachOperation(session, target);
    }

    private void execute(@NotNull Session session, @NotNull SkillTarget target) {
        if (session.locked) return;
        session.locked = true;
        renderProcessing(session);
        boolean scheduled;
        if (session.type == ItemOrbEffectType.SIGIL_ATTACH) {
            scheduled = learnedSkillService.attachSigilAsync(
                session.accountId,
                target.learnedSkill.getLearnedSkillId(),
                session.orbInventoryEntryId,
                session.selectedSigilItemId,
                session.selectedInventoryEntryId,
                session.accountId,
                updated -> complete(session),
                error -> fail(session, error)
            );
        } else {
            scheduled = learnedSkillService.detachSigilAsync(
                session.accountId,
                target.learnedSkill.getLearnedSkillId(),
                session.orbInventoryEntryId,
                session.selectedLearnedSkillSigilId,
                session.accountId,
                updated -> complete(session),
                error -> fail(session, error)
            );
        }
        if (!scheduled) fail(session, null);
    }

    private void complete(@NotNull Session session) {
        if (sessions.get(session.player.getUniqueId()) != session) return;
        session.locked = false;
        passiveSkillService.markDirty(session.astPlayer);
        inventoryService.refreshManagedInventoryUi(session.astPlayer);
        sessions.remove(session.player.getUniqueId(), session);
        session.player.closeInventory();
        GuiSound.SUCCESS.play(session.player);
    }

    private void fail(@NotNull Session session, @Nullable Throwable error) {
        if (sessions.get(session.player.getUniqueId()) != session) return;
        session.locked = false;
        PlayerMsgId message = failureMessage(error);
        PlayerMessageService.getInstance().send(session.player, message);
        SkillTarget target = currentTarget(session);
        if (target == null) {
            closeAndRemove(session);
        } else {
            if (session.type == ItemOrbEffectType.SIGIL_ATTACH) {
                session.selectedSigilItemId = null;
                session.selectedInventoryEntryId = null;
            } else if (target.learnedSkill.getSigils().stream().noneMatch(sigil ->
                sigil.getLearnedSkillSigilId().equals(session.selectedLearnedSkillSigilId))) {
                session.selectedLearnedSkillSigilId = null;
                session.selectedSigilItemId = null;
            }
            renderOperation(session, target);
        }
        GuiSound.DENY.play(session.player);
    }

    private @NotNull PlayerMsgId failureMessage(@Nullable Throwable error) {
        if (!(error instanceof LearnedSkillMutationException mutation)) return PlayerMsgId.P_5873;
        LearnedSkillMutationFailure failure = mutation.getFailure();
        return switch (failure) {
            case SIGIL_NOT_ALLOWED -> PlayerMsgId.P_5859;
            case NO_SIGIL_SLOT -> PlayerMsgId.P_5860;
            case DUPLICATE_SIGIL_GROUP -> PlayerMsgId.P_5861;
            default -> PlayerMsgId.P_5873;
        };
    }

    private void sendEligibilityFailure(@NotNull Player player, @NotNull MaterialKind kind) {
        PlayerMsgId message = switch (kind) {
            case SIGIL_NOT_ALLOWED -> PlayerMsgId.P_5859;
            case NO_SIGIL_SLOT -> PlayerMsgId.P_5860;
            case DUPLICATE_SIGIL_GROUP -> PlayerMsgId.P_5861;
            default -> PlayerMsgId.P_5873;
        };
        PlayerMessageService.getInstance().send(player, message);
    }

    private void openList(@NotNull Session session, @NotNull List<SkillTarget> candidates, boolean transition) {
        if (candidates.isEmpty()) {
            closeAndRemove(session);
            PlayerMessageService.getInstance().send(session.player, PlayerMsgId.P_5872);
            GuiSound.DENY.play(session.player);
            return;
        }
        Inventory inventory = Bukkit.createInventory(
            new SkillSigilOrbGuiHolder(session.player.getUniqueId(), session.token, SkillSigilOrbGuiHolder.Screen.LIST),
            SkillSigilOrbGuiHolder.LIST_SIZE,
            Component.text("シジル対象スキル", NamedTextColor.DARK_PURPLE)
        );
        session.inventory = inventory;
        session.screen = SkillSigilOrbGuiHolder.Screen.LIST;
        renderList(session, candidates);
        if (transition) transition(session, inventory, SkillSigilOrbGuiHolder.Screen.LIST);
        else inventoryOpener.open(session.player, inventory, () -> GuiSound.OPEN.play(session.player), () ->
            sessions.remove(session.player.getUniqueId(), session));
    }

    private void renderList(@NotNull Session session, @NotNull List<SkillTarget> candidates) {
        fill(session.inventory);
        int pages = pageCount(candidates.size(), LIST_CONTENT_SIZE);
        session.page = Math.max(0, Math.min(session.page, pages - 1));
        int from = session.page * LIST_CONTENT_SIZE;
        int to = Math.min(from + LIST_CONTENT_SIZE, candidates.size());
        Map<Integer, UUID> displayed = new LinkedHashMap<>();
        for (int index = from; index < to; index++) {
            int slot = index - from;
            SkillTarget target = candidates.get(index);
            session.inventory.setItem(slot, createSkillItem(target, true));
            displayed.put(slot, target.learnedSkill.getLearnedSkillId());
        }
        session.displayedTargets = Map.copyOf(displayed);
        session.inventory.setItem(PREVIOUS_PAGE_SLOT, pageButton(false, session.page > 0));
        session.inventory.setItem(INFO_SLOT, GuiItems.backButton());
        session.inventory.setItem(NEXT_PAGE_SLOT, pageButton(true, session.page + 1 < pages));
    }

    private void openOperation(@NotNull Session session, @NotNull SkillTarget target) {
        session.selectedLearnedSkillId = target.learnedSkill.getLearnedSkillId();
        session.selectedSigilItemId = null;
        session.selectedInventoryEntryId = null;
        session.selectedLearnedSkillSigilId = null;
        if (session.type == ItemOrbEffectType.SIGIL_DETACH && target.learnedSkill.getSigils().size() == 1) {
            LearnedSkillSigil sigil = target.learnedSkill.getSigils().getFirst();
            session.selectedLearnedSkillSigilId = sigil.getLearnedSkillSigilId();
            session.selectedSigilItemId = sigil.getSigilId();
        }
        SkillSigilOrbGuiHolder.Screen screen = session.type == ItemOrbEffectType.SIGIL_ATTACH
            ? SkillSigilOrbGuiHolder.Screen.ATTACH : SkillSigilOrbGuiHolder.Screen.DETACH;
        Inventory inventory = createOperationInventory(session, screen);
        session.inventory = inventory;
        session.screen = screen;
        renderOperation(session, target);
        transition(session, inventory, screen);
    }

    private @NotNull Inventory createOperationInventory(
        @NotNull Session session,
        @NotNull SkillSigilOrbGuiHolder.Screen screen
    ) {
        return Bukkit.createInventory(
            new SkillSigilOrbGuiHolder(session.player.getUniqueId(), session.token, screen),
            SkillSigilOrbGuiHolder.OPERATION_SIZE,
            Component.text(screen == SkillSigilOrbGuiHolder.Screen.ATTACH ? "シジル装着" : "シジル脱着",
                NamedTextColor.DARK_PURPLE)
        );
    }

    private void renderOperation(@NotNull Session session, @NotNull SkillTarget target) {
        fill(session.inventory);
        session.inventory.setItem(TARGET_SLOT, createSkillItem(target, false));
        ItemStack selector = GuiItems.create(
            Material.CHEST,
            Component.text(session.screen == SkillSigilOrbGuiHolder.Screen.ATTACH
                ? "インベントリ内のシジルを選択" : "脱着するシジルを選択", NamedTextColor.YELLOW),
            List.of(Component.text(session.screen == SkillSigilOrbGuiHolder.Screen.ATTACH
                ? "下段の所持シジルをクリック" : "クリックして装着済みシジルを選択", NamedTextColor.GRAY))
        );
        ItemModel selected = session.selectedSigilItemId == null
            ? null : itemService.findLoadedById(session.selectedSigilItemId);
        if (selected != null) selector = itemStackFactory.create(selected, 1);
        session.inventory.setItem(SELECTION_SLOT, selector);
        session.inventory.setItem(BACK_SLOT, GuiItems.backButton());
        boolean ready = isOperationReady(session, target);
        session.inventory.setItem(RESULT_SLOT, GuiItems.create(
            ready ? Material.LIME_DYE : Material.BARRIER,
            Component.text(ready ? "クリックして確定" : "シジルを選択してください",
                ready ? NamedTextColor.GREEN : NamedTextColor.RED),
            List.of(Component.text(session.type == ItemOrbEffectType.SIGIL_ATTACH
                ? "シジル用オーブと選択したシジルを各1個消費して装着します"
                : "シジル用オーブを1個消費し、選択したシジルを所持品へ返却します",
                NamedTextColor.GRAY))
        ));
        if (session.screen == SkillSigilOrbGuiHolder.Screen.DETACH && ready && selected != null) {
            ItemStack returned = itemStackFactory.create(selected, 1);
            appendLore(returned, Component.text("取り外し後に返却されます", NamedTextColor.GREEN));
            session.inventory.setItem(RETURN_SLOT, returned);
        }
    }

    private void showDetachOperation(@NotNull Session session, @NotNull SkillTarget target) {
        SkillSigilOrbGuiHolder.Screen screen = SkillSigilOrbGuiHolder.Screen.DETACH;
        Inventory inventory = createOperationInventory(session, screen);
        session.inventory = inventory;
        session.screen = screen;
        renderOperation(session, target);
        transition(session, inventory, screen);
    }

    private void openDetachSelection(@NotNull Session session, @NotNull SkillTarget target) {
        SkillSigilOrbGuiHolder.Screen screen = SkillSigilOrbGuiHolder.Screen.DETACH_SELECT;
        Inventory inventory = Bukkit.createInventory(
            new SkillSigilOrbGuiHolder(session.player.getUniqueId(), session.token, screen),
            SkillSigilOrbGuiHolder.OPERATION_SIZE,
            Component.text("脱着シジル選択", NamedTextColor.DARK_PURPLE)
        );
        session.inventory = inventory;
        session.screen = screen;
        session.sigilPage = 0;
        renderDetachSelection(session, target);
        transition(session, inventory, screen);
    }

    private void renderDetachSelection(@NotNull Session session, @NotNull SkillTarget target) {
        fill(session.inventory);
        List<LearnedSkillSigil> sigils = target.learnedSkill.getSigils().stream()
            .sorted(Comparator.comparingInt(LearnedSkillSigil::getSlotIndex))
            .toList();
        int from = session.sigilPage * DETACH_SELECTION_SIZE;
        for (int index = from; index < Math.min(from + DETACH_SELECTION_SIZE, sigils.size()); index++) {
            LearnedSkillSigil attached = sigils.get(index);
            ItemModel sigil = itemService.findLoadedById(attached.getSigilId());
            ItemStack item = sigil == null
                ? GuiItems.create(Material.PAPER, Component.text("未登録のシジル", NamedTextColor.RED), List.of())
                : itemStackFactory.create(sigil, 1);
            appendLore(item, Component.text("クリックして選択", NamedTextColor.YELLOW));
            session.inventory.setItem(index - from, item);
        }
        session.inventory.setItem(DETACH_PREVIOUS_SLOT, pageButton(false, session.sigilPage > 0));
        session.inventory.setItem(BACK_SLOT, GuiItems.backButton());
        session.inventory.setItem(DETACH_NEXT_SLOT,
            pageButton(true, (session.sigilPage + 1) * DETACH_SELECTION_SIZE < sigils.size()));
    }

    private void renderProcessing(@NotNull Session session) {
        session.inventory.setItem(RESULT_SLOT, GuiItems.create(
            Material.CLOCK,
            Component.text("処理中...", NamedTextColor.YELLOW),
            List.of(Component.text("完了までお待ちください", NamedTextColor.GRAY))
        ));
    }

    private boolean isOperationReady(@NotNull Session session, @NotNull SkillTarget target) {
        if (!ownsOrb(session.accountId, session.orbInventoryEntryId, session.orbItemId, session.type)
            || session.selectedSigilItemId == null) return false;
        if (session.type == ItemOrbEffectType.SIGIL_DETACH) {
            return session.selectedLearnedSkillSigilId != null && target.learnedSkill.getSigils().stream().anyMatch(sigil ->
                sigil.getLearnedSkillSigilId().equals(session.selectedLearnedSkillSigilId)
                    && sigil.getSigilId().equalsIgnoreCase(session.selectedSigilItemId));
        }
        if (session.selectedInventoryEntryId == null) return false;
        ItemModel sigil = itemService.findLoadedById(session.selectedSigilItemId);
        return sigil != null
            && SkillSynthesisMaterialEligibility.resolve(target.learnedSkill, target.definition, sigil) == MaterialKind.SIGIL;
    }

    private @NotNull List<SkillTarget> collectCandidates(@NotNull Session session) {
        return learnedSkillService.getLearnedSkills(session.accountId).stream()
            .map(this::resolveTarget)
            .filter(target -> target != null && isEligible(session.type, target))
            .sorted(Comparator.comparing(target ->
                SkillPresentationUtil.plainName(target.definition, "未登録のスキル")))
            .toList();
    }

    private @Nullable SkillTarget resolveTarget(@NotNull LearnedSkillInstance learnedSkill) {
        SkillDefinition definition = skillService.registry().getDefinition(learnedSkill.getSkillId());
        if (definition == null) return null;
        return new SkillTarget(learnedSkill, definition, skillService.resolveLearnedSkill(learnedSkill));
    }

    private boolean isEligible(@NotNull ItemOrbEffectType type, @NotNull SkillTarget target) {
        if (type == ItemOrbEffectType.SIGIL_DETACH) return !target.learnedSkill.getSigils().isEmpty();
        return !target.definition.getAllowedSigilIds().isEmpty()
            && target.learnedSkill.getSigils().size()
                < SkillSynthesisMaterialEligibility.sigilSlotCount(target.learnedSkill, target.definition);
    }

    private @Nullable SkillTarget findCandidate(@NotNull Session session, @NotNull UUID learnedSkillId) {
        return collectCandidates(session).stream()
            .filter(target -> target.learnedSkill.getLearnedSkillId().equals(learnedSkillId))
            .findFirst()
            .orElse(null);
    }

    private @Nullable SkillTarget currentTarget(@NotNull Session session) {
        if (session.selectedLearnedSkillId == null) return null;
        LearnedSkillInstance learned = learnedSkillService.findInstance(session.accountId, session.selectedLearnedSkillId);
        if (learned == null) return null;
        SkillTarget target = resolveTarget(learned);
        return target != null && isEligible(session.type, target) ? target : null;
    }

    private @NotNull ItemStack createSkillItem(@NotNull SkillTarget target, boolean listDisplay) {
        Material icon = MaterialNameResolver.match(target.definition.getIcon());
        List<Component> lore = new ArrayList<>();
        if (target.resolved != null) {
            lore.addAll(SkillPresentationUtil.skillDescriptionAndFlavorLore(target.resolved, NamedTextColor.GRAY));
        } else {
            lore.addAll(SkillPresentationUtil.skillDescriptionAndFlavorLore(target.definition, NamedTextColor.GRAY));
        }
        lore.add(Component.empty());
        lore.add(Component.text("レベル: " + target.learnedSkill.getLevel() + " / " + target.definition.getMaxLevel(),
            NamedTextColor.AQUA));
        int slots = SkillSynthesisMaterialEligibility.sigilSlotCount(target.learnedSkill, target.definition);
        lore.add(Component.text("シジル: " + target.learnedSkill.getSigils().size() + " / " + slots,
            NamedTextColor.LIGHT_PURPLE));
        if (listDisplay) {
            lore.add(Component.text("クリックしてシジルを操作", NamedTextColor.YELLOW));
        }
        return GuiItems.create(
            icon == null ? DEFAULT_SKILL_ICON : icon,
            SkillPresentationUtil.skillNameComponent(target.definition, "未登録のスキル", NamedTextColor.WHITE),
            lore
        );
    }

    private @NotNull ItemStack pageButton(boolean next, boolean enabled) {
        return GuiItems.create(
            enabled ? Material.ARROW : Material.GRAY_DYE,
            Component.text(next ? "次のページ" : "前のページ",
                enabled ? NamedTextColor.YELLOW : NamedTextColor.DARK_GRAY),
            List.of()
        );
    }

    private void transition(
        @NotNull Session session,
        @NotNull Inventory inventory,
        @NotNull SkillSigilOrbGuiHolder.Screen screen
    ) {
        session.transitioning = true;
        inventoryOpener.open(session.player, inventory, () -> {
            if (sessions.get(session.player.getUniqueId()) != session) return;
            session.inventory = inventory;
            session.screen = screen;
            session.transitioning = false;
            GuiSound.SELECT.play(session.player);
        }, () -> {
            session.transitioning = false;
            sessions.remove(session.player.getUniqueId(), session);
        });
    }

    private void scheduleLockedReopen(@NotNull Session session) {
        if (session.reopenTask != null) return;
        session.reopenTask = plugin.getServer().getScheduler().runTask(plugin, () -> {
            session.reopenTask = null;
            if (!session.locked || sessions.get(session.player.getUniqueId()) != session
                || !session.player.isOnline()) return;
            session.player.openInventory(session.inventory);
            if (session.player.getOpenInventory().getTopInventory() != session.inventory) {
                sessions.remove(session.player.getUniqueId(), session);
            }
        });
    }

    private @Nullable Session currentSession(@NotNull Player player, @Nullable Inventory inventory) {
        if (inventory == null || !(inventory.getHolder() instanceof SkillSigilOrbGuiHolder holder)
            || !holder.ownerId().equals(player.getUniqueId())) return null;
        Session session = sessions.get(player.getUniqueId());
        return session != null && session.token.equals(holder.sessionToken()) && session.inventory == inventory
            ? session : null;
    }

    private boolean ownsOrb(
        @NotNull UUID accountId,
        @NotNull UUID orbInventoryEntryId,
        @NotNull String orbItemId,
        @NotNull ItemOrbEffectType expectedType
    ) {
        InventoryEntryModel entry = inventoryService.findOwnedEntry(accountId, orbInventoryEntryId);
        if (entry == null || entry.getQuantity() <= 0L || entry.getItemId() == null) return false;
        ItemModel model = itemService.findLoadedById(entry.getItemId());
        return model != null
            && effectType(model) == expectedType
            && model.getId().equalsIgnoreCase(orbItemId);
    }

    private @Nullable ItemOrbEffectType effectType(@Nullable ItemModel orbModel) {
        return orbModel == null || orbModel.getOrb() == null || orbModel.getOrb().getEffect() == null
            ? null : orbModel.getOrb().getEffect().getType();
    }

    private void fill(@NotNull Inventory inventory) {
        ItemStack filler = GuiItems.create(Material.GRAY_STAINED_GLASS_PANE, Component.text(" "), List.of());
        for (int slot = 0; slot < inventory.getSize(); slot++) inventory.setItem(slot, filler);
    }

    private void appendLore(@NotNull ItemStack item, @NotNull Component line) {
        ItemMeta meta = item.getItemMeta();
        List<Component> lore = new ArrayList<>();
        if (meta.lore() != null) lore.addAll(meta.lore());
        lore.add(GuiItems.noItalic(line));
        meta.lore(lore);
        item.setItemMeta(meta);
    }

    private int pageCount(int size, int pageSize) {
        return Math.max(1, (size + pageSize - 1) / pageSize);
    }

    private void closeAndRemove(@NotNull Session session) {
        sessions.remove(session.player.getUniqueId(), session);
        session.player.closeInventory();
    }

    private void removeSession(@NotNull UUID playerId) {
        Session removed = sessions.remove(playerId);
        if (removed != null && removed.reopenTask != null) removed.reopenTask.cancel();
    }

    private record SkillTarget(
        @NotNull LearnedSkillInstance learnedSkill,
        @NotNull SkillDefinition definition,
        @Nullable ResolvedLearnedSkill resolved
    ) {
    }

    private static final class Session {
        private final Player player;
        private final AstPlayer astPlayer;
        private final UUID accountId;
        private final UUID token;
        private final UUID orbInventoryEntryId;
        private final String orbItemId;
        private final ItemOrbEffectType type;
        private final Runnable openOrbList;
        private Inventory inventory;
        private SkillSigilOrbGuiHolder.Screen screen = SkillSigilOrbGuiHolder.Screen.LIST;
        private int page;
        private int sigilPage;
        private Map<Integer, UUID> displayedTargets = Map.of();
        private UUID selectedLearnedSkillId;
        private String selectedSigilItemId;
        private UUID selectedInventoryEntryId;
        private UUID selectedLearnedSkillSigilId;
        private boolean locked;
        private boolean transitioning;
        private BukkitTask reopenTask;

        private Session(
            Player player,
            AstPlayer astPlayer,
            UUID accountId,
            UUID token,
            UUID orbInventoryEntryId,
            String orbItemId,
            ItemOrbEffectType type,
            Runnable openOrbList
        ) {
            this.player = player;
            this.astPlayer = astPlayer;
            this.accountId = accountId;
            this.token = token;
            this.orbInventoryEntryId = orbInventoryEntryId;
            this.orbItemId = orbItemId;
            this.type = type;
            this.openOrbList = openOrbList;
        }
    }

    @FunctionalInterface
    interface InventoryOpener {
        void open(
            @NotNull Player player,
            @NotNull Inventory inventory,
            @NotNull Runnable onOpened,
            @NotNull Runnable onCancelled
        );
    }
}
