package io.github.maaasu.astralRecord.feature.skill.service;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.skill.executor.SkillExecutor;
import io.github.maaasu.astralRecord.feature.skill.model.PassiveSkillContext;
import io.github.maaasu.astralRecord.feature.skill.model.PassiveSkillStatusModifier;
import io.github.maaasu.astralRecord.feature.skill.model.SkillBindPreset;
import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.feature.skill.model.SkillKind;
import io.github.maaasu.astralRecord.feature.status.model.StatusModifierType;
import io.github.maaasu.astralRecord.feature.status.model.StatusType;
import io.github.maaasu.astralRecord.feature.status.service.StatusService;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * パッシブスキルの有効化状態とライフサイクルを管理するサービスです。
 */
public final class PassiveSkillService {
    private static final long TICK_INTERVAL = 1L;
    private static final long RECONCILE_INTERVAL_TICKS = 10L;
    private static final long CLEANUP_INTERVAL_TICKS = 20L;

    private final AstralRecord plugin;
    private final SkillService skillService;
    private final SkillBindPresetService presetService;
    private final SkillOwnershipService ownershipService;
    private final Map<UUID, PlayerPassiveState> activeStates = new ConcurrentHashMap<>();

    private StatusService statusService;
    private BukkitTask task;
    private long tickCounter;
    private int tickingPassiveCount;

    /**
     * サービスを構築します。
     *
     * @param plugin scheduler 利用用プラグイン
     * @param skillService スキル定義と executor を解決するサービス
     * @param presetService スキルバインドプリセットサービス
     * @param ownershipService 所持スキル判定サービス
     */
    public PassiveSkillService(
        @NotNull AstralRecord plugin,
        @NotNull SkillService skillService,
        @NotNull SkillBindPresetService presetService,
        @NotNull SkillOwnershipService ownershipService
    ) {
        this.plugin = plugin;
        this.skillService = skillService;
        this.presetService = presetService;
        this.ownershipService = ownershipService;
    }

    /**
     * ステータス再計算連携先を設定します。
     *
     * @param statusService ステータスサービス
     */
    public void setStatusService(@NotNull StatusService statusService) {
        this.statusService = statusService;
    }

