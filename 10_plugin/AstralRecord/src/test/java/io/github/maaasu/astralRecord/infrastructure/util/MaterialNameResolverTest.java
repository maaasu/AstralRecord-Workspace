package io.github.maaasu.astralRecord.infrastructure.util;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class MaterialNameResolverTest {

    /**
     * 設計入力: PLUGIN_GUIDE.md
     * 章・見出し: # AstralRecord Plugin > ## 共通基盤の設定スナップショットと入力正規化 > ### Material 名解決
     * 検証契約: trim/uppercase後legacy CHAIN aliasをIRON_CHAINへ解決する。
     */
    @Test
    void legacyChainNameResolvesToCurrentIronChainMaterial() {
        assertEquals(Material.IRON_CHAIN, MaterialNameResolver.match("CHAIN"));
        assertEquals(Material.IRON_CHAIN, MaterialNameResolver.match(" iron_chain "));
    }

    /**
     * 設計入力: PLUGIN_GUIDE.md
     * 章・見出し: # AstralRecord Plugin > ## 共通基盤の設定スナップショットと入力正規化 > ### Material 名解決
     * 検証契約: 未知Material名を例外やfallbackでなくnullとして返す。
     */
    @Test
    void unknownNameReturnsNull() {
        assertNull(MaterialNameResolver.match("NOT_A_MATERIAL"));
    }
}
