package io.github.maaasu.astralRecord.feature.skill.service;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.skill.executor.SkillExecutor;
import io.github.maaasu.astralRecord.feature.skill.model.LearnedSkillInstance;
import io.github.maaasu.astralRecord.feature.skill.model.PassiveSkillContext;
import io.github.maaasu.astralRecord.feature.skill.model.PassiveSkillStatusModifier;
import io.github.maaasu.astralRecord.feature.skill.model.ResolvedLearnedSkill;
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
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/** 習得個体単位でパッシブスキルの有効化状態を管理します。 */
public final class PassiveSkillService {
    public static final int BASE_PASSIVE_SLOT_COUNT = 5;
    public static final int MAX_PASSIVE_SLOT_COUNT = 9;
    private static final long TICK_INTERVAL = 1L;
    private static final long CLEANUP_INTERVAL_TICKS = 20L;
    private static final int MAX_DIRTY_RECONCILES_PER_TICK = 2;

    private final AstralRecord plugin;
    private final SkillService skillService;
    private final SkillBindPresetService presetService;
    private final SkillOwnershipService ownershipService;
    private final SkillPermissionService permissionService;
    private final LearnedSkillResolver learnedSkillResolver;
    private final Map<UUID, PlayerPassiveState> activeStates = new ConcurrentHashMap<>();
    private final Set<UUID> reconciledAccounts = ConcurrentHashMap.newKeySet();
    private final Set<UUID> dirtyAccounts = ConcurrentHashMap.newKeySet();
    private final Queue<UUID> dirtyQueue = new ConcurrentLinkedQueue<>();

    private StatusService statusService;
    private BukkitTask task;
    private long tickCounter;
    private int tickingPassiveCount;

    public PassiveSkillService(
        @NotNull AstralRecord plugin,
        @NotNull SkillService skillService,
        @NotNull SkillBindPresetService presetService,
        @NotNull SkillOwnershipService ownershipService,
        @NotNull SkillPermissionService permissionService,
        @NotNull LearnedSkillResolver learnedSkillResolver
    ) {
        this.plugin = plugin;
        this.skillService = skillService;
        this.presetService = presetService;
        this.ownershipService = ownershipService;
        this.permissionService = permissionService;
        this.learnedSkillResolver = learnedSkillResolver;
    }

    public void setStatusService(@NotNull StatusService statusService) {
        this.statusService = statusService;
    }

    public int activePassiveSlotCount(@NotNull AstPlayer player) {
        double slotStatus = statusService == null
            ? player.getStatusSnapshot().getMaxValue(StatusType.PASSIVE_SKILL_SLOTS)
            : statusService.getValueExcludingPassiveSkills(player, StatusType.PASSIVE_SKILL_SLOTS);
        int bonus = (int) Math.floor(
            Math.max(0.0D, slotStatus)
        );
        return Math.min(MAX_PASSIVE_SLOT_COUNT, BASE_PASSIVE_SLOT_COUNT + bonus);
    }

