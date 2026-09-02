package io.github.maaasu.astralRecord.feature.skill.service;

import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.skill.model.LearnedSkillInstance;
import io.github.maaasu.astralRecord.feature.skill.model.LearnedSkillMaterialMutationResult;
import io.github.maaasu.astralRecord.feature.skill.model.LearnedSkillSigilDetachResult;
import io.github.maaasu.astralRecord.feature.skill.model.LearnedSkillMutationException;
import io.github.maaasu.astralRecord.feature.skill.repository.LearnedSkillRepository;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 習得済みスキル個体のキャッシュと API 更新を扱います。
 *
 * <p>同じ skillId を持つ個体を複数保持できるため、参照・バインド・強化は常に
 * learnedSkillId を正本として行います。</p>
 */
public final class LearnedSkillService {
    private final Plugin plugin;
    private final LearnedSkillRepository repository;
    private final InventoryService inventoryService;
    private final Map<UUID, List<LearnedSkillInstance>> skillsByAccount = new ConcurrentHashMap<>();
    private final Map<UUID, AtomicBoolean> mutationLocks = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> sessionTokens = new ConcurrentHashMap<>();

    public LearnedSkillService(
        @NotNull Plugin plugin,
        @NotNull LearnedSkillRepository repository,
        @NotNull InventoryService inventoryService
    ) {
        this.plugin = plugin;
        this.repository = repository;
        this.inventoryService = inventoryService;
    }

    public @NotNull List<LearnedSkillInstance> loadInitialSkills(@NotNull UUID accountId) {
        return normalize(repository.findByAccountId(accountId));
    }

    public void applyInitialSkills(
        @NotNull UUID accountId,
        @NotNull List<LearnedSkillInstance> skills
    ) {
        skillsByAccount.put(accountId, normalize(skills));
        sessionTokens.put(accountId, UUID.randomUUID());
    }

    public void invalidate(@NotNull UUID accountId) {
        skillsByAccount.remove(accountId);
        mutationLocks.computeIfPresent(accountId, (ignored, lock) -> lock.get() ? lock : null);
        sessionTokens.remove(accountId);
    }

    public boolean hasLoadedSkills(@NotNull UUID accountId) {
        return skillsByAccount.containsKey(accountId);
    }

    public @NotNull List<LearnedSkillInstance> getLearnedSkills(@NotNull UUID accountId) {
        return new ArrayList<>(skillsByAccount.getOrDefault(accountId, List.of()));
    }

    public @Nullable LearnedSkillInstance findInstance(@NotNull UUID accountId, @NotNull UUID learnedSkillId) {
        return skillsByAccount.getOrDefault(accountId, List.of()).stream()
            .filter(skill -> skill.getLearnedSkillId().equals(learnedSkillId))
            .findFirst()
            .orElse(null);
    }

