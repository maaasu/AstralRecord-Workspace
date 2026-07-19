package io.github.maaasu.astralRecord.feature.world.model;

import org.jetbrains.annotations.Nullable;

/**
 * 拠点から開くオーバーワールド転送 GUI 上の配置設定です。
 *
 * @param slot 配置先スロット。未指定時は GUI に表示しない
 */
public record OverworldTeleportGuiSetting(@Nullable Integer slot) {
    public static final int MIN_SLOT = 0;
    public static final int MAX_SLOT = 44;

    /**
     * スロットが転送 GUI の配置可能範囲内かを返します。
     *
     * @return 0 以上 44 以下のスロットが指定されている場合は {@code true}
     */
    public boolean hasValidSlot() {
        return slot != null && slot >= MIN_SLOT && slot <= MAX_SLOT;
    }
}
