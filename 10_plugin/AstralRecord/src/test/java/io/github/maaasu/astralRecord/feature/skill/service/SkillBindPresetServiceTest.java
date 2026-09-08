package io.github.maaasu.astralRecord.feature.skill.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.maaasu.astralRecord.feature.mutation.model.PlayerStateSection;
import io.github.maaasu.astralRecord.feature.skill.model.SkillBindPreset;
import io.github.maaasu.astralRecord.feature.skill.repository.SkillBindPresetRepository;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SkillBindPresetServiceTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-サービス.md
     * 章・見出し: # 13_3-サービス > ## 7. bind preset cache / 保存
     * 検証契約: GUI読取は公開cacheだけを参照しrepository I/Oを行わない。
     */
    @Test
    void guiReadUsesOnlyPublishedCacheAndNeverCallsRepository() {
        Plugin plugin = mock(Plugin.class);
        SkillBindPresetRepository repository = mock(SkillBindPresetRepository.class);
        SkillBindPresetService service = new SkillBindPresetService(plugin, repository);
        UUID accountId = UUID.randomUUID();

        List<SkillBindPreset> fallback = service.getPresets(accountId);

        assertEquals(6, fallback.size());
        assertFalse(service.hasLoadedPresets(accountId));
        verify(repository, never()).findByAccountId(accountId);

        List<SkillBindPreset> loaded = presets(accountId);
        when(repository.findByAccountId(accountId)).thenReturn(loaded);
        service.applyInitialPresets(accountId, service.loadInitialPresets(accountId));

        assertTrue(service.hasLoadedPresets(accountId));
        assertEquals(loaded.getFirst().getPresetId(), service.getPresets(accountId).getFirst().getPresetId());
        verify(repository).findByAccountId(accountId);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/13_1-モデル定義.md
     * 章・見出し: # 13_1-モデル定義 > ## 5. バインドプリセット
     * 検証契約: APIが返した選択中プリセットをログイン後のサービスキャッシュへ復元する。
     */
    @Test
    void applyInitialPresetsRestoresSelectedPreset() {
        Plugin plugin = mock(Plugin.class);
        SkillBindPresetRepository repository = mock(SkillBindPresetRepository.class);
        UUID accountId = UUID.randomUUID();
        SkillBindPresetService service = new SkillBindPresetService(plugin, repository);

        service.applyInitialPresets(accountId, selectedPresets(accountId, 4));

        assertEquals(4, service.selectedPresetIndex(accountId));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-サービス.md
     * 章・見出し: # 13_3-サービス > ## 7. bind preset cache / 保存
     * 検証契約: API未保存プリセットはexpectedVersionを省略し、targetVersionを1以上で送信する。
     */
    @Test
    void snapshotNormalizesUnpersistedPresetVersionsForApi() {
        UUID accountId = UUID.randomUUID();
        SkillBindPresetService service = new SkillBindPresetService(
            mock(Plugin.class), mock(SkillBindPresetRepository.class));
        service.setLocalStatePersistence(localPersistence(accountId));
        service.applyInitialPresets(accountId, List.of());

        service.selectPreset(accountId, 2);

        JsonArray presets = service.snapshotPlayerState(accountId).payload().getAsJsonObject()
            .getAsJsonArray("presets");
        assertEquals(6, presets.size());
        for (com.google.gson.JsonElement element : presets) {
            JsonObject preset = element.getAsJsonObject();
            assertTrue(preset.get("expectedVersion").isJsonNull());
            assertEquals(1, preset.get("targetVersion").getAsInt());
        }

        PlayerStateSection first = service.snapshotPlayerState(accountId);
        first.acknowledge().accept(presetAck(first, 1));
        service.saveAsync(accountId, 2, List.of("new-skill"), null, List.of(), accountId,
            ignored -> { }, () -> { throw new AssertionError("local save should succeed"); });
        JsonObject nextPreset = service.snapshotPlayerState(accountId).payload().getAsJsonObject()
            .getAsJsonArray("presets").get(1).getAsJsonObject();
        assertEquals(1, nextPreset.get("expectedVersion").getAsInt());
        assertEquals(2, nextPreset.get("targetVersion").getAsInt());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-サービス.md
     * 章・見出し: # 13_3-サービス > ## 7. bind preset cache / 保存
     * 検証契約: プリセット切替は通信なしでローカル確定し、保存キューへ渡す。
     */
    @Test
    void selectPresetConfirmsSelectionLocallyAndQueuesSave() {
        Plugin plugin = mock(Plugin.class);
        SkillBindPresetRepository repository = mock(SkillBindPresetRepository.class);
        UUID accountId = UUID.randomUUID();
        InventoryService persistence = localPersistence(accountId);
        SkillBindPresetService service = new SkillBindPresetService(plugin, repository);
        service.setLocalStatePersistence(persistence);

        service.selectPreset(accountId, 4);

        assertEquals(4, service.selectedPresetIndex(accountId));
        verify(persistence).queueLocalPlayerSave(accountId);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-サービス.md
     * 章・見出し: # 13_3-サービス > ## 7. bind preset cache / 保存
     * 検証契約: 連続したプリセット切替は最後のローカル状態を一つのsnapshotとして後送する。
     */
    @Test
    void selectPresetWritesRapidChangesInOrder() {
        Plugin plugin = mock(Plugin.class);
        SkillBindPresetRepository repository = mock(SkillBindPresetRepository.class);
        UUID accountId = UUID.randomUUID();
        InventoryService persistence = localPersistence(accountId);
        SkillBindPresetService service = new SkillBindPresetService(plugin, repository);
        service.setLocalStatePersistence(persistence);

        service.selectPreset(accountId, 4);
        service.selectPreset(accountId, 5);

        assertEquals(5, service.selectedPresetIndex(accountId));
        assertEquals(5, service.snapshotPlayerState(accountId).payload().getAsJsonObject()
            .get("selectedPresetIndex").getAsInt());
        verify(persistence, times(2)).queueLocalPlayerSave(accountId);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-サービス.md
     * 章・見出し: # 13_3-サービス > ## 7. bind preset cache / 保存
     * 検証契約: バインド内容保存はrepository I/Oを行わずローカルcacheへ即時反映し、全プリセットsnapshotの保存を予約する。
     */
    @Test
    void saveUpdatesLocalCacheAndQueuesPlayerStateSnapshot() {
        Plugin plugin = mock(Plugin.class);
        SkillBindPresetRepository repository = mock(SkillBindPresetRepository.class);
        UUID accountId = UUID.randomUUID();
        InventoryService persistence = localPersistence(accountId);
        SkillBindPresetService service = new SkillBindPresetService(plugin, repository);
        service.setLocalStatePersistence(persistence);
        service.applyInitialPresets(accountId, presets(accountId));

        assertTrue(service.saveAsync(
            accountId,
            2,
            List.of("active-one"),
            "left-click",
            List.of("passive-one"),
            accountId,
            ignored -> { },
            () -> { throw new AssertionError("local save should succeed"); }
        ));

        SkillBindPreset saved = service.getPresets(accountId).get(1);
        assertEquals("active-one", saved.getActiveSkillSlots().getFirst());
        assertEquals("left-click", saved.getLeftClickSkillId());
        assertEquals("passive-one", saved.getPassiveSkillSlots().getFirst());
        assertEquals(3, service.snapshotPlayerState(accountId).payload().getAsJsonObject().getAsJsonArray("presets")
            .get(1).getAsJsonObject().get("targetVersion").getAsInt());
        verify(persistence).queueLocalPlayerSave(accountId);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/13_1-モデル定義.md
     * 章・見出し: # 13_1-モデル定義 > ## 5. バインドプリセット
     * 検証契約: action ring は6件へ正規化し、互換コンストラクタは武器通常攻撃を既定にする。
     */
    @Test
    void bindPresetNormalizesActionRingAndUsesWeaponNormalAttackByDefault() {
        UUID accountId = UUID.randomUUID();

        SkillBindPreset preset = new SkillBindPreset(
            UUID.randomUUID(), accountId, 1,
            List.of("one", "two", "three", "four", "five", "six", "seven"),
            List.of(), true, true, 1
        );

        assertEquals(6, preset.getActiveSkillSlots().size());
        assertEquals("six", preset.getActiveSkillSlots().getLast());
        assertEquals(SkillBindPreset.WEAPON_NORMAL_ATTACK_BINDING_ID, preset.getLeftClickSkillId());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/13_1-モデル定義.md
     * 章・見出し: # 13_1-モデル定義 > ## 5. バインドプリセット
     * 検証契約: 左クリックバインドのnull解除はローカルcacheとplayer-state snapshotへnullのまま確定する。
     */
    @Test
    void savePersistsNullLeftClickBindingInLocalSnapshot() {
        Plugin plugin = mock(Plugin.class);
        SkillBindPresetRepository repository = mock(SkillBindPresetRepository.class);
        UUID accountId = UUID.randomUUID();
        InventoryService persistence = localPersistence(accountId);
        SkillBindPresetService service = new SkillBindPresetService(plugin, repository);
        service.setLocalStatePersistence(persistence);
        service.applyInitialPresets(accountId, presets(accountId));

        assertTrue(service.saveAsync(
            accountId, 1, List.of(), null, List.of(), accountId, ignored -> { }, () -> { }
        ));

        assertNull(service.getPresets(accountId).getFirst().getLeftClickSkillId());
        assertTrue(service.snapshotPlayerState(accountId).payload().getAsJsonObject().getAsJsonArray("presets")
            .get(0).getAsJsonObject().get("leftClickSkillId").isJsonNull());
        verify(persistence).queueLocalPlayerSave(accountId);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-サービス.md
     * 章・見出し: # 13_3-サービス > ## 7. bind preset cache / 保存
     * 検証契約: バインド変更直後の退出はsnapshot捕捉前でも全内容と選択番号を保持し、完全ACK後に退出済みcacheを破棄する。
     */
    @Test
    void quitBeforeSnapshotRetainsBindingsUntilCompleteAck() {
        UUID accountId = UUID.randomUUID();
        SkillBindPresetService service = localService(accountId);
        assertTrue(service.saveAsync(accountId, 2, List.of("learned-id"), null, List.of(),
            accountId, ignored -> { }, () -> { throw new AssertionError("save failed"); }));
        service.selectPreset(accountId, 2);
        service.invalidate(accountId);

        PlayerStateSection snapshot = service.snapshotPlayerState(accountId);
        assertNotNull(snapshot);
        assertEquals(2, snapshot.payload().getAsJsonObject().get("selectedPresetIndex").getAsInt());
        assertEquals("learned-id", service.getPresets(accountId).get(1).getActiveSkillSlots().getFirst());
        snapshot.acknowledge().accept(new JsonObject());
        assertTrue(service.hasLoadedPresets(accountId));
        snapshot.acknowledge().accept(presetAck(snapshot, 9));
        assertFalse(service.hasLoadedPresets(accountId));
        assertNull(service.snapshotPlayerState(accountId));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-サービス.md
     * 章・見出し: # 13_3-サービス > ## 7. bind preset cache / 保存
     * 検証契約: 再参加ロードとapplyの間にACKが完了しても保持したバインドと選択番号を優先し、次回versionへ反映する。
     */
    @Test
    void rejoinRetainsBindingsAcrossAckBetweenLoadAndApply() {
        UUID accountId = UUID.randomUUID();
        SkillBindPresetService service = localService(accountId);
        service.saveAsync(accountId, 2, List.of("local-id"), null, List.of(), accountId, ignored -> { }, () -> { });
        service.selectPreset(accountId, 2);
        PlayerStateSection sent = service.snapshotPlayerState(accountId);
        service.invalidate(accountId);
        assertEquals("local-id", service.loadInitialPresets(accountId).get(1).getActiveSkillSlots().getFirst());
        sent.acknowledge().accept(presetAck(sent, 9));
        service.applyInitialPresets(accountId, selectedPresets(accountId, 1));
        assertEquals(2, service.selectedPresetIndex(accountId));
        assertEquals("local-id", service.getPresets(accountId).get(1).getActiveSkillSlots().getFirst());

        service.saveAsync(accountId, 2, List.of("next-id"), null, List.of(), accountId, ignored -> { }, () -> { });
        JsonObject next = service.snapshotPlayerState(accountId).payload().getAsJsonObject()
            .getAsJsonArray("presets").get(1).getAsJsonObject();
        assertEquals(9, next.get("expectedVersion").getAsInt());
        assertEquals(10, next.get("targetVersion").getAsInt());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-サービス.md
     * 章・見出し: # 13_3-サービス > ## 7. bind preset cache / 保存
     * 検証契約: 再参加applyは未保存バインドを旧API値で置換せず、旧ACKと重複ACKは後続変更の内容とdirtyを維持する。
     */
    @Test
    void rejoinAndDuplicateOldAckPreserveNewerBindings() {
        UUID accountId = UUID.randomUUID();
        SkillBindPresetService service = localService(accountId);
        service.saveAsync(accountId, 2, List.of("first-id"), null, List.of(), accountId, ignored -> { }, () -> { });
        PlayerStateSection first = service.snapshotPlayerState(accountId);
        service.invalidate(accountId);
        service.applyInitialPresets(accountId, presets(accountId));
        assertEquals("first-id", service.getPresets(accountId).get(1).getActiveSkillSlots().getFirst());
        service.saveAsync(accountId, 2, List.of("next-id"), null, List.of(), accountId, ignored -> { }, () -> { });
        first.acknowledge().accept(presetAck(first, 9));
        first.acknowledge().accept(presetAck(first, 9));
        JsonObject next = service.snapshotPlayerState(accountId).payload().getAsJsonObject()
            .getAsJsonArray("presets").get(1).getAsJsonObject();
        assertEquals("next-id", next.getAsJsonArray("activeSkillSlots").get(0).getAsString());
        assertEquals(9, next.get("expectedVersion").getAsInt());
        assertEquals(10, next.get("targetVersion").getAsInt());
    }

    private SkillBindPresetService localService(UUID accountId) {
        SkillBindPresetService service = new SkillBindPresetService(mock(Plugin.class), mock(SkillBindPresetRepository.class));
        service.setLocalStatePersistence(localPersistence(accountId));
        service.applyInitialPresets(accountId, presets(accountId));
        return service;
    }

    private JsonObject presetAck(PlayerStateSection snapshot, int version) {
        JsonObject ack = new JsonObject();
        ack.addProperty("clientRevision", snapshot.payload().getAsJsonObject().get("clientRevision").getAsLong());
        JsonArray entries = new JsonArray();
        for (int index = 1; index <= 6; index++) {
            JsonObject entry = new JsonObject();
            entry.addProperty("presetIndex", index);
            entry.addProperty("version", version);
            entries.add(entry);
        }
        ack.add("entries", entries);
        return ack;
    }

    private List<SkillBindPreset> presets(UUID accountId) {
        List<SkillBindPreset> presets = new ArrayList<>();
        for (int index = 1; index <= 6; index++) {
            presets.add(new SkillBindPreset(
                UUID.randomUUID(),
                accountId,
                index,
                List.of(),
                List.of(),
                true,
                true,
                index
            ));
        }
        return presets;
    }

    private List<SkillBindPreset> selectedPresets(UUID accountId, int selectedIndex) {
        List<SkillBindPreset> presets = new ArrayList<>();
        for (int index = 1; index <= 6; index++) {
            presets.add(new SkillBindPreset(
                UUID.randomUUID(), accountId, index,
                List.of(), SkillBindPreset.WEAPON_NORMAL_ATTACK_BINDING_ID, List.of(),
                true, true, index, index == selectedIndex
            ));
        }
        return presets;
    }

    private InventoryService localPersistence(UUID accountId) {
        InventoryService persistence = mock(InventoryService.class);
        doAnswer(invocation -> {
            java.util.function.Supplier<?> mutation = invocation.getArgument(1);
            return mutation.get();
        }).when(persistence).executeLocalPlayerMutation(eq(accountId), any());
        return persistence;
    }
}
