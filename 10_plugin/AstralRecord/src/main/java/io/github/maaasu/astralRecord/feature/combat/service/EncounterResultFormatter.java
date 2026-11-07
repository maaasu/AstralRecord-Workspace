package io.github.maaasu.astralRecord.feature.combat.service;

import io.github.maaasu.astralRecord.feature.combat.model.EncounterCombatStatistics;
import io.github.maaasu.astralRecord.feature.mob.service.MobDropPresentationService.RewardResult;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgResource;
import io.github.maaasu.astralRecord.shared.display.CombatRewardPlaque;
import io.github.maaasu.astralRecord.shared.display.DisplaySeparators;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

/** ボスとダンジョンで共通の参加者別戦績と統合リザルトを整形します。 */
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
     * 個人報酬を先に、確定戦績を後に表示する専用プレートを作ります。
     * @param heading タイトルと攻略対象名を改行で区切った見出し
     * @param elapsed 確定した所要時間
     * @param countdown 帰還残秒をその時点で返す関数
     * @param ranking 参加者ごとの空行区切りを含む確定戦績
     * @param rewards 閲覧者の討伐報酬。報酬対象外はnull
     * @param clearRewards 閲覧者のクリア報酬表示行。クリア報酬がなければ空
     * @return ページ分割した統合プレート
     */
    public static CombatRewardPlaque formatPlaque(String heading, String elapsed, Supplier<String> countdown,
                                                   String ranking, @Nullable RewardResult rewards,
                                                   List<CombatRewardPlaque.Row> clearRewards) {
        String[] headerLines = heading.split("\n", 2);
        List<CombatRewardPlaque.Row> rows = new ArrayList<>();
        rows.add(CombatRewardPlaque.Row.text(elapsed));
        if (rewards != null) {
            rows.add(CombatRewardPlaque.Row.text(PlayerMsgResource.getMessage(PlayerMsgId.P_8080.getId())));
            rows.addAll(rewards.rows());
        }
        rows.addAll(clearRewards);
        for (String block : ranking.split("\\R\\s*\\R")) {
            rows.add(CombatRewardPlaque.Row.text(block));
        }
        return CombatRewardPlaque.layout(headerLines[0], headerLines.length > 1 ? headerLines[1] : "", countdown, rows,
                rewards != null && rewards.rare());
    }

    /**
     * 戦績を先に、個人報酬を後に表示する従来の統合本文を作ります。
     * @param heading タイトルと攻略対象名を改行で区切った見出し
     * @param elapsed 確定した所要時間
     * @param countdown 現在の帰還残秒
     * @param ranking 参加者別戦績
     * @param rewards 閲覧者の個人報酬。報酬対象外はnull
     * @return 専用プレートに対応しない接続向けの従来本文
     */
    public static String formatFallback(String heading, String elapsed, String countdown,
                                        String ranking, @Nullable RewardResult rewards) {
        String separator = "\n\n&8" + DisplaySeparators.SECTION + "\n";
        return heading + "\n" + elapsed + " &8│ " + countdown + separator + ranking
                + (rewards == null || rewards.fallbackText().isEmpty() ? "" : separator + rewards.fallbackText());
    }

    /**
     * 帰還予定時刻から残秒を切り上げて表示用に整形します。
     * @param endsAtMs 帰還予定のエポックミリ秒
     * @return 現在の帰還残秒
     */
    public static String formatCountdown(long endsAtMs) {
        long remainingSeconds = Math.max(0L, (endsAtMs - System.currentTimeMillis() + 999L) / 1000L);
        return PlayerMsgResource.format(PlayerMsgId.P_6557.getId(), remainingSeconds);
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
