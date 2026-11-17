package io.github.maaasu.astralrecordgeyser;

import java.util.UUID;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReceptionDisplayEntityTest {
    private static final long MARKER = 0x4153545245435054L;

    @Test
    void decodesBothStylesAndYawWithoutChangingEntityIdBits() {
        assertEquals(new ReceptionDisplayEntity.Frame(0, 0),
            ReceptionDisplayEntity.decode(new UUID(MARKER, 0x0000_0000_1234_5678L), 0x1234_5678));
        assertEquals(new ReceptionDisplayEntity.Frame(1, 3599),
            ReceptionDisplayEntity.decode(new UUID(MARKER, 0x000e_0f01_fedc_ba98L), 0xfedc_ba98));
    }

    @Test
    void checksBothCompleteTextureSequences(@TempDir Path temp) throws Exception {
        assertTrue(packsWithFrames(temp.resolve("complete.zip"), null, false));
        assertTrue(packsWithFrames(temp.resolve("static-v1.zip"), null, true));
        assertFalse(packsWithFrames(temp.resolve("no-gate.zip"), "dungeon_31.png", false));
        assertFalse(packsWithFrames(temp.resolve("no-sword.zip"), "boss_00.png", false));
    }

    private static boolean packsWithFrames(Path target, String missing, boolean legacy) throws Exception {
        try (var out = new ZipOutputStream(Files.newOutputStream(target))) {
            for (String entry : new String[] {"animations/reception_display.animation.json", "models/entity/reception_display.geo.json",
                "render_controllers/reception_display.render_controllers.json", "textures/entity/world/boss_reception_altar.png",
                "textures/entity/world/dungeon_reception_gate.png"}) {
                put(out, entry);
            }
            var textures = new com.google.gson.JsonObject();
            if (legacy) {
                textures.addProperty("style_0", "textures/entity/world/boss_reception_altar");
                textures.addProperty("style_1", "textures/entity/world/dungeon_reception_gate");
            } else {
                for (String type : new String[] {"boss", "dungeon"}) {
                    for (int frame = 0; frame < 32; frame++) {
                        String suffix = (frame < 10 ? "0" : "") + frame;
                        String name = "textures/entity/world/reception_animation/" + type + "_" + suffix;
                        textures.addProperty((type.equals("boss") ? "sword_frame_" : "frame_") + suffix, name);
                        if (!(type + "_" + suffix + ".png").equals(missing)) put(out, name + ".png");
                    }
                }
            }
            out.putNextEntry(new ZipEntry("entity/reception_display.entity.json"));
            out.write(("{\"minecraft:client_entity\":{\"description\":{\"textures\":" + textures + "}}}")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.closeEntry();
        }
        try (var zip = new ZipFile(target.toFile())) {
            return DamagePack.hasReceptionDisplayTextures(zip);
        }
    }

    private static void put(ZipOutputStream out, String name) throws Exception {
        out.putNextEntry(new ZipEntry(name));
        out.write(1);
        out.closeEntry();
    }

    @Test
    void rejectsAnUnknownMarkerOrAnEntityIdMismatch() {
        assertNull(ReceptionDisplayEntity.decode(new UUID(0L, 0x1234L), 0x1234));
        assertNull(ReceptionDisplayEntity.decode(new UUID(MARKER, 0x1234L), 0x4321));
    }

    @Test
    void rejectsUnsupportedStylesYawAndReservedBits() {
        assertNull(ReceptionDisplayEntity.decode(new UUID(MARKER, 0x0000_0002_0000_1234L), 0x1234));
        assertNull(ReceptionDisplayEntity.decode(new UUID(MARKER, 0x000e_1001_0000_1234L), 0x1234));
        assertNull(ReceptionDisplayEntity.decode(new UUID(MARKER, 0x0100_0000_0000_1234L), 0x1234));
    }
}