    public void start() {
        if (task == null) {
            task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, TICK_INTERVAL, TICK_INTERVAL);
        }
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        for (Map.Entry<UUID, PlayerPassiveState> entry : activeStates.entrySet()) {
            AstPlayer player = findOnlineAstPlayer(entry.getKey());
            if (player != null) deactivateAll(player, entry.getValue());
        }
        activeStates.clear();
        reconciledAccounts.clear();
        dirtyAccounts.clear();
        dirtyQueue.clear();
        tickingPassiveCount = 0;
    }

    public void reconcileNow(@NotNull AstPlayer player) {
        reconcileNow(player, true);
    }

    public void reconcileNow(@NotNull AstPlayer player, boolean refreshStatus) {
        dirtyAccounts.remove(player.getAccount().getUuid());
        reconcile(player, refreshStatus);
    }

    public void markDirty(@NotNull AstPlayer player) {
        markDirty(player.getAccount().getUuid());
    }

    public void markDirty(@NotNull UUID accountId) {
        reconciledAccounts.remove(accountId);
        if (dirtyAccounts.add(accountId)) dirtyQueue.offer(accountId);
    }

    /** スキルツリー許可の変更後は個体一覧を再評価します。 */
    public void reconcileSkillPermissionDelta(
        @NotNull AstPlayer player,
        @NotNull Set<String> addedSkillIds,
        @NotNull Set<String> removedSkillIds,
        boolean refreshStatus
    ) {
        reconcileNow(player, refreshStatus);
    }

    public double getStatusBonus(
        @NotNull AstPlayer player,
        @NotNull StatusType statusType,
        double baseValue
    ) {
        reconcileIfNeeded(player);
        PlayerPassiveState state = activeStates.get(player.getAccount().getUuid());
        if (state == null) return 0.0D;

        double flat = 0.0D;
        double scalar = 0.0D;
        for (ActivePassiveSkill active : state.skillsByInstanceId.values()) {
            flat += active.statusBonuses().getOrDefault(statusType, 0.0D);
            PassiveSkillContext context = context(player, active);
            for (PassiveSkillStatusModifier modifier : active.executor().passiveStatusModifiers(context)) {
                if (modifier.statusType() != statusType) continue;
                if (modifier.type() == StatusModifierType.SCALAR) scalar += modifier.value();
                else flat += modifier.value();
            }
        }
        return flat + baseValue * scalar;
    }

    private void tick() {
        tickCounter++;
        if (tickCounter % CLEANUP_INTERVAL_TICKS == 0L) cleanupOfflinePlayers();
        processDirtyReconciles();
        if (tickingPassiveCount <= 0) return;

        for (Player bukkitPlayer : Bukkit.getOnlinePlayers()) {
            AstPlayer player = AstPlayerCache.get(bukkitPlayer);
            if (player == null) continue;
            PlayerPassiveState state = activeStates.get(player.getAccount().getUuid());
            if (state == null) continue;
            for (ActivePassiveSkill active : List.copyOf(state.skillsByInstanceId.values())) {
                if (!active.shouldTick(tickCounter)) continue;
                active.markTicked(tickCounter);
                active.executor().onTick(context(player, active));
            }
        }
    }

    private void cleanupOfflinePlayers() {
        for (Map.Entry<UUID, PlayerPassiveState> entry : List.copyOf(activeStates.entrySet())) {
            if (findOnlineAstPlayer(entry.getKey()) != null) continue;
            decrementTickingPassiveCount(entry.getValue());
            activeStates.remove(entry.getKey());
            reconciledAccounts.remove(entry.getKey());
            dirtyAccounts.remove(entry.getKey());
        }
    }

    private void processDirtyReconciles() {
        int processed = 0;
        while (processed < MAX_DIRTY_RECONCILES_PER_TICK) {
            UUID accountId = dirtyQueue.poll();
            if (accountId == null) return;
            if (!dirtyAccounts.remove(accountId)) continue;
            AstPlayer player = findOnlineAstPlayer(accountId);
            if (player == null) {
                reconciledAccounts.remove(accountId);
                continue;
            }
            reconcile(player, true);
            processed++;
        }
    }

    private void reconcileIfNeeded(@NotNull AstPlayer player) {
        UUID accountId = player.getAccount().getUuid();
        if (reconciledAccounts.contains(accountId) && !dirtyAccounts.remove(accountId)) return;
        reconcile(player, false);
    }

    private void reconcile(@NotNull AstPlayer player, boolean refreshStatus) {
        UUID accountId = player.getAccount().getUuid();
        Map<String, DesiredPassive> desired = resolveDesiredPassives(player);
        PlayerPassiveState state = activeStates.computeIfAbsent(accountId, ignored -> new PlayerPassiveState());
        boolean changed = false;

        for (Map.Entry<String, ActivePassiveSkill> entry : List.copyOf(state.skillsByInstanceId.entrySet())) {
            DesiredPassive next = desired.remove(entry.getKey());
            ActivePassiveSkill current = entry.getValue();
            if (next != null && next.matches(current)) continue;
            deactivate(player, current);
            removeActiveSkill(state, entry.getKey());
            changed = true;
            if (next != null) activate(player, state, next);
        }
        for (DesiredPassive next : desired.values()) {
            activate(player, state, next);
            changed = true;
        }

        if (state.skillsByInstanceId.isEmpty()) activeStates.remove(accountId);
        reconciledAccounts.add(accountId);
        if (changed && refreshStatus && statusService != null) statusService.refreshStatus(player);
    }

    private @NotNull Map<String, DesiredPassive> resolveDesiredPassives(@NotNull AstPlayer player) {
        Set<String> boundInstanceIds = resolveEnabledBoundPassiveInstanceIds(player);
        Map<String, DesiredPassive> desired = new LinkedHashMap<>();
        for (LearnedSkillInstance learned : ownershipService.learnedSkills(player)) {
            SkillDefinition base = skillService.registry().getDefinition(learned.getSkillId());
            if (base == null || base.getKind() != SkillKind.PASSIVE) continue;
            if (!permissionService.isPermitted(player, learned.getSkillId())) continue;
            String instanceId = learned.getLearnedSkillId().toString();
            if (base.getPassiveBindRequired() && !boundInstanceIds.contains(instanceId)) continue;
            ResolvedLearnedSkill resolved = learnedSkillResolver.resolve(base, learned);
            desired.put(instanceId, new DesiredPassive(
                learned, resolved.definition(), resolved.statusBonuses(), resolved.sigilIds()
            ));
        }
        return desired;
    }

    private @NotNull Set<String> resolveEnabledBoundPassiveInstanceIds(@NotNull AstPlayer player) {
        UUID accountId = player.getAccount().getUuid();
        int selectedPresetIndex = presetService.selectedPresetIndex(accountId);
        SkillBindPreset preset = presetService.getPresets(accountId).stream()
            .filter(candidate -> candidate.isUnlocked() && candidate.getPresetIndex() == selectedPresetIndex)
            .findFirst()
            .orElse(null);
        if (preset == null) return Set.of();

        int enabledSlots = activePassiveSlotCount(player);
        Set<String> result = new LinkedHashSet<>();
        List<String> bindings = preset.getPassiveSkillSlots();
        for (int index = 0; index < Math.min(enabledSlots, bindings.size()); index++) {
            String learnedSkillId = bindings.get(index);
            if (learnedSkillId != null && !learnedSkillId.isBlank()) result.add(learnedSkillId.trim());
        }
        return result;
    }

    private void activate(AstPlayer player, PlayerPassiveState state, DesiredPassive desired) {
        SkillExecutor executor = skillService.registry().getExecutor(desired.definition().getImplementationId());
        if (executor == null) return;
        ActivePassiveSkill active = new ActivePassiveSkill(
            desired.learnedSkill(), desired.definition(), desired.statusBonuses(), executor,
            desired.sigilIds(), Instant.now(), tickCounter, Math.max(1L, executor.passiveTickIntervalTicks())
        );
        state.skillsByInstanceId.put(desired.learnedSkill().getLearnedSkillId().toString(), active);
        if (active.requiresTick()) tickingPassiveCount++;
        executor.onActivate(context(player, active));
    }

    private void deactivate(AstPlayer player, ActivePassiveSkill active) {
        active.executor().onDeactivate(context(player, active));
    }

    private void deactivateAll(AstPlayer player, PlayerPassiveState state) {
        for (ActivePassiveSkill active : state.skillsByInstanceId.values()) deactivate(player, active);
    }

    private PassiveSkillContext context(AstPlayer player, ActivePassiveSkill active) {
        return new PassiveSkillContext(
            player, active.definition(), active.activatedAt(), active.activeTicks(tickCounter),
            active.learnedSkill(), active.sigilIds()
        );
    }

    private ActivePassiveSkill removeActiveSkill(PlayerPassiveState state, String instanceId) {
        ActivePassiveSkill removed = state.skillsByInstanceId.remove(instanceId);
        if (removed != null && removed.requiresTick()) tickingPassiveCount = Math.max(0, tickingPassiveCount - 1);
        return removed;
    }

    private void decrementTickingPassiveCount(PlayerPassiveState state) {
        for (ActivePassiveSkill active : state.skillsByInstanceId.values()) {
            if (active.requiresTick()) tickingPassiveCount = Math.max(0, tickingPassiveCount - 1);
        }
    }

    private AstPlayer findOnlineAstPlayer(UUID accountId) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            AstPlayer astPlayer = AstPlayerCache.get(player);
            if (astPlayer != null && accountId.equals(astPlayer.getAccount().getUuid())) return astPlayer;
        }
        return null;
    }

    private static final class PlayerPassiveState {
        private final Map<String, ActivePassiveSkill> skillsByInstanceId = new LinkedHashMap<>();
    }

    private record DesiredPassive(
        LearnedSkillInstance learnedSkill,
        SkillDefinition definition,
        Map<StatusType, Double> statusBonuses,
        Set<String> sigilIds
    ) {
        private boolean matches(ActivePassiveSkill active) {
            return learnedSkill.equals(active.learnedSkill())
                && definition.equals(active.definition())
                && statusBonuses.equals(active.statusBonuses())
                && sigilIds.equals(active.sigilIds());
        }
    }

    private static final class ActivePassiveSkill {
        private final LearnedSkillInstance learnedSkill;
        private final SkillDefinition definition;
        private final Map<StatusType, Double> statusBonuses;
        private final Set<String> sigilIds;
        private final SkillExecutor executor;
        private final Instant activatedAt;
        private final long activatedTick;
        private final boolean requiresTick;
        private final long tickIntervalTicks;
        private long nextTickAt;

        private ActivePassiveSkill(
            LearnedSkillInstance learnedSkill,
            SkillDefinition definition,
            Map<StatusType, Double> statusBonuses,
            SkillExecutor executor,
            Set<String> sigilIds,
            Instant activatedAt,
            long activatedTick,
            long tickIntervalTicks
        ) {
            this.learnedSkill = learnedSkill;
            this.definition = definition;
            this.statusBonuses = Map.copyOf(statusBonuses);
            this.sigilIds = Set.copyOf(sigilIds);
            this.executor = executor;
            this.activatedAt = activatedAt;
            this.activatedTick = activatedTick;
            this.requiresTick = executor.requiresPassiveTick();
            this.tickIntervalTicks = tickIntervalTicks;
            this.nextTickAt = activatedTick + tickIntervalTicks;
        }

        private LearnedSkillInstance learnedSkill() { return learnedSkill; }
        private SkillDefinition definition() { return definition; }
        private Map<StatusType, Double> statusBonuses() { return statusBonuses; }
        private Set<String> sigilIds() { return sigilIds; }
        private SkillExecutor executor() { return executor; }
        private Instant activatedAt() { return activatedAt; }
        private long activeTicks(long currentTick) { return Math.max(0L, currentTick - activatedTick); }
        private boolean requiresTick() { return requiresTick; }
        private boolean shouldTick(long currentTick) { return requiresTick && currentTick >= nextTickAt; }
        private void markTicked(long currentTick) { nextTickAt = currentTick + tickIntervalTicks; }
    }
}
