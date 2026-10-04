package io.github.maaasu.astralRecord.feature.pet.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.NotNull;

import static io.github.maaasu.astralRecord.feature.pet.model.PetJson.array;
import static io.github.maaasu.astralRecord.feature.pet.model.PetJson.number;
import static io.github.maaasu.astralRecord.feature.pet.model.PetJson.object;

/** ペットの必要・獲得EXP、個体成長補正、外見サイズ倍率を純粋計算します。 */
public final class PetGrowthCalculator {
    private PetGrowthCalculator() { }

    /**
     * プレイヤー報酬を参照せず、討伐対象のレベルからペット用の基礎獲得EXPを計算します。
     * @param master 公開済みペットマスター
     * @param targetLevel 討伐された敵の実効レベル
     * @return 個体補正・活動倍率を適用する前の獲得EXP
     * @throws ArithmeticException 結果がlongの範囲外の場合
     */
    public static long combatExperience(@NotNull PetMaster master, int targetLevel) {
        return activityExperience(object(master.experience(), "combatGain"), targetLevel, 50, 25);
    }

    /**
     * プレイヤー報酬を参照せず、採集対象のレベルからペット用の基礎獲得EXPを計算します。
     * @param master 公開済みペットマスター
     * @param targetLevel 破壊された採集オブジェクトのレベル
     * @return 個体補正・活動倍率を適用する前の獲得EXP
     * @throws ArithmeticException 結果がlongの範囲外の場合
     */
    public static long gatheringExperience(@NotNull PetMaster master, int targetLevel) {
        return activityExperience(object(master.experience(), "gatheringGain"), targetLevel, 20, 10);
    }

    private static long activityExperience(JsonObject gain, int targetLevel, double defaultBase, double defaultPerLevel) {
        double amount = number(gain, "base", defaultBase)
            + number(gain, "perLevel", defaultPerLevel) * Math.max(1, targetLevel);
        if (!Double.isFinite(amount) || amount < 0 || amount >= Long.MAX_VALUE)
            throw new ArithmeticException("Pet activity experience exceeds long range");
        return (long) Math.floor(amount);
    }

    /**
     * 五つの基礎能力が種族の抽選範囲で高いほど、小さい獲得EXP倍率を返します。
     * @param master 公開済みペットマスター
     * @param speciesId 所有個体の種族ID
     * @param details 孵化済み個体の詳細。基礎能力だけを参照します
     * @return 成長補正。マスター下限から1までの倍率
     * @throws IllegalArgumentException 種族の基礎能力定義が不正な場合
     */
    public static double growthRate(@NotNull PetMaster master, @NotNull String speciesId, @NotNull JsonObject details) {
        JsonObject definitions = object(master.species(speciesId), "stats");
        JsonObject stats = object(details, "stats");
        double score = 0;
        for (String id : java.util.List.of("VITALITY", "POWER", "DEFENSE", "EVASION", "SUPPORT")) {
            JsonArray tiers = array(object(definitions, id), "tiers");
            double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
            for (var row : tiers) {
                JsonObject tier = row.getAsJsonObject();
                min = Math.min(min, number(tier, "min", 0));
                max = Math.max(max, number(tier, "max", 0));
            }
            if (!Double.isFinite(min) || !Double.isFinite(max) || max <= min)
                throw new IllegalArgumentException("Invalid pet stat range");
            score += Math.clamp((number(object(stats, id), "baseValue", min) - min) / (max - min), 0, 1);
        }
        double minimum = number(master.experience(), "growthRateMin", .25);
        return 1 - (1 - minimum) * score / 5;
    }

    /**
     * APIと同じ式で、指定成長度から次の成長度へ必要な経験値を返します。
     * @param master 読み込み済みのペットマスター
     * @param level 現在の成長度。1以上、最大成長度未満
     * @return 次の成長度までに必要な経験値
     * @throws IllegalArgumentException 成長度または曲線の間隔が不正な場合
     * @throws ArithmeticException 算出値がlongに収まらない場合
     */
    public static long requiredExperience(@NotNull PetMaster master, int level) {
        JsonObject curve = master.experience();
        int tierInterval = (int) number(curve, "tierInterval", 10);
        int milestoneInterval = (int) number(curve, "milestoneInterval", 5);
        JsonArray wave = array(curve, "wave");
        if (level < 1 || tierInterval < 1 || milestoneInterval < 1 || wave.isEmpty())
            throw new IllegalArgumentException("Invalid pet experience curve or level");
        double milestone = level % milestoneInterval == 0
            ? number(curve, "milestoneBase", 600) + level * number(curve, "milestoneLinear", 80) : 0;
        double required = number(curve, "base", 500) + level * (double) level * number(curve, "quadratic", 120)
            + level / tierInterval * number(curve, "tierBonus", 850)
            + wave.get((level - 1) % wave.size()).getAsDouble() + milestone;
        if (!Double.isFinite(required) || required < 0 || required >= Long.MAX_VALUE)
            throw new ArithmeticException("Pet required experience exceeds long range");
        return (long) required;
    }

    /**
     * レベル内残EXPを、次の成長度までの表示進捗へ変換します。
     * @param master 読み込み済みのペットマスター
     * @param level 現在の成長度
     * @param currentExperience API details.experienceのレベル内残量
     * @return 表示用の現在量・必要量・比率・最大成長度状態
     */
    public static @NotNull Progress progress(@NotNull PetMaster master, int level, long currentExperience) {
        int maxLevel = (int) number(master.rules(), "maxLevel", 100);
        if (level >= maxLevel) return new Progress(0, 0, 1, true);
        long required = requiredExperience(master, Math.max(1, level));
        long current = Math.clamp(currentExperience, 0, required);
        return new Progress(current, required, required == 0 ? 1 : current / (double) required, false);
    }

    /**
     * 孵化時に確定した基礎個体サイズへ、成長度による線形外見補正を掛けます。
     * @param master 読み込み済みのペットマスター
     * @param baseSize API details.sizeの基礎個体差
     * @param level 現在の成長度
     * @return BukkitのSCALE属性へ設定する外見倍率。成熟後は一定
     */
    public static double effectiveSize(@NotNull PetMaster master, double baseSize, int level) {
        JsonObject growth = object(master.rules(), "sizeGrowth");
        double initial = number(growth, "initialScale", .5);
        double mature = number(growth, "matureScale", 1);
        int matureLevel = (int) number(growth, "matureLevel", 20);
        double fraction = Math.clamp((level - 1.0) / Math.max(1, matureLevel - 1), 0, 1);
        return baseSize * (initial + (mature - initial) * fraction);
    }

    /** @param currentExperience 表示用のレベル内残EXP @param requiredExperience 次の成長度への必要EXP @param ratio 0〜1の表示比率 @param maxLevel 最大成長度ならtrue */
    public record Progress(long currentExperience, long requiredExperience, double ratio, boolean maxLevel) { }
}
