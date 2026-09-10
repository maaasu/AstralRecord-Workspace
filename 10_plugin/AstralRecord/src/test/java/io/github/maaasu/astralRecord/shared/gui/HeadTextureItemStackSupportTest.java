package io.github.maaasu.astralRecord.shared.gui;

import org.junit.jupiter.api.Test;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

class HeadTextureItemStackSupportTest extends MockBukkitTestBase {
    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 5. ItemStack生成 > ### display・shop ItemStack生成
     * 検証契約: iconTexture は Base64 JSON の textures.SKIN.url が公式 texture URL のときだけ適用可能とする。
     */
    @Test
    void acceptsOnlyOfficialTextureUrlInTexturesSkinPayload() {
        assertTrue(HeadTextureItemStackSupport.isValid(texture("https://textures.minecraft.net/texture/a1b2c3")));
        assertFalse(HeadTextureItemStackSupport.isValid("not-base64"));
        assertFalse(HeadTextureItemStackSupport.isValid(texture("https://example.invalid/texture/a1b2c3")));
        assertFalse(HeadTextureItemStackSupport.isValid(Base64.getEncoder().encodeToString("{}".getBytes(StandardCharsets.UTF_8))));
        assertTrue(HeadTextureItemStackSupport.isValid(texture("https://TEXTURES.MINECRAFT.NET/texture/abc")));
        assertFalse(HeadTextureItemStackSupport.isValid("A".repeat(16385)));
        for (String invalid : new String[]{"{", "[]", "{\"textures\":[]}", "{\"textures\":{\"SKIN\":[]}}", "{\"textures\":null}"}) {
            assertFalse(HeadTextureItemStackSupport.isValid(Base64.getEncoder().encodeToString(invalid.getBytes(StandardCharsets.UTF_8))));
        }
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 5. ItemStack生成 > ### display・shop ItemStack生成
     * 検証契約: PLAYER_HEADにはtexturesを設定し、無効値と通常Materialでは既存のアイコンを維持する。
     */
    @Test
    void appliesProfileOnlyToPlayerHeadsAndPreservesExistingProfileOnInvalidInput() {
        String value = texture("https://textures.minecraft.net/texture/abc");
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        assertTrue(HeadTextureItemStackSupport.apply(head, " " + value + " "));
        SkullMeta meta = (SkullMeta) head.getItemMeta();
        assertEquals(value, meta.getPlayerProfile().getProperties().stream()
            .filter(property -> property.getName().equals("textures")).findFirst().orElseThrow().getValue());
        assertFalse(HeadTextureItemStackSupport.apply(head, "invalid"));
        assertEquals(meta, head.getItemMeta());
        ItemStack paper = new ItemStack(Material.PAPER);
        assertFalse(HeadTextureItemStackSupport.apply(paper, value));
        assertEquals(Material.PAPER, paper.getType());
    }

    private static String texture(String url) {
        String json = "{\"textures\":{\"SKIN\":{\"url\":\"" + url + "\"}}}";
        return Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }
}
