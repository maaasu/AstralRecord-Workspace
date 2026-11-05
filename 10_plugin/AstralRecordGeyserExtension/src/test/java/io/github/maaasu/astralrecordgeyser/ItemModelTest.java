package io.github.maaasu.astralrecordgeyser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        ), ItemModel.parse(catalog).models());
        assertThrows(IOException.class, () -> ItemModel.parse(catalog.replace("nox_staff", "nox_sword")));
        assertThrows(IOException.class, () -> ItemModel.parse(catalog.replace("21003", "21001")));
        assertThrows(IOException.class, () -> ItemModel.parse(catalog.replace("21003", "21003.5")));
        assertThrows(IOException.class, () -> ItemModel.parse(catalog.replace("21003", "16777216")));
        assertThrows(IOException.class, () -> ItemModel.parse(catalog.replace("21003", "0")));
    }

    @Test
    void keepsHeldWeaponEligibilitySeparateFromItemMappings() throws IOException {
        var legacy = ItemModel.parse(CATALOG);
        var enabled = ItemModel.parse(CATALOG.replace("21001", "21001,\"held_weapon\":true"));
        var disabled = ItemModel.parse(CATALOG.replace("21001", "21001,\"held_weapon\":false"));

        assertEquals(legacy.models(), enabled.models());
        assertEquals(legacy, disabled);
        assertEquals(Set.of(), legacy.heldWeaponModels());
        assertEquals(Set.of(21001), enabled.heldWeaponModels());
        assertThrows(IOException.class,
            () -> ItemModel.parse(CATALOG.replace("21001", "21001,\"held_weapon\":\"true\"")));
    }

    @Test
    void heldWeaponRequiresBothMatchingRenderAssets(@TempDir Path directory) throws IOException {
        String attachable = """
            {"minecraft:attachable":{"description":{
              "identifier":"minecraft:trident",
              "geometry":{"weapon_nox_sword":"geometry.astralrecord.nox_sword"},
              "textures":{"weapon_nox_sword":"textures/entity/items/nox_sword"},
              "render_controllers":[{"controller.render.astralrecord.held_nox_sword":"v.model == 21001"}]
            }}}
            """;
        String controllers = """
            {"render_controllers":{"controller.render.astralrecord.held_nox_sword":{"geometry":"geometry.weapon_nox_sword"}}}
            """;
        String attachablePath = "attachables/trident.entity.json";
        String controllersPath = "render_controllers/held_weapon.render_controllers.json";
        var catalog = ItemModel.parse(CATALOG.replace("21001", "21001,\"held_weapon\":true"));
        Path pack = directory.resolve("held-weapon.mcpack");

        writePack(pack, Map.of(attachablePath, attachable, controllersPath, controllers));
        try (var zip = new ZipFile(pack.toFile())) {
            assertEquals(Set.of(21001), ItemModel.verifiedHeldWeaponModels(zip, catalog));
            assertEquals(Set.of(), ItemModel.verifiedHeldWeaponModels(zip, ItemModel.parse(CATALOG)));
        }
        writePack(pack, Map.of(attachablePath, attachable));
        try (var zip = new ZipFile(pack.toFile())) {
            assertEquals(Set.of(), ItemModel.verifiedHeldWeaponModels(zip, catalog));
        }
        writePack(pack, Map.of(attachablePath, attachable, controllersPath, controllers.replace("nox_sword", "nox_staff")));
        try (var zip = new ZipFile(pack.toFile())) {
            assertEquals(Set.of(), ItemModel.verifiedHeldWeaponModels(zip, catalog));
        }
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
            assertEquals(Set.of(new ItemModel("nox_sword", "minecraft:wooden_sword", 21001)), ItemModel.read(zip).models());
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
            assertEquals(new ItemModel.Catalog(Set.of(), Set.of()), ItemModel.read(zip));
        }
    }

    @Test
    void generatedItemsRequireIconsWithoutRequiringAttachables(@TempDir Path directory) throws IOException {
        String catalog = CATALOG.replace("\"custom_model_data\":21001", "\"custom_model_data\":21001,\"render_mode\":\"generated\"");
        var entries = new HashMap<>(Map.of(
            "astralrecord_item_models.json", catalog,
            "textures/item_texture.json", "{\"texture_data\":{\"astralrecord.nox_sword\":{\"textures\":\"textures/items/nox_sword\"}}}",
            "textures/items/nox_sword.png", "icon"
        ));
        Path pack = directory.resolve("generated.mcpack");
        writePack(pack, entries);
        try (var zip = new ZipFile(pack.toFile())) {
            assertEquals(Set.of(new ItemModel("nox_sword", "minecraft:wooden_sword", 21001, true)), ItemModel.read(zip).models());
        }
        entries.remove("textures/items/nox_sword.png");
        writePack(pack, entries);
        try (var zip = new ZipFile(pack.toFile())) {
            assertThrows(IOException.class, () -> ItemModel.read(zip));
        }
        assertThrows(IOException.class, () -> ItemModel.parse(catalog.replace("\"generated\"", "\"unknown\"")));
        assertThrows(IOException.class, () -> ItemModel.parse(catalog.replace("\"generated\"", "null")));
        assertEquals(ItemModel.parse(CATALOG), ItemModel.parse(catalog.replace("\"generated\"", "\"3d\"")));
        assertThrows(IOException.class, () -> ItemModel.parse(catalog.replace("\"generated\"", "\"generated\",\"held_weapon\":true")));
        // Distribution uses containsAll to keep packs incompatible with a changed render mode unpublished.
        assertFalse(ItemModel.parse(catalog).models().containsAll(ItemModel.parse(CATALOG).models()));
        assertFalse(ItemModel.parse(CATALOG).models().containsAll(ItemModel.parse(catalog).models()));
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
