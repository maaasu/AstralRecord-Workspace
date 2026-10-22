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
    public static final String PLUGIN_WHITELIST_USERS = "plugin.whitelistUsers";
    public static final String PLUGIN_WHITELIST_ENABLED = "plugin.whitelistEnabled";

    // プレイヤー接続人数制限
    public static final String PLAYER_CAPACITY_MAX_PLAYERS = "playerCapacity.maxPlayers";
    public static final String PLAYER_CAPACITY_DONOR_EXTRA_PLAYERS = "playerCapacity.donorExtraPlayers";
    public static final String PLAYER_CAPACITY_ADMIN_EXTRA_PLAYERS = "playerCapacity.adminExtraPlayers";

    // プレイヤー参加ロード
    public static final String PLAYER_JOIN_MAX_CONCURRENT_LOADS = "playerJoin.maxConcurrentLoads";
    public static final String PLAYER_JOIN_SKILL_TREE_RETRY_MAX_ATTEMPTS = "playerJoin.skillTreeRetry.maxAttempts";
    public static final String PLAYER_JOIN_SKILL_TREE_RETRY_INITIAL_DELAY_MILLIS = "playerJoin.skillTreeRetry.initialDelayMillis";
    public static final String PLAYER_JOIN_SKILL_TREE_RETRY_MAX_DELAY_MILLIS = "playerJoin.skillTreeRetry.maxDelayMillis";

    // Boss／Dungeon インスタンス作成枠
    public static final String INSTANCE_LIMITS_BOSS = "instanceLimits.boss";
    public static final String INSTANCE_LIMITS_DUNGEON = "instanceLimits.dungeon";

    // ログ関連
    public static final String LOGGING_AUDIT_ENABLED = "logging.audit.enabled";
    public static final String LOGGING_ENTRY_CONSOLE_LOG_ENTRY = "logging.entry.ConsoleLogEntry";
    public static final String LOGGING_USE_ANSI_COLORS = "logging.useAnsiColors";
    public static final String LOGGING_CRAFTY_CONTROLLER_COLORS = "logging.craftyControllerColors";

    // AstralRecord API 関連
    public static final String API_BASE_URL = "api.baseUrl";
    public static final String API_AUTH_API_KEY = "api.auth.apiKey";
    public static final String API_TIMEOUT = "api.timeout";
    public static final String API_OPERATION_TIMEOUT = "api.operationTimeout";
    public static final String API_SSL_VERIFY_ENABLED = "api.ssl.verifyEnabled";
    public static final String API_SERVER_ID = "api.serverId";
    public static final String API_NETWORK_MODERATION_KEY = "api.networkModerationKey";
    public static final String API_SKILL_TREE_RUNTIME_KEY = "api.skillTreeRuntimeKey";

    // マスターデータ自動再読込
    public static final String MASTER_DATA_AUTO_RELOAD_ENABLED = "masterData.autoReload.enabled";
    public static final String MASTER_DATA_AUTO_RELOAD_POLL_INTERVAL_SECONDS =
            "masterData.autoReload.pollIntervalSeconds";

    // Velocity network settings
    public static final String NETWORK_ENABLED = "network.enabled";
    public static final String NETWORK_CHANNEL_NAME = "network.channelName";

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

    // ローマ字チャット変換設定
    public static final String CHAT_ROMAJI_CONVERSION_ENABLED = "chat.romajiConversion.enabled";
    public static final String CHAT_KANJI_CONVERSION_ENABLED = "chat.romajiConversion.kanjiConversionEnabled";
    public static final String CHAT_GOOGLE_IME_ENDPOINT = "chat.romajiConversion.googleImeEndpoint";
    public static final String CHAT_GOOGLE_IME_TIMEOUT_MILLIS = "chat.romajiConversion.googleImeTimeoutMillis";
    public static final String CHAT_ROMAJI_BYPASS_MARKER = "chat.romajiConversion.bypassMarker";

}
