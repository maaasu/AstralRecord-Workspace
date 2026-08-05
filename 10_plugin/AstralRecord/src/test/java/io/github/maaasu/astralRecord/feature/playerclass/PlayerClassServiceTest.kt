package io.github.maaasu.astralRecord.feature.playerclass

import io.github.maaasu.astralRecord.feature.`class`.model.ClassModel
import io.github.maaasu.astralRecord.feature.`class`.model.ClassUnlockClassLevel
import io.github.maaasu.astralRecord.feature.account.model.AccountMode
import io.github.maaasu.astralRecord.support.DesignTestFixtures
import io.github.maaasu.astralRecord.support.MockBukkitTestBase
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows

class PlayerClassServiceTest : MockBukkitTestBase() {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/03-player/3-メソッド仕様/03_3-サービス.md
     * 章・見出し: # 03_3-サービス > ## 5. PlayerClassService
     * 検証契約: player進行を持つclassだけを表示対象にする。
     */
    @Test
    fun displaysOnlyClassesWithPlayerProgress() {
        val player = server().addPlayer()
        val astPlayer = DesignTestFixtures.astPlayer(player, AccountMode.PLAYER)
        astPlayer.setClassProgress("mage", 4, 900L)

        val service = PlayerClassService()
        service.replaceSnapshot(
            mapOf(
                "adventurer" to classModel("adventurer", "Adventurer"),
                "mage" to classModel("mage", "Mage"),
                "warrior" to classModel("warrior", "Warrior"),
            )
        )

        assertEquals(
            listOf("adventurer", "mage"),
            service.getClassProgressViewEntries(astPlayer).map { it.id },
        )
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/03-player/3-メソッド仕様/03_3-モデル操作.md
     * 章・見出し: # 03_3-モデル操作 > ## 1. model メソッド仕様 > ### 全クラス進行取得
     * 検証契約: プレイヤー情報の全クラスレベルは、class masterのorder昇順で表示する。
     */
    @Test
    fun displaysClassProgressInConfiguredOrder() {
        val player = server().addPlayer()
        val astPlayer = DesignTestFixtures.astPlayer(player, AccountMode.PLAYER)
        astPlayer.setClassProgress("mage", 4, 900L)
        astPlayer.setClassProgress("adventurer", 3, 300L)

        val service = PlayerClassService()
        service.replaceSnapshot(
            mapOf(
                "mage" to classModel("mage", "Mage", order = 1.3),
                "adventurer" to classModel("adventurer", "Adventurer", order = 0.0),
            )
        )

        assertEquals(
            listOf("adventurer", "mage"),
            service.getClassProgressViewEntries(astPlayer).map { it.id },
        )
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/03-player/3-メソッド仕様/03_3-サービス.md
     * 章・見出し: # 03_3-サービス > ## 5. PlayerClassService
     * 検証契約: 現在classから複数path上の全ancestorを重複なく解決する。
     */
    @Test
    fun resolvesCurrentClassAndEveryAncestorAcrossMultiplePaths() {
        val service = PlayerClassService()
        service.replaceSnapshot(
            mapOf(
                "adventurer" to classModel("adventurer", "Adventurer"),
                "hunter" to classModel("hunter", "Hunter", listOf("adventurer")),
                "scout" to classModel("scout", "Scout", listOf("adventurer")),
                "ranger" to classModel("ranger", "Ranger", listOf("hunter", "scout")),
            )
        )

        assertTrue(service.isClassOrAncestor("ranger", "ranger"))
        assertTrue(service.isClassOrAncestor("ranger", "hunter"))
        assertTrue(service.isClassOrAncestor("ranger", "scout"))
        assertTrue(service.isClassOrAncestor("ranger", "adventurer"))
        assertFalse(service.isClassOrAncestor("hunter", "ranger"))
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/03-player/3-メソッド仕様/03_3-サービス.md
     * 章・見出し: # 03_3-サービス > ## 5. PlayerClassService
     * 検証契約: 循環class定義でもancestor探索を有限回で停止する。
     */
    @Test
    fun ancestorResolutionStopsOnCyclicDefinitions() {
        val service = PlayerClassService()
        service.replaceSnapshot(
            mapOf(
                "cycle_a" to classModel("cycle_a", "A", listOf("cycle_b")),
                "cycle_b" to classModel("cycle_b", "B", listOf("cycle_a")),
            )
        )

        assertTrue(service.isClassOrAncestor("cycle_a", "cycle_b"))
        assertFalse(service.isClassOrAncestor("cycle_a", "adventurer"))
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/03-player/3-メソッド仕様/03_3-サービス.md
     * 章・見出し: # 03_3-サービス > ## 5. PlayerClassService
     * 検証契約: class masterで設定した3文字短縮名を返す。
     */
    @Test
    fun returnsConfiguredThreeCharacterShortName() {
        val service = PlayerClassService()
        service.replaceSnapshot(mapOf("mage" to classModel("mage", "&bメイジ", shortName = "&b魔術師")))

        assertEquals("§b魔術師", service.getShortDisplayName("mage"))
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/10-hud/3-メソッド仕様/10_3-View.md
     * 章・見出し: # 10_3-View > ## 5. tab list 描画
     * 検証契約: tab list名の左へ正式class名tagを反映する。
     */
    @Test
    fun updatesTabListNameWithFullClassName() {
        val player = server().addPlayer()
        val astPlayer = DesignTestFixtures.astPlayer(player, AccountMode.PLAYER)
        astPlayer.selectClass("mage")
        val service = PlayerClassService()
        service.replaceSnapshot(mapOf("mage" to classModel("mage", "&bメイジ", shortName = "&b魔術師")))

        service.updatePlayerListName(astPlayer)

        assertEquals(
            "[メイジ] ${player.name}",
            PlainTextComponentSerializer.plainText().serialize(player.playerListName()),
        )
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/03-player/3-メソッド仕様/03_3-サービス.md
     * 章・見出し: # 03_3-サービス > ## 5. PlayerClassService
     * 検証契約: 可視class間で3文字短縮名が重複するmasterを拒否する。
     */
    @Test
    fun rejectsDuplicateVisibleShortNames() {
        val service = PlayerClassService()

        assertThrows(IllegalArgumentException::class.java) {
            service.replaceSnapshot(
                mapOf(
                    "mage" to classModel("mage", "Mage", shortName = "&b魔術師"),
                    "wizard" to classModel("wizard", "Wizard", shortName = "&d魔術師"),
                ),
            )
        }
    }

    private fun classModel(
        id: String,
        name: String,
        parentIds: List<String> = emptyList(),
        shortName: String = id.takeLast(3).padStart(3, '_'),
        order: Double = 0.0,
    ) = ClassModel(
        schemaVersion = 1,
        id = id,
        type = "CLASS",
        name = name,
        order = order,
        shortName = shortName,
        description = null,
        icon = "EXPERIENCE_BOTTLE",
        role = "DEALER",
        maxLevel = 10,
        commandOnly = false,
        unlockLevel = 1,
        unlockClassLevel = parentIds.map { ClassUnlockClassLevel(it, 1) },
        baseStats = emptyList(),
        growthPerLevel = emptyList(),
        expRate = 100,
        usableSkills = emptyList(),
        tags = emptyList(),
    )
}
