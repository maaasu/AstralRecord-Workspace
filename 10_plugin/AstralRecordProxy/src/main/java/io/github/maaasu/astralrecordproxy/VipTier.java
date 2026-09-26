package io.github.maaasu.astralrecordproxy;

/** アカウントの期限付きVIP種別。旧ユーザー権限とは独立する。 */
enum VipTier {
    NONE, DONER, ASTRALDER;

    /** 不明・未指定の種別は通常アカウントとして扱う。 */
    static VipTier parse(String value) {
        if ("DONER".equals(value)) return DONER;
        if ("ASTRALDER".equals(value)) return ASTRALDER;
        return NONE;
    }
}
