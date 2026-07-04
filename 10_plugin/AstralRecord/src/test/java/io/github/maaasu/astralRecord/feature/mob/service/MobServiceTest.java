package io.github.maaasu.astralRecord.feature.mob.service;

import io.github.maaasu.astralRecord.feature.mob.model.MobCategory;
import io.github.maaasu.astralRecord.feature.mob.model.MobShieldConfig;
import io.github.maaasu.astralRecord.feature.mob.model.MobTemplate;
import io.github.maaasu.astralRecord.feature.mob.repository.MobRepository;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import org.bukkit.Location;
import org.bukkit.entity.EntityType;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class MobServiceTest extends MockBukkitTestBase {

    @Test
    void canSeeTreatsDeadPlayersAsAbsent() {
        var world = server().addSimpleWorld("dead_viewer_world");
        PlayerMock player = server().addPlayer();
        Location mobLocation = new Location(world, 0.0D, 64.0D, 0.0D);
        player.teleport(mobLocation.clone());
        MobService service = new MobService(MockBukkit.createMockPlugin("AstralRecord"), mock(MobRepository.class));

        assertTrue(service.canSee(player, mobLocation));

        player.setHealth(0.0D);

        assertFalse(service.canSee(player, mobLocation));
    }

    @Test
    void resolveTemplateIdAcceptsDisplayNameAndDecoratedSelector() throws Exception {
        MobService service = new MobService(MockBukkit.createMockPlugin("AstralRecord"), mock(MobRepository.class));
        MobTemplate template = template("starter_shopkeeper", "&6始まりの商人");
        templates(service).put(template.id(), template);

        assertEquals("starter_shopkeeper", service.resolveTemplateId("始まりの商人", List.of(MobCategory.NPC)));
        assertEquals(
                "starter_shopkeeper",
                service.resolveTemplateId("starter_shopkeeper（始まりの商人）", List.of(MobCategory.NPC))
        );
    }

    @Test
    void resolveTemplateIdSkipsApiLookupForJapaneseDisplayName() throws Exception {
        MobRepository repository = mock(MobRepository.class);
        MobService service = new MobService(MockBukkit.createMockPlugin("AstralRecord"), repository);
        MobTemplate template = template("npc_storage", "&6ストレージ");
        templates(service).put(template.id(), template);

        assertEquals("npc_storage", service.resolveTemplateId("ストレージ", List.of(MobCategory.NPC)));
        verify(repository, never()).findById("ストレージ");
    }

    private MobTemplate template(String id, String displayName) {
        return new MobTemplate(
                1,
                id,
                MobCategory.NPC,
                displayName,
                null,
                1,
                EntityType.VILLAGER,
                false,
                null,
                List.of(),
                List.of(),
                null,
                null,
                List.of(),
                MobShieldConfig.EMPTY,
                null,
                true,
                null,
                null,
                null,
                null
        );
    }

    @SuppressWarnings("unchecked")
    private Map<String, MobTemplate> templates(MobService service) throws Exception {
        Field templatesField = MobService.class.getDeclaredField("templates");
        templatesField.setAccessible(true);
        return (Map<String, MobTemplate>) templatesField.get(service);
    }
}
