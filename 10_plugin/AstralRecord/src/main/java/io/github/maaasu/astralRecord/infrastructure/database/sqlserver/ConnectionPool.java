package io.github.maaasu.astralRecord.infrastructure.database.sqlserver;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.jetbrains.annotations.NotNull;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

public class ConnectionPool {
    private final HikariDataSource dataSource;

    public ConnectionPool(SqlServerConfig config) {
        HikariConfig hc = getHikariConfig(config);

        // SQL Server 固有のパフォーマンス最適化設定
        // sendStringParametersAsUnicode: 文字列パラメータを非Unicodeとして送信（パフォーマンス向上）
        hc.addDataSourceProperty("sendStringParametersAsUnicode", "false");

        // lastUpdateCount: 複数の更新カウントを返さない（パフォーマンス向上）
        hc.addDataSourceProperty("lastUpdateCount", "false");

        // selectMethod: カーソルタイプを direct に設定（大量データ取得時のメモリ効率向上）
        hc.addDataSourceProperty("selectMethod", "direct");

        // responseBuffering: レスポンスバッファリングを adaptive に設定
        hc.addDataSourceProperty("responseBuffering", "adaptive");

        this.dataSource = new HikariDataSource(hc);
    }

    private @NotNull HikariConfig getHikariConfig(SqlServerConfig config) {
        HikariConfig hc = new HikariConfig();
        hc.setJdbcUrl(config.getJdbcUrl());
        hc.setUsername(config.getUsername());
        hc.setPassword(config.getPassword());
        hc.setDriverClassName("com.microsoft.sqlserver.jdbc.SQLServerDriver");

        hc.setMaximumPoolSize(config.getMaxPoolSize());
        hc.setMinimumIdle(config.getMinPoolSize());
        hc.setConnectionTimeout(config.getConnectionTimeoutMillis());
        hc.setIdleTimeout(600_000);      // 10 minutes
        hc.setMaxLifetime(1_800_000);    // 30 minutes
        return hc;
    }

    public DataSource getDataSource() {
        return dataSource;
    }

    public void close() {
        if (!dataSource.isClosed()) {
            dataSource.close();
        }
    }

    public int getActiveConnections() {
        return dataSource.getHikariPoolMXBean() != null
                ? dataSource.getHikariPoolMXBean().getActiveConnections()
                : -1;
    }

    public int getIdleConnections() {
        return dataSource.getHikariPoolMXBean() != null
                ? dataSource.getHikariPoolMXBean().getIdleConnections()
                : -1;
    }

    public int getTotalConnections() {
        return dataSource.getHikariPoolMXBean() != null
                ? dataSource.getHikariPoolMXBean().getTotalConnections()
                : -1;
    }
}



