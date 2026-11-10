package io.github.maaasu.astralRecord.feature.hud.view;

import io.github.maaasu.astralRecord.feature.boss.model.BossChallengeSidebarInfo;
import io.github.maaasu.astralRecord.feature.buff.model.ActiveBuff;
import io.github.maaasu.astralRecord.feature.dungeon.model.DungeonSidebarInfo;
import io.github.maaasu.astralRecord.feature.hud.HudMsgId;
import io.github.maaasu.astralRecord.feature.hud.model.SidebarBuffPageState;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgResource;
import io.github.maaasu.astralRecord.infrastructure.util.ColorCodeUtil;
import io.github.maaasu.astralRecord.shared.challenge.ChallengeWaitingStatus;
import io.github.maaasu.astralRecord.shared.challenge.ParticipantNameLineFormatter;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static io.github.maaasu.astralRecord.feature.hud.view.HudOverlayGlyphs.appendGlyph;

/** Java版の右上へ、現在地・挑戦・効果を表示します。 */
final class LocationHudGlyphs {
    private static final HudOverlayGlyphs.Typeface TEXT = new HudOverlayGlyphs.Typeface(
            Key.key("astralrecord", "location_hud_text"), LocationHudGlyphs::textAdvance);
    private static final Key ICON_FONT = Key.key("astralrecord", "location_hud_icons");
    private static final int LEFT = 10;
    private static final int RIGHT = 166;
    private static final int COLUMN_SPLIT = 94;
    private static final int MAX_ROWS = 16;
    private static final int MAX_CHALLENGE_ROWS = 8;
    private static final int MAX_BUFF_ROWS = 5;
    private static final int SHADOW_ROW_OFFSET = 16;
    private static final int PERFORMANCE_PALETTE_OFFSET = 32;
    private static final String PARTICIPANT_SEPARATOR = "、";

    private LocationHudGlyphs() {
    }

    /** 現在地と該当する挑戦を整形し、空き行数に合わせて効果を一度だけ選びます。 */
    static List<Row> layout(String world, String area, int level, @Nullable Performance performance,
                             @Nullable BossChallengeSidebarInfo boss, @Nullable DungeonSidebarInfo dungeon,
                             SidebarBuffPageState pageState, List<ActiveBuff> activeBuffs, boolean showBuffs,
                             BuffHudIcons icons, LocalDateTime now) {
        List<Row> rows = locationRows(world, area, level, performance);
        if (boss != null || dungeon != null) {
            rows.addAll(challengeRows(boss, dungeon));
        }
        // 複数件の効果には件数・ページ番号を1行確保します。
        int pageSize = Math.min(MAX_BUFF_ROWS, MAX_ROWS - rows.size() - 1);
        SidebarBuffPageState.Page page = pageState.select(activeBuffs, showBuffs, pageSize);
        if (!page.buffs().isEmpty()) {
            rows.addAll(effectRows(page, icons, now));
        }
        return List.copyOf(rows);
    }

    /** ワールド名、エリア・金色のLv、任意の性能情報を右端に揃えます。 */
    private static List<Row> locationRows(String world, String area, int level, @Nullable Performance performance) {
        String unknown = message(HudMsgId.P_5673);
        String worldName = ColorCodeUtil.toPlainText(world, unknown).strip();
        String areaName = ColorCodeUtil.toPlainText(area, unknown).strip();
        List<Row> rows = new ArrayList<>(4);
        int split = TEXT.prefixEnd(worldName, RIGHT - LEFT);
        rows.add(new Row(right(worldName.substring(0, split).stripTrailing(), LEFT, RIGHT, Palette.WHITE)));
        if (split < worldName.length()) {
            rows.add(new Row(right(worldName.substring(split).stripLeading(), LEFT, RIGHT, Palette.WHITE)));
        }

        String levelText = "Lv." + Math.max(0, level);
        int levelLeft = RIGHT - textWidth(levelText);
        rows.add(new Row(right(areaName, LEFT, levelLeft - 6, Palette.SECONDARY),
                right(levelText, levelLeft, RIGHT, Palette.GOLD)));

        if (performance != null) {
            rows.add(performanceRow(performance));
        }
        return rows;
    }

