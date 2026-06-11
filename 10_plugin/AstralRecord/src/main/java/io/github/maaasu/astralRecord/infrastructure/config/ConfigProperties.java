package io.github.maaasu.astralRecord.infrastructure.config;

import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;

import java.util.List;

public class ConfigProperties {

    private static ConfigProperties instance;

    // Plugin 関連
    private boolean pluginDebugMode;

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
                "AstralRecord resource pack"
        );
        this.resourcePackSkipBedrock = configManager.getConfig().getBoolean(ConfigKeys.RESOURCE_PACK_SKIP_BEDROCK, true);
        this.resourcePackBedrockNamePrefixes = configManager.getConfig().getStringList(
                ConfigKeys.RESOURCE_PACK_BEDROCK_NAME_PREFIXES
        );
        if (this.resourcePackBedrockNamePrefixes.isEmpty()) {
            this.resourcePackBedrockNamePrefixes = List.of(".", "*");
        }
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
}
