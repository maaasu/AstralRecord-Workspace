package io.github.maaasu.astralRecord.feature.playersetting.service;

import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.jetbrains.annotations.NotNull;

/**
 * プレイヤー設定に応じた Bukkit の視覚効果を同期します。
 */
public final class PlayerSettingEffectService {
    private final PlayerSettingService playerSettingService;

    /**
     * プレイヤー設定由来の効果同期サービスを初期化します。
     *
     * @param playerSettingService プレイヤー設定参照サービス
     */
    public PlayerSettingEffectService(@NotNull PlayerSettingService playerSettingService) {
        this.playerSettingService = playerSettingService;
    }

    /**
     * プレイヤーのナイトビジョン効果を現在の設定へ同期します。
     * 有効時は粒子を表示しない無期限・増幅値0の効果を適用し、無効時は暗視効果を除去します。
     * Bukkit のプレイヤー状態を変更するため、メインスレッドから呼び出してください。
     *
     * @param player 効果を同期するプレイヤー
     */
    public void synchronizeNightVision(@NotNull Player player) {
        if (playerSettingService.isNightVisionEnabled(player.getUniqueId())) {
            player.addPotionEffect(new PotionEffect(
                PotionEffectType.NIGHT_VISION,
                PotionEffect.INFINITE_DURATION,
                0,
                false,
                false,
                true
            ));
            return;
        }
        player.removePotionEffect(PotionEffectType.NIGHT_VISION);
    }
}
