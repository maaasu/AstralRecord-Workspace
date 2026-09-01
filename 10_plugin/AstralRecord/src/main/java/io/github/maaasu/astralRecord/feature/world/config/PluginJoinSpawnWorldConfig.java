package io.github.maaasu.astralRecord.feature.world.config;

import io.github.maaasu.astralRecord.AstralRecord;
import org.jetbrains.annotations.NotNull;

/**
 * config.yml から参加時スポーン先の WorldMasterData ID を読み取ります。
 */
public final class PluginJoinSpawnWorldConfig {

    private static final String DEFAULT_WORLD_ID = "starlit_nox";
    private static final String LEGACY_WORLD_ID = "world";

    private PluginJoinSpawnWorldConfig() {
    }

    /**
     * config.yml から参加時スポーン先の WorldMasterData ID を読み取ります。
     * 未指定・空値・設定読込例外時は現行 BASE の {@code starlit_nox} を返し、
     * 旧構成の {@code world} は {@code starlit_nox} として扱います。
     * このメソッドは既存の config.yml を書き換えません。
     *
     * @param plugin プラグイン
     * @return 参加時スポーン先の WorldMasterData ID
     */
    @NotNull
    public static String load(@NotNull AstralRecord plugin) {
        try {
            String worldId = plugin.getConfig().getString("plugin.joinSpawn.world", DEFAULT_WORLD_ID);
            if (worldId == null || worldId.isBlank()) {
                return DEFAULT_WORLD_ID;
            }

            String normalizedWorldId = worldId.trim();
            return LEGACY_WORLD_ID.equals(normalizedWorldId) ? DEFAULT_WORLD_ID : normalizedWorldId;
        } catch (Exception ignored) {
            return DEFAULT_WORLD_ID;
        }
    }
}
