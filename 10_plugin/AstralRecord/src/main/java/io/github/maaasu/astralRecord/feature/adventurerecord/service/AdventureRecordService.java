package io.github.maaasu.astralRecord.feature.adventurerecord.service;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.adventurerecord.model.AdventureMobRecord;
import io.github.maaasu.astralRecord.feature.adventurerecord.model.AdventureRecordListType;
import io.github.maaasu.astralRecord.feature.adventurerecord.repository.AdventureRecordRepository;
import io.github.maaasu.astralRecord.feature.mob.model.MobCategory;
import io.github.maaasu.astralRecord.feature.mob.model.MobDropConfig;
import io.github.maaasu.astralRecord.feature.mob.model.MobDropItem;
import io.github.maaasu.astralRecord.feature.mob.model.MobTemplate;
import io.github.maaasu.astralRecord.feature.mob.service.MobService;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.playersetting.model.PlayerSettingKey;
import io.github.maaasu.astralRecord.feature.playersetting.service.PlayerSettingService;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * 冒険記録 GUI 向けの表示データ生成と討伐記録保存を扱います。
 */
public class AdventureRecordService {
    private final AstralRecord plugin;
    private final AdventureRecordRepository repository;
    private final MobService mobService;
    private final PlayerSettingService playerSettingService;

    public AdventureRecordService(
        @NotNull AstralRecord plugin,
        @NotNull AdventureRecordRepository repository,
        @NotNull MobService mobService,
        @NotNull PlayerSettingService playerSettingService
    ) {
        this.plugin = plugin;
        this.repository = repository;
        this.mobService = mobService;
        this.playerSettingService = playerSettingService;
    }

    /**
     * 討伐済みまたはスーパーモードで閲覧可能な Mob 一覧を返します。
     *
     * @param player 閲覧者
     * @param listType 一覧種別
     * @param searchItemIds 検索対象 item ID。空の場合は絞り込みなし
     * @return 表示エントリ一覧
     */
    public @NotNull List<Entry> buildEntries(
        @NotNull AstPlayer player,
        @NotNull AdventureRecordListType listType,
        @NotNull Set<String> searchItemIds
    ) {
        boolean superMode = isSuperMode(player);
        MobCategory category = listType.getCategory();
        List<AdventureMobRecord> records = repository.findMobRecords(player.getAccount().getUuid(), category);
        return buildEntries(records, listType, searchItemIds, superMode);
    }

    public void buildEntriesAsync(
        @NotNull AstPlayer player,
        @NotNull AdventureRecordListType listType,
        @NotNull Set<String> searchItemIds,
        @NotNull Consumer<EntryResult> completion,
        @NotNull Runnable failure
    ) {
        UUID accountId = player.getAccount().getUuid();
        UUID userId = player.getUser().getUuid();
        MobCategory category = listType.getCategory();
        Set<String> requestedItemIds = Set.copyOf(searchItemIds);
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                boolean superMode = isSuperMode(userId);
                List<AdventureMobRecord> records = repository.findMobRecords(accountId, category);
                plugin.getServer().getScheduler().runTask(plugin, () ->
                    completion.accept(new EntryResult(
                        buildEntries(records, listType, requestedItemIds, superMode),
                        superMode
                    ))
                );
            } catch (RuntimeException e) {
                plugin.getServer().getScheduler().runTask(plugin, failure);
            }
        });
    }

    private @NotNull List<Entry> buildEntries(
        @NotNull List<AdventureMobRecord> records,
        @NotNull AdventureRecordListType listType,
        @NotNull Set<String> searchItemIds,
        boolean superMode
    ) {
        Map<String, AdventureMobRecord> recordsByMobId = new HashMap<>();
        for (AdventureMobRecord record : records) {
            recordsByMobId.put(record.mobId(), record);
        }

        List<Entry> defeated = new ArrayList<>();
        Set<String> included = new HashSet<>();
        for (AdventureMobRecord record : records) {
            MobTemplate template = mobService.findTemplate(record.mobId());
            if (template == null || !matchesListType(template, listType) || !matchesSearch(template, searchItemIds)) {
                continue;
            }
            defeated.add(new Entry(template, record, true));
            included.add(template.id());
        }

        if (!superMode) {
            return defeated;
        }

        List<Entry> result = new ArrayList<>(defeated);
        List<Entry> undefeated = mobService.getLoadedMobIds().stream()
            .map(mobService::findTemplate)
            .filter(template -> template != null && !included.contains(template.id()))
            .filter(template -> matchesListType(template, listType))
            .filter(template -> matchesSearch(template, searchItemIds))
            .map(template -> new Entry(template, recordsByMobId.get(template.id()), false))
            .sorted(Comparator.comparing(entry -> entry.template().id()))
            .toList();
        result.addAll(undefeated);
        return result;
    }

    /**
     * Mob 討伐を非同期に記録します。
     *
     * @param recipient 記録対象プレイヤー
     * @param template 討伐された Mob テンプレート
     */
    public void recordDefeatAsync(@NotNull AstPlayer recipient, @NotNull MobTemplate template) {
        if (template.category() != MobCategory.ENEMY && template.category() != MobCategory.BOSS) {
            return;
        }
        UUID accountId = recipient.getAccount().getUuid();
        UUID userId = recipient.getUser().getUuid();
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                repository.recordMobDefeat(accountId, template.id(), template.category(), userId);
            } catch (RuntimeException ex) {
                Logger.error(LogId.E_6550, ex, accountId, template.id());
            }
        });
    }

    /**
     * スーパーモードが有効か判定します。
     *
     * @param player 判定対象
     * @return 有効なら true
     */
    public boolean isSuperMode(@NotNull AstPlayer player) {
        return isSuperMode(player.getUser().getUuid());
    }

    private boolean isSuperMode(@NotNull UUID userId) {
        Object value = playerSettingService.getPlayerSetting(userId, PlayerSettingKey.ADVENTURE_RECORD_SUPER_MODE);
        return value instanceof Boolean enabled && enabled;
    }

    private boolean matchesListType(@NotNull MobTemplate template, @NotNull AdventureRecordListType listType) {
        if (listType == AdventureRecordListType.ALL || listType == AdventureRecordListType.SEARCH) {
            return template.category() == MobCategory.ENEMY || template.category() == MobCategory.BOSS;
        }
        return template.category() == listType.getCategory();
    }

    private boolean matchesSearch(@NotNull MobTemplate template, @NotNull Set<String> searchItemIds) {
        if (searchItemIds.isEmpty()) {
            return true;
        }
        MobDropConfig drops = template.drops();
        if (drops == null) {
            return false;
        }
        for (MobDropItem item : drops.items()) {
            if (!item.hidden() && searchItemIds.contains(item.itemId())) {
                return true;
            }
        }
        return false;
    }

    public record Entry(
        @NotNull MobTemplate template,
        @Nullable AdventureMobRecord record,
        boolean defeated
    ) {
    }

    public record EntryResult(@NotNull List<Entry> entries, boolean superMode) {
        public EntryResult {
            entries = List.copyOf(entries);
        }
    }
}