    /** MSPTとPingを、アクションバーと同じ文字送りで右寄せします。 */
    private static Row performanceRow(Performance performance) {
        String msptLabel = message(HudMsgId.P_5674);
        String msptValue = String.format(Locale.ROOT, "%.1f", performance.mspt());
        String pingLabel = message(HudMsgId.P_5675);
        String pingValue = performance.ping() + "ms";
        int pingValueLeft = RIGHT - textWidth(pingValue);
        int pingLeft = pingValueLeft - 3 - textWidth(pingLabel);
        int msptRight = pingLeft - 9;
        int msptValueLeft = Math.max(LEFT + textWidth(msptLabel) + 3, msptRight - textWidth(msptValue));
        return new Row(right(msptLabel, LEFT, msptValueLeft - 3, Palette.GRAY),
                right(msptValue, msptValueLeft, msptRight, Palette.performanceColor(performance.msptStatus())),
                left(pingLabel, pingLeft, pingValueLeft - 3, Palette.GRAY),
                right(pingValue, pingValueLeft, RIGHT, Palette.performanceColor(performance.pingStatus())));
    }

    /** ボスを優先し、ダンジョンでは部屋進捗や帰還待ちも含めます。 */
    private static List<Row> challengeRows(@Nullable BossChallengeSidebarInfo boss, @Nullable DungeonSidebarInfo dungeon) {
        List<Row> rows = new ArrayList<>(MAX_CHALLENGE_ROWS);
        if (boss != null) {
            addChallengeTitle(rows, HudMsgId.P_5665, boss.bossDisplayName());
            addWaitingStatus(rows, boss.waitingStatus());
            rows.add(splitRow(message(HudMsgId.P_5667), clock(boss.elapsedSeconds()) + "/" + clock(boss.timeLimitSeconds()),
                    Palette.WHITE, message(HudMsgId.P_5668), boss.deathCount() + "/" + boss.deathLimit(),
                    boss.deathCount() > 0 ? Palette.RED : Palette.WHITE));
            addParticipants(rows, boss.participantNames(), boss.waitingParticipantNames());
        } else if (dungeon != null) {
            addChallengeTitle(rows, HudMsgId.P_5666, dungeon.dungeonDisplayName());
            addWaitingStatus(rows, dungeon.waitingStatus());
            rows.add(splitRow(message(HudMsgId.P_5670), dungeon.clearedRooms() + "/" + dungeon.totalRooms(),
                    Palette.WHITE, message(HudMsgId.P_5668), dungeon.deathCount() + "/" + dungeon.deathLimit(),
                    dungeon.deathCount() > 0 ? Palette.RED : Palette.WHITE));
            if (dungeon.timeLimitSeconds() != null) {
                rows.add(pair(message(HudMsgId.P_5667), clock(dungeon.elapsedSeconds()) + "/" + clock(dungeon.timeLimitSeconds()),
                        Palette.WHITE));
            }
            if (dungeon.returnRemainingSeconds() >= 0) {
                rows.add(pair(message(HudMsgId.P_5671), PlayerHudView.formatRemaining(dungeon.returnRemainingSeconds()), Palette.GOLD));
            } else if (!dungeon.waitingStatus().isVisible()) {
                rows.add(new Row(left(message(HudMsgId.P_5672), LEFT, RIGHT, Palette.GRAY)));
            }
            addParticipants(rows, dungeon.participantNames(), dungeon.waitingParticipantNames());
        }
        return rows;
    }

    /** 挑戦種別と名称を2行に分けます。 */
    private static void addChallengeTitle(List<Row> rows, HudMsgId title, String name) {
        String caption = message(title);
        rows.add(new Row(left(caption, LEFT, RIGHT, Palette.GOLD)));
        rows.add(new Row(left(ColorCodeUtil.toPlainText(name, caption), LEFT, RIGHT, Palette.WHITE)));
    }

