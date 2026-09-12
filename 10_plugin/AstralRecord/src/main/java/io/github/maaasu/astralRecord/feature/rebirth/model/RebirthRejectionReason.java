package io.github.maaasu.astralRecord.feature.rebirth.model;

/** 転生操作を受け付けられない理由です。 */
public enum RebirthRejectionReason {
    LEVEL_TOO_LOW,
    ALREADY_ACTIVE,
    NOT_ACTIVE,
    INSUFFICIENT_GOLD,
    INSUFFICIENT_ASTRALD,
    PLAYER_STATE_UNAVAILABLE
}
