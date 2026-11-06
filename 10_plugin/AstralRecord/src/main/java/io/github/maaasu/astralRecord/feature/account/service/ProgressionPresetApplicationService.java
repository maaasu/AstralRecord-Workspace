package io.github.maaasu.astralRecord.feature.account.service;

import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.feature.account.model.ProgressionPreset;
import io.github.maaasu.astralRecord.feature.inventory.model.EquipmentType;
import io.github.maaasu.astralRecord.feature.inventory.service.InventorySaveCoordinator;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentHandType;
import io.github.maaasu.astralRecord.feature.item.service.EquipmentRequirementService;
import io.github.maaasu.astralRecord.feature.item.service.ItemService;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.playerclass.PlayerClassService;
import io.github.maaasu.astralRecord.feature.skill.model.SkillBindPreset;
import io.github.maaasu.astralRecord.feature.skill.model.SkillKind;
import io.github.maaasu.astralRecord.feature.skill.service.LearnedSkillService;
import io.github.maaasu.astralRecord.feature.skill.service.SkillBindPresetService;
import io.github.maaasu.astralRecord.feature.skill.service.SkillActionRingService;
import io.github.maaasu.astralRecord.feature.skill.service.SkillService;
import io.github.maaasu.astralRecord.feature.skilltree.model.SkillTreeUnlockedNode;
import io.github.maaasu.astralRecord.feature.skilltree.service.SkillTreeService;
import io.github.maaasu.astralRecord.feature.status.service.StatusService;
import org.bukkit.Bukkit;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 戦闘検証用の構成をオンラインアカウントの保存状態へ反映します。 */
public final class ProgressionPresetApplicationService {
    public enum Result {
        APPLIED,
        UNAVAILABLE,
        INVALID
    }

    private final AccountService accounts;
    private final PlayerClassService classes;
    private final SkillTreeService tree;
    private final InventoryService inventory;
    private final SkillBindPresetService bindings;
    private final ItemService items;
    private final LearnedSkillService learned;
    private final SkillService skills;
    private final StatusService status;
    private final SkillActionRingService actionRing;

    public ProgressionPresetApplicationService(
        AccountService accounts, PlayerClassService classes, SkillTreeService tree, InventoryService inventory,
        SkillBindPresetService bindings, ItemService items, LearnedSkillService learned, SkillService skills,
        StatusService status, SkillActionRingService actionRing
    ) {
        this.accounts = accounts;
        this.classes = classes;
        this.tree = tree;
        this.inventory = inventory;
        this.bindings = bindings;
        this.items = items;
        this.learned = learned;
        this.skills = skills;
        this.status = status;
        this.actionRing = actionRing;
    }

    /**
     * 対象アカウントの戦闘状態をメインスレッドで切り替え、保存を予約します。
     * @param player 対象のオンラインプレイヤー
     * @param preset 適用するプリセット
     * @param checkpoint 指定レベルの構成
     * @param playerLevel 適用するプレイヤーレベル
     * @return ローカルでの適用結果
     */
    public @NotNull Result apply(
        @NotNull AstPlayer player, @NotNull ProgressionPreset preset,
        @NotNull ProgressionPreset.Checkpoint checkpoint, int playerLevel
    ) {
        UUID accountId = player.getAccount().getUuid();
        if (!Bukkit.isPrimaryThread() || !player.getBukkit().isOnline() || player.getBukkit().isDead()
            || AstPlayerCache.get(player.getBukkit()) != player || player.getAccount().getMode() != AccountMode.PLAYER
            || player.isPassiveSkillSessionClosed() || !tree.isStateReady(player) || !bindings.hasLoadedPresets(accountId)
            || !learned.hasLoadedSkills(accountId) || learned.hasMutationInProgress(accountId)
            || inventory.getInventories(accountId).isEmpty()) {
            return Result.UNAVAILABLE;
        }
        if (playerLevel < ProgressionPreset.MIN_LEVEL || playerLevel > ProgressionPreset.MAX_LEVEL
            || !preset.atLevel(playerLevel).equals(checkpoint)) {
            return Result.INVALID;
        }
        player.getBukkit().closeInventory();
        Result result;
        try {
            result = inventory.executeLocalPlayerMutation(accountId,
                () -> applyLocked(player, preset.classId(), checkpoint, playerLevel));
        } catch (InventorySaveCoordinator.ExternalOperationPendingException pending) {
            return Result.UNAVAILABLE;
        }
        if (result == Result.APPLIED) {
            try {
                skills.clearCasterState(player.getBukkit().getUniqueId());
                inventory.refreshProgressionPresetEquipment(player);
                tree.refreshProgressDerivedState(player);
                classes.refreshProgressionPresetClass(player);
                status.refreshStatus(player);
                status.restoreAll(player, null);
                actionRing.prepareProgressionPresetSelection(player.getBukkit());
            } finally {
                inventory.queueLocalPlayerSave(accountId);
            }
        }
        return result;
    }

