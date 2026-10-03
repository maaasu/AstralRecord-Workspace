package io.github.maaasu.astralRecord.feature.combat.service;

import io.github.maaasu.astralRecord.feature.combat.model.EncounterCombatStatistics;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgResource;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/** ボスとダンジョンで共通の戦績ランキング本文を生成します。 */
public final class EncounterResultFormatter {
    private EncounterResultFormatter() { }

    /**
     * 各指標を多い順に並べ、未記録の参加者も0として表示します。
     * @param statistics 挑戦中の確定値
     * @param participants 結果に含める固定参加者
     * @param deaths 参加者ごとの死亡回数
     * @param names 表示名の解決関数
     * @return リソースで整形した与ダメージ・被ダメージ・ヒール量の順位
     */
    public static String format(EncounterCombatStatistics statistics, Collection<UUID> participants,
                                Map<UUID, Integer> deaths, Function<UUID, String> names) {
        return ranking(PlayerMsgId.P_6550, statistics.damageDealtSnapshot(), participants, deaths, names)
                + "\n" + ranking(PlayerMsgId.P_6551, statistics.damageTakenSnapshot(), participants, deaths, names)
                + "\n" + ranking(PlayerMsgId.P_6552, statistics.healingSnapshot(), participants, deaths, names);
    }

    /**
     * 指標の合計・比率を同じスナップショットから計算して順位行へ変換します。
     * @param heading 指標の見出しID
     * @param amounts 指標の確定値
     * @param participants 固定参加者
     * @param deaths 死亡回数
     * @param names 表示名の解決関数
     * @return 合計と全参加者の順位行
     */
    private static String ranking(PlayerMsgId heading, Map<UUID, Double> amounts,
                                  Collection<UUID> participants, Map<UUID, Integer> deaths,
                                  Function<UUID, String> names) {
        double total = participants.stream().mapToDouble(id -> amounts.getOrDefault(id, 0.0D)).sum();
        List<UUID> ranked = participants.stream()
                .sorted(Comparator.<UUID>comparingDouble(id -> amounts.getOrDefault(id, 0.0D)).reversed())
                .toList();
        StringBuilder text = new StringBuilder(PlayerMsgResource.format(heading.getId(), number(total)));
        for (int index = 0; index < ranked.size(); index++) {
            UUID playerId = ranked.get(index);
            double amount = amounts.getOrDefault(playerId, 0.0D);
            String rate = String.format(Locale.ROOT, "%.1f", total > 0.0D ? amount * 100.0D / total : 0.0D);
            text.append('\n').append(PlayerMsgResource.format(PlayerMsgId.P_6553.getId(),
                    index + 1, names.apply(playerId), number(amount), rate, deaths.getOrDefault(playerId, 0)));
        }
        return text.toString();
    }

    /**
     * 実量を整数表示へ整形します。
     * @param value 実量
     * @return ロケールに依存しない整数表示
     */
    private static String number(double value) {
        return String.format(Locale.ROOT, "%.0f", Math.max(0.0D, value));
    }
}
