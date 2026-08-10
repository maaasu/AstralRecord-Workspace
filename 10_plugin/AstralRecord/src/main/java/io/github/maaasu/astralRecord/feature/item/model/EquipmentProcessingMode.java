package io.github.maaasu.astralRecord.feature.item.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** 装備加工 GUI が現在扱う操作モードです。 */
public enum EquipmentProcessingMode {
    REPAIR("repair", "修理"),
    ENHANCEMENT("enhancement", "強化");

    private final String contentId;
    private final String displayName;

    EquipmentProcessingMode(@NotNull String contentId, @NotNull String displayName) {
        this.contentId = contentId;
        this.displayName = displayName;
    }

    /** GUI holder の contentId へ保存する安定した値を返します。 */
    public @NotNull String contentId() {
        return contentId;
    }

    /**
     * プレイヤーへ表示する加工モード名を返します。
     *
     * @return 修理または強化の表示名
     */
    public @NotNull String displayName() {
        return displayName;
    }

    /** holder の contentId からモードを復元します。未知値は null を返します。 */
    public static @Nullable EquipmentProcessingMode fromContentId(@Nullable String contentId) {
        if (contentId == null) {
            return null;
        }
        for (EquipmentProcessingMode mode : values()) {
            if (mode.contentId.equalsIgnoreCase(contentId)) {
                return mode;
            }
        }
        return null;
    }
}
