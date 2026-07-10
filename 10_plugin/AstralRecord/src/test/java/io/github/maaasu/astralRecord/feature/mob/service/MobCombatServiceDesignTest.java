package io.github.maaasu.astralRecord.feature.mob.service;

import io.github.maaasu.astralRecord.feature.adventurerecord.service.AdventureRecordService;
import io.github.maaasu.astralRecord.feature.account.service.AccountService;
import io.github.maaasu.astralRecord.feature.mob.model.CombatStyle;
import io.github.maaasu.astralRecord.feature.mob.model.MobBaseStat;
import io.github.maaasu.astralRecord.feature.mob.model.MobCategory;
import io.github.maaasu.astralRecord.feature.mob.model.MobCombatConfig;
import io.github.maaasu.astralRecord.feature.mob.model.MobEquipmentConfig;
import io.github.maaasu.astralRecord.feature.mob.model.MobIdleConfig;
import io.github.maaasu.astralRecord.feature.mob.model.MobInstance;
import io.github.maaasu.astralRecord.feature.mob.model.MobInteractionsConfig;
import io.github.maaasu.astralRecord.feature.mob.model.MobShieldConfig;
import io.github.maaasu.astralRecord.feature.mob.model.MobTemplate;
import io.github.maaasu.astralRecord.feature.party.service.PartyService;
import io.github.maaasu.astralRecord.feature.playersetting.service.PlayerSettingService;
import io.github.maaasu.astralRecord.feature.playerclass.PlayerClassService;
import io.github.maaasu.astralRecord.feature.skilltree.service.SkillTreeService;
import io.github.maaasu.astralRecord.feature.status.model.StatusType;
import io.github.maaasu.astralRecord.feature.status.service.StatusService;
import io.github.maaasu.astralRecord.shared.display.DisplayTextService;
import io.github.maaasu.astralRecord.shared.effect.ParticleDisplayService;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import org.bukkit.Location;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

class MobCombatServiceDesignTest extends MockBukkitTestBase {

    @Test
    void computeDamageAppliesMobInstanceOutgoingDamageMultiplier() {
        MobCombatService service = newService();
        MobInstance mob = new MobInstance(UUID.randomUUID(), attackTemplate(), new Location(null, 0.0D, 0.0D, 0.0D));
        Player target = server().addPlayer();

        mob.outgoingDamageMultiplier(1.5D);

        assertEquals(30.0D, service.computeDamage(mob, target), 0.0001D);
    }

    private MobCombatService newService() {
        return new MobCombatService(
                mock(MobService.class),
                mock(MobDropService.class),
                mock(MobDropPresentationService.class),
                mock(PartyService.class),
                mock(AdventureRecordService.class),
                mock(AccountService.class),
                mock(PlayerClassService.class),
                mock(StatusService.class),
                mock(SkillTreeService.class),
                mock(ParticleDisplayService.class),
                mock(DisplayTextService.class),
                mock(PlayerSettingService.class)
        );
    }

    private MobTemplate attackTemplate() {
        return new MobTemplate(
                1,
                "scaled_mob",
                MobCategory.ENEMY,
                "Scaled Mob",
                null,
                1,
                EntityType.ZOMBIE,
                false,
                "ZOMBIE_HEAD",
                List.of(),
                List.of(),
                null,
                MobEquipmentConfig.EMPTY,
                List.of(
                        new MobBaseStat(StatusType.MAX_HEALTH.name(), 100.0D),
                        new MobBaseStat(StatusType.ATTACK.name(), 10.0D),
                        new MobBaseStat(StatusType.STRENGTH.name(), 100.0D)
                ),
                MobShieldConfig.EMPTY,
                MobIdleConfig.defaults(),
                false,
                MobInteractionsConfig.EMPTY,
                null,
                new MobCombatConfig(CombatStyle.MELEE, 2.0D, 20L, List.of()),
                null
        );
    }
}
