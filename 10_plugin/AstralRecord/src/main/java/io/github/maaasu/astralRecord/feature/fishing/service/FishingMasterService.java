package io.github.maaasu.astralRecord.feature.fishing.service;

import io.github.maaasu.astralRecord.feature.fishing.model.FishingMasters;
import io.github.maaasu.astralRecord.feature.fishing.model.FishingMasters.Area;
import io.github.maaasu.astralRecord.feature.fishing.model.FishingMasters.FishDefinition;
import io.github.maaasu.astralRecord.feature.fishing.model.FishingMasters.LevelRange;
import io.github.maaasu.astralRecord.feature.fishing.model.FishingMasters.Rules;
import io.github.maaasu.astralRecord.feature.fishing.repository.FishingRepository;
import io.github.maaasu.astralRecord.feature.item.model.ItemCategory;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.item.service.ItemService;
import io.github.maaasu.astralRecord.feature.loot.model.LootModel;
import io.github.maaasu.astralRecord.feature.loot.service.LootRollService;
import io.github.maaasu.astralRecord.feature.loot.service.LootService;
import io.github.maaasu.astralRecord.feature.mob.model.MobDropResult;
import io.github.maaasu.astralRecord.feature.mob.model.MobDropResultItem;
import io.github.maaasu.astralRecord.feature.mob.service.MobDropPresentationService;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.status.model.StatusType;
import io.github.maaasu.astralRecord.feature.status.service.StatusRateCalculator;
import io.github.maaasu.astralRecord.feature.world.service.WorldService;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import org.bukkit.Location;
import org.bukkit.World;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** 釣りマスタ3種と参照報酬をまとめて公開し、セッション開始時の世代を保持します。 */
public final class FishingMasterService {
    private final FishingRepository repository = new FishingRepository();
    private final ItemService items;
    private final LootService loot;
    private final LootRollService lootRoll = new LootRollService();
    private volatile Snapshot snapshot = new Snapshot(Map.of(), Map.of(), Map.of(), Map.of(), Map.of());
    private MobDropPresentationService dropPresentation;

    /** @param items アイテムキャッシュ @param loot 解決済みLootキャッシュ */
    public FishingMasterService(ItemService items, LootService loot) {
        this.items = items;
        this.loot = loot;
    }

    /** @param service 共通ドロップ表示・付与サービス。起動時に設定します */
    public void setDropPresentationService(MobDropPresentationService service) { dropPresentation = service; }

    /** @return 起動時に公開できた定義数。失敗時は旧世代を維持し0を返します */
    public int loadAll() {
        try {
            var itemMap = index(items.getLoadedItems(), ItemModel::getId);
            var lootMap = index(loot.getLoadedLoots(), LootModel::getId);
            Snapshot loaded = loadSnapshot(itemMap, lootMap);
            replaceSnapshot(loaded);
            return loaded.size();
        } catch (RuntimeException exception) {
            Logger.log(LogId.E_9700, exception);
            return 0;
        }
    }

    /**
     * 非同期prepareでAPI定義と参照を検証します。公開済みキャッシュは変更しません。
     * @param itemMap 同じ再読込計画のアイテム
     * @param lootMap 同じ再読込計画のLoot
     * @return 検証済みの不変スナップショット
     * @throws IllegalArgumentException 不正定義・重複・参照切れ
     */
    public Snapshot loadSnapshot(Map<String, ItemModel> itemMap, Map<String, LootModel> lootMap) {
        var fish = index(repository.findFish(), FishDefinition::id);
        var rules = index(repository.findRules(), Rules::id);
        var areas = index(repository.findAreas(), Area::id);
        var byWorld = new LinkedHashMap<String, Area>();
        for (FishDefinition definition : fish.values()) {
            ItemModel caughtItem = itemMap.get(definition.caughtItemId());
            require(caughtItem != null, "Missing caught item: " + definition.id());
            ItemCategory category = ItemCategory.fromApiValue(caughtItem.getCategory());
            require(category != ItemCategory.EQUIPMENT && category != ItemCategory.PET && category != ItemCategory.PET_EGG,
                "Caught fish reward must be a stack item: " + definition.id());
            require(definition.caughtAmount() <= caughtItem.getMaxStack(), "Caught amount exceeds item maxStack");
            if (definition.lootTableId() != null) {
                LootModel table = lootMap.get(definition.lootTableId());
                require(table != null, "Missing fishing loot: " + definition.id());
                for (var pool : table.getPools()) for (var content : pool.getContents()) {
                    require(itemMap.containsKey(FishingMasters.id(content.getItemId())), "Missing fishing loot item");
                }
            }
        }
        for (Area area : areas.values()) {
            require(rules.containsKey(area.rulesId()), "Missing fishing rules: " + area.id());
            for (LevelRange range : area.levelRanges()) for (var chance : range.fish()) {
                require(fish.containsKey(chance.fishId()), "Missing fish in area: " + area.id());
            }
            if (area.enabled()) require(byWorld.putIfAbsent(area.worldId(), area) == null,
                "Duplicate enabled fishing area for world: " + area.worldId());
        }
        return new Snapshot(fish, rules, byWorld, itemMap, lootMap);
    }

