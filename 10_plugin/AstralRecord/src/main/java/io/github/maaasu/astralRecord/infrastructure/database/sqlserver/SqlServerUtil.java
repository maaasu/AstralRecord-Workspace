package io.github.maaasu.astralRecord.infrastructure.database.sqlserver;

import org.jetbrains.exposed.v1.core.Transaction;
import org.jetbrains.exposed.v1.jdbc.Database;
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction;
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager;

import java.sql.Connection;

/**
 * Exposed を使用した SQL Server アクセスユーティリティクラス。
 *
 * <p>Repository 層での DB 操作は、Kotlin の {@code transaction { }} ブロックを直接使用してください。
 * Java から呼び出す場合は {@link #transaction(Database, TransactionBlock)} を使用してください。</p>
 *
 * <p><b>Kotlin での使用例:</b></p>
 * <pre>{@code
 * val db = SqlServerManager.getInstance().database
 * transaction(db) {
 *     AccountTable.selectAll()
 *         .where { AccountTable.userId eq targetUuid }
 *         .map { it[AccountTable.accountName] }
 * }
 * }</pre>
 */
public final class SqlServerUtil {
    private SqlServerUtil() {}

    /**
     * Exposed のトランザクションブロックを実行します（Java 向けラッパー）。
     *
     * <p>このメソッドは PaperAPI の非同期スケジューラ上で呼び出してください。
     * メインスレッド（同期スレッド）での実行は禁止です。</p>
     *
     * <p><b>Kotlin からの利用は非推奨です。</b>
     * Kotlin では {@code transaction(db) { ... }} を直接使用してください。</p>
     *
     * @param db    使用する Exposed Database インスタンス（{@link SqlServerManager#getDatabase()} から取得）
     * @param block トランザクション内で実行する処理
     * @param <T>   戻り値の型
     * @return トランザクションブロックの戻り値
     */
    public static <T> T transaction(Database db, TransactionBlock<T> block) {
        TransactionManager manager = (TransactionManager) TransactionManager.Companion.managerFor(db);
        JdbcTransaction tx = manager.newTransaction(Connection.TRANSACTION_READ_COMMITTED, false, null);
        try {
            T result = block.execute(tx);
            tx.commit();
            return result;
        } catch (Exception e) {
            tx.rollback();
            throw new RuntimeException(e);
        } finally {
            tx.close();
        }
    }

    /**
     * リソースを安全にクローズします。
     *
     * @deprecated Exposed の {@code transaction { }} を使用する場合、
     *             接続のクローズは Exposed が自動管理するため不要です。
     */
    @Deprecated
    public static void closeQuietly(AutoCloseable... resources) {
        for (AutoCloseable r : resources) {
            if (r == null) continue;
            try {
                r.close();
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * Exposed トランザクションブロック用の関数型インターフェース。
     *
     * @param <T> 戻り値の型
     */
    @FunctionalInterface
    public interface TransactionBlock<T> {
        /**
         * トランザクション内で実行する処理を定義します。
         *
         * @param tx Exposed の Transaction インスタンス
         * @return 処理結果
         * @throws Exception 処理中に例外が発生した場合
         */
        T execute(Transaction tx) throws Exception;
    }
}
