package io.github.maaasu.astralRecord.feature.boss.model;

/**
 * Reason why a boss challenge ended.
 */
public enum BossChallengeEndReason {
    DEFEATED(true),
    TIME_LIMIT(false),
    DEATH_LIMIT(false),
    NO_PARTICIPANTS(false),
    PARTICIPANT_REQUIREMENT_NOT_MET(false),
    FIELD_PREPARE_FAILED(false),
    TRANSFER_FAILED(false),
    BOSS_SPAWN_FAILED(false),
    ADMIN_STOP(false),
    PLUGIN_SHUTDOWN(false);

    private final boolean success;

    BossChallengeEndReason(boolean success) {
        this.success = success;
    }

    /**
     * Returns whether this reason means a successful clear.
     *
     * @return true when defeated
     */
    public boolean success() {
        return success;
    }
}
