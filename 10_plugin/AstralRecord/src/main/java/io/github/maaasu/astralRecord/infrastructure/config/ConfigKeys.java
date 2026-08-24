package io.github.maaasu.astralRecord.infrastructure.config;

/**
 * config.yml の設定キーを定数として定義するクラス。
 * タイポを防ぎ、IDE の補完機能を活用できます。
 */
public final class ConfigKeys {

    private ConfigKeys() {
        // utility class
    }

    // Plugin 関連
    public static final String PLUGIN_DEBUG_MODE = "plugin.debugMode";
    public static final String PLUGIN_DEBUG_USERS = "plugin.debugUsers";
    public static final String PLUGIN_WHITELIST_ENABLED = "plugin.whitelistEnabled";

    // Boss／Dungeon インスタンス作成枠
    public static final String INSTANCE_LIMITS_BOSS = "instanceLimits.boss";
    public static final String INSTANCE_LIMITS_DUNGEON = "instanceLimits.dungeon";

    // SQL Server 関連
    public static final String SQLSERVER_ENABLED = "database.sqlserver.enabled";
    public static final String SQLSERVER_IP_ADDRESS = "database.sqlserver.ipAddress";
    public static final String SQLSERVER_PORT = "database.sqlserver.port";
    public static final String SQLSERVER_DATABASE = "database.sqlserver.database";
    public static final String SQLSERVER_ENCRYPT = "database.sqlserver.encrypt";
    public static final String SQLSERVER_TRUST_SERVER_CERTIFICATE = "database.sqlserver.trustServerCertificate";
    public static final String SQLSERVER_USER = "database.sqlserver.user";
    public static final String SQLSERVER_PASSWORD = "database.sqlserver.password";

    // フォルダ型データベース関連
    public static final String FILE_DATABASE_ROOT_PATH = "database.file.rootPath";

    // データベース接続プール関連
    public static final String DATABASE_POOL_MAX_POOL_SIZE = "database.pool.maxPoolSize";
    public static final String DATABASE_POOL_MIN_IDLE = "database.pool.minIdle";
    public static final String DATABASE_POOL_CONNECTION_TIMEOUT = "database.pool.connectionTimeout";

    // ログ関連
    public static final String LOGGING_AUDIT_ENABLED = "logging.audit.enabled";
    public static final String LOGGING_ENTRY_CONSOLE_LOG_ENTRY = "logging.entry.ConsoleLogEntry";
    public static final String LOGGING_USE_ANSI_COLORS = "logging.useAnsiColors";
    public static final String LOGGING_CRAFTY_CONTROLLER_COLORS = "logging.craftyControllerColors";

    // AstralRecord API 関連
    public static final String API_BASE_URL = "api.baseUrl";
    public static final String API_AUTH_API_KEY = "api.auth.apiKey";
    public static final String API_TIMEOUT = "api.timeout";
    public static final String API_SSL_VERIFY_ENABLED = "api.ssl.verifyEnabled";
    public static final String API_SERVER_ID = "api.serverId";

    // Resource pack settings
    public static final String RESOURCE_PACK_ENABLED = "resourcePack.enabled";
    public static final String RESOURCE_PACK_URL = "resourcePack.url";
    public static final String RESOURCE_PACK_SHA1 = "resourcePack.sha1";
    public static final String RESOURCE_PACK_FORCE = "resourcePack.force";
    public static final String RESOURCE_PACK_PROMPT = "resourcePack.prompt";
    public static final String RESOURCE_PACK_SKIP_BEDROCK = "resourcePack.skipBedrock";
    public static final String RESOURCE_PACK_BEDROCK_NAME_PREFIXES = "resourcePack.bedrockNamePrefixes";

    // DiscordSRV chat bridge settings
    public static final String DISCORD_ENABLED = "discord.enabled";
    public static final String DISCORD_GLOBAL_CHANNEL_ID = "discord.globalChannelId";
    public static final String DISCORD_MAX_MESSAGE_LENGTH = "discord.maxMessageLength";

}
