package io.github.maaasu.astralRecord.infrastructure.util;

import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.components.CustomModelDataComponent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Experimental な CustomModelDataComponent API へのアクセスを隔離するユーティリティ。
 */
public final class CustomModelDataComponentUtil {

    private CustomModelDataComponentUtil() {
        // utility class
    }

    /**
     * アイテムマスタのモデルIDを float index 0 へ書き込みます。
     *
     * @param meta 更新するメタデータ
     * @param customModelData アイテムマスタのモデルID
     */
    @SuppressWarnings("UnstableApiUsage")
    public static void writeFromInt(@NotNull ItemMeta meta, int customModelData) {
        updateModelId(meta, customModelData);
    }

    /**
     * 他の component データを保ったまま、アイテムマスタが管理する float index 0 を同期します。
     *
     * @param meta 更新するメタデータ
     * @param customModelData 設定するモデルID。null の場合は index 0 を未設定に戻す
     * @return モデルIDを変更した場合は true
     */
    @SuppressWarnings("UnstableApiUsage")
    public static boolean updateModelId(@NotNull ItemMeta meta, @Nullable Integer customModelData) {
        CustomModelDataComponent component = meta.getCustomModelDataComponent();
        List<Float> current = component.getFloats();
        if (customModelData == null) {
            if (current.isEmpty()) {
                return false;
            }
            // 追加 float の index をずらさないよう、後続値がある場合は index 0 を 0 に戻す。
            if (current.size() == 1) {
                if (component.getFlags().isEmpty() && component.getStrings().isEmpty()
                    && component.getColors().isEmpty()) {
                    meta.setCustomModelDataComponent(null);
                } else {
                    component.setFloats(List.of());
                    meta.setCustomModelDataComponent(component);
                }
                return true;
            }
        }

        float value = customModelData == null ? 0.0F : customModelData.floatValue();
        if (!current.isEmpty() && current.getFirst() == value) {
            return false;
        }
        List<Float> updated = new ArrayList<>(current);
        if (updated.isEmpty()) {
            updated.add(value);
        } else {
            updated.set(0, value);
        }
        component.setFloats(updated);
        meta.setCustomModelDataComponent(component);
        return true;
    }

    /**
     * Bedrock向け身体防具の送信コピーで、予約済みflag index 0へ非表示状態を明示します。
     *
     * @param meta 更新する送信コピーのメタデータ
     * @param hidden 身体描画を非表示にする場合はtrue
     * @return flagを変更した場合はtrue。他のflag、float、string、colorは維持する
     */
    @SuppressWarnings("UnstableApiUsage")
    public static boolean updateArmorHiddenFlag(@NotNull ItemMeta meta, boolean hidden) {
        CustomModelDataComponent component = meta.getCustomModelDataComponent();
        List<Boolean> current = component.getFlags();
        if (!current.isEmpty() && current.getFirst() == hidden) return false;
        List<Boolean> updated = new ArrayList<>(current);
        if (updated.isEmpty()) updated.add(hidden);
        else updated.set(0, hidden);
        component.setFlags(updated);
        meta.setCustomModelDataComponent(component);
        return true;
    }
}

