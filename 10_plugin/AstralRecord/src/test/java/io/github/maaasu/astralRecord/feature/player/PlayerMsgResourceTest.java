package io.github.maaasu.astralRecord.feature.player;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerMsgResourceTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/03-player/03_1-モデル定義.md
     * 章・見出し: # 03_1-モデル定義 > ## 8. プレイヤーメッセージリソース
     * 検証契約: 引数中の&カラーコードも§コードへ変換する。
     */
    @Test
    void formatTranslatesColorCodesInMessageArguments() {
        String formatted = PlayerMsgResource.format(PlayerMsgId.P_6608.getId(), "&eColored Quest");

        assertTrue(formatted.contains("\u00a7eColored Quest"));
        assertFalse(formatted.contains("&eColored Quest"));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/18-mail/18_5-例外・ログ・運用.md
     * 章・見出し: # 18_5-例外・ログ・運用 > ## ログ・メッセージ
     * 検証契約: メール報酬受取成功メッセージに報酬個数の「件」を表示しない。
     */
    @Test
    void mailRewardMessageDoesNotDisplayRewardCount() {
        String formatted = PlayerMsgResource.format(PlayerMsgId.P_5620.getId(), "報酬メール");

        assertEquals("§aメール「§e報酬メール§a」を既読にし、報酬を受け取りました。", formatted);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/32-dungeon/32_3-処理契約.md
     * 章・見出し: # 32_3-処理契約 > ## 6. クリア報酬と30秒回収
     * 検証契約: 改行を含む報酬チェスト表示へダンジョン名を差し込む。
     */
    @Test
    void dungeonRewardDisplayFormatsDungeonNameAcrossMultipleLines() {
        String formatted = PlayerMsgResource.format(PlayerMsgId.P_7033.getId(), "黄昏の坑道");

        assertEquals("§6黄昏の坑道\n§eダンジョン報酬\n§f右クリックで開く", formatted);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 7. 補助サービス > ### オーブ装備操作
     * 検証契約: 強化の確定結果通知は成功時だけでなく、失敗時も使用した成功率を表示する。
     */
    @Test
    void formatsEnhancementFailureMessagesWithSuccessRate() {
        String intact = PlayerMsgResource.format(PlayerMsgId.P_5258.getId(), "テスト装備", "35");
        String decreased = PlayerMsgResource.format(PlayerMsgId.P_5259.getId(), "テスト装備", 2, "35");

        assertTrue(intact.contains("成功率 35%"));
        assertTrue(decreased.contains("+2"));
        assertTrue(decreased.contains("成功率 35%"));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/03-player/03_1-モデル定義.md
     * 章・見出し: # 03_1-モデル定義 > ## 8. プレイヤーメッセージリソース
     * 検証契約: Component化後のplain textに&カラーコードを残さず色付き本文を保持する。
     */
    @Test
    void formatComponentDoesNotLeaveAmpersandColorCodesInPlainText() {
        Component component = PlayerMsgResource.formatComponent(PlayerMsgId.P_6608.getId(), "&eColored Quest");
        String legacyText = LegacyComponentSerializer.legacySection().serialize(component);
        String plainText = PlainTextComponentSerializer.plainText().serialize(component);

        assertTrue(legacyText.contains("\u00a7eColored Quest"));
        assertTrue(plainText.contains("Colored Quest"));
        assertFalse(plainText.contains("&e"));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/14-combat/14_5-例外・ログ・運用.md
     * 章・見出し: # 14_5-例外・ログ・運用 > ## 2. player message
     * 検証契約: damage詳細を相手名なしの短縮damage/attack/element/AP/DEF/RES/hit/critical形式と被弾者HPスナップショットで整形する。
     */
    @Test
    void damageDetailMessageFormatsCompactCalculationBreakdown() {
        String formatted = PlayerMsgResource.format(
                PlayerMsgId.P_5350.getId(),
                "&c125",
                "MEL",
                "FIR",
                "180",
                "80",
                "64",
                " RES25>15",
                "92",
                "95",
                "3",
                " &eCRIT",
                "(150/200->25)"
        );

        assertTrue(formatted.contains("125"));
        assertTrue(formatted.contains("MEL/FIR"));
        assertTrue(formatted.contains("AP180 DEF80>64 RES25>15"));
        assertTrue(formatted.contains("H92"));
        assertTrue(formatted.contains("A95-E3"));
        assertTrue(formatted.contains("\u00a7eCRIT"));
        assertTrue(formatted.contains("(150/200->25)"));
        assertFalse(formatted.contains("HP125"));
        assertFalse(formatted.contains("Test Mob"));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/14-combat/14_5-例外・ログ・運用.md
     * 章・見出し: # 14_5-例外・ログ・運用 > ## 2. player message
     * 検証契約: HP回復メッセージを黄緑色のプラス付き回復量として整形する。
     */
    @Test
    void hpRecoveryMessageFormatsLimePlusAmount() {
        String formatted = PlayerMsgResource.format(PlayerMsgId.P_5354.getId(), "12.5");

        assertEquals("§a+12.5", formatted);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/03-player/3-メソッド仕様/03_3-イベント.md
     * 章・見出し: # 03_3-イベント > ## 1. event メソッド仕様 > ### プレイヤー参加イベント受付
     * 設計入力: 00_docs/10_Plugin設計書/feature/08-inventory/3-メソッド仕様/08_3-タスク・補助.md
     * 章・見出し: # 08_3-タスク・補助 > ## 2. インベントリオートセーブ
     * 検証契約: join loadとautosave完了通知に経過millisecondsを(ms)形式で含める。
     */
    @Test
    void externalDataOperationCompletionMessagesIncludeElapsedMilliseconds() {
        String joinLoad = PlayerMsgResource.format(PlayerMsgId.P_5072.getId(), 123L);
        String autoSave = PlayerMsgResource.format(PlayerMsgId.P_5281.getId(), 456L);

        assertTrue(joinLoad.contains("(123ms)"));
        assertTrue(autoSave.contains("(456ms)"));
    }
}
