package io.github.maaasu.astralRecord.infrastructure.util;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class MaterialNameResolverTest {

    @Test
    void legacyChainNameResolvesToCurrentIronChainMaterial() {
        assertEquals(Material.IRON_CHAIN, MaterialNameResolver.match("CHAIN"));
        assertEquals(Material.IRON_CHAIN, MaterialNameResolver.match(" iron_chain "));
    }

    @Test
    void unknownNameReturnsNull() {
        assertNull(MaterialNameResolver.match("NOT_A_MATERIAL"));
    }
}
