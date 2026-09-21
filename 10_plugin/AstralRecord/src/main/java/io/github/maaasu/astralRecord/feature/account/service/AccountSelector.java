package io.github.maaasu.astralRecord.feature.account.service;

import io.github.maaasu.astralRecord.feature.account.model.AccountModel;

/** アカウント管理コマンドに共通するスロット・名称の判定です。 */
public final class AccountSelector {
    private AccountSelector() { }

    /**
     * 1～2桁のASCII数字はスロット、それ以外は大文字小文字を区別しない名称として照合します。
     * @param account 照合する作成済みアカウント
     * @param selector 入力された識別子。3桁以上の数字だけの名称も許可
     * @return 識別子が対象アカウントを示す場合true
     */
    public static boolean matches(AccountModel account, String selector) {
        if (selector == null) return false;
        if (selector.matches("[0-9]{1,2}")) return account.getSlotIndex() == Integer.parseInt(selector);
        return account.getAccountName().equalsIgnoreCase(selector);
    }
}
