package io.github.maaasu.astralRecord.infrastructure.config;

import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public class ConfigProperties {

    private static final int DEFAULT_PLAYER_CAPACITY_MAX_PLAYERS = 30;
    private static final int DEFAULT_PLAYER_CAPACITY_DONOR_EXTRA_PLAYERS = 5;
    private static final int DEFAULT_PLAYER_CAPACITY_ADMIN_EXTRA_PLAYERS = 1;
    private static final int DEFAULT_PLAYER_JOIN_MAX_CONCURRENT_LOADS = 4;
    private static final int DEFAULT_PLAYER_JOIN_SKILL_TREE_RETRY_MAX_ATTEMPTS = 3;
    private static final long DEFAULT_PLAYER_JOIN_SKILL_TREE_RETRY_INITIAL_DELAY_MILLIS = 250L;
    private static final long DEFAULT_PLAYER_JOIN_SKILL_TREE_RETRY_MAX_DELAY_MILLIS = 2_000L;
    private static final String DEFAULT_CHAT_GOOGLE_IME_ENDPOINT = "https://www.google.com/transliterate";
    private static final int DEFAULT_CHAT_GOOGLE_IME_TIMEOUT_MILLIS = 1_500;

    private static ConfigProperties instance;

    // Plugin 関連
    private boolean pluginDebugMode;
    private Set<UUID> pluginDebugUsers = Collections.emptySet();
    private volatile Set<UUID> pluginWhitelistUsers = Collections.emptySet();
    private volatile boolean pluginWhitelistEnabled;

    // プレイヤー接続人数制限
    private volatile int playerCapacityMaxPlayers;
    private volatile int playerCapacityDonorExtraPlayers;
    private volatile int playerCapacityAdminExtraPlayers;

    // プレイヤー参加ロード
    private volatile int playerJoinMaxConcurrentLoads = DEFAULT_PLAYER_JOIN_MAX_CONCURRENT_LOADS;
    private volatile int playerJoinSkillTreeRetryMaxAttempts = DEFAULT_PLAYER_JOIN_SKILL_TREE_RETRY_MAX_ATTEMPTS;
    private volatile long playerJoinSkillTreeRetryInitialDelayMillis = DEFAULT_PLAYER_JOIN_SKILL_TREE_RETRY_INITIAL_DELAY_MILLIS;
    private volatile long playerJoinSkillTreeRetryMaxDelayMillis = DEFAULT_PLAYER_JOIN_SKILL_TREE_RETRY_MAX_DELAY_MILLIS;

    // SQL Server 関連
    private boolean sqlserverEnabled;
    private String sqlserverIpAddress;
    private int sqlserverPort;
    private String sqlserverDatabaseName;
    private boolean sqlserverEncrypt;
    private boolean sqlserverTrustServerCertificate;
    private String sqlserverUser;
    private String sqlserverPassword;

    // フォルダ型データベース関連
    private String fileDatabaseRootPath;

    // データベース接続プール関連
    private int databasePoolMaxPoolSize;
    private int databasePoolMinIdle;
    private long databasePoolConnectionTimeout;

    // ログ関連
    private boolean loggingAuditEnabled;
    private boolean loggingEntryConsoleLogEntryEnabled;
    private boolean loggingUseAnsiColors;
    private boolean loggingCraftyControllerColors;

    // AstralRecord API 関連
    private String apiBaseUrl;
    private String apiAuthApiKey;
    private int apiTimeout;
    private long apiOperationTimeout;
    private boolean apiSslVerifyEnabled;
    private String apiServerId;

    // Resource pack settings
    private boolean resourcePackEnabled;
    private String resourcePackUrl;
    private String resourcePackSha1;
    private boolean resourcePackForce;
    private String resourcePackPrompt;
    private boolean resourcePackSkipBedrock;
    private List<String> resourcePackBedrockNamePrefixes;

    // DiscordSRV chat bridge settings
    private boolean discordEnabled;
    private String discordGlobalChannelId;
    private int discordMaxMessageLength;

    // ローマ字チャット変換設定
    private boolean chatRomajiConversionEnabled = true;
    private boolean chatKanjiConversionEnabled = true;
    private String chatGoogleImeEndpoint = DEFAULT_CHAT_GOOGLE_IME_ENDPOINT;
    private int chatGoogleImeTimeoutMillis = DEFAULT_CHAT_GOOGLE_IME_TIMEOUT_MILLIS;
    private String chatRomajiBypassMarker = "$";

    private ConfigProperties() {
        // private constructor for singleton
    }

    /**
     * ConfigPropertiesのシングルトンインスタンスを取得します。
     */
    public static synchronized ConfigProperties getInstance() {
        if (instance == null) {
            instance = new ConfigProperties();
        }
        return instance;
    }

    /**
     * ConfigManagerから設定値を読み込んでフィールドに設定します。
     * プラグインフォルダのconfig.ymlに書かれた実際の値を読み取ります。
     */
    public void initialize() {
        ConfigManager configManager = ConfigManager.getInstance();

        // Plugin 関連
        this.pluginDebugMode = configManager.getConfig().getBoolean(ConfigKeys.PLUGIN_DEBUG_MODE);
        this.pluginDebugUsers = parseConfiguredUsers(
                configManager.getConfig().getStringList(ConfigKeys.PLUGIN_DEBUG_USERS)
        );
        this.pluginWhitelistUsers = parseConfiguredUsers(
                configManager.getConfig().getStringList(ConfigKeys.PLUGIN_WHITELIST_USERS)
        );
        this.pluginWhitelistEnabled = configManager.getConfig().getBoolean(
                ConfigKeys.PLUGIN_WHITELIST_ENABLED,
                false
        );

        // プレイヤー接続人数制限
        this.playerCapacityMaxPlayers = Math.max(
                1,
                configManager.getConfig().getInt(
                        ConfigKeys.PLAYER_CAPACITY_MAX_PLAYERS,
                        DEFAULT_PLAYER_CAPACITY_MAX_PLAYERS
                )
        );
        this.playerCapacityDonorExtraPlayers = Math.max(
                0,
                configManager.getConfig().getInt(
                        ConfigKeys.PLAYER_CAPACITY_DONOR_EXTRA_PLAYERS,
                        DEFAULT_PLAYER_CAPACITY_DONOR_EXTRA_PLAYERS
                )
        );
        this.playerCapacityAdminExtraPlayers = Math.max(
                0,
                configManager.getConfig().getInt(
                        ConfigKeys.PLAYER_CAPACITY_ADMIN_EXTRA_PLAYERS,
                        DEFAULT_PLAYER_CAPACITY_ADMIN_EXTRA_PLAYERS
                )
        );
        this.playerJoinMaxConcurrentLoads = Math.max(
                1,
                configManager.getConfig().getInt(
                        ConfigKeys.PLAYER_JOIN_MAX_CONCURRENT_LOADS,
                        DEFAULT_PLAYER_JOIN_MAX_CONCURRENT_LOADS
                )
        );
        this.playerJoinSkillTreeRetryMaxAttempts = Math.max(
                1,
                configManager.getConfig().getInt(
                        ConfigKeys.PLAYER_JOIN_SKILL_TREE_RETRY_MAX_ATTEMPTS,
                        DEFAULT_PLAYER_JOIN_SKILL_TREE_RETRY_MAX_ATTEMPTS
                )
        );
        this.playerJoinSkillTreeRetryInitialDelayMillis = Math.max(
                1L,
                configManager.getConfig().getLong(
                        ConfigKeys.PLAYER_JOIN_SKILL_TREE_RETRY_INITIAL_DELAY_MILLIS,
                        DEFAULT_PLAYER_JOIN_SKILL_TREE_RETRY_INITIAL_DELAY_MILLIS
                )
        );
        this.playerJoinSkillTreeRetryMaxDelayMillis = Math.max(
                this.playerJoinSkillTreeRetryInitialDelayMillis,
                configManager.getConfig().getLong(
                        ConfigKeys.PLAYER_JOIN_SKILL_TREE_RETRY_MAX_DELAY_MILLIS,
                        DEFAULT_PLAYER_JOIN_SKILL_TREE_RETRY_MAX_DELAY_MILLIS
                )
        );

        // SQL Server 関連
        this.sqlserverEnabled = configManager.getConfig().getBoolean(ConfigKeys.SQLSERVER_ENABLED, true);
        this.sqlserverIpAddress = configManager.getConfig().getString(ConfigKeys.SQLSERVER_IP_ADDRESS);
        this.sqlserverPort = configManager.getConfig().getInt(ConfigKeys.SQLSERVER_PORT);
        this.sqlserverDatabaseName = configManager.getConfig().getString(ConfigKeys.SQLSERVER_DATABASE);
        this.sqlserverEncrypt = configManager.getConfig().getBoolean(ConfigKeys.SQLSERVER_ENCRYPT);
        this.sqlserverTrustServerCertificate = configManager.getConfig().getBoolean(ConfigKeys.SQLSERVER_TRUST_SERVER_CERTIFICATE);
        this.sqlserverUser = configManager.getConfig().getString(ConfigKeys.SQLSERVER_USER);
        this.sqlserverPassword = configManager.getConfig().getString(ConfigKeys.SQLSERVER_PASSWORD);

        // フォルダ型データベース関連
        this.fileDatabaseRootPath = configManager.getConfig().getString(ConfigKeys.FILE_DATABASE_ROOT_PATH, "filebase.path");

        // データベース接続プール関連
        this.databasePoolMaxPoolSize = configManager.getConfig().getInt(ConfigKeys.DATABASE_POOL_MAX_POOL_SIZE);
        this.databasePoolMinIdle = configManager.getConfig().getInt(ConfigKeys.DATABASE_POOL_MIN_IDLE);
        this.databasePoolConnectionTimeout = configManager.getConfig().getLong(ConfigKeys.DATABASE_POOL_CONNECTION_TIMEOUT);

        // ログ関連
        this.loggingAuditEnabled = configManager.getConfig().getBoolean(ConfigKeys.LOGGING_AUDIT_ENABLED, true);
        this.loggingEntryConsoleLogEntryEnabled = configManager.getConfig().getBoolean(ConfigKeys.LOGGING_ENTRY_CONSOLE_LOG_ENTRY, true);
        this.loggingUseAnsiColors = configManager.getConfig().getBoolean(ConfigKeys.LOGGING_USE_ANSI_COLORS, true);
        this.loggingCraftyControllerColors = configManager.getConfig().getBoolean(ConfigKeys.LOGGING_CRAFTY_CONTROLLER_COLORS, false);

        // AstralRecord API 関連
        this.apiBaseUrl = configManager.getConfig().getString(ConfigKeys.API_BASE_URL, "https://api.astralrecord.example.com");
        this.apiAuthApiKey = configManager.getConfig().getString(ConfigKeys.API_AUTH_API_KEY, "");
        this.apiTimeout = configManager.getConfig().getInt(ConfigKeys.API_TIMEOUT, 30000);
        this.apiOperationTimeout = Math.max(
                1_000L,
                configManager.getConfig().getLong(ConfigKeys.API_OPERATION_TIMEOUT, 60_000L)
        );
        this.apiSslVerifyEnabled = configManager.getConfig().getBoolean(ConfigKeys.API_SSL_VERIFY_ENABLED, true);
        this.apiServerId = configManager.getConfig().getString(ConfigKeys.API_SERVER_ID, "main");
        if (!this.apiSslVerifyEnabled) {
            Logger.log(LogId.W_1601);
        }

        // Resource pack settings
        this.resourcePackEnabled = configManager.getConfig().getBoolean(ConfigKeys.RESOURCE_PACK_ENABLED, false);
        this.resourcePackUrl = configManager.getConfig().getString(ConfigKeys.RESOURCE_PACK_URL, "");
        this.resourcePackSha1 = configManager.getConfig().getString(ConfigKeys.RESOURCE_PACK_SHA1, "");
        this.resourcePackForce = configManager.getConfig().getBoolean(ConfigKeys.RESOURCE_PACK_FORCE, false);
        this.resourcePackPrompt = configManager.getConfig().getString(
                ConfigKeys.RESOURCE_PACK_PROMPT,
                "AstralRecord のリソースパックを適用してください。"
        );
        this.resourcePackSkipBedrock = configManager.getConfig().getBoolean(ConfigKeys.RESOURCE_PACK_SKIP_BEDROCK, true);
        this.resourcePackBedrockNamePrefixes = configManager.getConfig().getStringList(
                ConfigKeys.RESOURCE_PACK_BEDROCK_NAME_PREFIXES
        );
        if (this.resourcePackBedrockNamePrefixes.isEmpty()) {
            this.resourcePackBedrockNamePrefixes = List.of(".", "*");
        }

        // DiscordSRV chat bridge settings
        this.discordEnabled = configManager.getConfig().getBoolean(ConfigKeys.DISCORD_ENABLED, true);
        this.discordGlobalChannelId = configManager.getConfig().getString(ConfigKeys.DISCORD_GLOBAL_CHANNEL_ID, "");
        this.discordMaxMessageLength = Math.max(
                1,
                configManager.getConfig().getInt(ConfigKeys.DISCORD_MAX_MESSAGE_LENGTH, 256)
        );

        // ローマ字チャット変換設定
        this.chatRomajiConversionEnabled = configManager.getConfig().getBoolean(
                ConfigKeys.CHAT_ROMAJI_CONVERSION_ENABLED,
                true
        );
        this.chatKanjiConversionEnabled = configManager.getConfig().getBoolean(
                ConfigKeys.CHAT_KANJI_CONVERSION_ENABLED,
                true
        );
        this.chatGoogleImeEndpoint = configManager.getConfig().getString(
                ConfigKeys.CHAT_GOOGLE_IME_ENDPOINT,
                DEFAULT_CHAT_GOOGLE_IME_ENDPOINT
        );
        this.chatGoogleImeTimeoutMillis = Math.max(
                100,
                configManager.getConfig().getInt(
                        ConfigKeys.CHAT_GOOGLE_IME_TIMEOUT_MILLIS,
                        DEFAULT_CHAT_GOOGLE_IME_TIMEOUT_MILLIS
                )
        );
        this.chatRomajiBypassMarker = configManager.getConfig().getString(
                ConfigKeys.CHAT_ROMAJI_BYPASS_MARKER,
                "$"
        );
    }

    /**
     * 設定をリロードします。
     */
    public void reload() {
        ConfigManager.getInstance().reload();
        initialize();
    }

    // Plugin 関連のゲッター
    public boolean isPluginDebugMode() {
        return pluginDebugMode;
    }

    /**
     * 指定されたプレイヤー UUID がデバッグユーザーとして設定されているかを返します。
     *
     * @param uuid 判定対象のプレイヤー UUID
     * @return `plugin.debugUsers` に完全一致する UUID が含まれていれば true
     */
    public boolean isDebugUser(UUID uuid) {
        return uuid != null && pluginDebugUsers.contains(uuid);
    }

    /**
     * 指定されたプレイヤー UUID が whitelist ユーザーとして設定されているかを返します。
     *
     * @param uuid 判定対象のプレイヤー UUID
     * @return `plugin.whitelistUsers` に完全一致する UUID が含まれていれば true
     */
    public boolean isWhitelistUser(UUID uuid) {
        return uuid != null && pluginWhitelistUsers.contains(uuid);
    }

    /**
     * 実行中の whitelist ユーザー UUID を取得します。
     * 返却する集合は変更できません。
     *
     * @return whitelist ユーザー UUID の不変集合
     */
    public Set<UUID> getPluginWhitelistUsers() {
        return pluginWhitelistUsers;
    }

    /**
     * 実行中の whitelist ユーザー UUID を置き換えます。
     * 設定ファイルへの保存は呼び出し側が担当します。
     *
     * @param users 置き換え後の whitelist ユーザー UUID 集合
     */
    public void setPluginWhitelistUsers(Set<UUID> users) {
        this.pluginWhitelistUsers = Collections.unmodifiableSet(new HashSet<>(users));
    }

    /**
     * whitelist が有効かどうかを返します。
     *
     * @return whitelist が有効なら {@code true}
     */
    public boolean isPluginWhitelistEnabled() {
        return pluginWhitelistEnabled;
    }

    /**
     * 実行中の whitelist 状態を更新します。
     * 設定ファイルへの保存は呼び出し側が担当します。
     *
     * @param enabled 更新後の whitelist 状態
     */
    public void setPluginWhitelistEnabled(boolean enabled) {
        this.pluginWhitelistEnabled = enabled;
    }

    /**
     * 通常プレイヤーが参加できる基本人数を返します。
     *
     * @return 通常プレイヤー用の基本人数
     */
    public int getPlayerCapacityMaxPlayers() {
        return playerCapacityMaxPlayers;
    }

    /**
     * 寄付者以上のプレイヤーへ追加する参加枠を返します。
     *
     * @return 寄付者追加枠。管理者も利用可能
     */
    public int getPlayerCapacityDonorExtraPlayers() {
        return playerCapacityDonorExtraPlayers;
    }

    /**
     * 管理者だけへ追加する参加枠を返します。
     *
     * @return 管理者追加枠
     */
    public int getPlayerCapacityAdminExtraPlayers() {
        return playerCapacityAdminExtraPlayers;
    }

    /** 同時に外部データを読み込める参加処理数を返します。 */
    public int getPlayerJoinMaxConcurrentLoads() {
        return playerJoinMaxConcurrentLoads;
    }

    /** スキルツリー初期読込の最大試行回数を返します。 */
    public int getPlayerJoinSkillTreeRetryMaxAttempts() {
        return playerJoinSkillTreeRetryMaxAttempts;
    }

    /** スキルツリー初期読込の再試行初回待機時間をミリ秒で返します。 */
    public long getPlayerJoinSkillTreeRetryInitialDelayMillis() {
        return playerJoinSkillTreeRetryInitialDelayMillis;
    }

    /** スキルツリー初期読込の再試行最大待機時間をミリ秒で返します。 */
    public long getPlayerJoinSkillTreeRetryMaxDelayMillis() {
        return playerJoinSkillTreeRetryMaxDelayMillis;
    }

    // SQL Server 関連のゲッター
    public boolean isSqlserverEnabled() {
        return sqlserverEnabled;
    }

    public String getSqlserverIpAddress() {
        return sqlserverIpAddress;
    }

    public int getSqlserverPort() {
        return sqlserverPort;
    }

    public String getSqlserverDatabaseName() {
        return sqlserverDatabaseName;
    }

    public boolean isSqlserverEncrypt() {
        return sqlserverEncrypt;
    }

    public boolean isSqlserverTrustServerCertificate() {
        return sqlserverTrustServerCertificate;
    }

    public String getSqlserverUser() {
        return sqlserverUser;
    }

    public String getSqlserverPassword() {
        return sqlserverPassword;
    }

    // フォルダ型データベース関連のゲッター
    public String getFileDatabaseRootPath() {
        return fileDatabaseRootPath;
    }

    // データベース接続プール関連のゲッター
    public int getDatabasePoolMaxPoolSize() {
        return databasePoolMaxPoolSize;
    }

    public int getDatabasePoolMinIdle() {
        return databasePoolMinIdle;
    }

    public long getDatabasePoolConnectionTimeout() {
        return databasePoolConnectionTimeout;
    }

    // ログ関連のゲッター
    public boolean isLoggingAuditEnabled() {
        return loggingAuditEnabled;
    }

    public boolean isLoggingEntryConsoleLogEntryEnabled() {
        return loggingEntryConsoleLogEntryEnabled;
    }

    public boolean isLoggingUseAnsiColors() {
        return loggingUseAnsiColors;
    }

    public boolean isLoggingCraftyControllerColors() {
        return loggingCraftyControllerColors;
    }

    /**
     * SQL Server接続文字列を構築して返します。
     */
    public String buildSqlServerConnectionString() {
        return String.format(
                "jdbc:sqlserver://%s:%d;databaseName=%s;encrypt=%s;trustServerCertificate=%s;user=%s;password=%s",
                sqlserverIpAddress,
                sqlserverPort,
                sqlserverDatabaseName,
                sqlserverEncrypt,
                sqlserverTrustServerCertificate,
                sqlserverUser,
                sqlserverPassword
        );
    }

    // AstralRecord API 関連のゲッター

    /**
     * AstralRecord API のベースURLを返します。
     */
    public String getApiBaseUrl() {
        return apiBaseUrl;
    }

    /**
     * AstralRecord API の認証APIキーを返します。
     */
    public String getApiAuthApiKey() {
        return apiAuthApiKey;
    }

    /**
     * AstralRecord API のタイムアウト（ミリ秒）を返します。
     */
    public int getApiTimeout() {
        return apiTimeout;
    }

    /**
     * API を伴う一連のゲーム操作の初回待機時間（ミリ秒）を返します。
     *
     * <p>HTTP リクエスト単体のタイムアウトとは別に、複数回の照会・正本同期を含む
     * プレイヤー向け待機時間を制限するために使用します。</p>
     */
    public long getApiOperationTimeout() {
        return apiOperationTimeout;
    }

    /**
     * AstralRecord API の SSL 証明書検証が有効かどうかを返します。
     * 自己署名証明書や内部CA証明書を使用している場合は false を返します。
     */
    public boolean isApiSslVerifyEnabled() {
        return apiSslVerifyEnabled;
    }

    /**
     * Web ログインチャレンジ発行元として API に渡すサーバー ID を返します。
     *
     * @return API 発行元サーバー ID
     */
    public String getApiServerId() {
        return apiServerId;
    }

    public boolean isResourcePackEnabled() {
        return resourcePackEnabled;
    }

    public String getResourcePackUrl() {
        return resourcePackUrl;
    }

    public String getResourcePackSha1() {
        return resourcePackSha1;
    }

    public boolean isResourcePackForce() {
        return resourcePackForce;
    }

    public String getResourcePackPrompt() {
        return resourcePackPrompt;
    }

    public boolean isResourcePackSkipBedrock() {
        return resourcePackSkipBedrock;
    }

    public List<String> getResourcePackBedrockNamePrefixes() {
        return resourcePackBedrockNamePrefixes;
    }

    public boolean isDiscordEnabled() {
        return discordEnabled;
    }

    public String getDiscordGlobalChannelId() {
        return discordGlobalChannelId;
    }

    public int getDiscordMaxMessageLength() {
        return discordMaxMessageLength;
    }

    /**
     * ローマ字チャット変換が有効かを返します。
     *
     * @return ローマ字変換が有効なら true
     */
    public boolean isChatRomajiConversionEnabled() {
        return chatRomajiConversionEnabled;
    }

    /**
     * Google CGI APIによるかな漢字変換が有効かを返します。
     *
     * @return かな漢字変換が有効なら true
     */
    public boolean isChatKanjiConversionEnabled() {
        return chatKanjiConversionEnabled;
    }

    /**
     * Google CGI APIの変換先URLを返します。
     *
     * @return 変換先URL
     */
    public String getChatGoogleImeEndpoint() {
        return chatGoogleImeEndpoint == null || chatGoogleImeEndpoint.isBlank()
            ? DEFAULT_CHAT_GOOGLE_IME_ENDPOINT : chatGoogleImeEndpoint;
    }

    /**
     * Google CGI APIへの接続タイムアウトをミリ秒で返します。
     *
     * @return 接続タイムアウト（ミリ秒）
     */
    public int getChatGoogleImeTimeoutMillis() {
        return chatGoogleImeTimeoutMillis;
    }

    /**
     * ローマ字変換を一時的に無効化する発言先頭文字列を返します。
     *
     * @return 変換を無効化する発言先頭文字列。未設定時は空文字
     */
    public String getChatRomajiBypassMarker() {
        return chatRomajiBypassMarker == null ? "" : chatRomajiBypassMarker;
    }

    private Set<UUID> parseConfiguredUsers(List<String> configuredUsers) {
        if (configuredUsers == null || configuredUsers.isEmpty()) {
            return Collections.emptySet();
        }

        Set<UUID> parsedUsers = new HashSet<>();
        for (String configuredUser : configuredUsers) {
            if (configuredUser == null || configuredUser.isBlank()) {
                continue;
            }
            try {
                parsedUsers.add(UUID.fromString(configuredUser.trim()));
            } catch (IllegalArgumentException ignored) {
                // Invalid entries are ignored so a typo cannot grant access.
            }
        }
        return Collections.unmodifiableSet(parsedUsers);
    }
}
