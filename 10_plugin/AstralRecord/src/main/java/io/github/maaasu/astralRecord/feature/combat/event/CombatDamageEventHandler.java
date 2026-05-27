package io.github.maaasu.astralRecord.feature.combat.event;

import io.github.maaasu.astralRecord.core.event.AbstractEventHandler;
import io.github.maaasu.astralRecord.feature.combat.service.DamageService;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.jetbrains.annotations.NotNull;

/**
 * エンティティ間のダメージイベントを受け取り、{@link DamageService} へ委譲するイベントハンドラ。
 * <p>
 * 本ハンドラはイベント受信と委譲のみに責務を限定し、ダメージ計算式はここに記述しない。
 */
public class CombatDamageEventHandler extends AbstractEventHandler {

    private final DamageService damageService;

    /**
     * {@link CombatDamageEventHandler} を構築します。
     *
     * @param damageService ダメージ確定処理を担うサービス
     */
    public CombatDamageEventHandler(@NotNull DamageService damageService) {
        this.damageService = damageService;
    }

    /**
     * エンティティ間ダメージを受け取り、{@link DamageService#handleEntityDamage} に委譲する。
     *
     * @param event エンティティ間ダメージイベント
     */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onEntityDamageByEntity(@NotNull EntityDamageByEntityEvent event) {
        runSafely(
                () -> damageService.handleEntityDamage(event),
                LogId.E_5900,
                event.getEntity().getName()
        );
    }
}
