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
