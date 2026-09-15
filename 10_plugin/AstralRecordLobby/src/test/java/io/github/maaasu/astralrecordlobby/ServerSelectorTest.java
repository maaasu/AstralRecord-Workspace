package io.github.maaasu.astralrecordlobby;

import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.persistence.PersistentDataContainer;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.bukkit.persistence.PersistentDataType.BYTE;

class ServerSelectorTest {
    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/33-network/33_0-概要.md
     * 章・見出し: # 33_0-概要 > ## 不変条件
     * 検証契約: サーバー選択NPCの再生成・停止時は、ロード済み全ワールドの識別済みNPCをすべて除去する。
     */
    @Test
    void removesEveryMarkedSelectorNpcAcrossLoadedWorlds() {
        AstralRecordLobbyPlugin plugin = mock(AstralRecordLobbyPlugin.class);
        Server server = mock(Server.class);
        World firstWorld = mock(World.class);
        World secondWorld = mock(World.class);
        Entity firstSelector = markedEntity();
        Entity secondSelector = markedEntity();
        Entity unrelatedEntity = unmarkedEntity();
        when(plugin.getServer()).thenReturn(server);
        when(server.getWorlds()).thenReturn(List.of(firstWorld, secondWorld));
        when(firstWorld.getEntities()).thenReturn(List.of(firstSelector, unrelatedEntity));
        when(secondWorld.getEntities()).thenReturn(List.of(secondSelector));

        ServerSelector selector = new ServerSelector(plugin);

        assertDoesNotThrow(selector::removeNpc);

        verify(firstSelector).remove();
        verify(secondSelector).remove();
        verify(unrelatedEntity, never()).remove();
    }

    private static Entity markedEntity() {
        Entity entity = mock(Entity.class);
        PersistentDataContainer data = mock(PersistentDataContainer.class);
        when(entity.getPersistentDataContainer()).thenReturn(data);
        when(data.has(any(), eq(BYTE))).thenReturn(true);
        return entity;
    }

    private static Entity unmarkedEntity() {
        Entity entity = mock(Entity.class);
        PersistentDataContainer data = mock(PersistentDataContainer.class);
        when(entity.getPersistentDataContainer()).thenReturn(data);
        when(data.has(any(), eq(BYTE))).thenReturn(false);
        return entity;
    }
}
