package io.github.maaasu.astralRecord.feature.skill.service;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.skill.executor.SkillExecutor;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastContext;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastResult;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastTrigger;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCaster;
import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.feature.skill.model.SkillKind;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParameterException;
import io.github.maaasu.astralRecord.feature.skill.model.SkillResourceType;
import io.github.maaasu.astralRecord.feature.skill.model.SkillSummary;
import io.github.maaasu.astralRecord.feature.skill.registry.SkillRegistry;
import io.github.maaasu.astralRecord.feature.skill.repository.SkillRepository;
import io.github.maaasu.astralRecord.feature.status.model.StatusSnapshot;
import io.github.maaasu.astralRecord.support.DesignTestFixtures;
import org.bukkit.Location;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.util.logging.Logger;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SkillServiceDesignTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-サービス.md
     * 章・見出し: # 13_3-サービス > ## 1. definition load / reload
     * 検証契約: reload時に登録executorがありparams検証を通るdefinitionだけを一括公開する。
     */
    @Test
    void reloadDefinitionsKeepsOnlyDefinitionsWithRegisteredExecutorsAndValidParams() {
        SkillRepository repository = mock(SkillRepository.class);
        SkillRegistry registry = new SkillRegistry();
        SkillService service = new SkillService(repository, registry, null);
        registry.registerExecutor(new TestExecutor("valid_impl"));
        registry.registerExecutor(new RejectingExecutor("rejecting_impl"));

        when(repository.findAll()).thenReturn(List.of(
            summary("valid_skill", "valid_impl"),
            summary("missing_executor_skill", "missing_impl"),
            summary("bad_params_skill", "rejecting_impl")
        ));
        when(repository.findById("valid_skill")).thenReturn(skill("valid_skill", "valid_impl", 0.0D, 0L, Map.of()));
        when(repository.findById("missing_executor_skill")).thenReturn(skill("missing_executor_skill", "missing_impl", 0.0D, 0L, Map.of()));
        when(repository.findById("bad_params_skill")).thenReturn(skill("bad_params_skill", "rejecting_impl", 0.0D, 0L, Map.of()));

        int loaded;
        try (MockedStatic<AstralRecord> astralRecord = Mockito.mockStatic(AstralRecord.class)) {
            AstralRecord plugin = mock(AstralRecord.class);
            when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
            astralRecord.when(AstralRecord::getInstance).thenReturn(plugin);
            loaded = service.reloadDefinitions();
        }

        assertEquals(1, loaded);
        assertEquals(1, registry.definitionCount());
        assertNotNull(registry.getDefinition("valid_skill"));
        assertNull(registry.getDefinition("missing_executor_skill"));
        assertNull(registry.getDefinition("bad_params_skill"));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-サービス.md
     * 章・見出し: # 13_3-サービス > ## 4. skill 発動
     * 検証契約: executor成功後だけdefinition resourceを消費し短縮後cooldownを開始する。
     */
    @Test
    void castSkillConsumesDefinitionResourceAndStartsDefinitionCooldownAfterExecutorSuccess() {
        SkillRepository repository = mock(SkillRepository.class);
        SkillRegistry registry = new SkillRegistry();
        SkillService service = new SkillService(repository, registry, null);
        TestExecutor executor = new TestExecutor("damage_impl");
        SkillDefinition definition = skill(
            "arc_slash",
            "damage_impl",
            0.0D,
            40L,
            Map.of(),
            SkillKind.ACTIVE,
            0L,
            1,
            SkillResourceType.ENERGY,
            5.0D
        );
        registry.registerExecutor(executor);
        registry.replaceDefinitions(Map.of(definition.getId(), definition));
        TestCaster caster = new TestCaster(3, 20.0D, 12.0D);

        SkillCastResult result = service.castSkill(
            caster,
            definition.getId(),
            SkillCastTrigger.SYSTEM,
            new Location(null, 0.0D, 0.0D, 0.0D),
            null,
            List.of()
        );

        assertTrue(result.success());
        assertEquals(20.0D, caster.currentMana(), 0.0001D);
        assertEquals(7.0D, caster.currentEnergy(), 0.0001D);
        assertTrue(service.isOnCooldown(caster, definition.getId()));
        assertTrue(service.getRemainingCooldownTicks(caster, definition.getId()) > 0L);
        assertTrue(service.getRemainingCooldownTicks(caster, definition.getId()) <= 40L);
        assertSame(definition, executor.lastContext.skill());
        assertEquals(SkillCastTrigger.SYSTEM, executor.lastContext.trigger());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-サービス.md
     * 章・見出し: # 13_3-サービス > ## 4. skill 発動
     * 検証契約: executor失敗時はresourceを消費せずcooldownも開始しない。
     */
    @Test
    void castSkillDoesNotConsumeResourceOrStartCooldownAfterExecutorFailure() {
        SkillRepository repository = mock(SkillRepository.class);
        SkillRegistry registry = new SkillRegistry();
        SkillService service = new SkillService(repository, registry, null);
        SkillDefinition definition = skill("failed_skill", "failed_impl", 5.0D, 40L, Map.of());
        registry.registerExecutor(new FailingExecutor("failed_impl"));
        registry.replaceDefinitions(Map.of(definition.getId(), definition));
        TestCaster caster = new TestCaster(3, 20.0D, 0.0D);

        SkillCastResult result = service.castSkill(
            caster,
            definition.getId(),
            SkillCastTrigger.SYSTEM,
            new Location(null, 0.0D, 0.0D, 0.0D),
            null,
            List.of()
        );

        assertFalse(result.success());
        assertEquals(20.0D, caster.currentMana(), 0.0001D);
        assertFalse(service.isOnCooldown(caster, definition.getId()));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-サービス.md
     * 章・見出し: # 13_3-サービス > ## 1. definition load / reload
     * 検証契約: legacy mana項目をresourceへ正規化しmalformed definitionだけを除外して他を公開する。
     */
    @Test
    void loadDefinitionsNormalizesLegacyResourcesAndIsolatesMalformedDefinitions() {
        SkillRepository repository = mock(SkillRepository.class);
        SkillRegistry registry = new SkillRegistry();
        SkillService service = new SkillService(repository, registry, null);
        registry.registerExecutor(new TestExecutor("valid_impl"));
        registry.registerExecutor(new RuntimeRejectingExecutor("runtime_rejecting_impl"));
        when(repository.findAll()).thenReturn(List.of());

        service.registerBuiltInDefinitions(List.of(
            skill("legacy_mana", "valid_impl", 4.0D, 0L, Map.of()),
            skill("legacy_energy", "valid_impl", 0.0D, 0L,
                Map.of("resourceType", "ENERGY", "resourceCost", 2.0D)),
            skill("top_level_wins", "valid_impl", 0.0D, 0L,
                Map.of("resourceType", "ENERGY", "resourceCost", 99.0D), SkillKind.ACTIVE,
                0L, 1, SkillResourceType.MANA, 3.0D),
            skill("", "valid_impl", 0.0D, 0L, Map.of()),
            skill("blank_impl", "", 0.0D, 0L, Map.of()),
            skill("bad_resource_type", "valid_impl", 0.0D, 0L,
                Map.of("resourceType", "HEALTH")),
            skill("negative_resource", "valid_impl", 0.0D, 0L, Map.of(), SkillKind.ACTIVE,
                0L, 1, SkillResourceType.MANA, -1.0D),
            skill("negative_cooldown", "valid_impl", 0.0D, -1L, Map.of()),
            skill("negative_cast", "valid_impl", 0.0D, 0L, Map.of(), SkillKind.ACTIVE,
                -1L, 1, null, null),
            skill("negative_level", "valid_impl", 0.0D, 0L, Map.of(), SkillKind.ACTIVE,
                0L, -1, null, null),
            skill("runtime_rejected", "runtime_rejecting_impl", 0.0D, 0L, Map.of())
        ));

        Map<String, SkillDefinition> loaded = service.loadDefinitions();

        assertEquals(3, loaded.size());
        assertEquals(SkillResourceType.MANA, loaded.get("legacy_mana").getResourceType());
        assertEquals(4.0D, loaded.get("legacy_mana").getResourceCost(), 0.0001D);
        assertEquals(SkillResourceType.ENERGY, loaded.get("legacy_energy").getResourceType());
        assertEquals(2.0D, loaded.get("legacy_energy").getResourceCost(), 0.0001D);
        assertEquals(SkillResourceType.MANA, loaded.get("top_level_wins").getResourceType());
        assertEquals(3.0D, loaded.get("top_level_wins").getResourceCost(), 0.0001D);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-サービス.md
     * 章・見出し: # 13_3-サービス > ## 3. cast 可否
     * 検証契約: passive skillおよび必要mana不足をexecutor前に拒否する。
     */
    @Test
    void canCastRejectsPassiveSkillAndInsufficientManaBeforeExecution() {
        SkillService service = new SkillService(mock(SkillRepository.class), new SkillRegistry(), null);
        TestCaster caster = new TestCaster(10, 1.0D, 0.0D);
        SkillDefinition passive = skill(
            "passive_guard",
            "passive_impl",
            0.0D,
            0L,
            Map.of(),
            SkillKind.PASSIVE
        );
        SkillDefinition costly = skill("costly", "active_impl", 5.0D, 0L, Map.of());

        SkillCastResult passiveResult = service.canCast(caster, passive);
        SkillCastResult costlyResult = service.canCast(caster, costly);

        assertFalse(passiveResult.success());
        assertEquals(PlayerMsgId.P_5805, passiveResult.messageId());
        assertFalse(costlyResult.success());
        assertEquals(PlayerMsgId.P_5801, costlyResult.messageId());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-サービス.md
     * 章・見出し: # 13_3-サービス > ## 5. cooldown・cast lifecycle
     * 検証契約: caster cleanupで詠唱stateを消し、指定方針に応じcooldownを保持または除去する。
     */
    @Test
    @SuppressWarnings("unchecked")
    void casterLifecycleCleanupRunsCastingCleanupAndCanPreserveOrRemoveCooldown() throws ReflectiveOperationException {
        SkillService service = new SkillService(mock(SkillRepository.class), new SkillRegistry(), null);
        TestCaster caster = new TestCaster(1, 10.0D, 10.0D);
        BukkitTask castingTask = mock(BukkitTask.class);
        AtomicBoolean castingCleanupRan = new AtomicBoolean(false);
        Class<?> sessionType = Class.forName(SkillService.class.getName() + "$CastingSession");
        var sessionConstructor = sessionType.getDeclaredConstructor(BukkitTask.class, Runnable.class);
        sessionConstructor.setAccessible(true);
        Object session = sessionConstructor.newInstance(castingTask, (Runnable) () -> castingCleanupRan.set(true));
        var sessionsField = SkillService.class.getDeclaredField("castingSessions");
        sessionsField.setAccessible(true);
        Map<UUID, Object> sessions = (Map<UUID, Object>) sessionsField.get(service);
        sessions.put(caster.casterId(), session);
        service.startCooldown(caster, "lifecycle_skill", 100L);

        service.cancelCasting(caster.casterId());
        verify(castingTask).cancel();
        assertTrue(castingCleanupRan.get());
        assertFalse(sessions.containsKey(caster.casterId()));
        assertTrue(service.isOnCooldown(caster, "lifecycle_skill"));

        service.clearCasterState(caster.casterId());
        assertFalse(service.isOnCooldown(caster, "lifecycle_skill"));
    }

    private SkillSummary summary(String id, String implementationId) {
        return new SkillSummary(id, id, implementationId, null, List.of());
    }

    private SkillDefinition skill(
        String id,
        String implementationId,
        double manaCost,
        long cooldownTicks,
        Map<String, Object> params
    ) {
        return skill(id, implementationId, manaCost, cooldownTicks, params, SkillKind.ACTIVE);
    }

    private SkillDefinition skill(
        String id,
        String implementationId,
        double manaCost,
        long cooldownTicks,
        Map<String, Object> params,
        SkillKind kind
    ) {
        return skill(id, implementationId, manaCost, cooldownTicks, params, kind, 0L, 1, null, null);
    }

    private SkillDefinition skill(
        String id,
        String implementationId,
        double manaCost,
        long cooldownTicks,
        Map<String, Object> params,
        SkillKind kind,
        long castTimeTicks,
        int requiredLevel,
        SkillResourceType resourceType,
        Double resourceCost
    ) {
        return new SkillDefinition(
            id,
            implementationId,
            id,
            null,
            null,
            List.of(),
            cooldownTicks,
            manaCost,
            castTimeTicks,
            requiredLevel,
            null,
            params,
            List.of(),
            kind,
            true,
            resourceType,
            resourceCost
        );
    }

    private static class TestExecutor implements SkillExecutor {
        private final String implementationId;
        private SkillCastContext lastContext;

        TestExecutor(String implementationId) {
            this.implementationId = implementationId;
        }

        @Override
        public String implementationId() {
            return implementationId;
        }

        @Override
        public SkillCastResult cast(SkillCastContext context) {
            this.lastContext = context;
            return SkillCastResult.succeeded();
        }
    }

    private static final class FailingExecutor extends TestExecutor {
        FailingExecutor(String implementationId) {
            super(implementationId);
        }

        @Override
        public SkillCastResult cast(SkillCastContext context) {
            return SkillCastResult.failure(PlayerMsgId.P_5805);
        }
    }

    private static final class RejectingExecutor extends TestExecutor {
        RejectingExecutor(String implementationId) {
            super(implementationId);
        }

        @Override
        public void validateParams(SkillDefinition skill) {
            throw new SkillParameterException("params", "invalid");
        }
    }

    private static final class RuntimeRejectingExecutor extends TestExecutor {
        RuntimeRejectingExecutor(String implementationId) {
            super(implementationId);
        }

        @Override
        public void validateParams(SkillDefinition skill) {
            throw new IllegalStateException("unexpected validation failure");
        }
    }

    private static final class TestCaster implements SkillCaster {
        private final UUID casterId = UUID.randomUUID();
        private final int level;
        private double mana;
        private double energy;
        private PlayerMsgId lastMessageId;

        TestCaster(int level, double mana, double energy) {
            this.level = level;
            this.mana = mana;
            this.energy = energy;
        }

        @Override
        public UUID casterId() {
            return casterId;
        }

        @Override
        public int level() {
            return level;
        }

        @Override
        public StatusSnapshot statusSnapshot() {
            return DesignTestFixtures.statusSnapshot(Map.of(), 0.0D, mana, energy);
        }

        @Override
        public double currentMana() {
            return mana;
        }

        @Override
        public double currentEnergy() {
            return energy;
        }

        @Override
        public void consumeMana(double amount) {
            mana -= amount;
        }

        @Override
        public void consumeEnergy(double amount) {
            energy -= amount;
        }

        @Override
        public void notify(PlayerMsgId messageId, Object... args) {
            lastMessageId = messageId;
        }
    }
}
