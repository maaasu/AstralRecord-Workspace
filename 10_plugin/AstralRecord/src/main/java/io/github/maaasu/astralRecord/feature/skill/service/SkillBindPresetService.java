package io.github.maaasu.astralRecord.feature.skill.service;

import io.github.maaasu.astralRecord.feature.skill.model.SkillBindPreset;
import io.github.maaasu.astralRecord.feature.skill.repository.SkillBindPresetRepository;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * スキルバインドプリセットの取得と保存を扱います。
 */
public final class SkillBindPresetService {
    private static final int PRESET_COUNT = 9;

    private final SkillBindPresetRepository repository;
    private final Map<UUID, Integer> selectedPresetIndexes = new ConcurrentHashMap<>();

    public SkillBindPresetService(@NotNull SkillBindPresetRepository repository) {
        this.repository = repository;
    }

    /**
     * アカウントのプリセット一覧を取得します。
     *
     * @param accountId アカウント ID
     * @return 1 から 9 までのプリセット一覧
     */
    public @NotNull List<SkillBindPreset> getPresets(@NotNull UUID accountId) {
        try {
            List<SkillBindPreset> presets = new ArrayList<>(repository.findByAccountId(accountId));
            if (presets.size() >= PRESET_COUNT) {
                return presets.subList(0, PRESET_COUNT);
            }
        } catch (RuntimeException ignored) {
        }

        List<SkillBindPreset> fallback = new ArrayList<>(PRESET_COUNT);
        for (int index = 1; index <= PRESET_COUNT; index++) {
            fallback.add(new SkillBindPreset(
                null,
                accountId,
                index,
                List.of(),
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
     * 指定プリセットを保存します。
     *
     * @param accountId アカウント ID
     * @param presetIndex プリセット番号
     * @param activeSkillSlots 発動系スロット
     * @param passiveSkillSlots パッシブ系スロット
     * @param updatedBy 更新者
     * @return 保存後プリセット
     */
    public @NotNull SkillBindPreset save(
        @NotNull UUID accountId,
        int presetIndex,
        @NotNull List<String> activeSkillSlots,
        @NotNull List<String> passiveSkillSlots,
        @NotNull UUID updatedBy
    ) {
        return repository.save(accountId, presetIndex, activeSkillSlots, passiveSkillSlots, updatedBy);
    }
}
