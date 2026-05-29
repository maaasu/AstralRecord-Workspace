package io.github.maaasu.astralRecord.feature.world.config;

import io.github.maaasu.astralRecord.AstralRecord;
import org.jetbrains.annotations.NotNull;

/**
 * config.yml から参加時スポーン先の WorldMasterData ID を読み取ります。
 */
public final class PluginJoinSpawnWorldConfig {

    private static final String DEFAULT_WORLD_ID = "world";

    private PluginJoinSpawnWorldConfig() {
    }

    /**
     * config.yml から参加時スポーン先の WorldMasterData ID を読み取ります。
     *
     * @param plugin プラグイン
     * @return 参加時スポーン先の WorldMasterData ID
     */
    @NotNull
    public static String load(@NotNull AstralRecord plugin) {
        try {
            String worldId = plugin.getConfig().getString("plugin.joinSpawn.world", DEFAULT_WORLD_ID);
            return worldId == null || worldId.isBlank() ? DEFAULT_WORLD_ID : worldId;
        } catch (Exception ignored) {
            return DEFAULT_WORLD_ID;
        }
    }
}