    /**
     * パッシブ管理タスクを開始します。
     */
    public void start() {
        if (task != null) {
            return;
        }
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, TICK_INTERVAL, TICK_INTERVAL);
    }

    /**
     * パッシブ管理タスクを停止し、全パッシブを解除します。
     */
    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        for (Map.Entry<UUID, PlayerPassiveState> entry : activeStates.entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            AstPlayer astPlayer = player == null ? null : AstPlayerCache.get(player);
            if (astPlayer != null) {
                deactivateAll(astPlayer, entry.getValue());
            }
        }
        activeStates.clear();
        tickingPassiveCount = 0;
    }

    /**
     * 指定プレイヤーのパッシブ状態を即時再評価します。
     *
     * @param player プレイヤー
     */
    public void reconcileNow(@NotNull AstPlayer player) {
        reconcile(player, true);
    }

    public void reconcileSkillOwnershipDelta(
        @NotNull AstPlayer player,
        @NotNull Set<String> addedSkillIds,
        @NotNull Set<String> removedSkillIds,
        boolean refreshStatus
    ) {
        UUID accountId = player.getAccount().getUuid();
        PlayerPassiveState state = activeStates.computeIfAbsent(accountId, ignored -> new PlayerPassiveState());
        Set<String> boundPassiveSkillIds = resolveBoundPassiveSkillIds(accountId);
        boolean changed = false;

        for (String skillId : removedSkillIds) {
            ActivePassiveSkill current = removeActiveSkill(state, skillId);
            if (current == null) {
                continue;
            }
            deactivate(player, current);
            changed = true;
        }

        for (String skillId : addedSkillIds) {
            if (state.skillsById.containsKey(skillId)) {
                continue;
            }
            SkillDefinition definition = skillService.registry().getDefinition(skillId);
            if (definition == null || definition.getKind() != SkillKind.PASSIVE) {
                continue;
            }
            if (definition.getPassiveBindRequired() && !boundPassiveSkillIds.contains(skillId)) {
                continue;
            }
            activate(player, state, definition);
            changed = true;
        }

        if (state.skillsById.isEmpty()) {
            activeStates.remove(accountId);
        }
        if (changed && refreshStatus && statusService != null) {
            statusService.refreshStatus(player);
        }
    }

    /**
     * 指定プレイヤーの有効中パッシブからステータス補正を取得します。
     *
     * @param player プレイヤー
     * @param statusType 対象ステータス
     * @param baseValue FLAT 適用後の基準値
     * @return 総補正値
     */
    public double getStatusBonus(
        @NotNull AstPlayer player,
        @NotNull StatusType statusType,
        double baseValue
    ) {
        reconcile(player, false);
        PlayerPassiveState state = activeStates.get(player.getAccount().getUuid());
        if (state == null) {
            return 0.0D;
        }

        double flat = 0.0D;
        double scalar = 0.0D;
        for (ActivePassiveSkill activeSkill : state.skillsById.values()) {
            PassiveSkillContext context = new PassiveSkillContext(
                player,
                activeSkill.definition(),
                activeSkill.activatedAt(),
                activeSkill.activeTicks(tickCounter)
            );
            for (PassiveSkillStatusModifier modifier : activeSkill.executor().passiveStatusModifiers(context)) {
                if (modifier.statusType() != statusType) {
                    continue;
                }
                if (modifier.type() == StatusModifierType.SCALAR) {
                    scalar += modifier.value();
                } else {
                    flat += modifier.value();
                }
            }
        }
        return flat + (baseValue * scalar);
    }

    private void tick() {
        tickCounter++;
        if (tickCounter % CLEANUP_INTERVAL_TICKS == 0L) {
            cleanupOfflinePlayers();
        }
        if (tickCounter % RECONCILE_INTERVAL_TICKS == 0L) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                AstPlayer astPlayer = AstPlayerCache.get(player);
                if (astPlayer != null) {
                    reconcile(astPlayer, true);
                }
            }
        }

        if (tickingPassiveCount <= 0) {
            return;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            AstPlayer astPlayer = AstPlayerCache.get(player);
            if (astPlayer == null) {
                continue;
            }
            PlayerPassiveState state = activeStates.get(astPlayer.getAccount().getUuid());
            if (state == null) {
                continue;
            }
            tickPassives(astPlayer, state);
        }
    }

    private void cleanupOfflinePlayers() {
        for (Map.Entry<UUID, PlayerPassiveState> entry : List.copyOf(activeStates.entrySet())) {
            Player player = Bukkit.getPlayer(entry.getKey());
            AstPlayer astPlayer = player == null ? null : AstPlayerCache.get(player);
            if (astPlayer != null) {
                continue;
            }
            decrementTickingPassiveCount(entry.getValue());
            activeStates.remove(entry.getKey());
        }
    }

    private void tickPassives(@NotNull AstPlayer player, @NotNull PlayerPassiveState state) {
        for (ActivePassiveSkill activeSkill : List.copyOf(state.skillsById.values())) {
            if (!activeSkill.shouldTick(tickCounter)) {
                continue;
            }
            activeSkill.markTicked(tickCounter);
            activeSkill.executor().onTick(new PassiveSkillContext(
                player,
                activeSkill.definition(),
                activeSkill.activatedAt(),
                activeSkill.activeTicks(tickCounter)
            ));
        }
    }

    private void reconcile(@NotNull AstPlayer player, boolean refreshStatus) {
        UUID accountId = player.getAccount().getUuid();
        Map<String, SkillDefinition> desired = resolveDesiredPassiveDefinitions(player);
        PlayerPassiveState state = activeStates.computeIfAbsent(accountId, ignored -> new PlayerPassiveState());
        boolean changed = false;

        for (Map.Entry<String, ActivePassiveSkill> entry : List.copyOf(state.skillsById.entrySet())) {
            ActivePassiveSkill current = entry.getValue();
            SkillDefinition desiredDefinition = desired.remove(entry.getKey());
            if (desiredDefinition != null && desiredDefinition.equals(current.definition())) {
                continue;
            }
            deactivate(player, current);
            removeActiveSkill(state, entry.getKey());
            changed = true;
            if (desiredDefinition != null) {
                activate(player, state, desiredDefinition);
            }
        }

        for (SkillDefinition definition : desired.values()) {
            activate(player, state, definition);
            changed = true;
        }

        if (state.skillsById.isEmpty()) {
            activeStates.remove(accountId);
        }
        if (changed && refreshStatus && statusService != null) {
            statusService.refreshStatus(player);
        }
    }

    private @NotNull Map<String, SkillDefinition> resolveDesiredPassiveDefinitions(@NotNull AstPlayer player) {
        Set<String> ownedSkillIds = ownershipService.ownedSkillIds(player);
        Set<String> boundPassiveSkillIds = resolveBoundPassiveSkillIds(player.getAccount().getUuid());
        Map<String, SkillDefinition> desired = new LinkedHashMap<>();

        for (String skillId : ownedSkillIds) {
            SkillDefinition definition = skillService.registry().getDefinition(skillId);
            if (definition == null || definition.getKind() != SkillKind.PASSIVE) {
                continue;
            }
            if (definition.getPassiveBindRequired() && !boundPassiveSkillIds.contains(skillId)) {
                continue;
            }
            desired.put(skillId, definition);
        }
        return desired;
    }

    private @NotNull Set<String> resolveBoundPassiveSkillIds(@NotNull UUID accountId) {
        int selectedPresetIndex = presetService.selectedPresetIndex(accountId);
        SkillBindPreset selectedPreset = presetService.getPresets(accountId).stream()
            .filter(preset -> preset.isUnlocked() && preset.getPresetIndex() == selectedPresetIndex)
            .findFirst()
            .orElse(null);
        if (selectedPreset == null) {
            return Set.of();
        }
        Set<String> skillIds = new LinkedHashSet<>();
        for (String skillId : selectedPreset.getPassiveSkillSlots()) {
            if (skillId != null && !skillId.isBlank()) {
                skillIds.add(skillId.trim());
            }
        }
        return skillIds;
    }

    private void activate(
        @NotNull AstPlayer player,
        @NotNull PlayerPassiveState state,
        @NotNull SkillDefinition definition
    ) {
        SkillExecutor executor = skillService.registry().getExecutor(definition.getImplementationId());
        if (executor == null) {
            return;
        }
        ActivePassiveSkill activeSkill = new ActivePassiveSkill(
            definition,
            executor,
            Instant.now(),
            tickCounter,
            Math.max(1L, executor.passiveTickIntervalTicks())
        );
        state.skillsById.put(definition.getId(), activeSkill);
        if (activeSkill.requiresTick()) {
            tickingPassiveCount++;
        }
        executor.onActivate(new PassiveSkillContext(
            player,
            definition,
            activeSkill.activatedAt(),
            activeSkill.activeTicks(tickCounter)
        ));
    }

    private void deactivate(@NotNull AstPlayer player, @NotNull ActivePassiveSkill activeSkill) {
        activeSkill.executor().onDeactivate(new PassiveSkillContext(
            player,
            activeSkill.definition(),
            activeSkill.activatedAt(),
            activeSkill.activeTicks(tickCounter)
        ));
    }

    private void deactivateAll(@NotNull AstPlayer player, @NotNull PlayerPassiveState state) {
        for (ActivePassiveSkill activeSkill : state.skillsById.values()) {
            deactivate(player, activeSkill);
        }
    }

    private ActivePassiveSkill removeActiveSkill(@NotNull PlayerPassiveState state, @NotNull String skillId) {
        ActivePassiveSkill removed = state.skillsById.remove(skillId);
        if (removed != null && removed.requiresTick()) {
            tickingPassiveCount = Math.max(0, tickingPassiveCount - 1);
        }
        return removed;
    }

    private void decrementTickingPassiveCount(@NotNull PlayerPassiveState state) {
        for (ActivePassiveSkill activeSkill : state.skillsById.values()) {
            if (activeSkill.requiresTick()) {
                tickingPassiveCount = Math.max(0, tickingPassiveCount - 1);
            }
        }
    }

    private static final class PlayerPassiveState {
        private final Map<String, ActivePassiveSkill> skillsById = new LinkedHashMap<>();
    }

    private static final class ActivePassiveSkill {
        private final SkillDefinition definition;
        private final SkillExecutor executor;
        private final Instant activatedAt;
        private final long activatedTick;
        private final boolean requiresTick;
        private final long tickIntervalTicks;
        private long nextTickAt;

        private ActivePassiveSkill(
            @NotNull SkillDefinition definition,
            @NotNull SkillExecutor executor,
            @NotNull Instant activatedAt,
            long activatedTick,
            long tickIntervalTicks
        ) {
            this.definition = definition;
            this.executor = executor;
            this.activatedAt = activatedAt;
            this.activatedTick = activatedTick;
            this.requiresTick = executor.requiresPassiveTick();
            this.tickIntervalTicks = tickIntervalTicks;
            this.nextTickAt = activatedTick + tickIntervalTicks;
        }

        private @NotNull SkillDefinition definition() {
            return definition;
        }

        private @NotNull SkillExecutor executor() {
            return executor;
        }

        private @NotNull Instant activatedAt() {
            return activatedAt;
        }

        private long activeTicks(long currentTick) {
            return Math.max(0L, currentTick - activatedTick);
        }

        private boolean requiresTick() {
            return requiresTick;
        }

        private boolean shouldTick(long currentTick) {
            return requiresTick && currentTick >= nextTickAt;
        }

        private void markTicked(long currentTick) {
            nextTickAt = currentTick + tickIntervalTicks;
        }
    }
}
