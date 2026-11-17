package io.github.maaasu.astralRecord.infrastructure.util;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class MaterialNameResolverTest {

    /**
     * trim/uppercase後legacy CHAIN aliasをIRON_CHAINへ解決する。
     */
    @Test
    void legacyChainNameResolvesToCurrentIronChainMaterial() {
        assertEquals(Material.IRON_CHAIN, MaterialNameResolver.match("CHAIN"));
        assertEquals(Material.IRON_CHAIN, MaterialNameResolver.match(" iron_chain "));
    }

    /**
     * 未知Material名を例外やfallbackでなくnullとして返す。
     */
    @Test
    void unknownNameReturnsNull() {
        assertNull(MaterialNameResolver.match("NOT_A_MATERIAL"));
    }
}
