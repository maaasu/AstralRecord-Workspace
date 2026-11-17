package io.github.maaasu.astralRecord.feature.user.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNull;

class UserPermissionTest {

    /**
     * 廃止したDONOR権限は名称・値ともに解決できない。
     */
    @Test
    void retiredDonorPermissionCannotBeGranted() {
        assertNull(UserPermission.Companion.parse("DONOR"));
        assertNull(UserPermission.Companion.parse("5"));
    }
}
