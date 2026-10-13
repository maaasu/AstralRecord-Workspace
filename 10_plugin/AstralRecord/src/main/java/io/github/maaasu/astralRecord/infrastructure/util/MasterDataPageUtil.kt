package io.github.maaasu.astralRecord.infrastructure.util

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.IOException
import java.net.http.HttpResponse

/** 起動/再読込用の詳細マスタを100件ずつ同一世代から取得する。 */
object MasterDataPageUtil {
    /**
     * @param path 詳細マスタページのAPIパス
     * @return 同一世代の全詳細。途中世代が変わった場合は先頭から再取得します
     */
    @JvmStatic
    fun fetchAll(path: String): List<JsonObject> {
        try {
            repeat(3) {
                val result = mutableListOf<JsonObject>()
                var generation: String? = null
                var offset = 0
                while (true) {
                    val pagePath = "$path?offset=$offset&limit=100" +
                        (generation?.let { "&generation=$it" } ?: "")
                    val request = ApiRequestUtil.buildRequestBuilder(pagePath).GET().build()
                    val response = ApiRequestUtil.sharedClient().send(request, HttpResponse.BodyHandlers.ofString())
                    if (response.statusCode() == 404 || response.statusCode() == 405) {
                        throw MasterDataPageUnavailableException(path)
                    }
                    if (response.statusCode() == 409) break
                    if (response.statusCode() != 200) {
                        throw IOException("Unexpected status ${response.statusCode()} for GET $pagePath")
                    }
                    val page = JsonParser.parseString(response.body()).asJsonObject
                    val currentGeneration = page.get("generation").asString
                    if (generation != null && generation != currentGeneration) break
                    generation = currentGeneration
                    val items = page.getAsJsonArray("items")
                    if (items.size() > 100) throw IOException("Master page exceeds limit: $pagePath")
                    result.addAll(items.map { it.asJsonObject })
                    offset += items.size()
                    val total = page.get("total").asInt
                    if (offset == total) return result
                    if (items.isEmpty || offset > total) throw IOException("Invalid master page: $pagePath")
                }
            }
            throw IOException("Master generation changed during preparation: $path")
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("Interrupted while GET $path", e)
        }
    }
}

/** 旧APIだけに一覧・単件の互換経路を許可する。 */
class MasterDataPageUnavailableException(path: String) : IOException("Master detail pages unavailable: $path")
