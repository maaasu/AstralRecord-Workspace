package io.github.maaasu.astralRecord.feature.item.castdisk;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

class CastDiskSettingsTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 7. 補助サービス > ### キャストディスク
     * 検証契約: スキルキャストディスクは他機能のmetadataを保持したまま、アクション枠と武器枠だけを保存して再読込する。
     */
    @Test
    void storesSlotNumbersWithoutDiscardingOtherMetadata() {
        CastDiskSettings expected = new CastDiskSettings(5, 8);

        String updated = CastDiskSettings.write("{\"other\":{\"value\":1}}", expected);

        assertEquals(expected, CastDiskSettings.read(updated));
        assertTrue(CastDiskSettings.read(updated).isComplete());
        assertTrue(JsonParser.parseString(updated).getAsJsonObject().has("other"));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 7. 補助サービス > ### キャストディスク
     * 検証契約: 未設定または範囲外の設定値を持つディスクは発動設定が未完了として扱う。
     */
    @Test
    void treatsMissingAndOutOfRangeSlotsAsIncomplete() {
        assertFalse(CastDiskSettings.read(null).isComplete());
        assertFalse(CastDiskSettings.read("{\"castDisk\":{\"actionSlot\":6,\"weaponHotbarSlot\":9}}").isComplete());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 7. 補助サービス > ### キャストディスク
     * 検証契約: ドッジキャストディスクの受付は開始から250ms未満だけ有効で、期限切れの受付は通常ドッジへ委譲できる。
     */
    @Test
    void acceptsOnlyTheConfiguredDodgeWindow() {
        assertTrue(CastDiskUseService.isWithinDodgeWindow(1_000L, 1_249L));
        assertFalse(CastDiskUseService.isWithinDodgeWindow(1_000L, 1_250L));
        assertFalse(CastDiskUseService.isWithinDodgeWindow(1_000L, 999L));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 7. 補助サービス > ### キャストディスク
     * 検証契約: ドッジキャストディスクの3秒クールダウンはディスク個体ではなくプレイヤー単位で共有する。
     */
    @Test
    void sharesCooldownAcrossAllDisksForOnePlayer() {
        DodgeCastDiskCooldown cooldown = new DodgeCastDiskCooldown();
        UUID playerId = UUID.randomUUID();

        cooldown.start(playerId, 10_000L);

        assertTrue(cooldown.isActive(playerId, 12_999L));
        assertFalse(cooldown.isActive(playerId, 13_000L));
        assertFalse(cooldown.isActive(UUID.randomUUID(), 12_999L));
    }
}
