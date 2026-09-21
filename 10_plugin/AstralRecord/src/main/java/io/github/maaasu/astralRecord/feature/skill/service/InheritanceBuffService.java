package io.github.maaasu.astralRecord.feature.skill.service;

import io.github.maaasu.astralRecord.feature.buff.model.ActiveBuff;
import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.skill.executor.SharpshooterInheritanceMasterySkillExecutor;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillContext;
import io.github.maaasu.astralRecord.feature.skill.model.PlayerSkillCaster;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastContext;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastTrigger;
import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.feature.status.service.StatusService;
import org.bukkit.Location;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** 継承バフの付与個体と元スキルの実行値を紐付け、通常攻撃の着弾で効果を発揮します。 */
public final class InheritanceBuffService {
    private final SkillService skillService;
    private final PassiveSkillService passiveSkillService;
    private final StatusService statusService;
    private final Map<UUID, Map<String, Inheritance>> active = new HashMap<>();

    /**
     * メインスレッドで利用する継承サービスを構築します。
     * @param skillService スキル定義・共通リソース消費
     * @param passiveSkillService パッシブ有効状態
     * @param statusService バフの付与・消費
     */
    public InheritanceBuffService(@NotNull SkillService skillService,
                                  @NotNull PassiveSkillService passiveSkillService,
                                  @NotNull StatusService statusService) {
        this.skillService = skillService;
        this.passiveSkillService = passiveSkillService;
        this.statusService = statusService;
    }

    /**
     * 有効な継承の心得に定義された元スキルのバフを付与・更新します。
     * @param context 成功した元スキルのレベル・シジル反映済みcontext
     * @param effect 通常攻撃の着弾位置で実行する元スキル効果（再付与は行わない）
     */
    public void grant(@NotNull PlayerActiveSkillContext context, @NotNull InheritanceEffect effect) {
        grant(context, ignored -> true, effect);
    }

    /**
     * 有効な継承の心得に定義された元スキルのバフを、発動条件付きで付与・更新します。
     * @param context 成功した元スキルのレベル・シジル反映済みcontext
     * @param condition 通常攻撃の着弾内容が追撃条件を満たすかの判定
     * @param effect 通常攻撃の着弾位置・対象へ適用する元スキル効果
     */
    public void grant(
            @NotNull PlayerActiveSkillContext context,
            @NotNull Predicate<InheritanceImpact> condition,
            @NotNull InheritanceEffect effect
    ) {
        AstPlayer player = context.caster().player();
        if (!isActive(player)) return;
        SkillDefinition mastery = skillService.registry().getDefinition(SharpshooterInheritanceMasterySkillExecutor.ID);
        if (mastery == null || !(mastery.getParams().get("inheritanceBuffs") instanceof List<?> definitions)) return;
        double damageMultiplier = ((Number) mastery.getParams().getOrDefault(
                "inheritedSkillDamageMultiplier",
                SharpshooterInheritanceMasterySkillExecutor.INHERITED_SKILL_DAMAGE_MULTIPLIER
        )).doubleValue();
        for (int order = 0; order < definitions.size(); order++) {
            Object raw = definitions.get(order);
            if (!(raw instanceof Map<?, ?> entry)
                    || !context.source().skill().getId().equals(stripReference(entry.get("sourceSkillId"), "skill:"))) continue;
            String buffId = stripReference(entry.get("buffId"), "buff:");
            long ticks = ((Number) entry.get("durationConsumptionTicks")).longValue();
            boolean consumeSourceSkillResources = !(entry.get("consumeSourceSkillResources") instanceof Boolean value)
                    || value;
            statusService.applyBuff(player, buffId);
            ActiveBuff buff = statusService.getActiveBuffs(player).stream()
                    .filter(value -> value.getType().getId().equals(buffId)).findFirst().orElse(null);
            if (buff == null) continue;
            UUID id = context.caster().casterId();
            Inheritance inherited = new Inheritance(
                    context,
                    buff,
                    ticks,
                    damageMultiplier,
                    order,
                    consumeSourceSkillResources,
                    condition,
                    effect
            );
            active.computeIfAbsent(id, ignored -> new HashMap<>()).put(buffId, inherited);
            // lifecycleの中断でもcleanupが走り、死亡・退出・world移動後へ効果を持ち越さない。
            context.services().tasks().repeat(id, "inheritance:" + buffId,
                    buff.getType().getDurationTicks(), 1L, 1, ignored -> { }, () -> {
                        Map<String, Inheritance> values = active.get(id);
                        if (values != null && values.remove(buffId, inherited)) {
                            if (values.isEmpty()) active.remove(id);
                            if (statusService.getActiveBuffs(player).contains(inherited.buff())) statusService.removeBuff(player, buffId);
                        }
                    });
        }
    }