    /** 入場準備中にだけ既存の待機案内を追加します。 */
    private static void addWaitingStatus(List<Row> rows, ChallengeWaitingStatus status) {
        if (status.messageId() != null) {
            String text = PlayerMsgResource.getMessage(status.messageId().getId());
            rows.add(new Row(left(text, LEFT, RIGHT, Palette.GOLD)));
        }
    }

    /** 参加者名を字送りで折り返し、未到着者は灰色で表示します。 */
    private static void addParticipants(List<Row> rows, List<String> names, Set<String> waiting) {
        String caption = message(HudMsgId.P_5669);
        int labelEnd = LEFT + textWidth(caption) + 5;
        List<List<String>> lines = ParticipantNameLineFormatter.wrap(names, MAX_CHALLENGE_ROWS - rows.size(),
                name -> textWidth(ColorCodeUtil.toPlainText(name, "")), RIGHT - labelEnd, textWidth(PARTICIPANT_SEPARATOR));
        for (int index = 0; index < lines.size(); index++) {
            List<String> namesInRow = lines.get(index);
            List<Label> labels = new ArrayList<>();
            if (index == 0) {
                labels.add(left(caption, LEFT, labelEnd, Palette.GRAY));
            }
            if (namesInRow.size() == 1) {
                String name = namesInRow.getFirst();
                labels.add(right(ColorCodeUtil.toPlainText(name, ""), labelEnd, RIGHT,
                        waiting.contains(name) ? Palette.GRAY : Palette.WHITE));
            } else if (!namesInRow.isEmpty()) {
                int totalWidth = namesInRow.stream().mapToInt(name -> textWidth(ColorCodeUtil.toPlainText(name, ""))).sum()
                        + (namesInRow.size() - 1) * textWidth(PARTICIPANT_SEPARATOR);
                int x = RIGHT - totalWidth;
                for (int n = 0; n < namesInRow.size(); n++) {
                    String name = namesInRow.get(n);
                    String display = ColorCodeUtil.toPlainText(name, "") + (n + 1 == namesInRow.size() ? "" : PARTICIPANT_SEPARATOR);
                    int next = x + textWidth(display);
                    labels.add(left(display, x, next, waiting.contains(name) ? Palette.GRAY : Palette.WHITE));
                    x = next;
                }
            }
            rows.add(new Row(labels, 0));
        }
    }

    /** 期限内の効果をアイコン、名称、残り時間で表示します。 */
    private static List<Row> effectRows(SidebarBuffPageState.Page page, BuffHudIcons icons, LocalDateTime now) {
        List<Row> rows = new ArrayList<>();
        if (page.totalCount() > 1) {
            String title = PlayerMsgResource.format(HudMsgId.P_5660.getId(), page.totalCount(), page.pageIndex() + 1, page.pageCount());
            rows.add(new Row(left(ColorCodeUtil.toPlainText(title, ""), LEFT, RIGHT, Palette.SECONDARY)));
        }
        for (ActiveBuff buff : page.buffs()) {
            String name = ColorCodeUtil.toPlainText(buff.getType().getDisplayName(), message(HudMsgId.P_5673));
            String remaining = PlayerHudView.formatRemaining(Math.max(0L, Duration.between(now, buff.getExpiresAt()).toSeconds()));
            int timeLeft = RIGHT - textWidth(remaining);
            rows.add(new Row(List.of(left(name, LEFT + 15, timeLeft - 5, buff.getType().isDebuff() ? Palette.RED : Palette.PURPLE),
                    right(remaining, timeLeft, RIGHT, Palette.WHITE)), icons.glyph(buff.getType())));
        }
        return rows;
    }

