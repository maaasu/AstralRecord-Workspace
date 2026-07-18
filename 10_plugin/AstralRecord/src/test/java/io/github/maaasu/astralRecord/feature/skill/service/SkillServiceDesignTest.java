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
import io.github.maaasu.astralRecord.feature.skill.model.SkillSummary;
import io.github.maaasu.astralRecord.feature.skill.registry.SkillRegistry;
import io.github.maaasu.astralRecord.feature.skill.repository.SkillRepository;
import io.github.maaasu.astralRecord.feature.status.model.StatusSnapshot;
import io.github.maaasu.astralRecord.support.DesignTestFixtures;
import org.bukkit.Location;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.util.logging.Logger;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SkillServiceDesignTest {

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

    @Test
    void castSkillConsumesResourceAndStartsCooldownOnlyAfterExecutorSuccess() {
        SkillRepository repository = mock(SkillRepository.class);
        SkillRegistry registry = new SkillRegistry();
        SkillService service = new SkillService(repository, registry, null);
        TestExecutor executor = new TestExecutor("damage_impl");
        SkillDefinition definition = skill("arc_slash", "damage_impl", 5.0D, 40L, Map.of());
        registry.registerExecutor(executor);
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

        assertTrue(result.success());
        assertEquals(5.0D, result.consumedMana(), 0.0001D);
        assertEquals(40L, result.startedCooldownTicks());
        assertEquals(15.0D, caster.currentMana(), 0.0001D);
        assertTrue(service.isOnCooldown(caster, definition.getId()));
        assertTrue(service.getRemainingCooldownTicks(caster, definition.getId()) > 0L);
        assertTrue(service.getRemainingCooldownTicks(caster, definition.getId()) <= 40L);
        assertSame(definition, executor.lastContext.skill());
        assertEquals(SkillCastTrigger.SYSTEM, executor.lastContext.trigger());
    }

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
        return new SkillDefinition(
            id,
            implementationId,
            id,
            null,
            null,
            List.of(),
            cooldownTicks,
            manaCost,
            0L,
            1,
            null,
            params,
            List.of(),
            kind,
            true
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
            return SkillCastResult.success(context.skill().getManaCost(), context.skill().getCooldownTicks());
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
