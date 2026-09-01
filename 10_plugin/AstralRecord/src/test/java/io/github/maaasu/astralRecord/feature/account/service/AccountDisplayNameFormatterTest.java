package io.github.maaasu.astralRecord.feature.account.service;

import io.github.maaasu.astralRecord.feature.account.model.AccountModel;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AccountDisplayNameFormatterTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/02-account/02_1-モデル定義.md
     * 章・見出し: # 02_1-モデル定義 > ## 2. アカウントモデル > ### 2.1 項目 > #### 2.1.1 識別情報
     * 検証契約: アカウント名は通常色のまま、#スロット番号だけを灰色で描画し、&7を文字として送信しない。
     */
    @Test
    void colorsOnlySlotSuffixWithoutSendingLegacyCodeLiterally() {
        AccountModel account = mock(AccountModel.class);
        when(account.getAccountName()).thenReturn("Alice");
        when(account.getSlotIndex()).thenReturn(0);

        Component display = AccountDisplayNameFormatter.toComponent(account);

        assertEquals("Alice#0", PlainTextComponentSerializer.plainText().serialize(display));
        assertTrue(display.children().stream().anyMatch(child -> NamedTextColor.GRAY.equals(child.color())));
        assertFalse(PlainTextComponentSerializer.plainText().serialize(display).contains("&7"));
        assertEquals("Alice&7#0", AccountDisplayNameFormatter.toLegacy(account));
    }
}
