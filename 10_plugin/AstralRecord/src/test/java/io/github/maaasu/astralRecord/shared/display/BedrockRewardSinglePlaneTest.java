package io.github.maaasu.astralRecord.shared.display;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.TextDisplay;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class BedrockRewardSinglePlaneTest {
    // The same result body feeds the native Geyser nametag (mob kill and encounter).
    @Test
    void nativeBodyKeepsIconsAndCountdownOnShortMobResult() throws Exception {
        var plaque = CombatRewardPlaque.layout("Mob cleared", "Grass Boar", () -> "XP +8",
            List.of(new CombatRewardPlaque.Row("\uE700 ", "Loot", " x2")), false, CombatRewardPlaque.WidthMode.FIXED);
        String text = new NativeBody(plaque, 120, 120).sample(0);
        assertTrue(text.contains("Mob cleared"));
        assertTrue(text.contains("Grass Boar"));
        assertTrue(text.contains("XP +8"));
        assertTrue(text.contains("\uE700"));
        assertTrue(text.contains("Loot x2"));
        assertFalse(text.contains("\uE731")); // Java-only spacer/gold-panel layer
    }

    @Test
    void nativeEncounterBodyKeepsAllRowsAcrossPagesAndUpdatesCountdown() throws Exception {
        var countdown = new AtomicReference<>("15s");
        var details = new ArrayList<CombatRewardPlaque.Row>();
        for (int row = 1; row <= 35; row++) details.add(CombatRewardPlaque.Row.text("Loot-%02d".formatted(row)));
        var plaque = CombatRewardPlaque.layout("Boss clear", "Encounter", countdown::get, details, false, CombatRewardPlaque.WidthMode.EXPAND);
        assertTrue(plaque.pageCount() > 1);
        var view = new NativeBody(plaque, 300, 30);
        var first = view.sample(0);
        assertTrue(first.contains("Loot-01"));
        assertTrue(first.contains("15s"));
        var all = new StringBuilder(first);
        for (int page = 1; page < plaque.pageCount(); page++) {
            countdown.set("14s");
            all.append(view.sample(page * 30L));
        }
        for (int row = 1; row <= 35; row++) assertTrue(all.toString().contains("Loot-%02d".formatted(row)));
        assertTrue(all.toString().contains("14s"));
    }

    private record NativeBody(Object state, Object animation, TextDisplay display, AtomicReference<Component> text) {
        NativeBody(CombatRewardPlaque plaque, long duration, long pageTicks) throws Exception {
            this(newState(), newAnimation(plaque, duration, pageTicks), mock(TextDisplay.class), new AtomicReference<>());
            doAnswer(call -> { text.set(call.getArgument(0)); return null; }).when(display).text(any(Component.class));
        }

        String sample(long tick) throws Exception {
            var age = state.getClass().getDeclaredField("animationAge");
            age.setAccessible(true);
            age.setLong(state, tick);
            var update = DisplayTextService.class.getDeclaredMethod("updatePlaqueBody", state.getClass(), TextDisplay.class,
                animation.getClass(), CombatRewardPlaque.class);
            update.setAccessible(true);
            var plaque = animation.getClass().getDeclaredField("plaque");
            plaque.setAccessible(true);
            update.invoke(null, state, display, animation, plaque.get(animation));
            return PlainTextComponentSerializer.plainText().serialize(text.get());
        }

        private static Object newState() throws Exception {
            var ctor = Class.forName(DisplayTextService.class.getName() + "$ManagedDisplayState").getDeclaredConstructors()[0];
            ctor.setAccessible(true);
            return ctor.newInstance(null, null, false, 1L);
        }

        private static Object newAnimation(CombatRewardPlaque plaque, long duration, long pageTicks) throws Exception {
            var ctor = Class.forName(DisplayTextService.class.getName() + "$ResultAnimation").getDeclaredConstructors()[0];
            ctor.setAccessible(true);
            return ctor.newInstance(UUID.randomUUID(), duration, plaque, pageTicks, true, (int)duration);
        }
    }
}
