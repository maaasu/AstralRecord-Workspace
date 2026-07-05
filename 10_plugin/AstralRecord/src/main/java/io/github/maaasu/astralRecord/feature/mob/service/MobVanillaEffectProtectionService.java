package io.github.maaasu.astralRecord.feature.mob.service;

import org.bukkit.entity.Entity;
import org.jetbrains.annotations.NotNull;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * AstralRecord Mob に対するバニラ由来の可視状態と、プラグイン由来の可視状態を区別します。
 */
public final class MobVanillaEffectProtectionService {

    private final Set<UUID> pluginFireVisuals = new HashSet<>();

    /**
     * プラグインが意図して Mob へ炎上表示を付与します。
     *
     * <p>このメソッド経由で付与した fire ticks は、バニラ燃焼抑止処理の解除対象から除外されます。
     * tick 数に 0 以下を指定した場合は、プラグイン由来の炎上表示を解除します。
     *
     * @param entity 対象 Bukkit entity
     * @param ticks  付与する fire ticks。0 以下の場合は解除
     */
    public void applyPluginFireTicks(@NotNull Entity entity, int ticks) {
        UUID entityId = entity.getUniqueId();
        if (ticks <= 0) {
            pluginFireVisuals.remove(entityId);
            entity.setFireTicks(0);
            return;
        }

        pluginFireVisuals.add(entityId);
        entity.setFireTicks(ticks);
    }

    /**
     * バニラ由来の炎上表示を解除します。
     *
     * <p>プラグイン由来として登録された fire ticks が残っている間は解除しません。
     *
     * @param entity 対象 Bukkit entity
     */
    public void clearVanillaVisuals(@NotNull Entity entity) {
        if (isPluginFireActive(entity)) {
            return;
        }
        if (entity.getFireTicks() > 0) {
            entity.setFireTicks(0);
        }
    }

    private boolean isPluginFireActive(@NotNull Entity entity) {
        UUID entityId = entity.getUniqueId();
        if (!pluginFireVisuals.contains(entityId)) {
            return false;
        }
        if (entity.getFireTicks() > 0) {
            return true;
        }
        pluginFireVisuals.remove(entityId);
        return false;
    }
}
