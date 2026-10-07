package io.github.maaasu.astralRecord.feature.combat.service;

import io.github.maaasu.astralRecord.feature.combat.model.EncounterCombatStatistics;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgResource;

import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/** ボスとダンジョンで共通の参加者別戦績を生成します。 */
public final class EncounterResultFormatter {
    private EncounterResultFormatter() { }

    /**
     * 与ダメージ順の参加者別に3指標をまとめ、死亡回数を一度だけ表示します。
     * 各指標の順位・構成比は同じ確定スナップショットから計算し、未記録値は0にします。
     * @param statistics 挑戦中の確定値
     * @param participants 結果に含める固定参加者
     * @param deaths 参加者ごとの死亡回数
     * @param names 表示名の解決関数
     * @return 合計と参加者別の実量・順位・構成比・死亡回数
     */
    public static String format(EncounterCombatStatistics statistics, Collection<UUID> participants,
                                Map<UUID, Integer> deaths, Function<UUID, String> names) {
        List<UUID> members = participants.stream().distinct().toList();
        Map<UUID, Double> dealt = statistics.damageDealtSnapshot();
        Map<UUID, Double> taken = statistics.damageTakenSnapshot();
        Map<UUID, Double> healing = statistics.healingSnapshot();
        double dealtTotal = total(dealt, members);
        double takenTotal = total(taken, members);
        double healingTotal = total(healing, members);
        Map<UUID, Integer> dealtRanks = ranks(dealt, members);
        Map<UUID, Integer> takenRanks = ranks(taken, members);
        Map<UUID, Integer> healingRanks = ranks(healing, members);
        StringBuilder text = new StringBuilder(PlayerMsgResource.getMessage(PlayerMsgId.P_6561.getId()));
        text.append('\n').append(PlayerMsgResource.format(PlayerMsgId.P_6560.getId(),
                PlayerMsgResource.format(PlayerMsgId.P_6550.getId(), number(dealtTotal)),
                PlayerMsgResource.format(PlayerMsgId.P_6551.getId(), number(takenTotal)),
                PlayerMsgResource.format(PlayerMsgId.P_6552.getId(), number(healingTotal))));
        for (UUID playerId : dealtRanks.keySet()) {
            double damage = dealt.getOrDefault(playerId, 0.0D);
            double received = taken.getOrDefault(playerId, 0.0D);
            double recovered = healing.getOrDefault(playerId, 0.0D);
            text.append("\n\n").append(PlayerMsgResource.format(PlayerMsgId.P_6553.getId(),
                    dealtRanks.get(playerId), names.apply(playerId), deaths.getOrDefault(playerId, 0)));
            text.append('\n').append(PlayerMsgResource.format(PlayerMsgId.P_6558.getId(),
                    number(damage), rate(damage, dealtTotal)));
            text.append('\n').append(PlayerMsgResource.format(PlayerMsgId.P_6559.getId(),
                    number(received), takenRanks.get(playerId), rate(received, takenTotal),
                    number(recovered), healingRanks.get(playerId), rate(recovered, healingTotal)));
        }
        return text.toString();
    }

    /**
     * 指標を多い順に並べ、固定参加者順で同値の順位を安定させます。
     * @param amounts 指標の確定値
     * @param participants 固定参加者
     * @return 値の降順に走査できる参加者別順位
     */
    private static Map<UUID, Integer> ranks(Map<UUID, Double> amounts, Collection<UUID> participants) {
        List<UUID> ranked = participants.stream()
                .sorted(Comparator.<UUID>comparingDouble(id -> amounts.getOrDefault(id, 0.0D)).reversed())
                .toList();
        Map<UUID, Integer> ranks = new LinkedHashMap<>();
        for (int index = 0; index < ranked.size(); index++) {
            ranks.put(ranked.get(index), index + 1);
        }
        return ranks;
    }

    /**
     * @param amounts 指標の確定値
     * @param participants 固定参加者
     * @return 固定参加者だけを含む指標の合計
     */
    private static double total(Map<UUID, Double> amounts, Collection<UUID> participants) {
        return participants.stream().mapToDouble(id -> amounts.getOrDefault(id, 0.0D)).sum();
    }

    /**
     * @param amount 個人の実量
     * @param total 参加者全員の合計
     * @return 合計0でも有限な小数1桁の構成比
     */
    private static String rate(double amount, double total) {
        return String.format(Locale.ROOT, "%.1f", total > 0.0D ? amount * 100.0D / total : 0.0D);
    }

    /**
     * 実量を整数表示へ整形します。
     * @param value 実量
     * @return ロケールに依存しない3桁区切りの整数表示
     */
    private static String number(double value) {
        return String.format(Locale.ROOT, "%,.0f", Math.max(0.0D, value));
    }
}
