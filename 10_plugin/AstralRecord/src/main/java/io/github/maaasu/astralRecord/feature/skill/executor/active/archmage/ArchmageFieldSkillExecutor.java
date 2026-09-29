package io.github.maaasu.astralRecord.feature.skill.executor.active.archmage;

import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillContext;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillExecutor;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastResult;
import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParamReader;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParameterException;
import io.github.maaasu.astralRecord.feature.skill.active.service.ActiveSkillServices;
import io.github.maaasu.astralRecord.feature.player.AccountModeGuard;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;
import java.util.function.Predicate;

/** 時限式の設置魔法陣について登録、描画、停止処理を一元化します。 */
abstract class ArchmageFieldSkillExecutor extends PlayerActiveSkillExecutor {
    private static final int TASK_INTERVAL_TICKS = 10;
    private final boolean affectsPlayers;

    /**
     * 設置型の実行処理を初期化します。
     * @param id スキルID
     * @param services 共通発動サービス
     * @param affectsPlayers プレイヤーを支援する陣ならtrue
     */
    protected ArchmageFieldSkillExecutor(@NotNull String id, @NotNull ActiveSkillServices services,
                                         boolean affectsPlayers) {
        super(id, services);
        this.affectsPlayers = affectsPlayers;
    }

    /** {@inheritDoc} */
    @Override
    public final void validateParams(@NotNull SkillDefinition skill) {
        super.validateParams(skill);
        SkillParamReader p = new SkillParamReader(skill.getId(), skill.getParams());
        for (String key : new String[]{"range", "radius", "power"}) {
            double value = p.getDouble(key, Double.NaN);
            if (!Double.isFinite(value) || value <= 0.0D) {
                throw new SkillParameterException(key, "設置魔法陣の値は正数が必要です");
            }
        }
        if (p.getInt("durationTicks", 0) < TASK_INTERVAL_TICKS
                || p.getInt("durationTicks", 0) % TASK_INTERVAL_TICKS != 0
                || p.getInt("pulseTicks", 0) < TASK_INTERVAL_TICKS
                || p.getInt("pulseTicks", 0) % TASK_INTERVAL_TICKS != 0) {
            throw new SkillParameterException("durationTicks", "設置魔法陣の時間は10tick単位の正数が必要です");
        }
    }

    /** {@inheritDoc} */
    @Override
    protected final @NotNull SkillCastResult castPlayer(@NotNull PlayerActiveSkillContext context) {
        SkillParamReader p = context.params();
        Location center = ArchmageBuildSupport.groundAtSight(context, p.getDouble("range", 16.0D));
        if (center == null) {
            return SkillCastResult.failure(null);
        }
        double radius = p.getDouble("radius", 3.0D);
        double power = p.getDouble("power", 1.0D);
        int duration = p.getInt("durationTicks", 160);
        int interval = p.getInt("pulseTicks", 20);
        UUID casterId = context.player().getUniqueId();
        String scope = context.source().skill().getId() + ":circle";
        Predicate<Player> influence = player -> {
            if (!AccountModeGuard.isGameplayPlayer(player) || player.getWorld() != center.getWorld()) {
                return false;
            }
            Location position = player.getLocation();
            double dx = position.getX() - center.getX();
            double dz = position.getZ() - center.getZ();
            return dx * dx + dz * dz <= radius * radius
                    && Math.abs(position.getY() - center.getY()) <= 2.0D;
        };
        // 同種の旧陣を先に終了させ、登録と効果を一つだけ維持します。
        context.services().tasks().cancel(casterId, scope);
        UUID circleId = affectsPlayers
                ? context.services().circles().register(casterId, influence)
                : context.services().circles().register(casterId);
        try {
            ArchmageBuildSupport.drawCircle(context, center, radius, !affectsPlayers);
            context.services().tasks().repeat(casterId, scope, 1L, TASK_INTERVAL_TICKS,
                    duration / TASK_INTERVAL_TICKS + 1,
                    index -> {
                        int age = index * TASK_INTERVAL_TICKS;
                        if (age >= duration) {
                            context.services().tasks().cancel(casterId, scope);
                            return;
                        }
                        if (!context.player().isOnline() || context.player().isDead()
                                || context.player().getWorld() != center.getWorld()
                                || context.caster().player().getStatusSnapshot().getCurrentHp() <= 0.0D) {
                            context.services().tasks().cancel(casterId, scope);
                            return;
                        }
                        ArchmageBuildSupport.drawCircle(context, center, radius, !affectsPlayers);
                        if (age % interval == 0) {
                            pulse(context, center, radius, power);
                        }
                    },
                    () -> context.services().circles().remove(circleId));
        } catch (RuntimeException exception) {
            context.services().circles().remove(circleId);
            throw exception;
        }
        return context.success();
    }

    /**
     * この陣だけの周期効果を適用します。
     * @param context 発動者と共有戦闘サービス
     * @param center 固定した陣の中心
     * @param radius 陣の半径
     * @param power YAMLの効果量
     */
    protected abstract void pulse(@NotNull PlayerActiveSkillContext context, @NotNull Location center,
                                  double radius, double power);
}