    public @Nullable LearnedSkillInstance findInstance(@NotNull UUID accountId, @Nullable String learnedSkillId) {
        if (learnedSkillId == null || learnedSkillId.isBlank()) return null;
        try {
            return findInstance(accountId, UUID.fromString(learnedSkillId));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    public boolean ownsSkill(@NotNull UUID accountId, @NotNull String skillId) {
        return skillsByAccount.getOrDefault(accountId, List.of()).stream()
            .anyMatch(skill -> skill.getSkillId().equalsIgnoreCase(skillId));
    }

    /** @return 習得済みスキルに対する API mutation が進行中の場合は {@code true} */
    public boolean hasMutationInProgress(@NotNull UUID accountId) {
        AtomicBoolean lock = mutationLocks.get(accountId);
        return lock != null && lock.get();
    }

    /** スキルマネージャーから master 定義の素材を消費して初回習得します。 */
    public boolean learnFromManagerAsync(
        @NotNull UUID accountId,
        @NotNull String skillId,
        @NotNull UUID updatedBy,
        @NotNull List<UUID> requiredItemEntryIds,
        @NotNull Consumer<LearnedSkillInstance> onSuccess,
        @NotNull Consumer<Throwable> onFailure
    ) {
        return mutateAsync(
            accountId,
            requiredItemEntryIds,
            () -> managerMutationOutcome(repository.learn(accountId, skillId, updatedBy)),
            onSuccess,
            onFailure
        );
    }

    /** スキルマネージャーから master 定義の素材を消費してレベルアップします。 */
    public boolean levelUpFromManagerAsync(
        @NotNull UUID accountId,
        @NotNull UUID learnedSkillId,
        @NotNull UUID updatedBy,
        @NotNull List<UUID> requiredItemEntryIds,
        @NotNull Consumer<LearnedSkillInstance> onSuccess,
        @NotNull Consumer<Throwable> onFailure
    ) {
        return mutateAsync(
            accountId,
            requiredItemEntryIds,
            () -> managerMutationOutcome(repository.levelUp(accountId, learnedSkillId, updatedBy)),
            onSuccess,
            onFailure
        );
    }

    /**
     * シジルを指定した習得済みスキル個体へ装着し、装着シジルと起点オーブを正本へ同期します。
     *
     * @param accountId アカウント ID
     * @param learnedSkillId 対象の習得済みスキル個体 ID
     * @param orbInventoryEntryId 操作確定時に共通消費順で解決済みの SIGIL_ATTACH オーブ inventory entry ID
     * @param sigilId 装着するシジル item ID
     * @param sigilInventoryEntryId 操作確定時に共通消費順で解決済みのシジル inventory entry ID
     * @param updatedBy 更新者 ID
     * @param onSuccess API 更新と素材entry同期の成功時処理
     * @param onFailure API 更新または正本同期の失敗時処理
     * @return 処理を受け付けた場合は {@code true}、別の習得スキル mutation 実行中は {@code false}
     */
    public boolean attachSigilAsync(
        @NotNull UUID accountId,
        @NotNull UUID learnedSkillId,
        @NotNull UUID orbInventoryEntryId,
        @NotNull String sigilId,
        @NotNull UUID sigilInventoryEntryId,
        @NotNull UUID updatedBy,
        @NotNull Consumer<LearnedSkillInstance> onSuccess,
        @NotNull Consumer<Throwable> onFailure
    ) {
        return mutateAsync(
            accountId,
            List.of(orbInventoryEntryId, sigilInventoryEntryId),
            () -> oneEachMutationOutcome(
                repository.attachSigil(
                    accountId,
                    learnedSkillId,
                    orbInventoryEntryId,
                    sigilId,
                    sigilInventoryEntryId,
                    updatedBy
                ),
                List.of(orbInventoryEntryId, sigilInventoryEntryId)
            ),
            onSuccess,
            onFailure
        );
    }

    /**
     * 装着済みシジルを API で取り外し、返却先 entry と習得個体を正本へ同期します。
     *
     * @param accountId アカウント ID
     * @param learnedSkillId 対象の習得済みスキル個体 ID
     * @param orbInventoryEntryId 操作確定時に共通消費順で解決済みの SIGIL_DETACH オーブ inventory entry ID
     * @param learnedSkillSigilId 取り外す装着シジル行 ID
     * @param updatedBy 更新者 ID
     * @param onSuccess API 更新と返却 entry 同期の成功時処理
     * @param onFailure API 更新または正本同期の失敗時処理
     * @return 処理を受け付けた場合は {@code true}、別の習得スキル mutation 実行中は {@code false}
     */
    public boolean detachSigilAsync(
        @NotNull UUID accountId,
        @NotNull UUID learnedSkillId,
        @NotNull UUID orbInventoryEntryId,
        @NotNull UUID learnedSkillSigilId,
        @NotNull UUID updatedBy,
        @NotNull Consumer<LearnedSkillInstance> onSuccess,
        @NotNull Consumer<Throwable> onFailure
    ) {
        AtomicBoolean lock = mutationLocks.computeIfAbsent(accountId, ignored -> new AtomicBoolean());
        if (!lock.compareAndSet(false, true)) return false;
        UUID sessionToken = sessionTokens.get(accountId);
        if (sessionToken == null) {
            lock.set(false);
            return false;
        }

        inventoryService.saveNow(accountId).whenComplete((saved, saveError) -> {
            if (!isCurrentSession(accountId, sessionToken)) {
                lock.set(false);
                return;
            }
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                try {
                    LearnedSkillSigilDetachResult result = repository.detachSigil(
                        accountId,
                        learnedSkillId,
                        orbInventoryEntryId,
                        learnedSkillSigilId,
                        updatedBy
                    );
                    if (!isCurrentSession(accountId, sessionToken)) {
                        lock.set(false);
                        return;
                    }
                    try {
                        inventoryService.reconcileAuthoritativeEntry(accountId, orbInventoryEntryId);
                    } catch (Throwable reconciliationError) {
                        // mutation は API 正本で成功している。オーブを復元すると二重消費に見えるため、
                        // 成功結果を維持し、次回ロードで回復できるようローカルでも1個だけ消費する。
                        inventoryService.consumeOwnedEntryAfterAuthoritativeMutation(
                            accountId,
                            orbInventoryEntryId
                        );
                        Logger.log(
                            LogId.W_5252,
                            "skill_sigil_detach_orb_reconcile",
                            reconciliationError.getMessage()
                        );
                    }
                    try {
                        inventoryService.reconcileAuthoritativeEntry(
                            accountId,
                            result.getReturnedInventoryEntryId()
                        );
                    } catch (Throwable reconciliationError) {
                        Logger.log(
                            LogId.W_5252,
                            "skill_sigil_detach_reconcile",
                            reconciliationError.getMessage()
                        );
                    }
                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        if (!isCurrentSession(accountId, sessionToken)) {
                            lock.set(false);
                            return;
                        }
                        replaceCached(result.getSkill());
                        lock.set(false);
                        onSuccess.accept(result.getSkill());
                    });
                } catch (Throwable error) {
                    reconcileAfterFailure(accountId, sessionToken, List.of(orbInventoryEntryId));
                    completeFailure(accountId, sessionToken, lock, onFailure, error);
                }
            });
        });
        return true;
    }

    /**
     * 習得済みスキル個体を API から忘却し、ロード済みキャッシュからも除去します。
     *
     * @param accountId アカウント ID
     * @param learnedSkillId 忘却対象の個体 ID
     * @param updatedBy 更新者 ID
     * @param onSuccess API 更新成功時の処理
     * @param onFailure API 更新失敗時の処理
     * @return 処理を受け付けた場合は {@code true}、別の mutation 実行中なら {@code false}
     */
    public boolean forgetAsync(
        @NotNull UUID accountId,
        @NotNull UUID learnedSkillId,
        @NotNull UUID updatedBy,
        @NotNull Consumer<LearnedSkillInstance> onSuccess,
        @NotNull Consumer<Throwable> onFailure
    ) {
        AtomicBoolean lock = mutationLocks.computeIfAbsent(accountId, ignored -> new AtomicBoolean());
        if (!lock.compareAndSet(false, true)) return false;
        UUID sessionToken = sessionTokens.get(accountId);
        if (sessionToken == null) {
            lock.set(false);
            return false;
        }

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                LearnedSkillInstance result = repository.forget(accountId, learnedSkillId, updatedBy);
                if (!isCurrentSession(accountId, sessionToken)) {
                    lock.set(false);
                    return;
                }
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    lock.set(false);
                    if (!isCurrentSession(accountId, sessionToken)) return;
                    removeCached(accountId, learnedSkillId);
                    onSuccess.accept(result);
                });
            } catch (Throwable error) {
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    lock.set(false);
                    if (isCurrentSession(accountId, sessionToken)) onFailure.accept(error);
                });
            }
        });
        return true;
    }

    private boolean mutateAsync(
        UUID accountId,
        UUID materialInventoryEntryId,
        Mutation mutation,
        Consumer<LearnedSkillInstance> onSuccess,
        Consumer<Throwable> onFailure
    ) {
        return mutateAsync(
            accountId,
            List.of(materialInventoryEntryId),
            mutation,
            onSuccess,
            onFailure
        );
    }

    private boolean mutateAsync(
        UUID accountId,
        List<UUID> materialInventoryEntryIds,
        Mutation mutation,
        Consumer<LearnedSkillInstance> onSuccess,
        Consumer<Throwable> onFailure
    ) {
        AtomicBoolean lock = mutationLocks.computeIfAbsent(accountId, ignored -> new AtomicBoolean());
        if (!lock.compareAndSet(false, true)) return false;
        UUID sessionToken = sessionTokens.get(accountId);
        if (sessionToken == null) {
            lock.set(false);
            return false;
        }

        inventoryService.saveNow(accountId).whenComplete((saved, saveError) -> {
            if (!isCurrentSession(accountId, sessionToken)) {
                lock.set(false);
                return;
            }
            // 直前の保存キューが失敗しても、素材消費 API は inventoryEntryId を正本で検証する。
            // ここで利用者操作を中断すると、無関係な stale entry の同期失敗だけで次のスキル習得・
            // 合成を永続的に拒否してしまう。保存完了（成功／失敗）後に API mutation を直列実行し、
            // 成否どちらでも素材 entry を API 正本へ再同期する。
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                try {
                    MutationOutcome outcome = mutation.execute();
                    LearnedSkillInstance result = outcome.skill();
                    if (!isCurrentSession(accountId, sessionToken)) {
                        lock.set(false);
                        return;
                    }
                    LinkedHashSet<UUID> reconciliationIds = new LinkedHashSet<>(materialInventoryEntryIds);
                    reconciliationIds.addAll(outcome.consumedAmounts().keySet());
                    for (UUID materialInventoryEntryId : reconciliationIds) {
                        try {
                            inventoryService.reconcileAuthoritativeEntry(accountId, materialInventoryEntryId);
                        } catch (Throwable reconciliationError) {
                            Long consumedAmount = outcome.consumedAmounts().get(materialInventoryEntryId);
                            if (consumedAmount != null && consumedAmount > 0L) {
                                inventoryService.consumeOwnedEntryAfterAuthoritativeMutation(
                                    accountId,
                                    materialInventoryEntryId,
                                    consumedAmount
                                );
                            }
                            Logger.log(LogId.W_5252, "skill_mutation_reconcile", reconciliationError.getMessage());
                        }
                    }
                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        if (!isCurrentSession(accountId, sessionToken)) {
                            lock.set(false);
                            return;
                        }
                        replaceCached(result);
                        lock.set(false);
                        onSuccess.accept(result);
                    });
                } catch (Throwable error) {
                    reconcileAfterFailure(accountId, sessionToken, materialInventoryEntryIds);
                    completeFailure(accountId, sessionToken, lock, onFailure, error);
                }
            });
        });
        return true;
    }

    private void reconcileAfterFailure(UUID accountId, UUID sessionToken, List<UUID> materialInventoryEntryIds) {
        if (!isCurrentSession(accountId, sessionToken)) return;
        try {
            List<LearnedSkillInstance> refreshed = normalize(repository.findByAccountId(accountId));
            for (UUID materialInventoryEntryId : materialInventoryEntryIds) {
                inventoryService.reconcileAuthoritativeEntry(accountId, materialInventoryEntryId);
            }
            if (isCurrentSession(accountId, sessionToken)) {
                skillsByAccount.put(accountId, refreshed);
            }
        } catch (Throwable ignored) {
            // 元の mutation 例外を通知する。再同期は次回ロードでも再試行される。
        }
    }

    private void reconcileSkillsAfterFailure(UUID accountId, UUID sessionToken) {
        if (!isCurrentSession(accountId, sessionToken)) return;
        try {
            List<LearnedSkillInstance> refreshed = normalize(repository.findByAccountId(accountId));
            if (isCurrentSession(accountId, sessionToken)) {
                skillsByAccount.put(accountId, refreshed);
            }
        } catch (Throwable ignored) {
            // 元の mutation 例外を通知し、次回ロードで再同期する。
        }
    }

    private void completeFailure(
        UUID accountId,
        UUID sessionToken,
        AtomicBoolean lock,
        Consumer<Throwable> onFailure,
        Throwable error
    ) {
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            lock.set(false);
            if (isCurrentSession(accountId, sessionToken)) {
                onFailure.accept(error);
            }
        });
    }

    private boolean isCurrentSession(UUID accountId, UUID sessionToken) {
        return sessionToken.equals(sessionTokens.get(accountId));
    }

    private void replaceCached(LearnedSkillInstance updated) {
        skillsByAccount.compute(updated.getAccountId(), (ignored, current) -> {
            List<LearnedSkillInstance> next = new ArrayList<>(current == null ? List.of() : current);
            next.removeIf(skill -> skill.getLearnedSkillId().equals(updated.getLearnedSkillId()));
            next.add(updated);
            return normalize(next);
        });
    }

    private void removeCached(UUID accountId, UUID learnedSkillId) {
        skillsByAccount.computeIfPresent(accountId, (ignored, current) ->
            current.stream().filter(skill -> !skill.getLearnedSkillId().equals(learnedSkillId)).toList()
        );
    }

    private static List<LearnedSkillInstance> normalize(List<LearnedSkillInstance> skills) {
        return skills.stream()
            .sorted(Comparator.comparing(LearnedSkillInstance::getCreatedAt,
                Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(LearnedSkillInstance::getLearnedSkillId))
            .toList();
    }

    private static MutationOutcome managerMutationOutcome(LearnedSkillMaterialMutationResult result) {
        Map<UUID, Long> consumedAmounts = new LinkedHashMap<>();
        result.getConsumedMaterials().forEach(material -> consumedAmounts.merge(
            material.getInventoryEntryId(),
            material.getConsumedAmount(),
            Long::sum
        ));
        return new MutationOutcome(result.getSkill(), Map.copyOf(consumedAmounts));
    }

    private static MutationOutcome oneEachMutationOutcome(
        LearnedSkillInstance skill,
        List<UUID> consumedEntryIds
    ) {
        Map<UUID, Long> consumedAmounts = new LinkedHashMap<>();
        consumedEntryIds.forEach(entryId -> consumedAmounts.merge(entryId, 1L, Long::sum));
        return new MutationOutcome(skill, Map.copyOf(consumedAmounts));
    }

    private record MutationOutcome(LearnedSkillInstance skill, Map<UUID, Long> consumedAmounts) {
    }

    @FunctionalInterface
    private interface Mutation {
        MutationOutcome execute();
    }

}
