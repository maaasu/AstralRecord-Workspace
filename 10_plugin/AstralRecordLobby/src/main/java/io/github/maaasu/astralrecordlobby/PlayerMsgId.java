package io.github.maaasu.astralrecordlobby;

/** ロビーのネットワーク通知に使用するメッセージ識別子です。 */
enum PlayerMsgId {
    /** 共通システム接頭辞。 */
    P_5940,
    /** チャンネル接続通知。 */
    P_7156,
    ACCOUNT_ALREADY_EXISTS,
    ACCOUNT_LOADING,
    ACCOUNT_UNAVAILABLE,
    ACCOUNT_CREATING,
    ACCOUNT_EDITING,
    ACCOUNT_CREATED,
    ACCOUNT_CREATE_FAILED,
    ACCOUNT_CREATED_SELECTION_FAILED
}
