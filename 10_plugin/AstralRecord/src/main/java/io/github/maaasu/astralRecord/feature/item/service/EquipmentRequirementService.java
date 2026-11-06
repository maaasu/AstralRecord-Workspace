package io.github.maaasu.astralRecord.feature.item.service;

import io.github.maaasu.astralRecord.feature.item.model.ItemEquipment;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentClassRequirement;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import org.jetbrains.annotations.NotNull;

import java.util.Comparator;
import java.util.stream.Collectors;

/**
 * Evaluates equipment requirements against the player's account level and current class.
 */
public final class EquipmentRequirementService {

    private EquipmentRequirementService() {
    }

    /**
     * Evaluates whether a player satisfies an equipment definition's requirements.
     *
     * @param player player to evaluate
     * @param equipment equipment master definition
     * @return requirement evaluation result
     */
    public static @NotNull Result check(
        @NotNull AstPlayer player,
        @NotNull ItemEquipment equipment
    ) {
        return check(player.getAccount().getLevel(), player.getClassId(), player.getClassLevel(), equipment);
    }

    /**
     * 設定予定のレベル・職業に対して装備条件を評価します。
     * @param playerLevel プレイヤーレベル
     * @param classId 現在職ID
     * @param classLevel 現在職レベル
     * @param equipment 装備マスター
     * @return 装備条件の評価結果
     */
    public static @NotNull Result check(
        int playerLevel, @NotNull String classId, int classLevel, @NotNull ItemEquipment equipment
    ) {
        int requiredPlayerLevel = Math.max(0, equipment.getRequiredLevel());
        int currentPlayerLevel = Math.max(0, playerLevel);
        if (currentPlayerLevel < requiredPlayerLevel) {
            return new Result(
                Failure.PLAYER_LEVEL,
                requiredPlayerLevel,
                currentPlayerLevel,
                classId,
                0,
                classLevel,
                requiredClassesLabel(equipment)
            );
        }

        if (equipment.getRequiredClasses().isEmpty()) {
            return Result.satisfied();
        }

        ItemEquipmentClassRequirement currentRequirement = equipment.getRequiredClasses().stream()
            .filter(requirement -> requirement.getClassId().equalsIgnoreCase(classId))
            .min(Comparator.comparingInt(ItemEquipmentClassRequirement::getLevel))
            .orElse(null);
        if (currentRequirement == null) {
            return new Result(
                Failure.CLASS,
                requiredPlayerLevel,
                currentPlayerLevel,
                classId,
                0,
                classLevel,
                requiredClassesLabel(equipment)
            );
        }

        int requiredClassLevel = Math.max(1, currentRequirement.getLevel());
        int currentClassLevel = Math.max(1, classLevel);
        if (currentClassLevel < requiredClassLevel) {
            return new Result(
                Failure.CLASS_LEVEL,
                requiredPlayerLevel,
                currentPlayerLevel,
                currentRequirement.getClassId(),
                requiredClassLevel,
                currentClassLevel,
                requiredClassesLabel(equipment)
            );
        }
        return Result.satisfied();
    }

    /**
     * Evaluates requirements and sends the failure reason to the player when denied.
     *
     * @param player player to evaluate and notify
     * @param equipment equipment master definition
     * @return {@code true} when every requirement is satisfied
     */
    public static boolean checkAndNotify(
        @NotNull AstPlayer player,
        @NotNull ItemEquipment equipment
    ) {
        Result result = check(player, equipment);
        if (result.allowed()) {
            return true;
        }
        switch (result.failure()) {
            case PLAYER_LEVEL -> PlayerMessageService.getInstance().send(
                player.getBukkit(),
                PlayerMsgId.P_5284,
                result.requiredPlayerLevel(),
                result.currentPlayerLevel()
            );
            case CLASS -> PlayerMessageService.getInstance().send(
                player.getBukkit(),
                PlayerMsgId.P_5285,
                result.requiredClassesLabel(),
                result.classId()
            );
            case CLASS_LEVEL -> PlayerMessageService.getInstance().send(
                player.getBukkit(),
                PlayerMsgId.P_5286,
                result.classId(),
                result.requiredClassLevel(),
                result.currentClassLevel()
            );
            case NONE -> {
            }
        }
        return false;
    }

    private static @NotNull String requiredClassesLabel(@NotNull ItemEquipment equipment) {
        return equipment.getRequiredClasses().stream()
            .map(requirement -> requirement.getClassId() + " Lv." + Math.max(1, requirement.getLevel()))
            .collect(Collectors.joining(", "));
    }

    /** Equipment requirement failure reason. */
    public enum Failure {
        NONE,
        PLAYER_LEVEL,
        CLASS,
        CLASS_LEVEL
    }

    /** Equipment requirement evaluation result. */
    public record Result(
        @NotNull Failure failure,
        int requiredPlayerLevel,
        int currentPlayerLevel,
        @NotNull String classId,
        int requiredClassLevel,
        int currentClassLevel,
        @NotNull String requiredClassesLabel
    ) {
        /**
         * @return a result representing satisfied requirements
         */
        public static @NotNull Result satisfied() {
            return new Result(Failure.NONE, 0, 0, "", 0, 0, "");
        }

        /**
         * @return {@code true} when every requirement is satisfied
         */
        public boolean allowed() {
            return failure == Failure.NONE;
        }
    }
}
