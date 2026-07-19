package io.github.maaasu.astralRecord.feature.menu.model;

import net.kyori.adventure.text.Component;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/**
 * GUI 描画時点の装備表示名を保持する不変スナップショットです。
 *
 * @param helmet 頭装備の表示名
 * @param chestplate 胴装備の表示名
 * @param leggings 脚装備の表示名
 * @param boots 足装備の表示名
 */
public record PlayerEquipmentSnapshot(
    @NotNull Component helmet,
    @NotNull Component chestplate,
    @NotNull Component leggings,
    @NotNull Component boots
) {
    public PlayerEquipmentSnapshot {
        Objects.requireNonNull(helmet, "helmet");
        Objects.requireNonNull(chestplate, "chestplate");
        Objects.requireNonNull(leggings, "leggings");
        Objects.requireNonNull(boots, "boots");
    }
}
