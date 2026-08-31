package io.github.maaasu.astralRecord.feature.skill.executor;

import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.skill.model.PassiveSkillContext;
import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.feature.skill.model.SkillKind;
import io.github.maaasu.astralRecord.feature.skill.model.SkillResourceType;
import io.github.maaasu.astralRecord.feature.skill.service.PassiveSkillService;
import io.github.maaasu.astralRecord.feature.status.model.ShieldRechargeState;
import io.github.maaasu.astralRecord.feature.status.model.StatusSnapshot;
import io.github.maaasu.astralRecord.feature.status.model.StatusType;
import io.github.maaasu.astralRecord.feature.status.service.StatusService;
import io.github.maaasu.astralRecord.shared.effect.ParticleDisplayService;
import io.github.maaasu.astralRecord.support.DesignTestFixtures;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class AdministratorShieldRechargeSkillExecutorTest extends MockBukkitTestBase {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/14-combat/14_0-概要.md
     * 章・見出し: # 14_0-概要 > ## 5. 固定HPダメージとShield
     * 検証契約: filebaseの2%設定がexecutor経由で実行時の最大Shield30・毎秒0.6へ反映される。
     */
    @Test
    void filebaseRechargeRateReachesStatusRuntime() {
        StatusService statusService = activatedStatusService();
        AstPlayer player = DesignTestFixtures.astPlayer(server().addPlayer(), AccountMode.ADMIN);
        player.setStatusSnapshot(shieldSnapshot(30.0D, 10.0D));

        AdministratorShieldRechargeSkillExecutor executor = new AdministratorShieldRechargeSkillExecutor(
            statusService,
            mock(ParticleDisplayService.class)
        );
        executor.onActivate(new PassiveSkillContext(
            player,
            loadDefinitionFromFilebase(),
            Instant.EPOCH,
            0L
        ));

        ShieldRechargeState state = statusService.startShieldRechargeWhileRetained(player, 1_000L);

        assertNotNull(state);
        assertEquals(9_000L, state.completesAtMs());
        assertEquals(0.6D, state.rechargeAmount(), 0.0001D);
        assertTrue(statusService.completeShieldRechargeIfReady(player, 9_000L));
        assertEquals(10.6D, player.getStatusSnapshot().getCurrentShield(), 0.0001D);
        assertNotNull(statusService.getShieldRechargeState(player));

        for (int second = 1; second < 34; second++) {
            assertTrue(statusService.completeShieldRechargeIfReady(player, 9_000L + second * 1_000L));
        }
        assertEquals(30.0D, player.getStatusSnapshot().getCurrentShield(), 0.0001D);
        assertNull(statusService.getShieldRechargeState(player));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/14-combat/14_0-概要.md
     * 章・見出し: # 14_0-概要 > ## 5. 固定HPダメージとShield
     * 検証契約: 管理者向けスキルを有効にしていても、破壊後は通常の30秒全回復となり、スキル演出を出さない。
     */
    @Test
    void brokenShieldUsesFullRecoveryEvenWithSkillConfigured() {
        StatusService statusService = activatedStatusService();
        ParticleDisplayService particleDisplayService = mock(ParticleDisplayService.class);
        AstPlayer player = DesignTestFixtures.astPlayer(server().addPlayer(), AccountMode.ADMIN);
        player.setStatusSnapshot(shieldSnapshot(30.0D));

        AdministratorShieldRechargeSkillExecutor executor = new AdministratorShieldRechargeSkillExecutor(
            statusService,
            particleDisplayService
        );
        executor.onActivate(new PassiveSkillContext(
            player,
            loadDefinitionFromFilebase(),
            Instant.EPOCH,
            0L
        ));

        ShieldRechargeState state = statusService.startShieldRecharge(player, 1_000L);
        executor.onTick(new PassiveSkillContext(
            player,
            loadDefinitionFromFilebase(),
            Instant.EPOCH,
            10L
        ));

        assertEquals(31_000L, state.completesAtMs());
        assertEquals(30.0D, state.rechargeAmount(), 0.0001D);
        assertEquals(false, state.incrementalRecovery());
        verify(particleDisplayService, never()).spawnForNearbyViewers(
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.anyList(),
            org.mockito.ArgumentMatchers.any()
        );
    }

    private static SkillDefinition loadDefinitionFromFilebase() {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(filebasePath().toFile());
        ConfigurationSection rawParams = yaml.getConfigurationSection("params");
        if (rawParams == null) {
            throw new AssertionError("passive.params must be defined in administrator shield recharge filebase");
        }
        Map<String, Object> params = new LinkedHashMap<>();
        for (String key : rawParams.getKeys(false)) {
            params.put(key, rawParams.get(key));
        }
        return new SkillDefinition(
            yaml.getString("id", AdministratorShieldRechargeSkillExecutor.ID),
            yaml.getString("implementationId", AdministratorShieldRechargeSkillExecutor.ID),
            yaml.getString("name", "シールドリチャージ"),
            yaml.getString("description"),
            yaml.getString("icon"),
            yaml.getStringList("lore"),
            yaml.getLong("cooldownTicks", 0L),
            yaml.getDouble("resourceCost", 0.0D),
            yaml.getLong("castTimeTicks", 0L),
            yaml.getInt("requiredLevel", 1),
            null,
            params,
            yaml.getStringList("tags"),
            SkillKind.PASSIVE,
            yaml.getBoolean("passive.bindRequired", true),
            SkillResourceType.valueOf(yaml.getString("resourceType", "MANA")),
            yaml.getDouble("resourceCost", 0.0D)
        );
    }

    private static Path filebasePath() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null) {
            Path candidate = current.resolve("40_filebase/30.features.skill/v1.administrator_shield_recharge.yml");
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            current = current.getParent();
        }
        throw new AssertionError("administrator_shield_recharge filebase was not found from the test directory");
    }

    private static StatusSnapshot shieldSnapshot(double maxShield) {
        return shieldSnapshot(maxShield, 0.0D);
    }

    private static StatusSnapshot shieldSnapshot(double maxShield, double currentShield) {
        return DesignTestFixtures.statusSnapshot(Map.of(
            StatusType.MAX_HEALTH, 100.0D,
            StatusType.MAX_SHIELD, maxShield
        ), 100.0D, 0.0D, 0.0D).withCurrentShield(currentShield);
    }

    private StatusService activatedStatusService() {
        StatusService statusService = new StatusService();
        PassiveSkillService passiveSkillService = mock(PassiveSkillService.class);
        org.mockito.Mockito.when(passiveSkillService.isPassiveSkillActive(
            org.mockito.ArgumentMatchers.any(AstPlayer.class),
            org.mockito.ArgumentMatchers.eq(StatusService.SHIELD_ACTIVATE_SKILL_ID)
        )).thenReturn(true);
        statusService.setPassiveSkillService(passiveSkillService);
        return statusService;
    }
}
