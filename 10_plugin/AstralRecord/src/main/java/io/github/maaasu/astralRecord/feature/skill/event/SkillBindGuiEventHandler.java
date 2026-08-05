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
import io.github.maaasu.astralRecord.feature.skill.gui.SkillBindGui;
import io.github.maaasu.astralRecord.feature.skill.model.LearnedSkillInstance;
import io.github.maaasu.astralRecord.feature.skill.model.LearnedSkillMutationException;
import io.github.maaasu.astralRecord.feature.skill.model.SkillBindInventoryHolder;
import io.github.maaasu.astralRecord.feature.skill.model.SkillBindPreset;
import io.github.maaasu.astralRecord.feature.skill.model.SkillBindScreen;
import io.github.maaasu.astralRecord.feature.skill.model.SkillBindSession;
import io.github.maaasu.astralRecord.feature.skill.model.SkillBindType;
import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.feature.skill.model.SkillKind;
import io.github.maaasu.astralRecord.feature.skill.model.SkillManagerEntry;
import io.github.maaasu.astralRecord.feature.skill.model.ResolvedLearnedSkill;
import io.github.maaasu.astralRecord.feature.skill.service.LearnedSkillService;
import io.github.maaasu.astralRecord.feature.skill.service.PassiveSkillService;
import io.github.maaasu.astralRecord.feature.skill.service.SkillBindPresetService;
import io.github.maaasu.astralRecord.feature.skill.service.SkillOwnershipService;
import io.github.maaasu.astralRecord.feature.skill.service.SkillPermissionService;
import io.github.maaasu.astralRecord.feature.skill.service.SkillService;
import io.github.maaasu.astralRecord.feature.skill.service.SkillSynthesisMaterialEligibility;
import io.github.maaasu.astralRecord.feature.skill.service.SkillSynthesisMaterialEligibility.MaterialKind;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.shared.gui.confirm.ConfirmDialogView;
import io.github.maaasu.astralRecord.shared.gui.hotbar.HotbarShortcutClickSupport;
import io.github.maaasu.astralRecord.shared.gui.sound.GuiSound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.PlayerInventory;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** スキルマネージャーの一覧・バインド・合成操作を処理します。 */
public final class SkillBindGuiEventHandler extends AbstractEventHandler {
    private static final String ACTION_BACK = "back";
    private static final String ACTION_CLOSE = "close";
    private static final String ACTION_SWITCH_PRESET = "switch_preset";

    private final AstralRecord plugin;
    private final SkillBindGui gui;
    private final SkillService skillService;
    private final SkillBindPresetService presetService;
    private final SkillOwnershipService ownershipService;
    private final SkillPermissionService permissionService;
    private final LearnedSkillService learnedSkillService;
    private final PassiveSkillService passiveSkillService;
    private final InventoryService inventoryService;
    private final Map<UUID, SkillBindSession> sessions = new ConcurrentHashMap<>();
    private final Map<UUID, SynthesisSelection> synthesisSelections = new ConcurrentHashMap<>();
    /** 選択不可素材の理由を、消費せず合成画面のバリア表示へ渡す一時プレビューです。 */
    private final Map<UUID, SynthesisPreview> synthesisPreviews = new ConcurrentHashMap<>();
    private final Set<UUID> suppressClose = ConcurrentHashMap.newKeySet();
    private final Map<UUID, UUID> savingSessions = new ConcurrentHashMap<>();

    public SkillBindGuiEventHandler(
        @NotNull AstralRecord plugin,
        @NotNull SkillBindGui gui,
        @NotNull SkillService skillService,
        @NotNull SkillBindPresetService presetService,
        @NotNull SkillOwnershipService ownershipService,
        @NotNull SkillPermissionService permissionService,
        @NotNull LearnedSkillService learnedSkillService,
        @NotNull PassiveSkillService passiveSkillService,
        @NotNull InventoryService inventoryService
    ) {
        this.plugin = plugin;
        this.gui = gui;
        this.skillService = skillService;
        this.presetService = presetService;
        this.ownershipService = ownershipService;
        this.permissionService = permissionService;
        this.learnedSkillService = learnedSkillService;
        this.passiveSkillService = passiveSkillService;
        this.inventoryService = inventoryService;
    }

