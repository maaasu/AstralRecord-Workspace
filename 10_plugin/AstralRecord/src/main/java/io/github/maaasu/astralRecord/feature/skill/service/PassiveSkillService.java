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

    private final AstralRecord plugin;
    private final SkillService skillService;
    private final SkillBindPresetService presetService;
    private final SkillOwnershipService ownershipService;
    private final Map<UUID, PlayerPassiveState> activeStates = new ConcurrentHashMap<>();

    private StatusService statusService;
    private BukkitTask task;
    private long tickCounter;

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
    }

    /**
     * 指定プレイヤーのパッシブ状態を即時再評価します。
     *
     * @param player プレイヤー
     */
    public void reconcileNow(@NotNull AstPlayer player) {
        reconcile(player, true);
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
            SkillExecutor executor = skillService.registry().getExecutor(activeSkill.definition().getImplementationId());
            if (executor == null) {
                continue;
            }
            PassiveSkillContext context = new PassiveSkillContext(
                player,
                activeSkill.definition(),
                activeSkill.activatedAt(),
                activeSkill.activeTicks()
            );
            for (PassiveSkillStatusModifier modifier : executor.passiveStatusModifiers(context)) {
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
        cleanupOfflinePlayers();
        if (tickCounter % RECONCILE_INTERVAL_TICKS == 0L) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                AstPlayer astPlayer = AstPlayerCache.get(player);
                if (astPlayer != null) {
                    reconcile(astPlayer, true);
                }
            }
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
            activeStates.remove(entry.getKey());
        }
    }

    private void tickPassives(@NotNull AstPlayer player, @NotNull PlayerPassiveState state) {
        for (Map.Entry<String, ActivePassiveSkill> entry : List.copyOf(state.skillsById.entrySet())) {
            ActivePassiveSkill next = entry.getValue().incrementTick();
            state.skillsById.put(entry.getKey(), next);
            SkillExecutor executor = skillService.registry().getExecutor(next.definition().getImplementationId());
            if (executor == null) {
                continue;
            }
            executor.onTick(new PassiveSkillContext(
                player,
                next.definition(),
                next.activatedAt(),
                next.activeTicks()
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
            state.skillsById.remove(entry.getKey());
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
        ActivePassiveSkill activeSkill = new ActivePassiveSkill(definition, Instant.now(), 0L);
        state.skillsById.put(definition.getId(), activeSkill);
        executor.onActivate(new PassiveSkillContext(
            player,
            definition,
            activeSkill.activatedAt(),
            activeSkill.activeTicks()
        ));
    }

    private void deactivate(@NotNull AstPlayer player, @NotNull ActivePassiveSkill activeSkill) {
        SkillExecutor executor = skillService.registry().getExecutor(activeSkill.definition().getImplementationId());
        if (executor == null) {
            return;
        }
        executor.onDeactivate(new PassiveSkillContext(
            player,
            activeSkill.definition(),
            activeSkill.activatedAt(),
            activeSkill.activeTicks()
        ));
    }

    private void deactivateAll(@NotNull AstPlayer player, @NotNull PlayerPassiveState state) {
        for (ActivePassiveSkill activeSkill : state.skillsById.values()) {
            deactivate(player, activeSkill);
        }
    }

    private static final class PlayerPassiveState {
        private final Map<String, ActivePassiveSkill> skillsById = new LinkedHashMap<>();
    }

    private record ActivePassiveSkill(
        @NotNull SkillDefinition definition,
        @NotNull Instant activatedAt,
        long activeTicks
    ) {
        private @NotNull ActivePassiveSkill incrementTick() {
            return new ActivePassiveSkill(definition, activatedAt, activeTicks + 1L);
        }
    }
}
