package io.github.maaasu.astralRecord.shared.effect;

import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ParticleDisplayServiceTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 7. 補助サービス > ### デバッグ釣り竿仮想キャスト
     * 検証契約: 統合版 viewer へ釣り竿の糸dustを送らず、Java版 viewer へは同じ定義を送る。
     */
    @Test
    void hidesBedrockOnlyParticleDefinitionsPerViewer() {
        World world = mock(World.class);
        Player bedrockViewer = mock(Player.class);
        Player javaViewer = mock(Player.class);
        Location center = new Location(world, 0.0D, 64.0D, 0.0D);
        when(world.getPlayers()).thenReturn(List.of(bedrockViewer, javaViewer));
        when(bedrockViewer.getLocation()).thenReturn(center);
        when(javaViewer.getLocation()).thenReturn(center);

        AstPlayer bedrockAstPlayer = mock(AstPlayer.class);
        AstPlayer javaAstPlayer = mock(AstPlayer.class);
        when(bedrockAstPlayer.isBedrock()).thenReturn(true);
        when(javaAstPlayer.isBedrock()).thenReturn(false);

        ParticleDisplayService service = new ParticleDisplayService();
        clearInvocations(bedrockViewer, javaViewer);
        try (MockedStatic<AstPlayerCache> cache = mockStatic(AstPlayerCache.class)) {
            cache.when(() -> AstPlayerCache.get(bedrockViewer)).thenReturn(bedrockAstPlayer);
            cache.when(() -> AstPlayerCache.get(javaViewer)).thenReturn(javaAstPlayer);

            service.spawnForNearbyViewers(center, SharedParticleDefinitions.FISHING_ROD_LINE);
        }

        assertTrue(SharedParticleDefinitions.FISHING_ROD_LINE.hideForBedrock());
        verify(bedrockViewer, never()).spawnParticle(
            eq(Particle.DUST),
            eq(center),
            anyInt(),
            anyDouble(),
            anyDouble(),
            anyDouble(),
            anyDouble(),
            any(Particle.DustOptions.class)
        );
        verify(javaViewer).spawnParticle(
            eq(Particle.DUST),
            eq(center),
            eq(1),
            eq(0.0D),
            eq(0.0D),
            eq(0.0D),
            eq(0.0D),
            any(Particle.DustOptions.class)
        );
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 7. 補助サービス > ### デバッグ釣り竿仮想キャスト
     * 検証契約: world向け共通定義送信でも、統合版 viewer だけを除外する。
     */
    @Test
    void hidesBedrockOnlyParticleDefinitionsInWorldOverload() {
        World world = mock(World.class);
        Player bedrockViewer = mock(Player.class);
        Player javaViewer = mock(Player.class);
        Location center = new Location(world, 0.0D, 64.0D, 0.0D);
        when(world.getPlayers()).thenReturn(List.of(bedrockViewer, javaViewer));

        AstPlayer bedrockAstPlayer = mock(AstPlayer.class);
        AstPlayer javaAstPlayer = mock(AstPlayer.class);
        when(bedrockAstPlayer.isBedrock()).thenReturn(true);
        when(javaAstPlayer.isBedrock()).thenReturn(false);

        ParticleDisplayService service = new ParticleDisplayService();
        try (MockedStatic<AstPlayerCache> cache = mockStatic(AstPlayerCache.class)) {
            cache.when(() -> AstPlayerCache.get(bedrockViewer)).thenReturn(bedrockAstPlayer);
            cache.when(() -> AstPlayerCache.get(javaViewer)).thenReturn(javaAstPlayer);

            service.spawnWorld(world, center, SharedParticleDefinitions.FISHING_ROD_LINE, 1.0D);
        }

        verify(bedrockViewer, never()).spawnParticle(
            eq(Particle.DUST),
            eq(center),
            anyInt(),
            anyDouble(),
            anyDouble(),
            anyDouble(),
            anyDouble(),
            any(Particle.DustOptions.class)
        );
        verify(javaViewer).spawnParticle(
            eq(Particle.DUST),
            eq(center),
            eq(1),
            eq(0.0D),
            eq(0.0D),
            eq(0.0D),
            eq(0.0D),
            any(Particle.DustOptions.class)
        );
    }
}
