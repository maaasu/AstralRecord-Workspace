package io.github.maaasu.astralRecord.feature.skill.service;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.condition.model.ConditionApplyReason;
import io.github.maaasu.astralRecord.feature.condition.model.ConditionApplyRequest;
import io.github.maaasu.astralRecord.feature.condition.model.ConditionType;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.skill.active.service.ActiveSkillServices;
import io.github.maaasu.astralRecord.feature.skill.executor.WizardBurnMeteorStrikeSkillExecutor;
import io.github.maaasu.astralRecord.feature.skill.executor.active.wizard.WizardMeteorExecutor;
import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.feature.skill.model.SkillResourceType;
import io.github.maaasu.astralRecord.feature.status.model.StatusSnapshot;
import io.github.maaasu.astralRecord.feature.status.service.StatusService;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/** 自身の攻撃による炎上成功時に小型メテオを生成します。 */
public final class WizardBurnMeteorStrikeRuntimeService {
    private static final double RANGE_SQUARED = 30.0D * 30.0D;

    private final SkillService skillService;
    private final PassiveSkillService passiveSkillService;
    private final StatusService statusService;
    private final ActiveSkillServices activeSkillServices;

    /**
     * 状態異常成功通知を処理する runtime を構築します。
     *
     * @param skillService スキル定義の取得元
     * @param passiveSkillService バインド済みパッシブの判定元
     * @param statusService MPの確認と消費を行うサービス
     * @param activeSkillServices メテオの戦闘・演出サービス
     */
    public WizardBurnMeteorStrikeRuntimeService(
            @NotNull SkillService skillService,
            @NotNull PassiveSkillService passiveSkillService,
            @NotNull StatusService statusService,
            @NotNull ActiveSkillServices activeSkillServices
    ) {
        this.skillService = skillService;
        this.passiveSkillService = passiveSkillService;
        this.statusService = statusService;
        this.activeSkillServices = activeSkillServices;
    }

    /**
     * 自身の攻撃による炎上の付与・更新が成功した場合にメテオ発生を試みます。
     *
     * @param request 成功済みの状態異常付与要求
     */
    public void onConditionApplied(@NotNull ConditionApplyRequest request) {
        AstEntity source = request.source();
        if (request.type() != ConditionType.BURNING
                || request.reason() != ConditionApplyReason.SKILL
                || request.attackType() == null
                || source == null || !source.isPlayer() || source.player() == null) {
            return;
        }
        AstPlayer caster = source.player();
        Player player = caster.getBukkit();
        if (!player.isOnline() || player.isDead() || !caster.getAccount().getMode().shouldProcessGameplay()
                || !passiveSkillService.isPassiveSkillActive(caster, WizardBurnMeteorStrikeSkillExecutor.ID)) {
            return;
        }
        Location impact = request.target().location();
        Location playerLocation = player.getLocation();
        if (impact.getWorld() == null || !impact.getWorld().equals(playerLocation.getWorld())
                || impact.distanceSquared(playerLocation) > RANGE_SQUARED) {
            return;
        }
        SkillDefinition definition = skillService.registry().getDefinition(WizardBurnMeteorStrikeSkillExecutor.ID);
        if (definition == null || definition.getResourceCost() == null) {
            return;
        }
        StatusSnapshot status = statusService.getStatus(caster);
        double manaCost = SkillService.resolveResourceCost(
                status, SkillResourceType.MANA, definition.getResourceCost());
        if (status.getCurrentMp() < manaCost) {
            return;
        }
        statusService.consumeMp(caster, manaCost);
        WizardMeteorExecutor.summonBurnStrike(activeSkillServices, caster, source, impact);
    }
}
