package io.github.maaasu.astralRecord.feature.skill.active.service;

import io.github.maaasu.astralRecord.feature.mob.service.MobService;
import io.github.maaasu.astralRecord.feature.party.model.Party;
import io.github.maaasu.astralRecord.feature.party.service.PartyService;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PaladinPartyTargetingTest {
    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-パラディン.md
     * 章・見出し: # 13_3-パラディン > ## 1. 対象と発動
     * 検証契約: 範囲内候補から無関係なプレイヤーと遮蔽された味方を除き、未所属では自分だけを対象とする。
     */
    @Test
    void filtersPartyAndSightAndSupportsSolo() {
        World world = mock(World.class);
        AstPlayer caster = player(world, 0);
        AstPlayer ally = player(world, 1);
        AstPlayer blocked = player(world, 2);
        AstPlayer stranger = player(world, 3);
        UUID casterId = caster.getBukkit().getUniqueId();
        Party party = new Party(UUID.randomUUID(), casterId);
        party.addMember(ally.getBukkit().getUniqueId());
        party.addMember(blocked.getBukkit().getUniqueId());
        PartyService parties = mock(PartyService.class);
        when(parties.findParty(casterId)).thenReturn(party);
        var targeting = spy(new SkillTargetingService(mock(MobService.class), parties));
        doReturn(List.of(caster, ally, blocked, stranger)).when(targeting).playersInRadius(any(), eq(8.0), eq(4.0));
        doReturn(true).when(targeting).hasLineOfSight(any(), any());
        Location blockedEye = blocked.getBukkit().getEyeLocation();
        doReturn(false).when(targeting).hasLineOfSight(any(), eq(blockedEye));
        assertEquals(List.of(caster, ally), targeting.partyInRadius(caster, 8, 4));
        when(parties.findParty(casterId)).thenReturn(null);
        assertEquals(List.of(caster), targeting.partyInRadius(caster, 8, 4));
    }

    private static AstPlayer player(World world, double x) {
        Player bukkit = mock(Player.class);
        when(bukkit.getUniqueId()).thenReturn(UUID.randomUUID());
        when(bukkit.getLocation()).thenReturn(new Location(world,x,64,0));
        when(bukkit.getEyeLocation()).thenReturn(new Location(world,x,65.6,0));
        AstPlayer player = mock(AstPlayer.class);
        when(player.getBukkit()).thenReturn(bukkit);
        return player;
    }
}