    /**
     * スキルマネージャーを開きます。
     *
     * @param player 表示対象プレイヤー
     * @return 習得スキルとプリセットのロードが完了して画面を開いた場合は {@code true}、未完了で拒否した場合は {@code false}
     */
    public boolean open(@NotNull Player player) {
        AstPlayer astPlayer = AstPlayerCache.get(player);
        if (astPlayer == null
            || !presetService.hasLoadedPresets(astPlayer.getAccount().getUuid())
            || !learnedSkillService.hasLoadedSkills(astPlayer.getAccount().getUuid())) {
            GuiSound.DENY.play(player);
            PlayerMessageService.getInstance().send(player, PlayerMsgId.P_5848);
            return false;
        }
        int presetIndex = presetService.selectedPresetIndex(astPlayer.getAccount().getUuid());
        SkillBindSession session = new SkillBindSession(presetService.getPresets(astPlayer.getAccount().getUuid()), presetIndex);
        if (!session.selectedPreset().isUnlocked()) session.loadPreset(1);
        presetService.selectPreset(astPlayer.getAccount().getUuid(), session.selectedPresetIndex());
        // 合成画面からコマンド等で開き直す場合も、表示予約していた素材を先に GUI へ復元する。
        removeSynthesisSelectionAndRestore(player);
        sessions.put(player.getUniqueId(), session);
        openMain(player, session, 0, true);
        return true;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        runSafely(() -> {
            if (!(event.getWhoClicked() instanceof Player player)) return;
            SkillBindInventoryHolder holder = gui.holder(event.getView().getTopInventory());
            if (holder == null) return;
            event.setCancelled(true);

            SkillBindSession session = sessions.get(player.getUniqueId());
            if (holder.screen() == SkillBindScreen.CONFIRM) {
                handleConfirmClick(player, holder, event.getRawSlot());
                return;
            }
            if (session == null || savingSessions.containsKey(player.getUniqueId())) {
                GuiSound.DENY.play(player);
                return;
            }
            if (holder.screen() == SkillBindScreen.SYNTHESIS) {
                handleSynthesisClick(player, session, holder, event);
                return;
            }
            if (event.getClickedInventory() instanceof PlayerInventory) {
                if (HotbarShortcutClickSupport.handle(event, player, inventoryService)) {
                    return;
                }
                GuiSound.DENY.play(player);
                return;
            }
            handleMainClick(player, session, holder, event);
        }, LogId.E_5601, event.getWhoClicked().getName(), "skill_manager_click");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (gui.isInventory(event.getView().getTopInventory())) {
            event.setCancelled(true);
            if (event.getWhoClicked() instanceof Player player) GuiSound.DENY.play(player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClose(InventoryCloseEvent event) {
        runSafely(() -> {
            if (!(event.getPlayer() instanceof Player player)) return;
            SkillBindInventoryHolder holder = gui.holder(event.getInventory());
            if (holder == null) return;
            UUID playerId = player.getUniqueId();
            if (suppressClose.remove(playerId)) return;
            SkillBindSession session = sessions.get(playerId);
            if (savingSessions.containsKey(playerId) && session != null) {
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (holder.screen() == SkillBindScreen.SYNTHESIS) openSynthesis(player, session, holder.learnedSkillId(), holder.pageIndex(), false);
                    else openMain(player, session, holder.pageIndex(), false);
                });
                return;
            }
            if (holder.screen() == SkillBindScreen.MAIN && session != null && session.isDirty()) {
                plugin.getServer().getScheduler().runTask(plugin, () -> openConfirm(
                    player, session, ACTION_CLOSE, -1, holder.pageIndex(),
                    Component.text("変更を破棄して閉じますか", NamedTextColor.YELLOW)
                ));
                return;
            }
            sessions.remove(playerId);
            removeSynthesisSelectionAndRestore(player);
            restorePlayerInventory(player);
        }, LogId.E_5601, event.getPlayer().getName(), "skill_manager_close");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        SynthesisSelection selection = synthesisSelections.remove(playerId);
        if (selection != null) {
            // PlayerJoinEventHandler が先に AstPlayerCache を破棄するため、選択時に保持した
            // accountId で予約を解除する。キャッシュへ依存すると再接続後も素材が隠れ続ける。
            inventoryService.clearHiddenEntriesFromGui(selection.accountId());
        }
        synthesisPreviews.remove(playerId);
        sessions.remove(playerId);
        suppressClose.remove(playerId);
        savingSessions.remove(playerId);
    }

    private void handleMainClick(
        Player player,
        SkillBindSession session,
        SkillBindInventoryHolder holder,
        InventoryClickEvent event
    ) {
        int slot = event.getRawSlot();
        int page = holder.pageIndex();
        AstPlayer astPlayer = AstPlayerCache.get(player);
        if (astPlayer == null) return;

        if (slot == SkillBindGui.PREVIOUS_PAGE_SLOT) {
            if (page <= 0) {
                GuiSound.DENY.play(player);
                return;
            }
            GuiSound.PAGE.play(player);
            openMain(player, session, page - 1, true);
            return;
        }
        if (slot == SkillBindGui.NEXT_PAGE_SLOT) {
            int pages = gui.totalPages(visibleEntries(astPlayer, session).size());
            if (page + 1 >= pages) {
                GuiSound.DENY.play(player);
                return;
            }
            GuiSound.PAGE.play(player);
            openMain(player, session, page + 1, true);
            return;
        }
        if (slot == SkillBindGui.BACK_SLOT) {
            String action = ACTION_BACK;
            if (session.isDirty()) {
                openConfirm(
                    player, session, action, -1, page,
                    Component.text("変更を破棄しますか", NamedTextColor.YELLOW)
                );
            } else {
                returnToPrevious(player);
            }
            return;
        }
        int presetIndex = SkillBindGui.presetIndexAtSlot(slot);
        if (presetIndex > 0) {
            handlePresetClick(player, session, presetIndex, page);
            return;
        }
        if (slot >= SkillBindGui.PASSIVE_BIND_SLOT_START
            && slot < SkillBindGui.PASSIVE_BIND_SLOT_START + SkillBindPreset.PASSIVE_SLOT_COUNT) {
            handleBindSlotClick(
                player, session, SkillBindType.PASSIVE,
                slot - SkillBindGui.PASSIVE_BIND_SLOT_START, page,
                passiveSkillService.activePassiveSlotCount(astPlayer)
            );
            return;
        }
        if (slot == SkillBindGui.LEFT_CLICK_BIND_SLOT) {
            handleBindSlotClick(player, session, SkillBindType.LEFT_CLICK, 0, page, 1);
            return;
        }
        if (slot >= SkillBindGui.ACTION_RING_BIND_SLOT_START
            && slot < SkillBindGui.ACTION_RING_BIND_SLOT_START + SkillBindPreset.ACTION_RING_SLOT_COUNT) {
            handleBindSlotClick(
                player, session, SkillBindType.ACTIVE,
                slot - SkillBindGui.ACTION_RING_BIND_SLOT_START, page, SkillBindPreset.ACTION_RING_SLOT_COUNT
            );
            return;
        }
        if (slot == SkillBindGui.NORMAL_ATTACK_SLOT
            && SkillBindGui.shouldShowNormalAttack(page, session.selectedBindType())) {
            if (!event.isLeftClick()) {
                GuiSound.DENY.play(player);
                return;
            }
            SkillBindType selectedType = session.selectedBindType();
            if (selectedType != SkillBindType.ACTIVE && selectedType != SkillBindType.LEFT_CLICK) {
                GuiSound.DENY.play(player);
                return;
            }
            session.setSlot(selectedType, session.selectedBindSlotIndex(), SkillBindPreset.WEAPON_NORMAL_ATTACK_BINDING_ID);
            session.clearSelectedBindSlot();
            GuiSound.SELECT.play(player);
            saveCurrentPreset(player, session, page);
            return;
        }

        String learnedSkillId = gui.learnedSkillId(event.getCurrentItem());
        SkillManagerEntry entry = entry(astPlayer, learnedSkillId);
        if (entry == null) {
            GuiSound.DENY.play(player);
            return;
        }
        if (event.isRightClick()) {
            if (!canOpenSynthesis(entry)) {
                GuiSound.DENY.play(player);
                return;
            }
            removeSynthesisSelectionAndRestore(player);
            GuiSound.SELECT.play(player);
            openSynthesis(player, session, entry.bindingId(), page, true);
            return;
        }
        if (!event.isLeftClick()) {
            GuiSound.DENY.play(player);
            return;
        }
        if (session.selectedBindType() != null) {
            if (!isSelectedSlotCurrentlyEnabled(astPlayer, session)
                || !canBindToSelected(session, entry)) {
                session.clearSelectedBindSlot();
                GuiSound.DENY.play(player);
                openMain(player, session, page, true);
                return;
            }
            session.setSlot(session.selectedBindType(), session.selectedBindSlotIndex(), entry.bindingId());
            session.clearSelectedBindSlot();
            GuiSound.SELECT.play(player);
            saveCurrentPreset(player, session, page);
            return;
        }
        if (!session.assignSelectedOrNextSlot(
            entry.bindingId(),
            entry.definition().getKind(),
            passiveSkillService.activePassiveSlotCount(astPlayer)
        )) {
            GuiSound.DENY.play(player);
            PlayerMessageService.getInstance().send(player, PlayerMsgId.P_5865);
            return;
        }
        GuiSound.SELECT.play(player);
        saveCurrentPreset(player, session, page);
    }

    private void handleBindSlotClick(
        Player player,
        SkillBindSession session,
        SkillBindType type,
        int index,
        int page,
        int enabledSlotCount
    ) {
        String current = session.skillIdAt(type, index);
        if (current != null && !current.isBlank()) {
            session.setSlot(type, index, null);
            session.clearSelectedBindSlot();
            GuiSound.SELECT.play(player);
            saveCurrentPreset(player, session, page);
            return;
        }
        if (type == SkillBindType.PASSIVE && index >= enabledSlotCount) {
            GuiSound.DENY.play(player);
            return;
        }
        if (session.isSelectedBindSlot(type, index)) session.clearSelectedBindSlot();
        else session.selectBindSlot(type, index);
        GuiSound.SELECT.play(player);
        openMain(player, session, page, true);
    }

    private void handleSynthesisClick(
        Player player,
        SkillBindSession session,
        SkillBindInventoryHolder holder,
        InventoryClickEvent event
    ) {
        AstPlayer astPlayer = AstPlayerCache.get(player);
        if (astPlayer == null) return;
        SkillManagerEntry entry = entry(astPlayer, holder.learnedSkillId());
        if (entry == null) {
            openMain(player, session, holder.pageIndex(), true);
            return;
        }
        if (event.getClickedInventory() instanceof PlayerInventory) {
            InventoryEntryModel inventoryEntry = inventoryService.getOwnedEntryAtBukkitSlot(astPlayer, event.getSlot());
            ItemModel item = inventoryService.getOwnedItemModelAtBukkitSlot(astPlayer, event.getSlot());
            MaterialKind kind = item == null ? MaterialKind.NONE : SkillSynthesisMaterialEligibility.resolve(entry, item);
            if (inventoryEntry == null || item == null || !kind.usable()) {
                removeSynthesisSelectionAndRestore(player);
                if (item != null) {
                    synthesisPreviews.put(player.getUniqueId(), new SynthesisPreview(item, kind));
                    openSynthesis(player, session, entry.bindingId(), holder.pageIndex(), true);
                }
                GuiSound.DENY.play(player);
                sendMaterialFailure(player, kind);
                return;
            }
            removeSynthesisSelectionAndRestore(player);
            synthesisPreviews.remove(player.getUniqueId());
            synthesisSelections.put(
                player.getUniqueId(),
                new SynthesisSelection(
                    astPlayer.getAccount().getUuid(), inventoryEntry.getInventoryEntryId(), item
                )
            );
            inventoryService.hideOwnedEntryFromGui(astPlayer, inventoryEntry.getInventoryEntryId());
            GuiSound.SELECT.play(player);
            openSynthesis(player, session, entry.bindingId(), holder.pageIndex(), true);
            return;
        }
        if (event.getRawSlot() == SkillBindGui.BACK_SLOT) {
            removeSynthesisSelectionAndRestore(player);
            synthesisPreviews.remove(player.getUniqueId());
            GuiSound.SELECT.play(player);
            openMain(player, session, holder.pageIndex(), true);
            return;
        }
        if (event.getRawSlot() == SkillBindGui.SYNTHESIS_MATERIAL_SLOT) {
            boolean restored = removeSynthesisSelection(player);
            boolean clearedPreview = synthesisPreviews.remove(player.getUniqueId()) != null;
            if (!restored && !clearedPreview) {
                GuiSound.DENY.play(player);
                return;
            }
            restorePlayerInventory(player);
            GuiSound.SELECT.play(player);
            openSynthesis(player, session, entry.bindingId(), holder.pageIndex(), true);
            return;
        }
        if (event.getRawSlot() != SkillBindGui.SYNTHESIS_RESULT_SLOT) return;

        SynthesisSelection selection = synthesisSelections.get(player.getUniqueId());
        if (selection == null) {
            GuiSound.DENY.play(player);
            return;
        }
        MaterialKind kind = SkillSynthesisMaterialEligibility.resolve(entry, selection.item());
        if (!kind.usable()) {
            removeSynthesisSelectionAndRestore(player);
            synthesisPreviews.put(player.getUniqueId(), new SynthesisPreview(selection.item(), kind));
            GuiSound.DENY.play(player);
            sendMaterialFailure(player, kind);
            openSynthesis(player, session, entry.bindingId(), holder.pageIndex(), true);
            return;
        }
        UUID playerId = player.getUniqueId();
        UUID operationToken = UUID.randomUUID();
        if (savingSessions.putIfAbsent(playerId, operationToken) != null) {
            GuiSound.DENY.play(player);
            return;
        }
        UUID accountId = astPlayer.getAccount().getUuid();
        boolean scheduled;
        if (kind == MaterialKind.GEM) {
            scheduled = learnedSkillService.levelUpAsync(
                accountId,
                entry.learnedSkill().getLearnedSkillId(),
                selection.inventoryEntryId(),
                accountId,
                updated -> completeSynthesis(player, session, holder.pageIndex(), updated, operationToken),
                error -> failSynthesis(player, session, entry.bindingId(), holder.pageIndex(), operationToken, error)
            );
        } else {
            scheduled = learnedSkillService.attachSigilAsync(
                accountId,
                entry.learnedSkill().getLearnedSkillId(),
                selection.item().getId(),
                selection.inventoryEntryId(),
                accountId,
                updated -> completeSynthesis(player, session, holder.pageIndex(), updated, operationToken),
                error -> failSynthesis(player, session, entry.bindingId(), holder.pageIndex(), operationToken, error)
            );
        }
        if (!scheduled) failSynthesis(player, session, entry.bindingId(), holder.pageIndex(), operationToken, null);
    }

    private void completeSynthesis(
        Player player,
        SkillBindSession session,
        int returnPage,
        LearnedSkillInstance updated,
        UUID operationToken
    ) {
        UUID playerId = player.getUniqueId();
        if (!savingSessions.remove(playerId, operationToken)) return;
        removeSynthesisSelection(player);
        synthesisPreviews.remove(playerId);
        AstPlayer astPlayer = AstPlayerCache.get(player);
        if (astPlayer == null || sessions.get(playerId) != session) return;
        inventoryService.applyInventoriesToGui(astPlayer);
        passiveSkillService.markDirty(astPlayer);
        SkillManagerEntry updatedEntry = entry(astPlayer, updated.getLearnedSkillId().toString());
        GuiSound.UPGRADE.play(player);
        if (updatedEntry != null && canOpenSynthesis(updatedEntry)) {
            openSynthesis(player, session, updatedEntry.bindingId(), returnPage, true);
        } else {
            openMain(player, session, returnPage, true);
        }
    }

    private void failSynthesis(
        Player player,
        SkillBindSession session,
        String learnedSkillId,
        int returnPage,
        UUID operationToken,
        @Nullable Throwable error
    ) {
        UUID playerId = player.getUniqueId();
        if (!savingSessions.remove(playerId, operationToken) || sessions.get(playerId) != session) return;
        removeSynthesisSelectionAndRestore(player);
        synthesisPreviews.remove(playerId);
        GuiSound.DENY.play(player);
        PlayerMessageService.getInstance().send(player, mutationFailureMessage(error));
        openSynthesis(player, session, learnedSkillId, returnPage, true);
    }

    private boolean canOpenSynthesis(SkillManagerEntry entry) {
        return SkillSynthesisMaterialEligibility.canOpenSynthesis(entry);
    }

    private boolean canBindToSelected(SkillBindSession session, SkillManagerEntry entry) {
        SkillBindType selected = session.selectedBindType();
        if (selected == null) return false;
        if (selected == SkillBindType.PASSIVE) {
            return entry.definition().getKind() == SkillKind.PASSIVE && entry.definition().getPassiveBindRequired();
        }
        return entry.definition().getKind() != SkillKind.PASSIVE;
    }

    private boolean isSelectedSlotCurrentlyEnabled(AstPlayer player, SkillBindSession session) {
        return session.selectedBindType() != SkillBindType.PASSIVE
            || session.selectedBindSlotIndex() < passiveSkillService.activePassiveSlotCount(player);
    }

    /** 既存の超過バインド維持と解除だけを許可し、新規設定・置換を元へ戻します。 */
    private boolean restoreInvalidPassiveOverflowChanges(AstPlayer player, SkillBindSession session) {
        int enabledSlots = passiveSkillService.activePassiveSlotCount(player);
        List<String> persisted = session.selectedPreset().getPassiveSkillSlots();
        boolean restored = false;
        for (int index = enabledSlots; index < SkillBindPreset.PASSIVE_SLOT_COUNT; index++) {
            String before = persisted.get(index);
            String draft = session.passiveDraft().get(index);
            if (draft != null && !Objects.equals(before, draft)) {
                session.setSlot(SkillBindType.PASSIVE, index, before);
                restored = true;
            }
        }
        return restored;
    }

    private void handlePresetClick(Player player, SkillBindSession session, int presetIndex, int page) {
        if (!session.presets().get(presetIndex - 1).isUnlocked()) {
            GuiSound.DENY.play(player);
            return;
        }
        if (presetIndex == session.selectedPresetIndex()) return;
        if (session.isDirty()) {
            openConfirm(
                player, session, ACTION_SWITCH_PRESET, presetIndex, page,
                Component.text("変更を破棄してプリセットを切り替えますか", NamedTextColor.YELLOW)
            );
            return;
        }
        switchPreset(player, session, presetIndex, page);
    }

    private void switchPreset(Player player, SkillBindSession session, int presetIndex, int page) {
        session.loadPreset(presetIndex);
        AstPlayer astPlayer = AstPlayerCache.get(player);
        if (astPlayer != null) {
            presetService.selectPreset(astPlayer.getAccount().getUuid(), presetIndex);
            passiveSkillService.reconcileNow(astPlayer);
            PlayerMessageService.getInstance().send(astPlayer, PlayerMsgId.P_5808, presetIndex);
        }
        GuiSound.TOGGLE.play(player);
        openMain(player, session, page, true);
    }

    private void handleConfirmClick(Player player, SkillBindInventoryHolder holder, int slot) {
        SkillBindSession session = sessions.get(player.getUniqueId());
        if (session == null) {
            restoreAndClose(player);
            return;
        }
        if (slot == ConfirmDialogView.CANCEL_SLOT) {
            GuiSound.SELECT.play(player);
            openMain(player, session, holder.pageIndex(), true);
            return;
        }
        if (slot != ConfirmDialogView.CONFIRM_SLOT) return;
        GuiSound.CONFIRM.play(player);
        switch (holder.action()) {
            case ACTION_SWITCH_PRESET -> switchPreset(player, session, holder.pendingPresetIndex(), holder.pageIndex());
            case ACTION_BACK -> returnToPrevious(player);
            case ACTION_CLOSE -> restoreAndClose(player);
            default -> openMain(player, session, holder.pageIndex(), true);
        }
    }

    private void saveCurrentPreset(Player player, SkillBindSession session, int page) {
        AstPlayer astPlayer = AstPlayerCache.get(player);
        if (astPlayer == null || !session.selectedPreset().isUnlocked()) return;
        if (restoreInvalidPassiveOverflowChanges(astPlayer, session)) {
            session.clearSelectedBindSlot();
            GuiSound.DENY.play(player);
            openMain(player, session, page, true);
            return;
        }
        UUID accountId = astPlayer.getAccount().getUuid();
        int presetIndex = session.selectedPresetIndex();
        List<String> active = new ArrayList<>(session.activeDraft());
        String left = session.leftClickDraft();
        List<String> passive = new ArrayList<>(session.passiveDraft());
        UUID playerId = player.getUniqueId();
        UUID operationToken = UUID.randomUUID();
        if (savingSessions.putIfAbsent(playerId, operationToken) != null) {
            GuiSound.DENY.play(player);
            return;
        }
        boolean scheduled = presetService.saveAsync(
            accountId, presetIndex, active, left, passive, accountId,
            saved -> {
                if (!savingSessions.remove(playerId, operationToken) || sessions.get(playerId) != session) return;
                if (session.selectedPresetIndex() != presetIndex
                    || !session.activeDraft().equals(active)
                    || !Objects.equals(session.leftClickDraft(), left)
                    || !session.passiveDraft().equals(passive)) return;
                session.replaceSelectedPreset(saved);
                AstPlayer current = AstPlayerCache.get(player);
                if (current != null) passiveSkillService.reconcileNow(current);
                openMain(player, session, page, true);
            },
            () -> {
                if (!savingSessions.remove(playerId, operationToken) || sessions.get(playerId) != session) return;
                GuiSound.DENY.play(player);
                PlayerMessageService.getInstance().send(player, PlayerMsgId.P_5849);
            }
        );
        if (!scheduled) {
            if (savingSessions.remove(playerId, operationToken)) GuiSound.DENY.play(player);
        }
    }

    private List<SkillManagerEntry> visibleEntries(AstPlayer player, SkillBindSession session) {
        return allEntries(player).stream()
            .filter(entry -> {
                SkillBindType selected = session.selectedBindType();
                if (selected == null) {
                    return !entry.definition().getKind().isPassive()
                        || entry.definition().getPassiveBindRequired();
                }
                if (selected == SkillBindType.PASSIVE) {
                    return entry.definition().getKind() == SkillKind.PASSIVE
                        && entry.definition().getPassiveBindRequired();
                }
                return entry.definition().getKind() != SkillKind.PASSIVE;
            })
            .toList();
    }

    private List<SkillManagerEntry> allEntries(AstPlayer player) {
        List<SkillManagerEntry> result = new ArrayList<>();
        for (LearnedSkillInstance learned : ownershipService.learnedSkills(player)) {
            SkillDefinition base = skillService.registry().getDefinition(learned.getSkillId());
            if (base == null) continue;
            ResolvedLearnedSkill resolved = skillService.resolveLearnedSkill(learned);
            boolean permitted = permissionService.isPermitted(player, learned.getSkillId());
            result.add(resolved == null
                ? new SkillManagerEntry(learned, base, permitted)
                : new SkillManagerEntry(learned, resolved.definition(), permitted, resolved));
        }
        result.sort(Comparator
            .comparing((SkillManagerEntry entry) -> !entry.permitted())
            .thenComparing(entry -> entry.definition().getId())
            .thenComparing(entry -> entry.learnedSkill().getCreatedAt(), Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(entry -> entry.learnedSkill().getLearnedSkillId()));
        return result;
    }

    private @Nullable SkillManagerEntry entry(AstPlayer player, String learnedSkillId) {
        if (learnedSkillId == null || learnedSkillId.isBlank()) return null;
        LearnedSkillInstance learned = ownershipService.findInstance(player, learnedSkillId);
        if (learned == null) return null;
        SkillDefinition base = skillService.registry().getDefinition(learned.getSkillId());
        if (base == null) return null;
        ResolvedLearnedSkill resolved = skillService.resolveLearnedSkill(learned);
        boolean permitted = permissionService.isPermitted(player, learned.getSkillId());
        return resolved == null
            ? new SkillManagerEntry(learned, base, permitted)
            : new SkillManagerEntry(learned, resolved.definition(), permitted, resolved);
    }

    private void openMain(Player player, SkillBindSession session, int page, boolean suppressPreviousClose) {
        AstPlayer astPlayer = AstPlayerCache.get(player);
        if (astPlayer == null) return;
        if (suppressPreviousClose) suppressCloseIfSwitching(player);
        List<SkillManagerEntry> visible = visibleEntries(astPlayer, session);
        Map<String, SkillManagerEntry> allById = new LinkedHashMap<>();
        for (SkillManagerEntry entry : allEntries(astPlayer)) allById.put(entry.bindingId(), entry);
        gui.open(
            player,
            session,
            visible,
            allById,
            passiveSkillService.activePassiveSlotCount(astPlayer),
            page
        );
    }

    private void openSynthesis(
        Player player,
        SkillBindSession session,
        String learnedSkillId,
        int returnPage,
        boolean suppressPreviousClose
    ) {
        AstPlayer astPlayer = AstPlayerCache.get(player);
        if (astPlayer == null) return;
        SkillManagerEntry entry = entry(astPlayer, learnedSkillId);
        if (entry == null) {
            removeSynthesisSelectionAndRestore(player);
            synthesisPreviews.remove(player.getUniqueId());
            openMain(player, session, returnPage, suppressPreviousClose);
            return;
        }
        if (suppressPreviousClose) suppressCloseIfSwitching(player);
        SynthesisSelection selection = synthesisSelections.get(player.getUniqueId());
        SynthesisPreview preview = selection == null ? synthesisPreviews.get(player.getUniqueId()) : null;
        ItemModel material = selection != null ? selection.item() : preview == null ? null : preview.item();
        MaterialKind materialKind = selection != null
            ? SkillSynthesisMaterialEligibility.resolve(entry, selection.item())
            : preview == null ? MaterialKind.NONE : preview.kind();
        gui.openSynthesis(
            player,
            session.selectedPresetIndex(),
            returnPage,
            entry,
            material,
            materialKind,
            selection != null
        );
    }

    private void openConfirm(
        Player player,
        SkillBindSession session,
        String action,
        int pending,
        int page,
        Component message
    ) {
        suppressCloseIfSwitching(player);
        gui.openConfirm(player, session.selectedPresetIndex(), page, action, pending, message);
        GuiSound.CONFIRM.play(player);
    }

    private void suppressCloseIfSwitching(Player player) {
        if (gui.isInventory(player.getOpenInventory().getTopInventory())) suppressClose.add(player.getUniqueId());
    }

    private void returnToPrevious(Player player) {
        sessions.remove(player.getUniqueId());
        removeSynthesisSelectionAndRestore(player);
        suppressClose.add(player.getUniqueId());
        if (plugin.getGuiNavigationService().openPrevious(player)) {
            GuiSound.SELECT.play(player);
            return;
        }
        player.closeInventory();
        GuiSound.CLOSE.play(player);
    }

    private void restoreAndClose(Player player) {
        sessions.remove(player.getUniqueId());
        removeSynthesisSelectionAndRestore(player);
        suppressClose.add(player.getUniqueId());
        player.closeInventory();
        GuiSound.CLOSE.play(player);
    }

    private void restorePlayerInventory(Player player) {
        AstPlayer astPlayer = AstPlayerCache.get(player);
        if (astPlayer != null) inventoryService.applyInventoriesToGui(astPlayer);
        player.updateInventory();
    }

    private void removeSynthesisSelectionAndRestore(@NotNull Player player) {
        synthesisPreviews.remove(player.getUniqueId());
        if (removeSynthesisSelection(player)) {
            restorePlayerInventory(player);
        }
    }

    private boolean removeSynthesisSelection(@NotNull Player player) {
        SynthesisSelection selection = synthesisSelections.remove(player.getUniqueId());
        if (selection == null) {
            return false;
        }
        AstPlayer astPlayer = AstPlayerCache.get(player);
        if (astPlayer != null) {
            inventoryService.restoreHiddenEntryToGui(astPlayer, selection.inventoryEntryId());
        }
        return true;
    }

    private void sendMaterialFailure(@NotNull Player player, @NotNull MaterialKind kind) {
        PlayerMsgId messageId = switch (kind) {
            case SIGIL_NOT_ALLOWED -> PlayerMsgId.P_5859;
            case NO_SIGIL_SLOT -> PlayerMsgId.P_5860;
            case DUPLICATE_SIGIL_GROUP -> PlayerMsgId.P_5861;
            case NONE, INVALID_GEM -> PlayerMsgId.P_5862;
            case GEM, SIGIL -> null;
        };
        if (messageId != null) {
            PlayerMessageService.getInstance().send(player, messageId);
        }
    }

    private PlayerMsgId mutationFailureMessage(@Nullable Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof LearnedSkillMutationException mutationException) {
                return switch (mutationException.getFailure()) {
                    case NO_SIGIL_SLOT -> PlayerMsgId.P_5860;
                    case SIGIL_NOT_ALLOWED -> PlayerMsgId.P_5859;
                    case DUPLICATE_SIGIL_GROUP -> PlayerMsgId.P_5861;
                    case INVALID_MATERIAL, MAX_LEVEL_REACHED -> PlayerMsgId.P_5862;
                    default -> PlayerMsgId.P_5864;
                };
            }
            current = current.getCause();
        }
        return PlayerMsgId.P_5864;
    }

    private record SynthesisSelection(
        @NotNull UUID accountId,
        @NotNull UUID inventoryEntryId,
        @NotNull ItemModel item
    ) {
    }

    private record SynthesisPreview(@NotNull ItemModel item, @NotNull MaterialKind kind) {
    }
}
