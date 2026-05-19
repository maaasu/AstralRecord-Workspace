package io.github.maaasu.astralRecord.feature.loot.service;

import io.github.maaasu.astralRecord.feature.loot.model.LootModel;
import io.github.maaasu.astralRecord.feature.loot.repository.LootRepository;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * ルートテーブルのキャッシュ管理サービス。
 * <p>
 * 起動時に API から一括取得してメモリに保持し、
 * bundle の lootTableId 解決時にはキャッシュから即時返却します。
 */
public class LootService {

    private final LootRepository lootRepository;
    private final Map<String, LootModel> loadedLoots;

    public LootService() {
        this.lootRepository = new LootRepository();
        this.loadedLoots = new LinkedHashMap<>();
    }

    /**
     * 全ルートテーブルを API から一括取得してキャッシュへ登録します。
     * 起動時の非同期初期ロードに使用します。
     *
     * @return ロードしたルートテーブルの件数
     */
    public int loadAll() {
        try {
            List<LootModel> loots = lootRepository.findAll();
            for (LootModel loot : loots) {
                cacheLoot(loot);
            }
            Logger.log(LogId.I_5300, loots.size());
            return loots.size();
        } catch (Exception e) {
            Logger.log(LogId.E_5301, e, "loadAll");
            return 0;
        }
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
     * キャッシュ済みルートテーブルの一覧を返します。
     *
     * @return ロード済み全 LootModel
     */
    public @NotNull List<LootModel> getLoadedLoots() {
        return List.copyOf(loadedLoots.values());
    }

    /**
     * キャッシュをクリアします。
     */
    public void clearCache() {
        loadedLoots.clear();
    }

    /**
     * ルートテーブルをキャッシュへ登録し、詳細情報を debug ログへ出力します。
     *
     * @param loot 登録するルートテーブル
     */
    private void cacheLoot(@NotNull LootModel loot) {
        loadedLoots.put(normalize(loot.getId()), loot);
        Logger.log(LogId.D_5301, loot);
    }

    private @NotNull String normalize(@NotNull String value) {
        return value.trim().toLowerCase(Locale.ROOT);
    }
}

