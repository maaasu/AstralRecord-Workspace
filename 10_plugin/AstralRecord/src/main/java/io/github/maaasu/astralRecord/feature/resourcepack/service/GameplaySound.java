package io.github.maaasu.astralRecord.feature.resourcepack.service;

import org.bukkit.Location;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** 通常攻撃とGUIのリソースパック音を、対応する接続へ送ります。 */
public final class GameplaySound {
    private static final String NAMESPACE = "astralrecord:";
    private static @Nullable ResourcePackService resourcePacks;

    private GameplaySound() {
    }

    /**
     * メインスレッドでパック適用状態の参照先を設定または解除します。
     *
     * @param service 起動時の適用状態サービス。終了時は null
     */
    public static void configure(@Nullable ResourcePackService service) {
        resourcePacks = service;
    }

    /**
     * メインスレッドから、対象が対応パックを利用できる場合に専用音を送ります。
     *
     * @param player 再生対象
     * @param origin 音源座標
     * @param eventPath astralrecord 名前空間のイベントパス
     * @param volume 再生音量
     * @param pitch 再生音程
     * @return 専用音を送信した場合は true
     */
    public static boolean tryPlay(@NotNull Player player, @NotNull Location origin, @NotNull String eventPath,
                                  float volume, float pitch) {
        if (resourcePacks == null || !resourcePacks.hasGameplaySounds(player)) {
            return false;
        }
        player.playSound(origin, NAMESPACE + eventPath, SoundCategory.PLAYERS, volume, pitch);
        return true;
    }
}
