package io.github.maaasu.astralRecord.feature.mob.service;

import io.github.maaasu.astralRecord.feature.mob.model.MobInstance;
import io.github.maaasu.astralRecord.feature.mob.model.MobSkillBinding;
import io.github.maaasu.astralRecord.feature.mob.model.MobSkillTiming;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;

/**
 * Mob 専用スキルExecutorへ渡す、発動開始時点で固定された文脈です。
 *
 * @param mob       発動する Mob
 * @param target    発動開始時の主対象
 * @param binding   Mob マスター上のスキル紐付け
 * @param timing    解決済みの発動・詠唱・再使用設定
 * @param origin    発動開始時の射出・詠唱位置
 * @param direction 発動開始時の照準方向
 */
public record MobSkillContext(
        @NotNull MobInstance mob,
        @NotNull Player target,
        @NotNull MobSkillBinding binding,
        @NotNull MobSkillTiming timing,
        @NotNull Location origin,
        @NotNull Vector direction
) {

    /** 呼び出し元による位置・方向の変更を防ぐため、コピーを保持します。 */
    public MobSkillContext {
        origin = origin.clone();
        direction = direction.clone();
    }
}
