package io.github.maaasu.astralRecord.feature.buff.service;

import io.github.maaasu.astralRecord.feature.buff.model.BuffModifier;
import io.github.maaasu.astralRecord.feature.status.model.StatusType;
import org.jetbrains.annotations.NotNull;

import java.math.BigDecimal;

/** バフの実際の補正方式に合わせた数値表示を、アイテム説明と獲得通知で共有します。 */
public final class BuffModifierDisplayFormatter {

    private BuffModifierDisplayFormatter() {
    }

    /**
     * 固定加算はステータスの単位、割合補正は百分率と計算基準を付けて表示します。
     * 百分率ステータスへの固定加算はポイント表記にし、割合での増加と区別します。
     *
     * @param modifier 有限値を持つ、実際に適用されるステータス補正
     * @return 符号・単位・割合の基準を含む補正値。色コードは含みません
     */
    public static @NotNull String formatValue(@NotNull BuffModifier modifier) {
        StatusType statusType = modifier.getStatus();
        double value = modifier.getValue();
        return switch (modifier.getType()) {
            case FLAT -> statusType.isPercentage()
                    ? signedDecimal(value, 0) + "ポイント"
                    : statusType.formatSignedValue(value);
            case SCALAR -> signedDecimal(value, 2) + "%（基準値に対して）";
            case FINAL_SCALAR -> signedDecimal(Math.max(-1.0D, value), 2) + "%（最終値に乗算）";
        };
    }

    /** 小数点を指定桁だけ移動し、正値の符号と末尾ゼロを除いた十進表示を返します。 */
    private static @NotNull String signedDecimal(double value, int decimalShift) {
        return (value > 0.0D ? "+" : "")
            + BigDecimal.valueOf(value).movePointRight(decimalShift).stripTrailingZeros().toPlainString();
    }
}
