package io.github.maaasu.astralRecord.feature.skill.service;

import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.skill.executor.passive.mage.MageBlinkSkillExecutor;
import io.github.maaasu.astralRecord.feature.skill.model.LearnedSkillInstance;
import io.github.maaasu.astralRecord.feature.skill.model.PlayerSkillCaster;
import io.github.maaasu.astralRecord.feature.skill.model.SkillBindPreset;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastTrigger;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.UUID;

/** バインド済みブリンクを空中のスニーク入力へ接続します。 */
public final class MageBlinkSkillRuntimeService {

    private final SkillService skillService;
    private final PassiveSkillService passiveSkillService;
    private final SkillBindPresetService presetService;
    private final SkillOwnershipService ownershipService;

    /** スキル実行とパッシブバインドの各サービスで初期化します。 */
    public MageBlinkSkillRuntimeService(
            @NotNull SkillService skillService,
            @NotNull PassiveSkillService passiveSkillService,
            @NotNull SkillBindPresetService presetService,
            @NotNull SkillOwnershipService ownershipService
    ) {
        this.skillService = skillService;
        this.passiveSkillService = passiveSkillService;
        this.presetService = presetService;
        this.ownershipService = ownershipService;
    }

    /**
     * 空中で有効なブリンクがバインドされていれば発動を試みます。
     *
     * @param astPlayer 入力したプレイヤー
     * @return ブリンク入力を処理した場合は {@code true}
     */
    public boolean tryTrigger(@NotNull AstPlayer astPlayer) {
        Player player = astPlayer.getBukkit();
        if (!player.isOnline()
                || player.isDead()
                || isGrounded(player)
                || player.isFlying()
                || player.isGliding()
                || player.isSwimming()
                || player.isInsideVehicle()
                || !passiveSkillService.isPassiveSkillActive(astPlayer, MageBlinkSkillExecutor.ID)) {
            return false;
        }

        String learnedSkillId = findBoundBlinkInstance(astPlayer);
        if (learnedSkillId == null) {
            return false;
        }

        skillService.castLearnedSkill(
                new PlayerSkillCaster(astPlayer),
                learnedSkillId,
                SkillCastTrigger.PLAYER_COMMAND,
                player.getEyeLocation(),
                null,
                List.of()
        );
        return true;
    }

    private static boolean isGrounded(@NotNull Player player) {
        Location feet = player.getLocation();
        Block supportCandidate = feet.getWorld().getBlockAt(
                feet.getBlockX(),
                (int) Math.floor(feet.getY() - 0.05D),
                feet.getBlockZ()
        );
        Location probe = feet.clone().add(0.0D, 0.05D, 0.0D);
        RayTraceResult surface = supportCandidate.rayTrace(
                probe,
                new Vector(0.0D, -1.0D, 0.0D),
                0.1D,
                FluidCollisionMode.NEVER
        );
        return surface != null
                && Math.abs(surface.getHitPosition().getY() - feet.getY()) <= 0.1D;
    }

    private String findBoundBlinkInstance(@NotNull AstPlayer player) {
        UUID accountId = player.getAccount().getUuid();
        int selectedPresetIndex = presetService.selectedPresetIndex(accountId);
        SkillBindPreset preset = presetService.getPresets(accountId).stream()
                .filter(candidate -> candidate.isUnlocked()
                        && candidate.getPresetIndex() == selectedPresetIndex)
                .findFirst()
                .orElse(null);
        if (preset == null) {
            return null;
        }

        int enabledSlots = Math.min(
                passiveSkillService.activePassiveSlotCount(player),
                preset.getPassiveSkillSlots().size()
        );
        for (int index = 0; index < enabledSlots; index++) {
            String boundInstanceId = preset.getPassiveSkillSlots().get(index);
            LearnedSkillInstance learned = ownershipService.findInstance(player, boundInstanceId);
            if (learned != null && MageBlinkSkillExecutor.ID.equals(learned.getSkillId())) {
                return learned.getLearnedSkillId().toString();
            }
        }
        return null;
    }
}
