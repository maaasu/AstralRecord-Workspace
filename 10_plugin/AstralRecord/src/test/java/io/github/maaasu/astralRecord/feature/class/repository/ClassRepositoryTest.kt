package io.github.maaasu.astralRecord.feature.`class`.repository

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.github.maaasu.astralRecord.feature.`class`.model.ClassModel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertIs
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import java.lang.reflect.InvocationTargetException
import org.junit.jupiter.api.Test

class ClassRepositoryTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/09-menu/09_0-概要.md
     * 章・見出し: # 09_0-概要 > ## 2. 責務
     * 検証契約: class master の classGui は未指定または null ならクラスGUIへ表示しない設定として読み込み、object以外の型は拒否する。
     */
    @Test
    fun acceptsOptionalClassGuiFromDetailResponse() {
        val repository = ClassRepository()
        val nullPayload = JsonParser.parseString(
            """{"schemaVersion":1,"id":"administrator","type":"CLASS","name":"管理者","order":999.9,"shortName":"ADM","role":"SUPPORT","baseStats":[],"classGui":null}"""
        ).asJsonObject
        val omittedPayload = JsonParser.parseString(
            """{"schemaVersion":1,"id":"administrator","type":"CLASS","name":"管理者","order":999.9,"shortName":"ADM","role":"SUPPORT","baseStats":[]}"""
        ).asJsonObject
        val configuredPayload = JsonParser.parseString(
            """{"schemaVersion":1,"id":"paladin","type":"CLASS","name":"パラディン","order":2.1,"shortName":"PAL","role":"TANK","baseStats":[],"classGui":{"slot":28}}"""
        ).asJsonObject
        val invalidPayload = JsonParser.parseString(
            """{"schemaVersion":1,"id":"paladin","type":"CLASS","name":"パラディン","order":2.1,"shortName":"PAL","role":"TANK","baseStats":[],"classGui":"invalid"}"""
        ).asJsonObject

        val nullModel = parseClass(repository, nullPayload)
        val omittedModel = parseClass(repository, omittedPayload)
        val configuredModel = parseClass(repository, configuredPayload)

        assertNull(nullModel.classGui)
        assertNull(omittedModel.classGui)
        assertEquals(28, configuredModel.classGui?.slot)
        val exception = assertThrows(InvocationTargetException::class.java) {
            parseClass(repository, invalidPayload)
        }
        assertIs<IllegalStateException>(exception.cause)
    }

    private fun parseClass(repository: ClassRepository, payload: JsonObject): ClassModel {
        val method = ClassRepository::class.java.getDeclaredMethod("parseClass", JsonObject::class.java)
        method.isAccessible = true
        return method.invoke(repository, payload) as ClassModel
    }
}
