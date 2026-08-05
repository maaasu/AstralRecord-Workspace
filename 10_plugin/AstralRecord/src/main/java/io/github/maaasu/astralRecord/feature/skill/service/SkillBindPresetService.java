package io.github.maaasu.astralRecord.feature.skill.service;

import io.github.maaasu.astralRecord.feature.skill.model.SkillBindPreset;
import io.github.maaasu.astralRecord.feature.skill.repository.SkillBindPresetRepository;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * スキルバインドプリセットの取得と保存を扱います。
 */
public final class SkillBindPresetService {
    private static final int PRESET_COUNT = 6;

    private final Plugin plugin;
    private final SkillBindPresetRepository repository;
    private final Map<UUID, Integer> selectedPresetIndexes = new ConcurrentHashMap<>();
    private final Map<UUID, List<SkillBindPreset>> presetsByAccount = new ConcurrentHashMap<>();
    private final Map<UUID, AccountSessionState> sessionStates = new ConcurrentHashMap<>();

    public SkillBindPresetService(@NotNull Plugin plugin, @NotNull SkillBindPresetRepository repository) {
        this.plugin = plugin;
        this.repository = repository;
    }

    /**
     * アカウントのプリセット一覧を取得します。
     *
     * @param accountId アカウント ID
     * @return 1 から 6 までのプリセット一覧
     */
    public @NotNull List<SkillBindPreset> getPresets(@NotNull UUID accountId) {
        List<SkillBindPreset> cached = presetsByAccount.get(accountId);
        return cached == null ? fallbackPresets(accountId) : new ArrayList<>(cached);
    }

    public boolean hasLoadedPresets(@NotNull UUID accountId) {
        return presetsByAccount.containsKey(accountId);
    }

    /**
     * 指定アカウントのプリセットキャッシュを破棄します。
     *
     * @param accountId アカウント ID
     */
    public void invalidate(@NotNull UUID accountId) {
        AccountSessionState state = sessionStates.computeIfAbsent(accountId, ignored -> new AccountSessionState());
        synchronized (state) {
            state.generation++;
        }
        presetsByAccount.remove(accountId);
        selectedPresetIndexes.remove(accountId);
    }

    public @NotNull List<SkillBindPreset> loadInitialPresets(@NotNull UUID accountId) {
        try {
            List<SkillBindPreset> presets = new ArrayList<>(repository.findByAccountId(accountId));
            return normalizePresets(accountId, presets);
        } catch (Exception exception) {
            Logger.log(LogId.E_5803, exception, "skill_bind_load:" + accountId);
        }
        return fallbackPresets(accountId);
    }

    public void applyInitialPresets(
        @NotNull UUID accountId,
        @NotNull List<SkillBindPreset> presets
    ) {
        presetsByAccount.put(accountId, normalizePresets(accountId, presets));
    }

    private @NotNull List<SkillBindPreset> fallbackPresets(@NotNull UUID accountId) {
        List<SkillBindPreset> fallback = new ArrayList<>(PRESET_COUNT);
        for (int index = 1; index <= PRESET_COUNT; index++) {
            fallback.add(new SkillBindPreset(
                null,
                accountId,
                index,
                List.of(),
                SkillBindPreset.WEAPON_NORMAL_ATTACK_BINDING_ID,
                List.of(),
                index <= 3,
                false,
                0
            ));
        }
        return fallback;
    }

    /**
     * 現在選択中として扱うプリセット番号を返します。
     *
     * @param accountId アカウント ID
     * @return 選択中プリセット番号
     */
    public int selectedPresetIndex(@NotNull UUID accountId) {
        return selectedPresetIndexes.getOrDefault(accountId, 1);
    }

    /**
     * 現在選択中として扱うプリセット番号を更新します。
     *
     * @param accountId アカウント ID
     * @param presetIndex プリセット番号
     */
    public void selectPreset(@NotNull UUID accountId, int presetIndex) {
        selectedPresetIndexes.put(accountId, Math.max(1, Math.min(PRESET_COUNT, presetIndex)));
    }

    /**
     * 指定した習得済みスキル個体を、ロード済み全プリセットのバインドから除去します。
     *
     * @param accountId アカウント ID
     * @param learnedSkillId 忘却した習得済みスキル個体 ID
     */
    public void clearBindings(@NotNull UUID accountId, @NotNull UUID learnedSkillId) {
        presetsByAccount.computeIfPresent(accountId, (ignored, current) -> current.stream()
            .map(preset -> clearBindings(preset, learnedSkillId))
            .toList()
        );
    }

