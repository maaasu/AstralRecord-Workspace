package io.github.maaasu.astralRecord.feature.user.model

/**
 * ユーザ権限レベルを表す列挙型。
 *
 * 番号・英語ID・日本語表示の 3 種で管理する。
 * 値が大きいほど権限が強く、最大値は [ADMIN] の `99`。
 */
enum class UserPermission(val value: Int, val displayName: String) {
    /** プレイヤー */
    PLAYER(0, "プレイヤー"),

    /** ビルダー */
    BUILDER(10, "ビルダー"),

    /** サーバー管理者 */
    ADMIN(99, "サーバー管理者");

    companion object {
        /**
         * 名称（大小文字無視）から [UserPermission] を取得します。
         *
         * @param name 英語ID（`PLAYER` / `BUILDER` / `ADMIN`）
         * @return 一致する権限。一致しない場合は `null`
         */
        fun fromName(name: String): UserPermission? =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) }

        /**
         * 数値から [UserPermission] を取得します。
         *
         * @param value 権限値
         * @return 一致する権限。一致しない場合は `null`
         */
        fun fromValue(value: Int): UserPermission? =
            entries.firstOrNull { it.value == value }

        /**
         * 名称または数値文字列から [UserPermission] を取得します。
         * 名称優先で解決し、整数として解析できる場合のみ数値で再解決します。
         *
         * @param token 入力トークン
         * @return 一致する権限。一致しない場合は `null`
         */
        fun parse(token: String): UserPermission? {
            fromName(token)?.let { return it }
            val intValue = token.toIntOrNull() ?: return null
            return fromValue(intValue)
        }
    }
}
