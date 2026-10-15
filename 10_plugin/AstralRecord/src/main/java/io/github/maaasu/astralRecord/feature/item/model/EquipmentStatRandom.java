package io.github.maaasu.astralRecord.feature.item.model;

import java.math.BigDecimal;
import java.util.random.RandomGenerator;

/** 単一基準値に対して、生成時に一度だけ適用する離散乱数の定義。 */
public record EquipmentStatRandom(double offsetMin, double offsetMax, double step, double zeroChance) {
    private static final long MAX_CANDIDATES = 1_000_000;

    /**
     * 有限の小数4桁以内の加算幅と刻み、0〜1のゼロ確率を検証します。
     * @param offsetMin 加算幅の下端
     * @param offsetMax 加算幅の上端
     * @param step offsetMinを起点にした正の刻み
     * @param zeroChance 先に0を選ぶ確率
     * @throws IllegalArgumentException 順序、刻み、確率、候補数が契約に反する場合
     */
    public EquipmentStatRandom {
        decimal(offsetMin);
        decimal(offsetMax);
        decimal(step);
        if (offsetMin > offsetMax || step <= 0 || !Double.isFinite(zeroChance)
                || zeroChance < 0 || zeroChance > 1) {
            throw new IllegalArgumentException("Invalid equipment stat random bounds or probability");
        }
        BigDecimal width = decimal(offsetMax).subtract(decimal(offsetMin));
        BigDecimal[] quotient = width.divideAndRemainder(decimal(step));
        if (quotient[1].signum() != 0 || quotient[0].compareTo(BigDecimal.valueOf(MAX_CANDIDATES - 1)) > 0) {
            throw new IllegalArgumentException("Invalid equipment stat random lattice");
        }
    }

    /**
     * 基準値と両端の有限性・保存精度、およびゼロ分岐との区間重複を検証します。
     * @param base マスターの単一基準値
     * @throws IllegalArgumentException 基準値または生成可能値が契約に反する場合
     */
    public void validateBase(double base) {
        BigDecimal value = decimal(base);
        BigDecimal exactLower = value.add(decimal(offsetMin));
        BigDecimal exactUpper = value.add(decimal(offsetMax));
        double lower = exactLower.doubleValue();
        double upper = exactUpper.doubleValue();
        if (!Double.isFinite(lower) || !Double.isFinite(upper)
                || (zeroChance > 0 && lower <= 0 && upper >= 0)) {
            throw new IllegalArgumentException("Invalid equipment stat random base or zero overlap");
        }
        if (BigDecimal.valueOf(lower).compareTo(exactLower) != 0
                || BigDecimal.valueOf(upper).compareTo(exactUpper) != 0
                || (offsetMin != offsetMax && step < Math.ulp(Math.max(Math.abs(lower), Math.abs(upper))))) {
            throw new IllegalArgumentException("Equipment stat random candidates exceed numeric precision");
        }
    }

    /**
     * ゼロ分岐の後、offsetMin起点の離散候補を等確率で抽選します。
     * @param base 検証対象の単一基準値
     * @param random 抽選源。描画・再ロード時にはこのメソッドを呼びません
     * @return doubleを経由せず、個体へ同一のmin/maxとして保存する十進確定値
     * @throws IllegalArgumentException 基準値が契約に反する場合
     */
    public BigDecimal sample(double base, RandomGenerator random) {
        validateBase(base);
        if (zeroChance > 0 && random.nextDouble() < zeroChance) {
            return BigDecimal.ZERO;
        }
        long candidates = decimal(offsetMax).subtract(decimal(offsetMin)).divide(decimal(step)).longValueExact() + 1;
        return decimal(base).add(decimal(offsetMin))
                .add(decimal(step).multiply(BigDecimal.valueOf(random.nextLong(candidates))));
    }

    /**
     * 通常抽選区間の端を返します。ゼロ分岐の0は含めません。
     * @param base 単一基準値
     * @param upper 上端ならtrue、下端ならfalse
     * @return 抽選区間の端
     */
    public double bound(double base, boolean upper) {
        validateBase(base);
        return decimal(base).add(decimal(upper ? offsetMax : offsetMin)).doubleValue();
    }

    private static BigDecimal decimal(double value) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("Equipment stat random requires finite numbers");
        }
        BigDecimal decimal = BigDecimal.valueOf(value).stripTrailingZeros();
        if (decimal.scale() > 4) {
            throw new IllegalArgumentException("Equipment stat random supports at most four decimal places");
        }
        return decimal;
    }
}
