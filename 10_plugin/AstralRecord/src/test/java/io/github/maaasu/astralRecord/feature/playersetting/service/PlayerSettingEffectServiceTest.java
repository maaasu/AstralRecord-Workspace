package io.github.maaasu.astralRecord.feature.playersetting.service;

import io.github.maaasu.astralRecord.feature.playersetting.cache.PlayerSettingCache;
import io.github.maaasu.astralRecord.feature.playersetting.model.PlayerSettingKey;
import io.github.maaasu.astralRecord.feature.playersetting.repository.PlayerSettingRepository;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlayerSettingEffectServiceTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/11-player-setting/3-メソッド仕様/11_3-サービス.md
     * 章・見出し: # 11_3-サービス > ## 7. 設定由来の効果同期
     * 検証契約: NIGHT_VISION が有効な場合、粒子なし・無期限・増幅値0の暗視効果を付与する。
     */
    @Test
    void enabledNightVisionAddsInfiniteParticleFreeEffect() {
        UUID userId = UUID.randomUUID();
        PlayerSettingService playerSettingService = mock(PlayerSettingService.class);
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(userId);
        when(playerSettingService.isNightVisionEnabled(userId)).thenReturn(true);

        new PlayerSettingEffectService(playerSettingService).synchronizeNightVision(player);

        ArgumentCaptor<PotionEffect> effectCaptor = ArgumentCaptor.forClass(PotionEffect.class);
        verify(player).addPotionEffect(effectCaptor.capture());
        PotionEffect effect = effectCaptor.getValue();
        assertEquals(PotionEffectType.NIGHT_VISION, effect.getType());
        assertEquals(PotionEffect.INFINITE_DURATION, effect.getDuration());
        assertTrue(effect.isInfinite());
        assertEquals(0, effect.getAmplifier());
        assertFalse(effect.isAmbient());
        assertFalse(effect.hasParticles());
        assertTrue(effect.hasIcon());
        verify(player, never()).removePotionEffect(PotionEffectType.NIGHT_VISION);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/11-player-setting/3-メソッド仕様/11_3-サービス.md
     * 章・見出し: # 11_3-サービス > ## 7. 設定由来の効果同期
     * 検証契約: NIGHT_VISION が無効な場合、プレイヤーの暗視効果を除去する。
     */
    @Test
    void disabledNightVisionRemovesEffect() {
        UUID userId = UUID.randomUUID();
        PlayerSettingService playerSettingService = mock(PlayerSettingService.class);
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(userId);
        when(playerSettingService.isNightVisionEnabled(userId)).thenReturn(false);

        new PlayerSettingEffectService(playerSettingService).synchronizeNightVision(player);

        verify(player).removePotionEffect(PotionEffectType.NIGHT_VISION);
        verify(player, never()).addPotionEffect(org.mockito.ArgumentMatchers.any(PotionEffect.class));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/11-player-setting/11_1-モデル定義.md
     * 章・見出し: # 11_1-モデル定義 > ## 3. 設定 key
     * 検証契約: NIGHT_VISION の既定値は false で、未選択時に暗視を有効化しない。
     */
    @Test
    void nightVisionSettingDefaultsToDisabled() {
        assertEquals(Boolean.FALSE, PlayerSettingKey.NIGHT_VISION.getDefaultValue());
        assertEquals(Boolean.TRUE, PlayerSettingKey.NIGHT_VISION.parseInputValue("on"));
        assertEquals(Boolean.FALSE, PlayerSettingKey.NIGHT_VISION.parseInputValue("off"));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/11-player-setting/3-メソッド仕様/11_3-サービス.md
     * 章・見出し: # 11_3-サービス > ## 4. 型別参照
     * 検証契約: NIGHT_VISION の cache 未読込時は API を呼ばず既定 false を返す。
     */
    @Test
    void nightVisionHelperDefaultsToDisabledWithoutCachedSnapshot() {
        PlayerSettingService playerSettingService = new PlayerSettingService(
            mock(PlayerSettingRepository.class),
            new PlayerSettingDefaults(),
            new PlayerSettingCache()
        );

        assertFalse(playerSettingService.isNightVisionEnabled(UUID.randomUUID()));
    }
}
