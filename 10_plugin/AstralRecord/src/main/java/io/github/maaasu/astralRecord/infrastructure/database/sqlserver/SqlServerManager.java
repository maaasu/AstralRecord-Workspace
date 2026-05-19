package io.github.maaasu.astralRecord.infrastructure.database.sqlserver;

import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.LogMessageProvider;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import org.jetbrains.exposed.v1.jdbc.Database;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

public class SqlServerManager {
    private static SqlServerManager instance;
    private ConnectionPool pool;
    private Database database;
    private boolean initialized = false;

    private SqlServerManager() {}

    public static synchronized SqlServerManager getInstance() {
        if (instance == null) {
            instance = new SqlServerManager();
        }
        return instance;
    }

    public synchronized void initialize() {
        if (initialized) return;

        SqlServerConfig config = new SqlServerConfig();
        this.pool = new ConnectionPool(config);

        // 接続テスト
        Logger.log(LogId.I_1100);
        try (Connection ignored = pool.getDataSource().getConnection()) {
            Logger.log(LogId.I_1101);
        } catch (SQLException e) {
            Logger.log(LogId.E_1100, e, e.getMessage());
        }

        // Exposed に HikariCP の DataSource を登録
        // Kotlin ラッパー経由でデフォルト引数を利用して接続する
        this.database = ExposedDatabaseConnector.connect(pool.getDataSource());

        initialized = true;
        Logger.log(LogId.I_1102);
    }

    /**
     * Exposed の Database インスタンスを取得します。
     * Repository 層の transaction { } ブロック内で使用してください。
     *
     * @return Exposed Database インスタンス
     */
    public Database getDatabase() {
        if (!initialized || database == null) {
            Logger.log(LogId.E_1101);
            throw new IllegalStateException(LogMessageProvider.getMessage(LogId.E_1101.getId()));
        }
        return database;
    }

    /**
     * データソースを取得します。
     * 通常の DB アクセスには Exposed の transaction { } を使用してください。
     *
     * @return DataSource
     */
    public DataSource getDataSource() {
        if (!initialized || pool == null) {
            Logger.log(LogId.E_1101);
            throw new IllegalStateException(LogMessageProvider.getMessage(LogId.E_1101.getId()));
        }
        return pool.getDataSource();
    }

    public synchronized void shutdown() {
        if (pool != null) {
            pool.close();
            pool = null;
        }
        database = null;
        initialized = false;
        Logger.log(LogId.I_1103);
    }
}



