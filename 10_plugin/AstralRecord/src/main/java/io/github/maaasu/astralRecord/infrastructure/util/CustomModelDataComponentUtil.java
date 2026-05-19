package io.github.maaasu.astralRecord.infrastructure.util;

import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.components.CustomModelDataComponent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Experimental な CustomModelDataComponent API へのアクセスを隔離するユーティリティ。
 */
public final class CustomModelDataComponentUtil {

    private CustomModelDataComponentUtil() {
        // utility class
    }

    @SuppressWarnings("UnstableApiUsage")
    public static @Nullable Integer readAsInt(@NotNull ItemMeta meta) {
        if (!meta.hasCustomModelDataComponent()) {
            return null;
        }

        CustomModelDataComponent component = meta.getCustomModelDataComponent();
        List<Float> values = component.getFloats();
        if (values.isEmpty()) {
            return null;
        }
        return Math.round(values.getFirst());
    }

    @SuppressWarnings("UnstableApiUsage")
    public static void writeFromInt(@NotNull ItemMeta meta, int customModelData) {
        CustomModelDataComponent component = meta.getCustomModelDataComponent();
        component.setFloats(List.of((float) customModelData));
        meta.setCustomModelDataComponent(component);
    }
}

