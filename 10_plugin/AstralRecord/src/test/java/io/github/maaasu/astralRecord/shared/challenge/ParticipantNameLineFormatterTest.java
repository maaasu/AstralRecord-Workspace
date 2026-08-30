package io.github.maaasu.astralRecord.shared.challenge;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ParticipantNameLineFormatterTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/10-hud/3-メソッド仕様/10_3-View.md
     * 章・見出し: # 10_3-View > ## 4. sidebar 描画・解除
     * 検証契約: 参加者名を区切り位置で20文字を目安に折り返し、空一覧は1行として扱い、長い1名の名前は分割しない。
     */
    @Test
    void wrapsAtBoundaryWithoutSplittingParticipantNames() {
        List<String> names = List.of(
                "123456789",
                "1234567890",
                "A",
                "123456789012345678901"
        );

        assertEquals(
                List.of(
                        List.of("123456789", "1234567890"),
                        List.of("A"),
                        List.of("123456789012345678901")
                ),
                ParticipantNameLineFormatter.wrap(names)
        );
        assertEquals(3, ParticipantNameLineFormatter.lineCount(names));
        assertEquals(List.of(List.of()), ParticipantNameLineFormatter.wrap(List.of()));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/10-hud/3-メソッド仕様/10_3-View.md
     * 章・見出し: # 10_3-View > ## 4. sidebar 描画・解除
     * 検証契約: 15行サイドバーへ収まらない参加者は名前を分割せず、最終行へ省略人数を表示する。
     */
    @Test
    void limitsSidebarLinesWithExplicitOverflowCount() {
        List<String> names = List.of(
                "12345678901234567890",
                "ABCDEFGHIJKLMNOPQRST",
                "abcdefghijklmnopqrst",
                "あいうえおかきくけこさしすせそたちつてと",
                "PlayerFives",
                "PlayerSix"
        );

        assertEquals(
                List.of(
                        List.of("12345678901234567890"),
                        List.of("ABCDEFGHIJKLMNOPQRST"),
                        List.of("abcdefghijklmnopqrst"),
                        List.of("あいうえおかきくけこさしすせそたちつてと"),
                        List.of("…ほか2人")
                ),
                ParticipantNameLineFormatter.wrap(
                        names,
                        ParticipantNameLineFormatter.MAX_SIDEBAR_PARTICIPANT_LINES
                )
        );
        assertEquals(
                ParticipantNameLineFormatter.MAX_SIDEBAR_PARTICIPANT_LINES,
                ParticipantNameLineFormatter.sidebarLineCount(names)
        );
    }
}
