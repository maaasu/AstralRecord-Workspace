package io.github.maaasu.astralRecord.feature.playerclass

import io.github.maaasu.astralRecord.feature.`class`.model.ClassModel
import io.github.maaasu.astralRecord.feature.`class`.model.ClassStat
import io.github.maaasu.astralRecord.feature.`class`.model.ClassUnlockClassLevel
import io.github.maaasu.astralRecord.feature.account.model.AccountMode
import io.github.maaasu.astralRecord.feature.status.model.StatusType
import io.github.maaasu.astralRecord.feature.status.service.StatusService
import io.github.maaasu.astralRecord.support.DesignTestFixtures
import io.github.maaasu.astralRecord.support.MockBukkitTestBase
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows
import java.util.Locale

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
                "cycle_a" to classModel("cycle_a", "A", listOf("cycle_b"), shortName = "CYA"),
                "cycle_b" to classModel("cycle_b", "B", listOf("cycle_a"), shortName = "CYB"),
            )
        )

        assertTrue(service.isClassOrAncestor("cycle_a", "cycle_b"))
        assertFalse(service.isClassOrAncestor("cycle_a", "adventurer"))
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/03-player/3-メソッド仕様/03_3-サービス.md
     * 章・見出し: # 03_3-サービス > ## 5. PlayerClassService
     * 検証契約: class masterで設定した英大文字3文字短縮名を返す。
     */
    @Test
    fun returnsConfiguredThreeCharacterShortName() {
        val service = PlayerClassService()
        service.replaceSnapshot(mapOf("mage" to classModel("mage", "&dメイジ", shortName = "&dMAG")))

        assertEquals("§dMAG", service.getShortDisplayName("mage"))
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/07-status/3-メソッド仕様/07_3-サービス.md
     * 章・見出し: # 07_3-サービス > ## 1. StatusService メソッド仕様 > ### 補正値取得
     * 検証契約: 現在クラスの baseStats と growthPerLevel を、クラスレベルに応じて全 StatusType の補正値として計算する。
     */
    @Test
    fun calculatesStatusBonusFromBaseAndCurrentClassLevelGrowth() {
        val player = server().addPlayer()
        val astPlayer = DesignTestFixtures.astPlayer(player, AccountMode.PLAYER)
        astPlayer.selectClass("growth")
        astPlayer.classLevel = 4

        val service = PlayerClassService()
        service.replaceSnapshot(
            mapOf(
                "growth" to classModel(
                    id = "growth",
                    name = "Growth",
                    baseStats = listOf(
                        ClassStat(StatusType.ATTACK.name, 10.0),
                        ClassStat(StatusType.MOVEMENT_SPEED_CAP.name, 150.0),
                        ClassStat(StatusType.NORMAL_ATTACK_DEGRADATION_DELAY.name, 3.0),
                    ),
                    growthPerLevel = listOf(
                        ClassStat(StatusType.ATTACK.name, 2.0),
                        ClassStat(StatusType.NORMAL_ATTACK_DEGRADATION_DELAY.name, 0.2),
                    ),
                ),
            ),
        )

        assertEquals(16.0, service.getStatusBonus(astPlayer, StatusType.ATTACK), 0.0001)
        assertEquals(150.0, service.getStatusBonus(astPlayer, StatusType.MOVEMENT_SPEED_CAP), 0.0001)
        assertEquals(3.6, service.getStatusBonus(astPlayer, StatusType.NORMAL_ATTACK_DEGRADATION_DELAY), 0.0001)
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/07-status/3-メソッド仕様/07_3-サービス.md
     * 章・見出し: # 07_3-サービス > ## 1. StatusService メソッド仕様 > ### ステータス再計算
     * 検証契約: クラスレベル上昇後の status refresh が、クラス補正を非 Shield ステータスの最終値へ反映する。
     */
    @Test
    fun refreshStatusReflectsLoadedClassGrowthAfterClassLevelIncrease() {
        val player = server().addPlayer()
        val astPlayer = DesignTestFixtures.astPlayer(player, AccountMode.PLAYER)
        astPlayer.selectClass("growth")

        val playerClassService = PlayerClassService()
        playerClassService.replaceSnapshot(
            mapOf(
                "growth" to classModel(
                    id = "growth",
                    name = "Growth",
                    baseStats = listOf(
                        ClassStat(StatusType.ATTACK.name, 10.0),
                        ClassStat(StatusType.MAX_HEALTH.name, 30.0),
                    ),
                    growthPerLevel = listOf(
                        ClassStat(StatusType.ATTACK.name, 2.0),
                        ClassStat(StatusType.MAX_HEALTH.name, 5.0),
                    ),
                ),
            ),
        )
        val statusService = StatusService()

        statusService.setPlayerClassService(playerClassService)
        val levelOne = statusService.refreshStatus(astPlayer)
        astPlayer.classLevel = 4
        val levelFour = statusService.refreshStatus(astPlayer)

        assertEquals(18.0, levelOne.getMaxValue(StatusType.ATTACK), 0.0001)
        assertEquals(24.0, levelFour.getMaxValue(StatusType.ATTACK), 0.0001)
        assertEquals(75.0, levelFour.getMaxValue(StatusType.MAX_HEALTH), 0.0001)
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/10-hud/3-メソッド仕様/10_3-View.md
     * 章・見出し: # 10_3-View > ## 5. tab list 描画
     * 検証契約: tab list名へクラス短縮名・クラスレベル・アカウント名とスロット番号を反映する。
     */
    @Test
    fun updatesTabListNameWithShortClassNameAndAccountDisplayName() {
        val player = server().addPlayer()
        val astPlayer = DesignTestFixtures.astPlayer(player, AccountMode.PLAYER)
        astPlayer.selectClass("mage")
        val service = PlayerClassService()
        service.replaceSnapshot(mapOf("mage" to classModel("mage", "&dメイジ", shortName = "&dMAG")))

        service.updatePlayerListName(astPlayer)

        assertEquals(
            "[MAG Lv.1] test-account#0",
            PlainTextComponentSerializer.plainText().serialize(player.playerListName()),
        )
        val shortNameComponent = player.playerListName().children()
            .firstOrNull { child ->
                PlainTextComponentSerializer.plainText().serialize(child) == "MAG"
            }
        assertNotNull(shortNameComponent)
        assertEquals(NamedTextColor.LIGHT_PURPLE, shortNameComponent!!.color())

        service.grantClassExperience(astPlayer, 53)

        assertEquals(
            "[MAG Lv.2] test-account#0",
            PlainTextComponentSerializer.plainText().serialize(player.playerListName()),
        )

        service.setClassLevel(astPlayer, "mage", 4)

        assertEquals(
            "[MAG Lv.4] test-account#0",
            PlainTextComponentSerializer.plainText().serialize(player.playerListName()),
        )
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/10-hud/3-メソッド仕様/10_3-View.md
     * 章・見出し: # 10_3-View > ## 5. tab list 描画
     * 検証契約: クラスマスター未解決時に内部クラスIDをTabへ表示しない。
     */
    @Test
    fun usesGenericClassNameWhenClassMasterIsUnavailable() {
        val player = server().addPlayer()
        val astPlayer = DesignTestFixtures.astPlayer(player, AccountMode.PLAYER)
        astPlayer.selectClass("missing_class")

        val service = PlayerClassService()
        service.updatePlayerListName(astPlayer)

        assertEquals(
            "[未登録のクラス Lv.1] test-account#0",
            PlainTextComponentSerializer.plainText().serialize(player.playerListName()),
        )
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/10-hud/3-メソッド仕様/10_3-View.md
     * 章・見出し: # 10_3-View > ## 5. tab list 描画
     * 検証契約: 現在クラス以外の進行度変更ではTabの現在クラス表示を変えない。
     */
    @Test
    fun keepsCurrentClassTabLabelWhenAnotherClassLevelChanges() {
        val player = server().addPlayer()
        val astPlayer = DesignTestFixtures.astPlayer(player, AccountMode.PLAYER)
        astPlayer.selectClass("mage")

        val service = PlayerClassService()
        service.replaceSnapshot(
            mapOf(
                "mage" to classModel("mage", "&dメイジ", shortName = "&dMAG"),
                "warrior" to classModel("warrior", "&cウォリアー"),
            ),
        )
        service.updatePlayerListName(astPlayer)

        service.setClassLevel(astPlayer, "warrior", 4)

        assertEquals(
            "[MAG Lv.1] test-account#0",
            PlainTextComponentSerializer.plainText().serialize(player.playerListName()),
        )
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/03-player/3-メソッド仕様/03_3-サービス.md
     * 章・見出し: # 03_3-サービス > ## 5. PlayerClassService
     * 検証契約: 可視class間で英大文字3文字短縮名が重複するmasterを拒否する。
     */
    @Test
    fun rejectsDuplicateVisibleShortNames() {
        val service = PlayerClassService()

        assertThrows(IllegalArgumentException::class.java) {
            service.replaceSnapshot(
                mapOf(
                    "mage" to classModel("mage", "Mage", shortName = "&bMAG"),
                    "wizard" to classModel("wizard", "Wizard", shortName = "&dMAG"),
                ),
            )
        }
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/03-player/3-メソッド仕様/03_3-サービス.md
     * 章・見出し: # 03_3-サービス > ## 5. PlayerClassService
     * 検証契約: shortNameへ英大文字3文字以外を指定したclass masterを拒否する。
     */
    @Test
    fun rejectsNonUppercaseEnglishShortNames() {
        val service = PlayerClassService()

        assertThrows(IllegalArgumentException::class.java) {
            service.replaceSnapshot(mapOf("mage" to classModel("mage", "Mage", shortName = "&bMag")))
        }
    }

    private fun classModel(
        id: String,
        name: String,
        parentIds: List<String> = emptyList(),
        shortName: String = id.takeLast(3).uppercase(Locale.ROOT).padStart(3, 'X'),
        order: Double = 0.0,
        baseStats: List<ClassStat> = emptyList(),
        growthPerLevel: List<ClassStat> = emptyList(),
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
        baseStats = baseStats,
        growthPerLevel = growthPerLevel,
        expRate = 100,
        usableSkills = emptyList(),
        tags = emptyList(),
    )
}
