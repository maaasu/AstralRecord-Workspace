package io.github.maaasu.astralRecord.feature.combat.service;

import io.github.maaasu.astralRecord.feature.combat.model.DamageContext;
import io.github.maaasu.astralRecord.feature.combat.model.DamageResult;
import io.github.maaasu.astralRecord.feature.status.service.StatusService;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.jetbrains.annotations.NotNull;

/**
 * 戦闘ダメージ処理の入口となるサービスクラスです。
 * <p>
 * イベント・スキル・Mob 攻撃などからの呼び出しを受け、
 * {@link DamageContext} の構築と {@link DamageCalculator} への委譲、
 * 計算結果のイベントへの反映を担います。
 * <p>
 * HP/MP/エネルギーの実値更新は
 * {@link io.github.maaasu.astralRecord.feature.status.service.StatusService} 側の責務であり、
 * 本サービスは戦闘ダメージの確定処理のみを行います。
 */
public final class DamageService {

    @SuppressWarnings("unused")
    private final StatusService statusService;
    private final DamageCalculator damageCalculator;

    /**
     * {@link DamageService} を構築します。
     *
     * @param statusService 被弾者の現在ステータス参照・更新に使用するサービス
     */
    public DamageService(@NotNull StatusService statusService) {
        this.statusService = statusService;
        this.damageCalculator = new DamageCalculator();
    }

    /**
     * Bukkit のエンティティ間ダメージイベントを処理します。
     * <p>
     * メインスレッドで呼び出される想定のため、内部で SQL/YAML 等の重い I/O を
     * 行わないこと。必要なステータス・装備・バフ情報は事前にメモリへロード済みの
     * データを参照してください。
     *
     * @param event Bukkit のエンティティ間ダメージイベント
     */
    public void handleEntityDamage(@NotNull EntityDamageByEntityEvent event) {
        DamageContext context = DamageContext.from(event);
        DamageResult result = damageCalculator.calculate(context);
        event.setDamage(result.finalDamage());
    }
}
