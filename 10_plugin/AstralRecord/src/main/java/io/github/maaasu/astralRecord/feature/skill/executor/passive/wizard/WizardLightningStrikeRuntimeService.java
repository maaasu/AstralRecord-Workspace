package io.github.maaasu.astralRecord.feature.skill.executor.passive.wizard;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.combat.model.AttackType;
import io.github.maaasu.astralRecord.feature.combat.model.DamageElement;
import io.github.maaasu.astralRecord.feature.condition.model.ConditionApplyReason;
import io.github.maaasu.astralRecord.feature.condition.model.ConditionApplyRequest;
import io.github.maaasu.astralRecord.feature.condition.model.ConditionType;
import io.github.maaasu.astralRecord.feature.skill.active.service.SkillCombatService;
import io.github.maaasu.astralRecord.feature.skill.active.service.SkillEffectService;
import io.github.maaasu.astralRecord.feature.skill.executor.WizardLightningStrikeSkillExecutor;
import io.github.maaasu.astralRecord.feature.skill.model.PlayerSkillCaster;
import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParamReader;
import io.github.maaasu.astralRecord.feature.skill.service.PassiveSkillService;
import io.github.maaasu.astralRecord.feature.skill.service.SkillService;
import io.github.maaasu.astralRecord.feature.status.service.StatusService;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.World;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/** 感電付与に反応するウィザードの単体落雷を管理します。 */
public final class WizardLightningStrikeRuntimeService {
    private final SkillService skillService;
    private final SkillCombatService combatService;
    private final SkillEffectService effects;
    private final StatusService statusService;
    @Nullable
    private PassiveSkillService passiveSkillService;

    /**
     * 落雷パッシブの実行に必要なサービスを関連付けます。
     *
     * @param skillService スキル定義と効果時のMP消費を管理するサービス
     * @param combatService 共通スキルダメージを適用するサービス
     * @param effects 軽い落雷演出と効果音を表示するサービス
     * @param statusService 消費軽減を含む現在ステータスを返すサービス
     */
    public WizardLightningStrikeRuntimeService(
            @NotNull SkillService skillService,
            @NotNull SkillCombatService combatService,
            @NotNull SkillEffectService effects,
            @NotNull StatusService statusService
    ) {
        this.skillService = skillService;
        this.combatService = combatService;
        this.effects = effects;
        this.statusService = statusService;
    }

    /**
     * バインドを含むパッシブの有効状態を調べるサービスを設定します。
     *
     * @param passiveSkillService パッシブ管理サービス。nullの場合は落雷を発動しない
     */
    public void setPassiveSkillService(@Nullable PassiveSkillService passiveSkillService) {
        this.passiveSkillService = passiveSkillService;
    }

    /**
     * 自身の攻撃で感電の新規付与または再付与が成功したとき、MPを消費して雷粒子と軽い雷鳴を表示し、対象へ雷ダメージを与えます。
     *
     * @param request 成功した状態異常の付与要求
     */
    public void onConditionApplied(@NotNull ConditionApplyRequest request) {
        AstEntity attacker = request.source();
        AstEntity target = request.target();
        if (request.type() != ConditionType.SHOCKED
                || request.reason() != ConditionApplyReason.SKILL
                || request.attackType() == null
                || attacker == null
                || !attacker.isPlayer()
                || attacker.player() == null
                || !target.isMob()
                || target.mob() == null) {
            return;
        }

        PassiveSkillService passives = passiveSkillService;
        if (passives == null || !passives.isPassiveSkillActive(
                attacker.player(), WizardLightningStrikeSkillExecutor.ID)) {
            return;
        }
        SkillDefinition definition = skillService.registry().getDefinition(WizardLightningStrikeSkillExecutor.ID);
        if (definition == null) {
            return;
        }

        SkillParamReader params = new SkillParamReader(definition.getId(), definition.getParams());
        double range = params.getDouble("range", 30.0D);
        Location attackerLocation = attacker.location();
        Location targetLocation = target.location();
        World world = targetLocation.getWorld();
        if (world == null || !world.equals(attackerLocation.getWorld())
                || attackerLocation.distanceSquared(targetLocation) > range * range) {
            return;
        }

        if (!skillService.tryConsumeEffectResources(
                new PlayerSkillCaster(attacker.player()),
                definition,
                statusService.getStatus(attacker.player()),
                () -> true)) {
            return;
        }

        List<Location> bolt = new ArrayList<>(24);
        for (int point = 0; point < 24; point++) {
            double height = 6.0D - point * 0.25D;
            double zigzag = point == 23 ? 0.0D : (point % 2 == 0 ? 0.20D : -0.20D);
            bolt.add(targetLocation.clone().add(zigzag, height, -zigzag));
        }
        effects.points(targetLocation, bolt, SharedParticleDefinitions.SKILL_MAGE_LIGHTNING);
        effects.sound(targetLocation, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 0.35F, 1.75F);
        combatService.hit(attacker, target, AttackType.MAGIC, DamageElement.LIGHTNING,
                params.getDouble("damageRatio", 1.5D));
    }
}
