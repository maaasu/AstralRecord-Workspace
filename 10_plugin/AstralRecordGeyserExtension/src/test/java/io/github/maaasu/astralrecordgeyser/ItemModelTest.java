package io.github.maaasu.astralrecordgeyser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ItemModelTest {
    private static final String CATALOG = """
        {"format_version":1,"items":[
          {"id":"nox_sword","base_item":"minecraft:wooden_sword","custom_model_data":21001}
        ]}
        """;

    @Test
    void readsCatalogAndRejectsAmbiguousOrInexactSelectors() throws IOException {
        String catalog = """
            {
              "format_version": 1,
              "items": [
                {"id":"nox_sword","base_item":"minecraft:wooden_sword","custom_model_data":21001},
                {"id":"nox_staff","base_item":"minecraft:wooden_shovel","custom_model_data":21003}
              ]
            }
            """;
        assertEquals(Set.of(
            new ItemModel("nox_sword", "minecraft:wooden_sword", 21001),
            new ItemModel("nox_staff", "minecraft:wooden_shovel", 21003)
        ), ItemModel.parse(catalog));
        assertThrows(IOException.class, () -> ItemModel.parse(catalog.replace("nox_staff", "nox_sword")));
        assertThrows(IOException.class, () -> ItemModel.parse(catalog.replace("21003", "21001")));
        assertThrows(IOException.class, () -> ItemModel.parse(catalog.replace("21003", "21003.5")));
        assertThrows(IOException.class, () -> ItemModel.parse(catalog.replace("21003", "16777216")));
        assertThrows(IOException.class, () -> ItemModel.parse(catalog.replace("21003", "0")));
    }

    @Test
    void rejectsMalformedJsonAndDuplicateFields() {
        assertThrows(IOException.class, () -> ItemModel.parse("/* comment */" + CATALOG));
        assertThrows(IOException.class, () -> ItemModel.parse(CATALOG + "{}"));
        assertThrows(IOException.class, () -> ItemModel.parse(CATALOG.replace(
            "\"format_version\":1", "\"format_version\":null,\"format_version\":1")));
        assertThrows(IOException.class, () -> ItemModel.parse(CATALOG.replace(
            "\"id\":\"nox_sword\"", "\"id\":\"other\",\"id\":\"nox_sword\"")));
    }

    @Test
    void acceptsCompletePacksAndRejectsMissingAssets(@TempDir Path directory) throws IOException {
        String atlas = """
            {"texture_data":{"astralrecord.nox_sword":{"textures":"textures/items/nox_sword"}}}
            """;
        var entries = Map.of(
            "manifest.json", "{}",
            "astralrecord_item_models.json", CATALOG,
            "textures/item_texture.json", atlas,
            "attachables/nox_sword.json", "{}",
            "models/entity/items/nox_sword.geo.json", "{}",
            "animations/nox_sword.animation.json", "{}",
            "textures/entity/items/nox_sword.png", "texture",
            "textures/items/nox_sword.png", "icon"
        );
        Path pack = directory.resolve("test.mcpack");
        writePack(pack, entries);
        try (var zip = new ZipFile(pack.toFile())) {
            assertEquals(Set.of(new ItemModel("nox_sword", "minecraft:wooden_sword", 21001)), ItemModel.read(zip));
        }
        for (String path : entries.keySet()) {
            if (path.equals("manifest.json") || path.equals("astralrecord_item_models.json")) continue;
            var incomplete = new HashMap<>(entries);
            incomplete.remove(path);
            writePack(pack, incomplete);
            try (var zip = new ZipFile(pack.toFile())) {
                assertThrows(IOException.class, () -> ItemModel.read(zip), path);
            }
        }
        var wrongAtlas = new HashMap<>(entries);
        wrongAtlas.put("textures/item_texture.json", atlas.replace("textures/items/nox_sword", "textures/items/other"));
        writePack(pack, wrongAtlas);
        try (var zip = new ZipFile(pack.toFile())) {
            assertThrows(IOException.class, () -> ItemModel.read(zip));
        }
    }

    @Test
    void supportsOldPacksWithoutItemCatalog(@TempDir Path directory) throws IOException {
        Path pack = directory.resolve("old.mcpack");
        writePack(pack, Map.of("manifest.json", "{}"));
        try (var zip = new ZipFile(pack.toFile())) {
            assertEquals(Set.of(), ItemModel.read(zip));
        }
    }

    private static void writePack(Path pack, Map<String, String> entries) throws IOException {
        try (var zip = new ZipOutputStream(Files.newOutputStream(pack))) {
            for (var entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
    }
}