    /** 画面固定シェーダー用のRGBマーカーを、幅ゼロのActionBar追加要素へ変換します。 */
    static Component render(List<Row> rows) {
        TextComponent.Builder result = HudOverlayGlyphs.builder();
        int textMarker = (rows.size() - 1) * 8;
        for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
            Row row = rows.get(rowIndex);
            if (row.icon() != 0) {
                appendGlyph(result, ICON_FONT, row.icon(), BuffHudIcons.ICON_ADVANCE,
                        textMarker + Palette.ICON.ordinal(), LEFT, rowIndex);
            }
            for (Label label : row.labels()) {
                List<Integer> glyphs = TEXT.fit(label.text(), label.right() - label.left());
                int x = label.rightAligned() ? label.right() - TEXT.width(glyphs) : label.left();
                int palette = label.palette().ordinal();
                int encodedRow = rowIndex + (palette >= 8 ? PERFORMANCE_PALETTE_OFFSET : 0);
                TEXT.appendLine(result, glyphs, x, encodedRow, textMarker + (palette & 7), SHADOW_ROW_OFFSET);
            }
        }
        return result.build();
    }

    /** 左右の列へラベルと値を並べます。 */
    private static Row splitRow(String labelA, String valueA, Palette colorA, String labelB, String valueB, Palette colorB) {
        int leftValue = LEFT + textWidth(labelA) + 4;
        int rightLabelWidth = textWidth(labelB);
        int latestSplit = RIGHT - rightLabelWidth - 4 - textWidth(valueB);
        int split = Math.max(COLUMN_SPLIT, Math.min(leftValue + textWidth(valueA) + 5, latestSplit));
        int rightValue = split + rightLabelWidth + 4;
        return new Row(left(labelA, LEFT, leftValue, Palette.GRAY), left(valueA, leftValue, split - 5, colorA),
                left(labelB, split, rightValue, Palette.GRAY), left(valueB, rightValue, RIGHT, colorB));
    }

    /** 1項目を左ラベルと右値へ分けます。 */
    private static Row pair(String label, String value, Palette color) {
        int labelEnd = LEFT + textWidth(label) + 5;
        return new Row(left(label, LEFT, labelEnd, Palette.GRAY), right(value, labelEnd, RIGHT, color));
    }

    /** @return 左揃えの文字列 */
    private static Label left(String text, int left, int right, Palette palette) {
        return new Label(text, left, right, palette, false);
    }

    /** @return 右揃えの文字列 */
    private static Label right(String text, int left, int right, Palette palette) {
        return new Label(text, left, right, palette, true);
    }

    /** @return アクションバー字形があればその送り幅、その他は現在地用の送り幅 */
    private static int textAdvance(int codePoint) {
        int gameplayAdvance = JavaProfileHud.gameplayTextAdvance(codePoint);
        return gameplayAdvance != 0 ? gameplayAdvance : ClassNameGlyphs.advance(codePoint, true);
    }

    /** @return 折り返し前の字送り */
    private static int textWidth(String text) {
        return TEXT.width(TEXT.fit(text, Integer.MAX_VALUE));
    }

    /** @return 挑戦時間の分:秒表示 */
    private static String clock(long seconds) {
        return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }

    /** @return HUDに表示する固定ラベル */
    private static String message(HudMsgId id) {
        return PlayerMsgResource.getMessage(id.getId());
    }

    /** 列挙順は現在地HUDシェーダーのパレット番号と一致させます。 */
    private enum Palette {
        WHITE, GRAY, GOLD, SECONDARY, RED, PURPLE, YELLOW, ICON,
        PERFORMANCE_GREEN, PERFORMANCE_RED, PERFORMANCE_YELLOW;

        /**
         * @param status 性能値の状態色
         * @return 状態色に対応するHUD色
         */
        private static Palette performanceColor(NamedTextColor status) {
            if (status == NamedTextColor.RED) return PERFORMANCE_RED;
            if (status == NamedTextColor.YELLOW) return PERFORMANCE_YELLOW;
            return PERFORMANCE_GREEN;
        }
    }

    record Performance(double mspt, int ping, NamedTextColor msptStatus, NamedTextColor pingStatus) {
    }

    record Row(List<Label> labels, int icon) {
        private Row(Label... labels) {
            this(List.of(labels), 0);
        }

        Row {
            labels = List.copyOf(labels);
        }
    }

    private record Label(String text, int left, int right, Palette palette, boolean rightAligned) {
    }
}
