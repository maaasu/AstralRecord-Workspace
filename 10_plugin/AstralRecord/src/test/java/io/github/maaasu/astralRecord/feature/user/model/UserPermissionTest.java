package io.github.maaasu.astralRecord.feature.user.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class UserPermissionTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/01-user/01_1-モデル定義.md
     * 章・見出し: # 01_1-モデル定義 > ## 2. ユーザモデル > ### 2.2 ユーザ権限
     * 検証契約: DONOR は値5で定義され、英語IDと数値文字列のどちらからも解決できる。
     */
    @Test
    void donorUsesPermissionValueFiveAndParsesByNameOrValue() {
        assertEquals(5, UserPermission.DONOR.getValue());
        assertSame(UserPermission.DONOR, UserPermission.Companion.parse("DONOR"));
        assertSame(UserPermission.DONOR, UserPermission.Companion.parse("5"));
    }
}