    /**
     * 通常攻撃開始時に有効だった継承だけを、その攻撃の最初の着弾で発動するcallbackを返します。
     * 空振り、期限切れ、挑戦時解除、パッシブ無効化、リソース不足では消費しません。
     * @param attack 通常攻撃context
     * @return 着弾位置を受け取る、一攻撃一回のcallback
     */
    public @NotNull Consumer<InheritanceImpact> prepareAttack(@NotNull SkillCastContext attack) {
        if (attack.trigger() != SkillCastTrigger.AUTO_ATTACK
                || !(attack.caster() instanceof PlayerSkillCaster caster) || !isActive(caster.player())) return ignored -> { };
        Map<String, Inheritance> current = active.get(caster.casterId());
        if (current == null) return ignored -> { };
        List<Inheritance> captured = current.values().stream()
                .filter(value -> statusService.getActiveBuffs(caster.player()).contains(value.buff()))
                .sorted(Comparator.comparingInt(Inheritance::order))
                .toList();
        boolean[] used = {false};
        return impact -> {
            if (used[0]) return;
            used[0] = true;
            AstPlayer player = caster.player();
            if (!player.getBukkit().isOnline() || player.getBukkit().isDead()
                    || player.getStatusSnapshot().getCurrentHp() <= 0.0D
                    || player.getBukkit().getWorld() != impact.location().getWorld() || !isActive(player)) return;
            for (Inheritance inherited : captured) {
                if (!statusService.getActiveBuffs(player).contains(inherited.buff())
                        || !inherited.condition().test(impact)) continue;
                boolean consumed = inherited.consumeSourceSkillResources()
                        ? skillService.tryConsumeEffectResources(
                                caster,
                                inherited.context().source().skill(),
                                inherited.context().source().statusSnapshot(),
                                () -> statusService.consumeBuffDuration(player, inherited.buff(), inherited.ticks())
                        )
                        : statusService.consumeBuffDuration(player, inherited.buff(), inherited.ticks());
                if (consumed) {
                    ActiveBuff remaining = statusService.getActiveBuffs(player).stream()
                            .filter(buff -> buff.getType().getId().equals(inherited.buff().getType().getId()))
                            .findFirst().orElse(null);
                    if (remaining != null) inherited.buff = remaining;
                    inherited.effect().apply(
                            new InheritanceImpact(impact.location().clone(), impact.target()),
                            inherited.damageMultiplier()
                    );
                }
            }
        };
    }

    /** 使用許可・習得・バインドを含むパッシブ有効条件を評価します。 */
    private boolean isActive(AstPlayer player) {
        return passiveSkillService.isPassiveSkillActive(player, SharpshooterInheritanceMasterySkillExecutor.ID);
    }

    /** マスター参照のprefixを除去します。 */
    private static String stripReference(Object raw, String prefix) {
        String value = raw == null ? "" : raw.toString().trim();
        return value.startsWith(prefix) ? value.substring(prefix.length()) : value;
    }

    /** 付与単位の所有権を維持し、時間消費後のバフ個体だけを追跡し直します。 */
    private static final class Inheritance {
        private final PlayerActiveSkillContext context;
        private ActiveBuff buff;
        private final long ticks;
        private final double damageMultiplier;
        private final int order;
        private final boolean consumeSourceSkillResources;
        private final Predicate<InheritanceImpact> condition;
        private final InheritanceEffect effect;

        private Inheritance(
                PlayerActiveSkillContext context,
                ActiveBuff buff,
                long ticks,
                double damageMultiplier,
                int order,
                boolean consumeSourceSkillResources,
                Predicate<InheritanceImpact> condition,
                InheritanceEffect effect
        ) {
            this.context = context;
            this.buff = buff;
            this.ticks = ticks;
            this.damageMultiplier = damageMultiplier;
            this.order = order;
            this.consumeSourceSkillResources = consumeSourceSkillResources;
            this.condition = condition;
            this.effect = effect;
        }

        private PlayerActiveSkillContext context() { return context; }
        private ActiveBuff buff() { return buff; }
        private long ticks() { return ticks; }
        private double damageMultiplier() { return damageMultiplier; }
        private int order() { return order; }
        private boolean consumeSourceSkillResources() { return consumeSourceSkillResources; }
        private Predicate<InheritanceImpact> condition() { return condition; }
        private InheritanceEffect effect() { return effect; }
    }

    /** 通常攻撃の最初の着弾位置と、その直接命中対象を保持します。 */
    public record InheritanceImpact(@NotNull Location location, @Nullable AstEntity target) {
    }

    /** 継承倍率を受け取り、元スキルの追撃効果を適用します。 */
    @FunctionalInterface
    public interface InheritanceEffect {
        void apply(@NotNull InheritanceImpact impact, double damageMultiplier);
    }
}
