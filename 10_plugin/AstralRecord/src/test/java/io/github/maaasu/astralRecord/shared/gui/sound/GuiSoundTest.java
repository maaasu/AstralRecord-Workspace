package io.github.maaasu.astralRecord.shared.gui.sound;

import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class GuiSoundTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/20-shop/20_4-統合フロー.md
     * 章・見出し: # 20_4-統合フロー > ## 3. Preview・購入 > ### 処理要点
     * 検証契約: ショップ購入成功に汎用クリック音ではなく購入結果音を割り当てる。
     */
    @Test
    void purchaseUsesTradeSoundWithPlayerCategory() {
        Player player = mock(Player.class);

        GuiSound.PURCHASE.play(player);

        verify(player).playSound(
                isNull(Location.class),
                eq(Sound.ENTITY_VILLAGER_TRADE),
                eq(SoundCategory.PLAYERS),
                eq(0.75f),
                eq(1.1f)
        );
    }
}
