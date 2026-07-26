package io.github.maaasu.astralRecord.feature.inventory.model;

import io.github.maaasu.astralRecord.shared.masterdata.tag.MasterTagIds;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public enum AccessorySlotType {
    OFF_HAND(1, "オフハンド", null),
    AMULET(2, "アミュレット", MasterTagIds.Equipment.AMULET),
    TALISMAN_1(3, "タリスマン 1", MasterTagIds.Equipment.TALISMAN),
    TALISMAN_2(4, "タリスマン 2", MasterTagIds.Equipment.TALISMAN),
    CHARM_1(5, "チャーム 1", MasterTagIds.Equipment.CHARM),
    CHARM_2(6, "チャーム 2", MasterTagIds.Equipment.CHARM),
    CHARM_3(7, "チャーム 3", MasterTagIds.Equipment.CHARM),
    CORE(8, "コア", MasterTagIds.Equipment.CORE),
    RELIC_1(9, "レリック 1", MasterTagIds.Equipment.RELIC),
    RELIC_2(10, "レリック 2", MasterTagIds.Equipment.RELIC);

    private final int slotIndex;
    private final String displayName;
    private final String equipmentTag;

    AccessorySlotType(int slotIndex, @NotNull String displayName, @Nullable String equipmentTag) {
        this.slotIndex = slotIndex;
        this.displayName = displayName;
        this.equipmentTag = equipmentTag;
    }

    public int getSlotIndex() {
        return slotIndex;
    }

    public @NotNull String getDisplayName() {
        return displayName;
    }

    /**
     * このスロットに配置できるアクセサリの equipment tag を返します。
     *
     * @return `AMULET` などの tag。オフハンドの場合は null
     */
    public @Nullable String getEquipmentTag() {
        return equipmentTag;
    }

    /**
     * 拡張アクセサリスロットか判定します。
     *
     * @return オフハンド以外のアクセサリスロットなら true
     */
    public boolean isAccessory() {
        return equipmentTag != null;
    }

    /**
     * equipment tag がこのスロットの種類と一致するか判定します。
     *
     * @param tag equipment master の tag
     * @return 大文字小文字を無視して一致する場合 true
     */
    public boolean matchesEquipmentTag(@Nullable String tag) {
        return equipmentTag != null && tag != null && equipmentTag.equalsIgnoreCase(tag.trim());
    }

    /**
     * ACCESSORY_SLOT の slotIndex からスロット種別を解決します。
     *
     * @param slotIndex ACCESSORY_SLOT のスロット番号
     * @return 対応する種別。範囲外の場合は null
     */
    public static @Nullable AccessorySlotType fromSlotIndex(int slotIndex) {
        for (AccessorySlotType type : values()) {
            if (type.slotIndex == slotIndex) {
                return type;
            }
        }
        return null;
    }

    /**
     * equipment tag から同種スロットの先頭を解決します。
     *
     * @param tag equipment master の tag
     * @return 対応する種別。未対応の場合は null
     */
    public static @Nullable AccessorySlotType fromEquipmentTag(@Nullable String tag) {
        for (AccessorySlotType type : values()) {
            if (type.matchesEquipmentTag(tag)) {
                return type;
            }
        }
        return null;
    }
}
