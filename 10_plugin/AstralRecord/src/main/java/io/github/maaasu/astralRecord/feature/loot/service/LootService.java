package io.github.maaasu.astralRecord.feature.loot.service;

import io.github.maaasu.astralRecord.feature.loot.model.LootModel;
import io.github.maaasu.astralRecord.feature.loot.repository.LootRepository;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * ルートテーブルのキャッシュ管理サービス。
 * <p>
 * 起動時に API から一括取得し、構築済みの不変 Map を原子的に公開します。
 * bundle・Mob・採集の lootTableId 解決時にはキャッシュから即時返却します。
 */
public class LootService {

    private final LootRepository lootRepository;
    private final Object cacheLock = new Object();
    private volatile Map<String, LootModel> loadedLoots;

    /**
     * API リポジトリを使用するキャッシュサービスを構築します。
     */
    public LootService() {
        this(new LootRepository());
    }

    LootService(@NotNull LootRepository lootRepository) {
        this.lootRepository = lootRepository;
        this.loadedLoots = Map.of();
    }

    /**
     * 全ルートテーブルを API から一括取得し、全件構築後のスナップショットを公開します。
     * 起動時の非同期初期ロードに使用します。
     *
     * @return ロードしたルートテーブルの件数
     */
    public int loadAll() {
        try {
            Map<String, LootModel> snapshot = loadSnapshot();
            replaceSnapshot(snapshot);
            return snapshot.size();
        } catch (Exception e) {
            Logger.log(LogId.E_5301, e, "loadAll");
            return 0;
        }
    }

    /**
     * 全ルートテーブルを読み込みますが、現在の公開キャッシュは変更しません。
     * マスターデータ再読込の prepare 段階で使用します。
     *
     * @return 正規化済みIDをキーとする不変スナップショット
     */
    public @NotNull Map<String, LootModel> loadSnapshot() {
        List<LootModel> loots = lootRepository.findAll();
        Map<String, LootModel> nextLoots = new LinkedHashMap<>();
        for (LootModel loot : loots) {
            nextLoots.put(normalize(loot.getId()), loot);
            Logger.log(LogId.D_5301, loot);
        }
        return immutableSnapshot(nextLoots);
    }

    /**
     * prepare 済みのルートテーブルを原子的に公開します。
     *
     * @param snapshot {@link #loadSnapshot()} で構築したスナップショット
     */
    public void replaceSnapshot(@NotNull Map<String, LootModel> snapshot) {
        synchronized (cacheLock) {
            loadedLoots = immutableSnapshot(snapshot);
        }
        Logger.log(LogId.I_5300, snapshot.size());
    }

    /**
     * キャッシュからルートテーブルを取得します。
     *
     * @param lootId ルートテーブルID
     * @return キャッシュ済み LootModel。未ロードなら {@code null}
     */
    public @Nullable LootModel getLoaded(@NotNull String lootId) {
        return loadedLoots.get(normalize(lootId));
    }

    /**
     * キャッシュ未命中時は API から単体取得して登録します。
     *
     * @param lootId ルートテーブルIDまたは参照値
     * @return 解決済み LootModel。取得失敗時は {@code null}
     */
    public @Nullable LootModel getLoadedOrFetch(@NotNull String lootId) {
        LootModel cached = getLoaded(lootId);
        if (cached != null) {
            return cached;
        }

        try {
            LootModel loaded = lootRepository.findById(lootId);
            if (loaded != null) {
                cacheLoot(loaded);
            }
            return loaded;
        } catch (Exception e) {
            Logger.log(LogId.E_5301, e, lootId);
            return null;
        }
    }

    /**
     * キャッシュ済みルートテーブルの一覧を返します。
     *
     * @return ロード済み全 LootModel
     */
    public @NotNull List<LootModel> getLoadedLoots() {
        Map<String, LootModel> snapshot = loadedLoots;
        return List.copyOf(snapshot.values());
    }

    /**
     * キャッシュを空の不変スナップショットへ差し替えます。
     */
    public void clearCache() {
        synchronized (cacheLock) {
            loadedLoots = Map.of();
        }
    }

    /**
     * ルートテーブルをキャッシュへ登録し、詳細情報を debug ログへ出力します。
     *
     * @param loot 登録するルートテーブル
     */
    private void cacheLoot(@NotNull LootModel loot) {
        synchronized (cacheLock) {
            Map<String, LootModel> nextLoots = new LinkedHashMap<>(loadedLoots);
            nextLoots.put(normalize(loot.getId()), loot);
            loadedLoots = immutableSnapshot(nextLoots);
        }
        Logger.log(LogId.D_5301, loot);
    }

    private @NotNull Map<String, LootModel> immutableSnapshot(
        @NotNull Map<String, LootModel> source
    ) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }

    private @NotNull String normalize(@NotNull String value) {
        String trimmed = value.trim();
        int prefixIndex = trimmed.indexOf(':');
        String id = prefixIndex >= 0
                ? trimmed.substring(prefixIndex + 1).trim()
                : trimmed;
        return id.toLowerCase(Locale.ROOT);
    }
}

