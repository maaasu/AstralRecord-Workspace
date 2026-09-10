package io.github.maaasu.astralRecord.shared.gui;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HeadTextureItemStackSupportTest {
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
    }

    private static String texture(String url) {
        String json = "{\"textures\":{\"SKIN\":{\"url\":\"" + url + "\"}}}";
        return Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }
}
