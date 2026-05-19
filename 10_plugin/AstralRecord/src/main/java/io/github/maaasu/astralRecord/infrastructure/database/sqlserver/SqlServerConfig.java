package io.github.maaasu.astralRecord.infrastructure.database.sqlserver;

import io.github.maaasu.astralRecord.infrastructure.config.ConfigProperties;

public class SqlServerConfig {
    private final String host;
    private final int port;
    private final String database;
    private final String username;
    private final String password;
    private final boolean encrypt;
    private final boolean trustServerCertificate;
    private final int maxPoolSize;
    private final int minPoolSize;
    private final long connectionTimeoutMillis;

    public SqlServerConfig() {
        ConfigProperties configProperties = ConfigProperties.getInstance();
        this.host = configProperties.getSqlserverIpAddress();
        this.port = configProperties.getSqlserverPort();
        this.database = configProperties.getSqlserverDatabaseName();
        this.username = configProperties.getSqlserverUser();
        this.password = configProperties.getSqlserverPassword();
        this.encrypt = configProperties.isSqlserverEncrypt();
        this.trustServerCertificate = configProperties.isSqlserverTrustServerCertificate();
        this.maxPoolSize = configProperties.getDatabasePoolMaxPoolSize();
        this.minPoolSize = configProperties.getDatabasePoolMinIdle();
        this.connectionTimeoutMillis = configProperties.getDatabasePoolConnectionTimeout();
    }

    public String getJdbcUrl() {
        // SQL Server JDBC URL
        // encrypt と trustServerCertificate の値は config.yml から取得
        return String.format(
                "jdbc:sqlserver://%s:%d;databaseName=%s;encrypt=%s;trustServerCertificate=%s",
                host, port, database, encrypt, trustServerCertificate
        );
    }

    public String getHost() { return host; }
    public int getPort() { return port; }
    public String getDatabase() { return database; }
    public String getUsername() { return username; }
    public String getPassword() { return password; }
    public boolean isEncrypt() { return encrypt; }
    public boolean isTrustServerCertificate() { return trustServerCertificate; }
    public int getMaxPoolSize() { return maxPoolSize; }
    public int getMinPoolSize() { return minPoolSize; }
    public long getConnectionTimeoutMillis() { return connectionTimeoutMillis; }
}



