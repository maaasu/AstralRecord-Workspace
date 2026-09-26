package io.github.maaasu.astralRecord.feature.user.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNull;

class UserPermissionTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/01-user/01_1-モデル定義.md
     * 章・見出し: # 01_1-モデル定義 > ## 2. ユーザモデル > ### 2.2 ユーザ権限
     * 検証契約: 廃止したDONOR権限は名称・値ともに解決できない。
     */
    @Test
    void retiredDonorPermissionCannotBeGranted() {
        assertNull(UserPermission.Companion.parse("DONOR"));
        assertNull(UserPermission.Companion.parse("5"));
    }
}
