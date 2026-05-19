package io.github.maaasu.astralRecord.infrastructure.logging;

import org.reflections.Reflections;
import org.reflections.scanners.Scanners;
import org.reflections.util.ConfigurationBuilder;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * クラスパス上の {@link LogEntry} 実装クラスをスキャンし、
 * 各クラスに対応する {@link AuditLogger} を自動生成・管理するレジストリです。
 * <p>
 * {@link LogEntryMeta} アノテーションが付与されたクラスはアノテーションの値を使用します。
 * アノテーションがなくデフォルトコンストラクタを持つクラスはインスタンスから設定を取得します。
 * どちらも持たないクラスはスキップされます。
 * </p>
 *
 * <h3>使用例</h3>
 * <pre>{@code
 * // プラグイン起動時
 * AuditLoggerRegistry.init("io.github.maaasu.astralRecord");
 *
 * // エントリ型に対応するロガーを取得
 * AuditLogger<ConsoleLogEntry> logger = AuditLoggerRegistry.getLogger(ConsoleLogEntry.class);
 * if (logger != null) {
 *     logger.offer(new ConsoleLogEntry("2024-01-01 00:00:00", "[INFO]", "Hello"));
 * }
 *
 * // プラグイン終了時
 * AuditLoggerRegistry.shutdownAll();
 * }</pre>
 */
public final class AuditLoggerRegistry {

    /** エントリクラス → AuditLogger のマップ */
    @SuppressWarnings("rawtypes")
    private static final Map<Class<? extends LogEntry>, AuditLogger> registry = new ConcurrentHashMap<>();

    private static volatile boolean initialized = false;

    private AuditLoggerRegistry() {}

    /**
     * 指定パッケージ配下の {@link LogEntry} 実装クラスをスキャンし、
     * 各クラスに対応する {@link AuditLogger} を生成・初期化します。
     * <p>
     * 既に初期化済みの場合は何もしません。
     * </p>
     *
     * @param basePackage スキャン対象のベースパッケージ（例: {@code "io.github.maaasu.astralRecord"}）
     */
    public static synchronized void init(String basePackage) {
        if (initialized) return;

        Reflections reflections = new Reflections(
                new ConfigurationBuilder()
                        .forPackage(basePackage)
                        .addScanners(Scanners.SubTypes)
        );

        Set<Class<? extends LogEntry>> entryClasses = reflections.getSubTypesOf(LogEntry.class);

        for (Class<? extends LogEntry> clazz : entryClasses) {
            // 内部クラス・抽象クラス・インターフェースはスキップ
            if (clazz.isInterface() || clazz.isAnonymousClass()) continue;
            if (java.lang.reflect.Modifier.isAbstract(clazz.getModifiers())) continue;

            AuditLogger<? extends LogEntry> logger = createLogger(clazz);
            if (logger != null) {
                registry.put(clazz, logger);
                logger.init();
                Logger.log(LogId.D_2100, clazz.getSimpleName());
            } else {
                Logger.log(LogId.W_2100, clazz.getName());
            }
        }

        initialized = true;
    }

    /**
     * 登録されているすべての {@link AuditLogger} をシャットダウンします。
     * プラグイン終了時に呼び出してください。
     */
    public static synchronized void shutdownAll() {
        for (AuditLogger<?> logger : registry.values()) {
            logger.shutdown();
        }
        registry.clear();
        initialized = false;
    }

    /**
     * 指定したエントリクラスに対応する {@link AuditLogger} を返します。
     *
     * @param <E>        ログエントリの型
     * @param entryClass エントリクラス
     * @return 対応する {@link AuditLogger}、登録されていない場合は {@code null}
     */
    @SuppressWarnings("unchecked")
    public static <E extends LogEntry> AuditLogger<E> getLogger(Class<E> entryClass) {
        return (AuditLogger<E>) registry.get(entryClass);
    }

    /**
     * 登録されているすべてのエントリクラスと {@link AuditLogger} のマップを返します（読み取り専用）。
     *
     * @return エントリクラス → {@link AuditLogger} の不変マップ
     */
    @SuppressWarnings("rawtypes")
    public static Map<Class<? extends LogEntry>, AuditLogger> getAll() {
        return Collections.unmodifiableMap(registry);
    }

    // -------------------------------------------------------------------------
    // 内部ユーティリティ
    // -------------------------------------------------------------------------

    /**
     * クラスから {@link AuditLogger} を生成します。
     * <ol>
     *   <li>{@link LogEntryMeta} アノテーションがあればその値を使用</li>
     *   <li>デフォルトコンストラクタがあればインスタンスを生成して設定を取得</li>
     *   <li>どちらもなければ {@code null} を返す</li>
     * </ol>
     */
    private static <E extends LogEntry> AuditLogger<E> createLogger(Class<E> clazz) {
        // 1. @LogEntryMeta アノテーションを優先
        LogEntryMeta meta = clazz.getAnnotation(LogEntryMeta.class);
        if (meta != null) {
            return new AuditLogger<>(meta.logDir(), meta.csvHeader(), meta.fileNamePrefix());
        }

        // 2. デフォルトコンストラクタがあればインスタンスから設定を取得
        try {
            java.lang.reflect.Constructor<E> ctor = clazz.getDeclaredConstructor();
            ctor.setAccessible(true);
            E sample = ctor.newInstance();
            return new AuditLogger<>(sample);
        } catch (NoSuchMethodException e) {
            // デフォルトコンストラクタなし → スキップ
        } catch (Exception e) {
            Logger.log(LogId.W_2101, clazz.getName(), e.getMessage());
        }

        return null;
    }
}
