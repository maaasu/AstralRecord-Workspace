package io.github.maaasu.astralRecord.feature.menu.model;

/** 通信せず読み込んだガイド進行と報酬状態を区別するメニュー表示状態です。 */
public enum GuideMenuStatus {
    UNKNOWN,
    INCOMPLETE,
    REWARD_CLAIMABLE,
    REWARD_CLAIMED,
    COMPLETED,
    REWARD_UNCONFIRMED,
    NO_GUIDES
}
