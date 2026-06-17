package io.github.maaasu.astralRecord.feature.item.service;

import io.github.maaasu.astralRecord.feature.status.model.StatusType;
import io.github.maaasu.astralRecord.infrastructure.util.ColorCodeUtil;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

class ItemStackFactoryTest {

    @Test
    void miningSpeedUsesJapaneseTooltipLabelAndUtilityColor() throws Exception {
        ItemStackFactory factory = new ItemStackFactory(mock(io.github.maaasu.astralRecord.feature.loot.service.LootService.class), mock(ItemService.class));

        Method displayNameMethod = ItemStackFactory.class.getDeclaredMethod(
                "resolveStatusDisplayName",
                String.class,
                StatusType.class
        );
        displayNameMethod.setAccessible(true);

        Method colorMethod = ItemStackFactory.class.getDeclaredMethod(
                "statusCategoryColor",
                String.class,
                StatusType.class
        );
        colorMethod.setAccessible(true);

        String displayName = (String) displayNameMethod.invoke(factory, "MINING_SPEED", null);
        String color = (String) colorMethod.invoke(factory, "MINING_SPEED", null);

        assertEquals("採集速度", displayName);
        assertEquals(ColorCodeUtil.YELLOW, color);
    }
}
