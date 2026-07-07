package io.github.maaasu.astralRecord.feature.quest.service;

import io.github.maaasu.astralRecord.feature.account.model.AccountExperienceResult;
import io.github.maaasu.astralRecord.feature.account.service.AccountService;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.item.service.ItemService;
import io.github.maaasu.astralRecord.feature.player.AccountModeGuard;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.feature.playerclass.PlayerClassService;
import io.github.maaasu.astralRecord.feature.quest.model.QuestBoardDefinition;
import io.github.maaasu.astralRecord.feature.quest.model.QuestCompletionMode;
import io.github.maaasu.astralRecord.feature.quest.model.QuestDefinition;
import io.github.maaasu.astralRecord.feature.quest.model.QuestDisplayState;
import io.github.maaasu.astralRecord.feature.quest.model.QuestItemStackDefinition;
import io.github.maaasu.astralRecord.feature.quest.model.QuestObjectiveDefinition;
import io.github.maaasu.astralRecord.feature.quest.model.QuestObjectiveType;
import io.github.maaasu.astralRecord.feature.quest.model.QuestPlayerState;
import io.github.maaasu.astralRecord.feature.quest.model.QuestProgress;
import io.github.maaasu.astralRecord.feature.quest.model.QuestRepeatMode;
import io.github.maaasu.astralRecord.feature.quest.model.QuestRequirementDefinition;
import io.github.maaasu.astralRecord.feature.quest.repository.QuestBoardRepository;
import io.github.maaasu.astralRecord.feature.quest.repository.QuestDefinitionRepository;
import io.github.maaasu.astralRecord.feature.quest.repository.QuestPlayerStateRepository;
import io.github.maaasu.astralRecord.feature.status.model.StatusType;
import io.github.maaasu.astralRecord.feature.status.service.StatusService;
import io.github.maaasu.astralRecord.infrastructure.util.ColorCodeUtil;
import io.github.maaasu.astralRecord.shared.effect.ParticleDisplayService;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class QuestService {
    private static final int DEFAULT_MAX_ACTIVE_QUESTS = 3;
    private static final String REWARD_SOURCE = "quest_reward";

    private final QuestDefinitionRepository questRepository;
    private final QuestBoardRepository boardRepository;
    private final QuestPlayerStateRepository stateRepository;
    private final ItemService itemService;
    private final InventoryService inventoryService;
    private final AccountService accountService;
    private final PlayerClassService playerClassService;
    private final StatusService statusService;
    private final ParticleDisplayService particleDisplayService;
    private final Map<String, QuestDefinition> quests = new LinkedHashMap<>();
    private final Map<String, QuestBoardDefinition> boards = new LinkedHashMap<>();
    private final Map<UUID, QuestPlayerState> states = new LinkedHashMap<>();

    public QuestService(
        @NotNull QuestDefinitionRepository questRepository,
        @NotNull QuestBoardRepository boardRepository,
        @NotNull QuestPlayerStateRepository stateRepository,
        @NotNull ItemService itemService,
        @NotNull InventoryService inventoryService,
        @NotNull AccountService accountService,
        @NotNull PlayerClassService playerClassService,
        @NotNull StatusService statusService,
        @NotNull ParticleDisplayService particleDisplayService
    ) {
        this.questRepository = questRepository;
        this.boardRepository = boardRepository;
        this.stateRepository = stateRepository;
        this.itemService = itemService;
        this.inventoryService = inventoryService;
        this.accountService = accountService;
        this.playerClassService = playerClassService;
        this.statusService = statusService;
        this.particleDisplayService = particleDisplayService;
    }

    public int loadAll() {
        quests.clear();
        boards.clear();
        for (QuestDefinition quest : questRepository.findAll()) {
            quests.put(quest.id(), quest);
        }
        for (QuestBoardDefinition board : boardRepository.findAll()) {
            boards.put(board.id(), board);
        }
        return quests.size();
    }

    public @Nullable QuestDefinition findQuest(@NotNull String questId) {
        return quests.get(stripPrefix(questId));
    }

    public @Nullable QuestBoardDefinition findBoard(@NotNull String boardId) {
        return boards.get(stripPrefix(boardId));
    }

    public @NotNull List<QuestDefinition> activeQuests(@NotNull AstPlayer player) {
        QuestPlayerState state = state(player);
        return state.activeQuests().keySet().stream()
            .map(quests::get)
            .filter(quest -> quest != null)
            .toList();
    }

    public @Nullable QuestProgress progress(@NotNull AstPlayer player, @NotNull String questId) {
        return state(player).activeQuests().get(stripPrefix(questId));
    }

    public @NotNull QuestDisplayState displayState(@NotNull AstPlayer player, @NotNull QuestDefinition quest) {
        QuestPlayerState state = state(player);
        QuestProgress progress = state.activeQuests().get(quest.id());
        if (progress != null) {
            return progress.readyToTurnIn() ? QuestDisplayState.READY_TO_TURN_IN : QuestDisplayState.IN_PROGRESS;
        }
        long now = System.currentTimeMillis();
        long cooldownUntil = state.cooldownUntil().getOrDefault(quest.id(), 0L);
        if (cooldownUntil > now) {
            return QuestDisplayState.COOLDOWN;
        }
        if (quest.repeatMode() == QuestRepeatMode.ONCE && state.completedAt().containsKey(quest.id())) {
            return QuestDisplayState.COMPLETED;
        }
        return canMeetRequirements(player, quest) ? QuestDisplayState.AVAILABLE : QuestDisplayState.LOCKED;
    }

    public long cooldownRemainingSeconds(@NotNull AstPlayer player, @NotNull QuestDefinition quest) {
        long cooldownUntil = state(player).cooldownUntil().getOrDefault(quest.id(), 0L);
        return Math.max(0L, (cooldownUntil - System.currentTimeMillis() + 999L) / 1000L);
    }

    public int maxActiveQuests(@NotNull AstPlayer player) {
        double statusValue = statusService.getStatus(player).getMaxValue(StatusType.QUEST_LIMIT);
        return Math.max(DEFAULT_MAX_ACTIVE_QUESTS, (int) Math.floor(statusValue));
    }

    public boolean accept(@NotNull AstPlayer player, @NotNull QuestDefinition quest, @Nullable String npcId) {
        if (!AccountModeGuard.isGameplayPlayer(player)) {
            return false;
        }
        QuestPlayerState state = state(player);
        QuestDisplayState displayState = displayState(player, quest);
        if (displayState != QuestDisplayState.AVAILABLE) {
            send(player, PlayerMsgId.P_6600);
            return false;
        }
        if (state.activeQuests().size() >= maxActiveQuests(player)) {
            send(player, PlayerMsgId.P_6601);
            return false;
        }
        if (!consumeRequirements(player, quest)) {
            send(player, PlayerMsgId.P_6602);
            return false;
        }
        state.activeQuests().put(quest.id(), QuestProgress.start(quest, stripNullablePrefix(npcId)));
        save(state);
        send(player, PlayerMsgId.P_6603, quest.name());
        player.getBukkit().playSound(player.getBukkit().getLocation(), Sound.UI_TOAST_IN, SoundCategory.PLAYERS, 0.7F, 1.1F);
        return true;
    }

    public boolean abandon(@NotNull AstPlayer player, @NotNull String questId) {
        QuestPlayerState state = state(player);
        QuestProgress removed = state.activeQuests().remove(stripPrefix(questId));
        if (removed == null) {
            return false;
        }
        save(state);
        QuestDefinition quest = quests.get(removed.questId());
        send(player, PlayerMsgId.P_6604, quest == null ? removed.questId() : quest.name());
        player.getBukkit().playSound(player.getBukkit().getLocation(), Sound.UI_BUTTON_CLICK, SoundCategory.PLAYERS, 0.55F, 0.75F);
        return true;
    }

    public boolean turnIn(@NotNull AstPlayer player, @NotNull QuestDefinition quest, @Nullable String npcId) {
        QuestPlayerState state = state(player);
        QuestProgress progress = state.activeQuests().get(quest.id());
        if (progress == null || !progress.readyToTurnIn()) {
            return false;
        }
        String requiredNpc = quest.turnInNpcId() == null ? progress.acceptedNpcId() : quest.turnInNpcId();
        if (quest.completionMode() == QuestCompletionMode.NPC
            && requiredNpc != null
            && (npcId == null || !requiredNpc.equalsIgnoreCase(stripPrefix(npcId)))) {
            send(player, PlayerMsgId.P_6605);
            return false;
        }
        return complete(player, state, quest);
    }

    public void recordMobKill(@NotNull AstPlayer player, @NotNull String mobId) {
        recordObjective(player, QuestObjectiveType.KILL_MOB, mobId);
    }

    public void recordGathering(@NotNull AstPlayer player, @NotNull String gatheringId) {
        recordObjective(player, QuestObjectiveType.GATHERING, gatheringId);
    }

    private void recordObjective(@NotNull AstPlayer player, @NotNull QuestObjectiveType type, @NotNull String targetId) {
        QuestPlayerState state = state(player);
        boolean changed = false;
        for (QuestProgress progress : new ArrayList<>(state.activeQuests().values())) {
            QuestDefinition quest = quests.get(progress.questId());
            if (quest == null || progress.readyToTurnIn()) {
                continue;
            }
            for (QuestObjectiveDefinition objective : quest.objectives()) {
                if (objective.type() != type || !objective.targetId().equalsIgnoreCase(stripPrefix(targetId))) {
                    continue;
                }
                int next = Math.min(objective.amount(), progress.progress(objective.id()) + 1);
                if (next != progress.progress(objective.id())) {
                    progress.setProgress(objective.id(), next);
                    changed = true;
                }
            }
            if (isComplete(quest, progress)) {
                if (quest.isAutoReward()) {
                    complete(player, state, quest);
                } else {
                    progress.readyToTurnIn(true);
                    changed = true;
                    notifyReady(player, quest);
                }
            }
        }
        if (changed) {
            save(state);
        }
    }

    private boolean isComplete(@NotNull QuestDefinition quest, @NotNull QuestProgress progress) {
        for (QuestObjectiveDefinition objective : quest.objectives()) {
            if (progress.progress(objective.id()) < objective.amount()) {
                return false;
            }
        }
        return true;
    }

    private boolean complete(@NotNull AstPlayer player, @NotNull QuestPlayerState state, @NotNull QuestDefinition quest) {
        if (!canReceiveRewards(player, quest)) {
            send(player, PlayerMsgId.P_6606);
            return false;
        }
        state.activeQuests().remove(quest.id());
        long now = System.currentTimeMillis();
        state.completedAt().put(quest.id(), now);
        if (quest.repeatMode() == QuestRepeatMode.COOLDOWN && quest.cooldownSeconds() > 0L) {
            state.cooldownUntil().put(quest.id(), now + quest.cooldownSeconds() * 1000L);
        }
        grantRewards(player, quest);
        save(state);
        notifyComplete(player, quest);
        return true;
    }

    private void grantRewards(@NotNull AstPlayer player, @NotNull QuestDefinition quest) {
        if (quest.rewards().exp() > 0) {
            AccountExperienceResult result = accountService.grantExperienceCached(
                player.getAccount(),
                quest.rewards().exp(),
                player.getUser().getUuid()
            );
            player.setAccount(result.updatedAccount());
            playerClassService.grantClassExperience(player, quest.rewards().exp());
        }
        if (quest.rewards().gold() > 0L) {
            inventoryService.addGold(player, quest.rewards().gold());
        }
        for (QuestItemStackDefinition item : quest.rewards().items()) {
            ItemModel model = resolveItem(item);
            if (model == null) {
                continue;
            }
            inventoryService.addItemToNormalInventory(player, model, item.amount(), REWARD_SOURCE);
        }
        inventoryService.saveNow(player.getAccount().getUuid());
    }

    private boolean canReceiveRewards(@NotNull AstPlayer player, @NotNull QuestDefinition quest) {
        for (QuestItemStackDefinition item : quest.rewards().items()) {
            ItemModel model = resolveItem(item);
            if (model != null && !inventoryService.canAddItemToNormalInventory(player, model, item.amount())) {
                return false;
            }
        }
        return true;
    }

    private boolean canMeetRequirements(@NotNull AstPlayer player, @NotNull QuestDefinition quest) {
        UUID accountId = player.getAccount().getUuid();
        for (QuestRequirementDefinition requirement : quest.requirements()) {
            if (inventoryService.getNormalItemAmount(accountId, requirement.item().itemId()) < requirement.item().amount()) {
                return false;
            }
        }
        return true;
    }

    private boolean consumeRequirements(@NotNull AstPlayer player, @NotNull QuestDefinition quest) {
        if (!canMeetRequirements(player, quest)) {
            return false;
        }
        UUID accountId = player.getAccount().getUuid();
        for (QuestRequirementDefinition requirement : quest.requirements()) {
            if (requirement.consume()
                && !inventoryService.consumeNormalItem(accountId, requirement.item().itemId(), requirement.item().amount())) {
                return false;
            }
        }
        inventoryService.saveNow(accountId);
        return true;
    }

    public @NotNull String resolveItemDisplayName(@NotNull QuestItemStackDefinition item) {
        ItemModel model = resolveItem(item);
        if (model == null || model.getName() == null || model.getName().isBlank()) {
            return item.itemId();
        }
        String displayName = ColorCodeUtil.stripColor(ColorCodeUtil.translateAlternateColorCodes(model.getName()));
        return displayName == null || displayName.isBlank() ? item.itemId() : displayName;
    }

    private @Nullable ItemModel resolveItem(@NotNull QuestItemStackDefinition item) {
        ItemModel model = itemService.findLoadedById(item.itemId());
        return model != null ? model : itemService.loadItem(item.itemId(), item.category());
    }

    private @NotNull QuestPlayerState state(@NotNull AstPlayer player) {
        UUID accountId = player.getAccount().getUuid();
        return states.computeIfAbsent(accountId, stateRepository::load);
    }

    private void save(@NotNull QuestPlayerState state) {
        stateRepository.save(state);
    }

    private void notifyReady(@NotNull AstPlayer player, @NotNull QuestDefinition quest) {
        send(player, PlayerMsgId.P_6607, quest.name());
        playQuestEffect(player.getBukkit());
    }

    private void notifyComplete(@NotNull AstPlayer player, @NotNull QuestDefinition quest) {
        send(player, PlayerMsgId.P_6608, quest.name());
        playQuestEffect(player.getBukkit());
    }

    private void playQuestEffect(@NotNull Player player) {
        Location location = player.getLocation().add(0.0D, 1.0D, 0.0D);
        player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, SoundCategory.PLAYERS, 0.75F, 1.0F);
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, SoundCategory.PLAYERS, 0.35F, 1.35F);
        particleDisplayService.spawnForNearbyViewers(location, SharedParticleDefinitions.PLAYER_LEVEL_UP_TOTEM);
        particleDisplayService.spawnForNearbyViewers(location, SharedParticleDefinitions.PLAYER_LEVEL_UP_END_ROD);
    }

    private void send(@NotNull AstPlayer player, @NotNull PlayerMsgId msgId, Object... args) {
        PlayerMessageService.getInstance().send(player, msgId, args);
    }

    private @NotNull String stripPrefix(@NotNull String raw) {
        String trimmed = raw.trim();
        int index = trimmed.indexOf(':');
        return (index < 0 ? trimmed : trimmed.substring(index + 1)).trim();
    }

    private @Nullable String stripNullablePrefix(@Nullable String raw) {
        return raw == null || raw.isBlank() ? null : stripPrefix(raw);
    }
}
