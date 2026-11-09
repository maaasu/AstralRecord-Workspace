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
import static io.github.maaasu.astralRecord.feature.hud.view.HudOverlayGlyphs.appendLine;
import static io.github.maaasu.astralRecord.feature.hud.view.HudOverlayGlyphs.SOLID_PIXEL;
import static io.github.maaasu.astralRecord.feature.hud.view.HudOverlayGlyphs.fit;
import static io.github.maaasu.astralRecord.feature.hud.view.HudOverlayGlyphs.width;

/** Java版の右上へ、現在地・挑戦・効果を表示します。 */
final class LocationHudGlyphs {
    private static final Key TEXT_FONT = Key.key("astralrecord", "location_hud_text");
    private static final Key ICON_FONT = Key.key("astralrecord", "location_hud_icons");
    private static final int LEFT = 10;
    private static final int RIGHT = 164;
    private static final int BADGE_PADDING = 4;
    private static final int COLUMN_SPLIT = 94;
    private static final int MAX_ROWS = 16;
    private static final int MAX_CHALLENGE_ROWS = 8;
    private static final int MAX_BUFF_ROWS = 5;
    private static final int PANEL_MARKER = 128;
    private static final int DIVIDER_MARKER = 143;
    private static final int BADGE_MARKER = 144;
    private static final int SHADOW_MARKER = 146;
    private static final String PARTICIPANT_SEPARATOR = "、";

    private LocationHudGlyphs() {
    }

    /** 現在地と該当する挑戦を整形し、空き行数に合わせて効果を一度だけ選びます。 */
    static List<Panel> layout(String world, String area, int level, @Nullable Performance performance,
                             @Nullable BossChallengeSidebarInfo boss, @Nullable DungeonSidebarInfo dungeon,
                             SidebarBuffPageState pageState, List<ActiveBuff> activeBuffs, boolean showBuffs,
                             BuffHudIcons icons, LocalDateTime now) {
        Panel location = locationPanel(world, area, level, performance);
        List<Panel> panels = new ArrayList<>(3);
        panels.add(location);
        int occupied = location.rows().size();
        if (boss != null || dungeon != null) {
            Panel challenge = challengePanel(boss, dungeon);
            panels.add(challenge);
            occupied += challenge.rows().size();
        }
        // 複数件の効果には件数・ページ番号を1行確保します。
        int pageSize = Math.min(MAX_BUFF_ROWS, MAX_ROWS - occupied - 1);
        SidebarBuffPageState.Page page = pageState.select(activeBuffs, showBuffs, pageSize);
        if (!page.buffs().isEmpty()) {
            panels.add(effectsPanel(page, icons, now));
        }
        return List.copyOf(panels);
    }

    /** ワールド名を見出しにし、その下にエリア・Lvと任意の性能行を配置します。 */
    private static Panel locationPanel(String world, String area, int level, @Nullable Performance performance) {
        String unknown = message(HudMsgId.P_5673);
        String worldName = ColorCodeUtil.toPlainText(world, unknown).strip();
        String areaName = ColorCodeUtil.toPlainText(area, unknown);
        List<Row> rows = new ArrayList<>(4);
        int split = HudOverlayGlyphs.prefixEnd(worldName, RIGHT - LEFT);
        rows.add(new Row(left(worldName.substring(0, split).stripTrailing(), LEFT, RIGHT, Palette.WHITE)));
        if (split < worldName.length()) {
            rows.add(new Row(left(worldName.substring(split).stripLeading(), LEFT, RIGHT, Palette.WHITE)));
        }

        String levelText = "Lv." + Math.max(0, level);
        int badgeWidth = textWidth(levelText) + BADGE_PADDING * 2;
        int badgeLeft = RIGHT - badgeWidth;
        String areaLabel = message(HudMsgId.P_5664);
        int areaLeft = LEFT + textWidth(areaLabel) + 4;
        rows.add(new Row(List.of(left(areaLabel, LEFT, areaLeft, Palette.GRAY),
                left(areaName, areaLeft, badgeLeft - 3, Palette.WHITE),
                right(levelText, badgeLeft + BADGE_PADDING, RIGHT - BADGE_PADDING, Palette.TEAL)), 0, false, badgeWidth));

        if (performance != null) {
            Row values = splitRow(message(HudMsgId.P_5674), String.format(Locale.ROOT, "%.1f", performance.mspt()),
                    performance.msptColor(), message(HudMsgId.P_5675), performance.ping() + "ms", performance.pingColor());
            rows.add(new Row(values.labels(), 0, true, 0));
        }
        return new Panel(Tone.LOCATION, rows);
    }

