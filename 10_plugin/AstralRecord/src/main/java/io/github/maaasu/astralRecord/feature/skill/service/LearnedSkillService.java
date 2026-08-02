package io.github.maaasu.astralRecord.feature.skill.service;

import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.skill.model.LearnedSkillInstance;
import io.github.maaasu.astralRecord.feature.skill.repository.LearnedSkillRepository;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
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
        mutationLocks.remove(accountId);
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

    public boolean learnAsync(
        @NotNull UUID accountId,
        @NotNull String skillId,
        @NotNull UUID gemInventoryEntryId,
        @NotNull UUID updatedBy,
        @NotNull Consumer<LearnedSkillInstance> onSuccess,
        @NotNull Consumer<Throwable> onFailure
    ) {
        return mutateAsync(
            accountId,
            gemInventoryEntryId,
            () -> repository.learn(accountId, skillId, gemInventoryEntryId, updatedBy),
            onSuccess,
            onFailure
        );
    }

    public boolean levelUpAsync(
        @NotNull UUID accountId,
        @NotNull UUID learnedSkillId,
        @NotNull UUID gemInventoryEntryId,
        @NotNull UUID updatedBy,
        @NotNull Consumer<LearnedSkillInstance> onSuccess,
        @NotNull Consumer<Throwable> onFailure
    ) {
        return mutateAsync(
            accountId,
            gemInventoryEntryId,
            () -> repository.levelUp(accountId, learnedSkillId, gemInventoryEntryId, updatedBy),
            onSuccess,
            onFailure
        );
    }

    public boolean attachSigilAsync(
        @NotNull UUID accountId,
        @NotNull UUID learnedSkillId,
        @NotNull String sigilId,
        @NotNull UUID sigilInventoryEntryId,
        @NotNull UUID updatedBy,
        @NotNull Consumer<LearnedSkillInstance> onSuccess,
        @NotNull Consumer<Throwable> onFailure
    ) {
        return mutateAsync(
            accountId,
            sigilInventoryEntryId,
            () -> repository.attachSigil(
                accountId,
                learnedSkillId,
                sigilId,
                sigilInventoryEntryId,
                updatedBy
            ),
            onSuccess,
            onFailure
        );
    }

    private boolean mutateAsync(
        UUID accountId,
        UUID materialInventoryEntryId,
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
            // ここで利用者操作を中断すると、無関係な stale entry の同期失敗だけで次のジェム習得・
            // 合成を永続的に拒否してしまう。保存完了（成功／失敗）後に API mutation を直列実行し、
            // 成否どちらでも素材 entry を API 正本へ再同期する。
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                try {
                    LearnedSkillInstance result = mutation.execute();
                    if (!isCurrentSession(accountId, sessionToken)) {
                        lock.set(false);
                        return;
                    }
                    try {
                        inventoryService.reconcileAuthoritativeEntry(accountId, materialInventoryEntryId);
                    } catch (Throwable reconciliationError) {
                        // mutation は API 正本で成功している。再同期失敗で素材を復元すると二重消費に見えるため、
                        // 成功結果を維持し、次回ロードで回復できるよう警告だけを残す。
                        inventoryService.consumeOwnedEntryAfterAuthoritativeMutation(accountId, materialInventoryEntryId);
                        Logger.log(LogId.W_5252, "skill_mutation_reconcile", reconciliationError.getMessage());
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
                    reconcileAfterFailure(accountId, sessionToken, materialInventoryEntryId);
                    completeFailure(accountId, sessionToken, lock, onFailure, error);
                }
            });
        });
        return true;
    }

    private void reconcileAfterFailure(UUID accountId, UUID sessionToken, UUID materialInventoryEntryId) {
        if (!isCurrentSession(accountId, sessionToken)) return;
        try {
            List<LearnedSkillInstance> refreshed = normalize(repository.findByAccountId(accountId));
            inventoryService.reconcileAuthoritativeEntry(accountId, materialInventoryEntryId);
            if (isCurrentSession(accountId, sessionToken)) {
                skillsByAccount.put(accountId, refreshed);
            }
        } catch (Throwable ignored) {
            // 元の mutation 例外を通知する。再同期は次回ロードでも再試行される。
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

    private static List<LearnedSkillInstance> normalize(List<LearnedSkillInstance> skills) {
        return skills.stream()
            .sorted(Comparator.comparing(LearnedSkillInstance::getCreatedAt,
                Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(LearnedSkillInstance::getLearnedSkillId))
            .toList();
    }

    @FunctionalInterface
    private interface Mutation {
        LearnedSkillInstance execute();
    }
}
