package io.github.maaasu.astralRecord.infrastructure.util

import com.google.gson.JsonObject
import io.github.maaasu.astralRecord.infrastructure.config.ConfigProperties
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.time.Duration
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLParameters
import javax.net.ssl.TrustManager
import javax.net.ssl.X509ExtendedTrustManager

/**
 * AstralRecord API への HTTP リクエスト構築を共通化するユーティリティ。
 * 各リポジトリで重複する HttpClient・HttpRequest.Builder の生成ロジックを提供します。
 * SSL 証明書検証の有無も [ConfigProperties.isApiSslVerifyEnabled] に基づいてここで一元管理します。
 */
object ApiRequestUtil {

    /**
     * JSON リクエストボディを構築します。
     * 生の JSON 文字列連結を避け、キー名や値のタイプミスを防ぎやすくします。
     *
     * @param build JSON オブジェクトへのプロパティ追加処理
     * @return 構築された JSON 文字列
     */
    @JvmStatic
    fun buildJsonBody(build: JsonObject.() -> Unit): String {
        val body = JsonObject()
        body.build()
        return body.toString()
    }

    /**
     * 設定値に基づく接続タイムアウトおよび SSL 設定を持つ [HttpClient] を生成します。
     * `api.ssl.verifyEnabled` が `false` の場合は全証明書を信頼する [SSLContext] を使用します。
     */
    @JvmStatic
    fun buildClient(): HttpClient {
        val props = ConfigProperties.getInstance()
        val timeoutMs = props.apiTimeout.toLong()
        val builder = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(timeoutMs))
        if (!props.isApiSslVerifyEnabled) {
            builder.sslContext(createTrustAllSslContext())
            // endpointIdentificationAlgorithm を空にして HttpClient レベルのホスト名検証も無効化する（多重防衛）。
            builder.sslParameters(SSLParameters().apply { endpointIdentificationAlgorithm = "" })
        }
        return builder.build()
    }

    /**
     * 指定パスに対する [HttpRequest.Builder] を生成します。
     * ベース URL・タイムアウト・Content-Type・認証ヘッダーを自動で設定します。
     * 認証には `X-Api-Key` ヘッダーを使用します。
     *
     * @param path APIパス（例: `/users/xxx`）
     */
    @JvmStatic
    fun buildRequestBuilder(path: String): HttpRequest.Builder {
        val props = ConfigProperties.getInstance()
        val url = buildUrl(props.apiBaseUrl, path)
        val builder = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofMillis(props.apiTimeout.toLong()))
            .header("Content-Type", "application/json")
        val apiKey = props.apiAuthApiKey
        if (!apiKey.isNullOrBlank()) {
            builder.header("X-Api-Key", apiKey)
        }
        return builder
    }

    /**
     * baseUrl と path を安全に連結します。
     * baseUrl 末尾と path 先頭のスラッシュ重複を吸収し、
     * baseUrl が `/api` を含む場合は path 先頭の `/api` を重複除去します。
     */
    private fun buildUrl(baseUrl: String, path: String): String {
        val normalizedBase = baseUrl.trimEnd('/')
        val normalizedPath = if (path.startsWith('/')) path else "/$path"

        val adjustedPath = if (
            normalizedBase.endsWith("/api") &&
            (normalizedPath == "/api" || normalizedPath.startsWith("/api/"))
        ) {
            normalizedPath.removePrefix("/api").ifEmpty { "/" }
        } else {
            normalizedPath
        }

        return normalizedBase + adjustedPath
    }

    /**
     * 全ての SSL 証明書を信頼する [SSLContext] を生成します。
     * 自己署名証明書や内部CA証明書を使用している環境向けの設定です。
     * [ConfigProperties.isApiSslVerifyEnabled] が `false` の場合のみ [buildClient] から呼び出されます。
     *
     * [X509ExtendedTrustManager] を実装することで、JVM が [sun.security.ssl.AbstractTrustManagerWrapper] で
     * ラップするのを防ぎます。[javax.net.ssl.X509TrustManager] を実装した場合、JVM は必ずラッパーで包み、
     * ラッパー内部で IP アドレスの SAN 検証を追加実行します（`api.ssl.verifyEnabled: false` の意図に反します）。
     * [X509ExtendedTrustManager] であればラップされず、3引数の checkServerTrusted が直接呼ばれるため、
     * no-op 実装により証明書・ホスト名の両検証を完全にスキップできます。
     */
    private fun createTrustAllSslContext(): SSLContext {
        val trustAllCerts = arrayOf<TrustManager>(object : X509ExtendedTrustManager() {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            // 2引数版（フォールバック用）
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
            // 3引数版（Socket）— HttpClient では通常呼ばれないが念のため実装
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String, socket: Socket) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, socket: Socket) {}
            // 3引数版（SSLEngine）— HttpClient が直接呼び出すエントリポイント
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String, engine: SSLEngine) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, engine: SSLEngine) {}
        })
        return SSLContext.getInstance("TLS").apply {
            init(null, trustAllCerts, SecureRandom())
        }
    }
}