    /** ボスを優先し、ダンジョンでは部屋進捗や帰還待ちも含めます。 */
    private static Panel challengePanel(@Nullable BossChallengeSidebarInfo boss, @Nullable DungeonSidebarInfo dungeon) {
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
        return new Panel(Tone.CHALLENGE, rows);
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
            rows.add(new Row(labels, 0, false, 0));
        }
    }

    /** 期限内の効果をアイコン、名称、残り時間で表示します。 */
    private static Panel effectsPanel(SidebarBuffPageState.Page page, BuffHudIcons icons, LocalDateTime now) {
        List<Row> rows = new ArrayList<>();
        if (page.totalCount() > 1) {
            String title = PlayerMsgResource.format(HudMsgId.P_5660.getId(), page.totalCount(), page.pageIndex() + 1, page.pageCount());
            rows.add(new Row(left(ColorCodeUtil.toPlainText(title, ""), LEFT, RIGHT, Palette.TEAL)));
        }
        for (ActiveBuff buff : page.buffs()) {
            String name = ColorCodeUtil.toPlainText(buff.getType().getDisplayName(), message(HudMsgId.P_5673));
            String remaining = PlayerHudView.formatRemaining(Math.max(0L, Duration.between(now, buff.getExpiresAt()).toSeconds()));
            int timeLeft = RIGHT - textWidth(remaining);
            rows.add(new Row(List.of(left(name, LEFT + 15, timeLeft - 5, buff.getType().isDebuff() ? Palette.RED : Palette.PURPLE),
                    right(remaining, timeLeft, RIGHT, Palette.WHITE)), icons.glyph(buff.getType()), false, 0));
        }
        return new Panel(Tone.EFFECTS, rows);
    }

    /** 画面固定シェーダー用のRGBマーカーを、幅ゼロのActionBar追加要素へ変換します。 */
    static Component render(List<Panel> panels) {
        int total = panels.stream().mapToInt(panel -> panel.rows().size()).sum();
        TextComponent.Builder result = HudOverlayGlyphs.builder();
        int textMarker = (total - 1) * 8;
        int shadowMarker = SHADOW_MARKER + total - 1;
        int rowIndex = 0;
        for (Panel panel : panels) {
            int marker = PANEL_MARKER + panel.tone().ordinal() * 5;
            int startRow = rowIndex;
            int span = startRow * 8 + panel.rows().size() - 1;
            for (int part = 0; part < 5; part++) {
                appendPixel(result, marker + part, span, total);
            }
            for (Row row : panel.rows()) {
                if (row.divider() || (panel.tone() == Tone.CHALLENGE && rowIndex == startRow + 2)) {
                    appendPixel(result, DIVIDER_MARKER, rowIndex, total);
                }
                if (row.badgeWidth() > 0) {
                    appendPixel(result, BADGE_MARKER + rowIndex - 1, row.badgeWidth(), total);
                }
                if (row.icon() != 0) {
                    appendGlyph(result, ICON_FONT, row.icon(), BuffHudIcons.ICON_ADVANCE,
                            textMarker + Palette.ICON.ordinal(), LEFT, rowIndex);
                }
                for (Label label : row.labels()) {
                    List<Integer> glyphs = fit(label.text(), label.right() - label.left());
                    int x = label.rightAligned() ? label.right() - width(glyphs) : label.left();
                    // 本文と同じフォントの字形に影を付け、背景とのテクスチャ順に依存せず読めるようにします。
                    appendLine(result, TEXT_FONT, glyphs, x, rowIndex, textMarker + label.palette().ordinal(), shadowMarker);
                }
                rowIndex++;
            }
        }
        return result.build();
    }

    /** パネルと効果アイコンを同じフォントのテクスチャページで描画します。 */
    private static void appendPixel(TextComponent.Builder result, int red, int green, int blue) {
        appendGlyph(result, ICON_FONT, SOLID_PIXEL, 7, red, green, blue);
    }

    /** 左右の列へラベルと値を並べます。 */
    private static Row splitRow(String labelA, String valueA, Palette colorA, String labelB, String valueB, Palette colorB) {
        int leftValue = LEFT + textWidth(labelA) + 4;
        int rightValue = COLUMN_SPLIT + textWidth(labelB) + 4;
        return new Row(left(labelA, LEFT, leftValue, Palette.GRAY), left(valueA, leftValue, COLUMN_SPLIT - 5, colorA),
                left(labelB, COLUMN_SPLIT, rightValue, Palette.GRAY), left(valueB, rightValue, RIGHT, colorB));
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

    /** @return 折り返し前の字送り */
    private static int textWidth(String text) {
        return width(fit(text, Integer.MAX_VALUE));
    }

    /** @return 挑戦時間の分:秒表示 */
    private static String clock(long seconds) {
        return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }

    /** @return HUDに表示する固定ラベル */
    private static String message(HudMsgId id) {
        return PlayerMsgResource.getMessage(id.getId());
    }

    private enum Tone { LOCATION, CHALLENGE, EFFECTS }

    private enum Palette { WHITE, GRAY, GOLD, TEAL, RED, PURPLE, YELLOW, ICON }

    record Performance(double mspt, int ping, NamedTextColor msptStatus, NamedTextColor pingStatus) {
        /** @return MSPTの状態色をパック用パレットへ変換した値 */
        private Palette msptColor() {
            return color(msptStatus);
        }

        /** @return Pingの状態色をパック用パレットへ変換した値 */
        private Palette pingColor() {
            return color(pingStatus);
        }

        /** @return 性能値の状態色に対応するHUD色 */
        private static Palette color(NamedTextColor status) {
            if (status == NamedTextColor.RED) return Palette.RED;
            if (status == NamedTextColor.YELLOW) return Palette.YELLOW;
            return Palette.TEAL;
        }
    }

    record Panel(Tone tone, List<Row> rows) {
        Panel {
            rows = List.copyOf(rows);
        }
    }

    private record Row(List<Label> labels, int icon, boolean divider, int badgeWidth) {
        private Row(Label... labels) {
            this(List.of(labels), 0, false, 0);
        }

        private Row {
            labels = List.copyOf(labels);
        }
    }

    private record Label(String text, int left, int right, Palette palette, boolean rightAligned) {
    }
}
