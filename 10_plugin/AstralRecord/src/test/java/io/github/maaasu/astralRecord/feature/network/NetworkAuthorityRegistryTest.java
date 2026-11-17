package io.github.maaasu.astralRecord.feature.network;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NetworkAuthorityRegistryTest {
    @AfterEach
    void clearRegistry() {
        NetworkAuthorityRegistry.clear();
    }

    /**
     * Proxy最高権限UUIDは保存permissionに依存せず実効permission 99として扱う。
     */
    @Test
    void authorityOverridesStoredPermissionWithoutChangingOtherPlayers() {
        UUID authority = UUID.randomUUID();
        UUID regular = UUID.randomUUID();
        NetworkAuthorityRegistry.replace(Set.of(authority));

        assertTrue(NetworkAuthorityRegistry.isAuthority(authority));
        assertEquals(99, NetworkAuthorityRegistry.effectivePermission(authority, 0));
        assertFalse(NetworkAuthorityRegistry.isAuthority(regular));
        assertEquals(5, NetworkAuthorityRegistry.effectivePermission(regular, 5));
    }

    /**
     * APIから30秒を超えて最高権限一覧を更新できない場合は古いUUIDの権限を失効させる。
     */
    @Test
    void authorityExpiresThirtySecondsAfterLastSuccessfulRefresh() {
        UUID authority = UUID.randomUUID();
        NetworkAuthorityRegistry.replace(Set.of(authority));
        long updatedAt = System.nanoTime();

        assertFalse(NetworkAuthorityRegistry.isAuthority(
            authority, updatedAt + NetworkAuthorityRegistry.AUTHORITY_TTL_NANOS + 1L));
    }
}
