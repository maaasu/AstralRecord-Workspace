package io.github.maaasu.astralRecord.feature.mob.event;

import io.github.maaasu.astralRecord.core.event.AbstractEventHandler;
import io.github.maaasu.astralRecord.feature.mob.model.MobCategory;
import io.github.maaasu.astralRecord.feature.mob.model.MobInstance;
import io.github.maaasu.astralRecord.feature.mob.service.MobService;
import io.github.maaasu.astralRecord.feature.mob.service.MobVanillaEffectProtectionService;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import org.bukkit.entity.Entity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.jetbrains.annotations.NotNull;

import java.util.EnumSet;
import java.util.Set;

/**
 * AstralRecord 管理 Mob へのバニラダメージとバニラ由来の可視状態を抑止します。
 */
public final class MobVanillaDamageBlockEventHandler extends AbstractEventHandler {

    private static final Set<MobCategory> PROTECTED_CATEGORIES = EnumSet.of(MobCategory.ENEMY, MobCategory.BOSS);

    private final MobService mobService;
    private final MobVanillaEffectProtectionService effectProtectionService;

    /**
     * ハンドラを生成します。
     *
     * @param mobService              Mob 管理サービス
     * @param effectProtectionService バニラ由来の可視状態を抑止するサービス
     */
    public MobVanillaDamageBlockEventHandler(
            @NotNull MobService mobService,
            @NotNull MobVanillaEffectProtectionService effectProtectionService
    ) {
        this.mobService = mobService;
        this.effectProtectionService = effectProtectionService;
    }

    /**
     * 管理対象の ENEMY / BOSS に入るバニラダメージを全て無効化します。
     *
     * @param event Bukkit ダメージイベント
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onEntityDamage(@NotNull EntityDamageEvent event) {
        runSafely(() -> {
            Entity entity = event.getEntity();
            if (!isProtectedMob(entity)) {
                return;
            }

            event.setDamage(0.0D);
            event.setCancelled(true);
            effectProtectionService.clearVanillaVisuals(entity);
        }, LogId.E_5706, "vanilla_damage", event.getEntity().getName());
    }

    /**
     * 管理対象の ENEMY / BOSS に対するバニラ燃焼を無効化します。
     *
     * @param event Bukkit 燃焼イベント
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onEntityCombust(@NotNull EntityCombustEvent event) {
        runSafely(() -> {
            Entity entity = event.getEntity();
            if (!isProtectedMob(entity)) {
                return;
            }

            event.setCancelled(true);
            effectProtectionService.clearVanillaVisuals(entity);
        }, LogId.E_5706, "vanilla_combust", event.getEntity().getName());
    }

    private boolean isProtectedMob(@NotNull Entity entity) {
        MobInstance instance = mobService.getInstanceByEntity(entity.getUniqueId());
        return instance != null && PROTECTED_CATEGORIES.contains(instance.template().category());
    }
}