    /**
     * 指定プリセットを保存します。
     *
     * @param accountId アカウント ID
     * @param presetIndex プリセット番号
     * @param activeSkillSlots 発動系スロット
     * @param leftClickSkillId 左クリックバインド
     * @param passiveSkillSlots パッシブ系スロット
     * @param updatedBy 更新者
     * @return 保存後プリセット
     */
    public boolean saveAsync(
        @NotNull UUID accountId,
        int presetIndex,
        @NotNull List<String> activeSkillSlots,
        String leftClickSkillId,
        @NotNull List<String> passiveSkillSlots,
        @NotNull UUID updatedBy,
        @NotNull Consumer<SkillBindPreset> onSuccess,
        @NotNull Runnable onFailure
    ) {
        AccountSessionState state = sessionStates.computeIfAbsent(accountId, ignored -> new AccountSessionState());
        SaveAttempt attempt;
        synchronized (state) {
            if (state.inProgress != null) {
                return false;
            }
            attempt = new SaveAttempt(state.generation);
            state.inProgress = attempt;
        }
        int normalizedPresetIndex = Math.max(1, Math.min(PRESET_COUNT, presetIndex));
        List<String> activeSnapshot = Collections.unmodifiableList(new ArrayList<>(activeSkillSlots));
        String leftClickSnapshot = leftClickSkillId == null || leftClickSkillId.isBlank() ? null : leftClickSkillId.trim();
        List<String> passiveSnapshot = Collections.unmodifiableList(new ArrayList<>(passiveSkillSlots));
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            SkillBindPreset saved = null;
            try {
                saved = repository.save(
                    accountId,
                    normalizedPresetIndex,
                    activeSnapshot,
                    leftClickSnapshot,
                    passiveSnapshot,
                    updatedBy
                );
            } catch (Exception exception) {
                Logger.log(LogId.E_5804, exception, "skill_bind_save:" + accountId + ":" + normalizedPresetIndex);
            }
            SkillBindPreset result = saved;
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (!completeSaveAttempt(state, attempt)) {
                    return;
                }
                if (result == null) {
                    onFailure.run();
                    return;
                }
                presetsByAccount.compute(accountId, (ignored, current) -> mergePreset(accountId, current, result));
                onSuccess.accept(result);
            });
        });
        return true;
    }

    /**
     * 左クリックバインド未導入の呼び出し元向けに、武器通常攻撃を既定値として保存します。
     */
    public boolean saveAsync(
        @NotNull UUID accountId,
        int presetIndex,
        @NotNull List<String> activeSkillSlots,
        @NotNull List<String> passiveSkillSlots,
        @NotNull UUID updatedBy,
        @NotNull Consumer<SkillBindPreset> onSuccess,
        @NotNull Runnable onFailure
    ) {
        return saveAsync(
            accountId, presetIndex, activeSkillSlots, SkillBindPreset.WEAPON_NORMAL_ATTACK_BINDING_ID,
            passiveSkillSlots, updatedBy, onSuccess, onFailure
        );
    }

    private boolean completeSaveAttempt(AccountSessionState state, SaveAttempt attempt) {
        synchronized (state) {
            if (state.inProgress != attempt) {
                return false;
            }
            state.inProgress = null;
            return state.generation == attempt.generation();
        }
    }

    private @NotNull List<SkillBindPreset> normalizePresets(
        @NotNull UUID accountId,
        @NotNull List<SkillBindPreset> presets
    ) {
        List<SkillBindPreset> normalized = new ArrayList<>(presets.subList(0, Math.min(PRESET_COUNT, presets.size())));
        List<SkillBindPreset> fallback = fallbackPresets(accountId);
        while (normalized.size() < PRESET_COUNT) {
            normalized.add(fallback.get(normalized.size()));
        }
        return List.copyOf(normalized);
    }

    private @NotNull SkillBindPreset clearBindings(
        @NotNull SkillBindPreset preset,
        @NotNull UUID learnedSkillId
    ) {
        String target = learnedSkillId.toString();
        List<String> active = preset.getActiveSkillSlots().stream()
            .map(value -> target.equalsIgnoreCase(value) ? null : value)
            .toList();
        List<String> passive = preset.getPassiveSkillSlots().stream()
            .map(value -> target.equalsIgnoreCase(value) ? null : value)
            .toList();
        String leftClick = target.equalsIgnoreCase(preset.getLeftClickSkillId())
            ? SkillBindPreset.WEAPON_NORMAL_ATTACK_BINDING_ID
            : preset.getLeftClickSkillId();
        return new SkillBindPreset(
            preset.getPresetId(), preset.getAccountId(), preset.getPresetIndex(), active, leftClick,
            passive, preset.isUnlocked(), preset.isSaved(), preset.getVersion()
        );
    }

    private @NotNull List<SkillBindPreset> mergePreset(
        @NotNull UUID accountId,
        List<SkillBindPreset> current,
        @NotNull SkillBindPreset saved
    ) {
        List<SkillBindPreset> merged = current == null
            ? fallbackPresets(accountId)
            : new ArrayList<>(current);
        int index = Math.max(1, Math.min(PRESET_COUNT, saved.getPresetIndex())) - 1;
        while (merged.size() < PRESET_COUNT) {
            merged.add(fallbackPresets(accountId).get(merged.size()));
        }
        merged.set(index, saved);
        return List.copyOf(merged.subList(0, PRESET_COUNT));
    }

    private static final class AccountSessionState {
        private long generation;
        private SaveAttempt inProgress;
    }

    private record SaveAttempt(long generation) {
    }
}