    private Result applyLocked(AstPlayer player, String classId, ProgressionPreset.Checkpoint checkpoint, int level) {
        UUID accountId = player.getAccount().getUuid();
        Map<String, Integer> classLevels = classes.progressionPresetClassLevels(classId, checkpoint.classLevel());
        if (classLevels == null) return Result.INVALID;
        List<SkillTreeUnlockedNode> nodes = tree.prepareProgressionPresetNodes(
            player, classId, level, classLevels, checkpoint.nodes());
        if (nodes == null) return Result.INVALID;

        Map<EquipmentType, ItemModel> gear = new EnumMap<>(EquipmentType.class);
        for (var entry : checkpoint.equipment()) {
            EquipmentType type = EquipmentType.valueOf(entry.slot());
            ItemModel item = items.findLoadedById(itemId(entry.item()));
            if (item == null || item.getEquipment() == null
                || EquipmentType.fromItemEquipmentSlot(item.getEquipment().getSlot()) != type
                || !EquipmentRequirementService.check(level, classId, checkpoint.classLevel(), item.getEquipment()).allowed()) {
                return Result.INVALID;
            }
            gear.put(type, item);
        }

        ItemModel mainHand = gear.get(EquipmentType.MAIN_HAND);
        if (mainHand == null || gear.containsKey(EquipmentType.OFF_HAND)
            && mainHand.getEquipment().getHandType() == ItemEquipmentHandType.TWO) {
            return Result.INVALID;
        }

        Set<String> permitted = new LinkedHashSet<>();
        classes.progressionPresetUsableSkills(classId).forEach(skill -> permitted.add(skillId(skill)));
        for (var node : nodes) {
            tree.getNode(node.nodeId()).skillEffects().forEach(effect -> permitted.add(skillId(effect.skillId())));
        }
        if (!validBindings(checkpoint.skills(), SkillKind.ACTIVE, permitted, level)
            || !validBindings(checkpoint.passives(), SkillKind.PASSIVE, permitted, level)) {
            return Result.INVALID;
        }

        var legacyInventory = inventory.getActiveEquipmentLoadout(accountId) == null
            ? inventory.snapshotState(accountId) : null;
        if (legacyInventory != null) inventory.syncCurrentEquipmentState(player);
        if (!inventory.applyProgressionPresetEquipment(accountId, gear)) {
            if (legacyInventory != null) inventory.restoreState(legacyInventory);
            return Result.UNAVAILABLE;
        }
        player.setAccount(accounts.setPlayerLevelCached(player.getAccount(), level, player.getUser().getUuid())
            .updatedAccount());
        classes.applyProgressionPresetClasses(player, classId, classLevels);
        tree.applyProgressionPresetNodes(player, nodes);
        for (String id : permitted) {
            var definition = skills.registry().getDefinition(id);
            if (definition != null && definition.getKind() == SkillKind.PASSIVE
                && !definition.getPassiveBindRequired() && definition.getRequiredLevel() <= level
                && !learned.ownsSkill(accountId, id)) {
                learned.ensureProgressionPresetSkill(accountId, id);
            }
        }

        List<String> active = resolveBindings(accountId, checkpoint.skills(), SkillBindPreset.ACTIVE_SLOT_COUNT);
        List<String> passive = resolveBindings(accountId, checkpoint.passives(), SkillBindPreset.PASSIVE_SLOT_COUNT);
        bindings.applyProgressionPresetBindings(accountId, active, passive);
        return Result.APPLIED;
    }

    private boolean validBindings(
        List<ProgressionPreset.SkillBinding> entries, SkillKind kind, Set<String> permitted, int level
    ) {
        for (var entry : entries) {
            String id = skillId(entry.skill());
            var definition = skills.registry().getDefinition(id);
            if (!permitted.contains(id) || definition == null || definition.getKind() != kind
                || definition.getRequiredLevel() > level) {
                return false;
            }
        }
        return true;
    }

    private List<String> resolveBindings(UUID accountId, List<ProgressionPreset.SkillBinding> entries, int slotCount) {
        List<String> slots = new ArrayList<>(Collections.nCopies(slotCount, null));
        for (var entry : entries) {
            slots.set(entry.slot() - 1, learned.ensureProgressionPresetSkill(accountId, skillId(entry.skill())));
        }
        return slots;
    }

    private static String skillId(String reference) {
        return reference.startsWith("skill:") ? reference.substring("skill:".length()) : reference;
    }

    private static String itemId(String reference) {
        return reference.substring("item:".length());
    }
}