    /** @param prepared 検証済み世代。公開はメインスレッドから行います */
    public void replaceSnapshot(Snapshot prepared) {
        snapshot = prepared;
        Logger.log(LogId.I_9700, prepared.size());
    }

    /**
     * 着水ワールドのマスタIDと使用中の竿レベルから、開始時世代を取得します。
     * @param world 針の着水ワールド
     * @param fishingLevel 竿単体の釣りレベル
     * @param worldService Bukkitワールドからマスタを解決するサービス
     * @return 開始用定義。ワールドに竿以下のレベル定義がない場合はnull
     */
    public @Nullable SessionDefinition findDefinition(World world, int fishingLevel, WorldService worldService) {
        var masterWorld = worldService.findByBukkitWorld(world);
        if (masterWorld == null) return null;
        Snapshot current = snapshot;
        Area area = current.areasByWorld().get(FishingMasters.id(masterWorld.id()));
        if (area == null) return null;
        LevelRange range = area.resolveLevelRange(fishingLevel);
        return range == null ? null : new SessionDefinition(area.id(), area.worldId(), fishingLevel,
            current.rules().get(area.rulesId()), range, current);
    }

    /**
     * 討伐済みセッションから一度だけ呼び、開始時世代の釣果とLootを共通付与経路へ渡します。
     * @param owner 所有者
     * @param location 討伐直前の位置
     * @param session 開始時定義
     * @param fish 討伐魚
     */
    public void grantCatch(AstPlayer owner, Location location, SessionDefinition session, FishDefinition fish) {
        if (dropPresentation == null) throw new IllegalStateException("Fishing rewards are not initialized");
        List<MobDropResultItem> rewards = new ArrayList<>();
        rewards.add(new MobDropResultItem(fish.caughtItemId(), fish.caughtAmount(), 100.0));
        if (fish.lootTableId() != null) {
            for (var rolled : lootRoll.roll(session.snapshot().loot().get(fish.lootTableId()),
                () -> StatusRateCalculator.resolveRatePercent(owner.getStatusSnapshot(), StatusType.DROP_RATE_INCREASE))) {
                rewards.add(new MobDropResultItem(rolled.getItemId(), rolled.getAmount(), rolled.getConfiguredRate()));
            }
        }
        dropPresentation.presentAndGrantSnapshot(owner, location,
            io.github.maaasu.astralRecord.infrastructure.util.ColorCodeUtil.toLegacyText(fish.name(), fish.name()),
            new MobDropResult(rewards, 0, 0), "fishing", session.snapshot().items());
        Logger.log(LogId.I_9701, owner.getAccount().getUuid(), session.areaId(), fish.id(), fish.caughtAmount());
    }

    private static void require(boolean valid, String message) {
        if (!valid) throw new IllegalArgumentException(message);
    }

    private static <T> Map<String, T> index(List<T> values, Function<T, String> id) {
        var result = new LinkedHashMap<String, T>();
        for (T value : values) require(result.putIfAbsent(FishingMasters.id(id.apply(value)), value) == null,
            "Duplicate fishing master ID: " + id.apply(value));
        return Map.copyOf(result);
    }

    /** 釣りと参照報酬をまとめた不変の公開世代。 */
    public record Snapshot(Map<String, FishDefinition> fish, Map<String, Rules> rules,
                           Map<String, Area> areasByWorld, Map<String, ItemModel> items, Map<String, LootModel> loot) {
        public Snapshot {
            fish = Map.copyOf(fish);
            rules = Map.copyOf(rules);
            areasByWorld = Map.copyOf(areasByWorld);
            items = Map.copyOf(items);
            loot = Map.copyOf(loot);
        }
        /** @return 公開魚・ルール・有効釣り場の件数 */
        public int size() { return fish.size() + rules.size() + areasByWorld.size(); }
    }

    /** 1セッションの固定設定。マスタ再読込後も同じ世代を使用します。 */
    public record SessionDefinition(String areaId, String worldId, int fishingLevel, Rules rules,
                                    LevelRange distribution, Snapshot snapshot) {
        /** @return 実際に適用している定義レベル。竿レベルとは区別します */
        public int effectiveFishingLevel() { return distribution.minLevel(); }

        /** @param percent 0以上100未満の抽選値 @return 定義された百分率に従う魚 */
        public FishDefinition drawFish(double percent) {
            if (!Double.isFinite(percent) || percent < 0 || percent >= 100) throw new IllegalArgumentException("Invalid fishing draw");
            double cumulative = 0;
            for (var chance : distribution.fish()) {
                cumulative += chance.chancePercent();
                if (percent < cumulative) return snapshot.fish().get(chance.fishId());
            }
            // 合計検証の浮動小数点許容差だけを最後の魚へ吸収します。
            return snapshot.fish().get(distribution.fish().getLast().fishId());
        }
    }
}
