package io.github.maaasu.astralRecord.feature.skill.service;

import io.github.maaasu.astralRecord.feature.account.model.AccountModel;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.skill.executor.MeditationSkillExecutor;
import io.github.maaasu.astralRecord.feature.skill.model.PassiveSkillContext;
import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.feature.skill.model.SkillKind;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParameterException;
import io.github.maaasu.astralRecord.feature.skill.model.SkillResourceType;
import io.github.maaasu.astralRecord.shared.effect.ParticleDisplayService;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MeditationSkillRuntimeServiceTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-イベント.md
     * 章・見出し: # 13_3-イベント > ## 5. メディテーション中断
     * 検証契約: 100 tickの連続スニークでだけ発動し、解除後は再び100 tick必要である。
     */
    @Test
    void activatesAfterContinuousSneakAndRequiresNewChargeAfterInterrupt() {
        UUID playerId = UUID.randomUUID();
        AstPlayer astPlayer = player(playerId);
        SkillDefinition definition = definition(Map.of(
            "chargeTicks", 100,
            "regenMultiplier", 3,
            "chargeParticleIntervalTicks", 10,
            "activeParticleIntervalTicks", 5,
            "activeSoundIntervalTicks", 40
        ));
        MeditationSkillRuntimeService runtime = new MeditationSkillRuntimeService(mock(ParticleDisplayService.class));
        PassiveSkillContext context = context(astPlayer, definition, 0L);

        runtime.tick(context);
        assertFalse(runtime.isEffectActive(playerId));
        runtime.tick(context(astPlayer, definition, 99L));
        assertFalse(runtime.isEffectActive(playerId));
        runtime.tick(context(astPlayer, definition, 100L));
        assertTrue(runtime.isEffectActive(playerId));

        MeditationSkillExecutor executor = new MeditationSkillExecutor(runtime);
        assertEquals(3.0D, executor.passiveResourceRegenMultiplier(
            context(astPlayer, definition, 100L), SkillResourceType.MANA
        ));
        assertEquals(3.0D, executor.passiveResourceRegenMultiplier(
            context(astPlayer, definition, 100L), SkillResourceType.ENERGY
        ));

        runtime.interrupt(playerId);
        assertFalse(runtime.isEffectActive(playerId));
        runtime.tick(context(astPlayer, definition, 101L));
        runtime.tick(context(astPlayer, definition, 200L));
        assertFalse(runtime.isEffectActive(playerId));
        runtime.tick(context(astPlayer, definition, 201L));
        assertTrue(runtime.isEffectActive(playerId));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-イベント.md
     * 章・見出し: # 13_3-イベント > ## 5. メディテーション中断
     * 検証契約: 発動時の音に加え、設定した20 tick間隔の環境音だけを再生する。
     */
    @Test
    void playsAmbientSoundAtConfiguredIntervalWhileActive() {
        UUID playerId = UUID.randomUUID();
        AstPlayer astPlayer = player(playerId);
        Player bukkit = astPlayer.getBukkit();
        World world = mock(World.class);
        Location location = new Location(world, 0.0D, 64.0D, 0.0D);
        when(bukkit.getLocation()).thenReturn(location);
        SkillDefinition definition = definition(Map.of(
            "chargeTicks", 100,
            "regenMultiplier", 3,
            "chargeParticleIntervalTicks", 10,
            "activeParticleIntervalTicks", 5,
            "activeSoundIntervalTicks", 20
        ));
        MeditationSkillRuntimeService runtime = new MeditationSkillRuntimeService(mock(ParticleDisplayService.class));

        runtime.tick(context(astPlayer, definition, 0L));
        runtime.tick(context(astPlayer, definition, 100L));
        verify(world).playSound(
            location,
            Sound.BLOCK_AMETHYST_BLOCK_CHIME,
            0.55F,
            1.15F
        );
        verify(world, org.mockito.Mockito.never()).playSound(
            location,
            Sound.BLOCK_BEACON_AMBIENT,
            SoundCategory.PLAYERS,
            0.35F,
            1.15F
        );

        runtime.tick(context(astPlayer, definition, 119L));
        verify(world, org.mockito.Mockito.never()).playSound(
            location,
            Sound.BLOCK_BEACON_AMBIENT,
            SoundCategory.PLAYERS,
            0.35F,
            1.15F
        );
        runtime.tick(context(astPlayer, definition, 120L));
        verify(world).playSound(
            location,
            Sound.BLOCK_BEACON_AMBIENT,
            SoundCategory.PLAYERS,
            0.35F,
            1.15F
        );

        runtime.interrupt(playerId);
        runtime.tick(context(astPlayer, definition, 180L));
        verify(world, org.mockito.Mockito.times(1)).playSound(
            location,
            Sound.BLOCK_BEACON_AMBIENT,
            SoundCategory.PLAYERS,
            0.35F,
            1.15F
        );
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-サービス.md
     * 章・見出し: # 13_3-サービス > ## 6.1 自然回復倍率
     * 検証契約: 100 tickと3倍倍率は固定値としてexecutorのparams検証で拒否できる。
     */
    @Test
    void validatesFixedChargeAndRegenMultiplier() {
        MeditationSkillExecutor executor = new MeditationSkillExecutor(
            new MeditationSkillRuntimeService(mock(ParticleDisplayService.class))
        );
        assertDoesNotThrow(() -> executor.validateParams(definition(Map.of(
            "chargeTicks", 100,
            "regenMultiplier", 3,
            "chargeParticleIntervalTicks", 10,
            "activeParticleIntervalTicks", 5,
            "activeSoundIntervalTicks", 40
        ))));
        assertThrows(RuntimeException.class, () -> executor.validateParams(definition(Map.of(
            "chargeTicks", 60,
            "regenMultiplier", 3,
            "chargeParticleIntervalTicks", 10,
            "activeParticleIntervalTicks", 5,
            "activeSoundIntervalTicks", 40
        ))));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-イベント.md
     * 章・見出し: # 13_3-イベント > ## 5. メディテーション中断
     * 検証契約: activeSoundIntervalTicks は正の整数だけを受け付け、未定義・0・小数・int範囲外を拒否する。
     */
    @Test
    void validatesActiveSoundIntervalAsPositiveInt() {
        MeditationSkillExecutor executor = new MeditationSkillExecutor(
            new MeditationSkillRuntimeService(mock(ParticleDisplayService.class))
        );
        assertDoesNotThrow(() -> executor.validateParams(definition(Map.of(
            "chargeTicks", 100,
            "regenMultiplier", 3,
            "chargeParticleIntervalTicks", 10,
            "activeParticleIntervalTicks", 5,
            "activeSoundIntervalTicks", 20
        ))));

        Map<String, Object> missing = new java.util.HashMap<>(validParams());
        missing.remove("activeSoundIntervalTicks");
        assertThrows(SkillParameterException.class, () -> executor.validateParams(definition(missing)));

        Map<String, Object> zero = new java.util.HashMap<>(validParams());
        zero.put("activeSoundIntervalTicks", 0);
        assertThrows(SkillParameterException.class, () -> executor.validateParams(definition(zero)));

        Map<String, Object> fractional = new java.util.HashMap<>(validParams());
        fractional.put("activeSoundIntervalTicks", 40.5D);
        assertThrows(SkillParameterException.class, () -> executor.validateParams(definition(fractional)));

        Map<String, Object> outOfRange = new java.util.HashMap<>(validParams());
        outOfRange.put("activeSoundIntervalTicks", (double) Integer.MAX_VALUE + 1.0D);
        assertThrows(SkillParameterException.class, () -> executor.validateParams(definition(outOfRange)));
    }

    private static Map<String, Object> validParams() {
        return new java.util.HashMap<>(Map.of(
            "chargeTicks", 100,
            "regenMultiplier", 3,
            "chargeParticleIntervalTicks", 10,
            "activeParticleIntervalTicks", 5,
            "activeSoundIntervalTicks", 40
        ));
    }

    private static AstPlayer player(UUID playerId) {
        AccountModel account = mock(AccountModel.class);
        when(account.getUuid()).thenReturn(UUID.randomUUID());
        Player bukkit = mock(Player.class);
        when(bukkit.getUniqueId()).thenReturn(playerId);
        when(bukkit.isOnline()).thenReturn(true);
        when(bukkit.isDead()).thenReturn(false);
        when(bukkit.isSneaking()).thenReturn(true);
        AstPlayer player = mock(AstPlayer.class);
        when(player.getAccount()).thenReturn(account);
        when(player.getBukkit()).thenReturn(bukkit);
        return player;
    }

    private static PassiveSkillContext context(AstPlayer player, SkillDefinition definition, long activeTicks) {
        return new PassiveSkillContext(player, definition, Instant.EPOCH, activeTicks);
    }

    private static SkillDefinition definition(Map<String, Object> params) {
        return new SkillDefinition(
            MeditationSkillExecutor.ID,
            MeditationSkillExecutor.ID,
            "&dメディテーション",
            null,
            null,
            List.of(),
            0L,
            0.0D,
            0L,
            1,
            null,
            params,
            List.of(),
            SkillKind.PASSIVE,
            true,
            SkillResourceType.MANA,
            0.0D
        );
    }
}
