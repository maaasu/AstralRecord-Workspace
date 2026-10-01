package io.github.maaasu.astralRecord.feature.combat.model;

import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.status.model.StatusType;
import org.bukkit.entity.LivingEntity;
import org.jetbrains.annotations.NotNull;

/** 個体保存と独立した、召喚中ペットの戦闘参照契約です。 */
public interface PetCombatActor {
    /** @return ペットの所有者。報酬・チーム判定に使用します */
    @NotNull AstPlayer owner();
    /** @return 召喚中の Bukkit エンティティ */
    @NotNull LivingEntity entity();
    /** @param type ステータス種別 @return 主人から換算した現在能力値 */
    double statValue(@NotNull StatusType type);
    /** @return 独自 HP の現在値 */
    double currentHealth();
    /** @return 独自 HP の最大値 */
    double maxHealth();
    /** @return 個体が死亡済みの場合 true */
    boolean dead();
    /** @param amount 適用済みダメージ。HP・死亡状態を読み込み済み個体へ反映します */
    void damage(double amount);
}
