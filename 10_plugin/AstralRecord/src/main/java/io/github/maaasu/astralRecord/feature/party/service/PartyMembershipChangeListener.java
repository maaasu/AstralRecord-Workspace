package io.github.maaasu.astralRecord.feature.party.service;

import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * パーティーのメンバー構成が変化したときに通知を受け取る listener です。
 */
@FunctionalInterface
public interface PartyMembershipChangeListener {
    /**
     * パーティーの現在構成が変化したことを通知します。
     *
     * @param partyId 構成が変化したパーティー ID。解散後も元の ID が渡されます
     */
    void onPartyMembershipChanged(@NotNull UUID partyId);
}
