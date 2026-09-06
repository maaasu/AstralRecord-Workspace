package io.github.maaasu.astralRecord.feature.skill.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.skill.model.LearnedSkillInstance;
import io.github.maaasu.astralRecord.feature.skill.model.LearnedSkillSigil;
import io.github.maaasu.astralRecord.feature.skill.repository.LearnedSkillRepository;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LearnedSkillServiceTest {
    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-サービス.md
     * 章・見出し: # 13_3-サービス > ## 習得済みスキル個体
     * 検証契約: 通常の習得、強化、忘却は通信なしでローカル状態を確定して保存キューへ積む。
     */
    @Test void confirmsLearnLevelUpAndForgetLocally() {
        UUID a=UUID.randomUUID(); InventoryService i=inventory(a); LearnedSkillService s=service(a,i,List.of());
        assertTrue(s.learnFromManagerAsync(a,"skill",a,List.of(),x->{},x->{})); LearnedSkillInstance k=s.getLearnedSkills(a).getFirst();
        assertTrue(s.levelUpFromManagerWithPaymentsAsync(a,k.getLearnedSkillId(),a,Map.of(),x->{},x->{},()->{}));
        assertEquals(2,s.findInstance(a,k.getLearnedSkillId()).getLevel()); assertTrue(s.forgetAsync(a,k.getLearnedSkillId(),a,x->{},x->{}));
        assertNull(s.findInstance(a,k.getLearnedSkillId())); verify(i,times(3)).queueLocalPlayerSave(a);
    }
    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-サービス.md
     * 章・見出し: # 13_3-サービス > ## 習得済みスキル個体
     * 検証契約: 支払いcommitが不成立なら素材予約を解放し習得済みスキルを変更しない。
     */
    @Test void releasesReservationWhenPaymentCommitFails() {
        UUID a=UUID.randomUUID(),e=UUID.randomUUID(); InventoryService i=inventory(a); when(i.commitLocalOrbOperationPayment(eq(a),any(),any())).thenReturn(false);
        LearnedSkillInstance k=skill(a); LearnedSkillService s=service(a,i,List.of(k));
        assertFalse(s.levelUpFromManagerWithPaymentsAsync(a,k.getLearnedSkillId(),a,Map.of(e,1L),x->{},x->{},()->{})); assertEquals(1,s.findInstance(a,k.getLearnedSkillId()).getLevel()); verify(i).releaseOrbOperationPayment(eq(a),any());
    }
    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-サービス.md
     * 章・見出し: # 13_3-サービス > ## 習得済みスキル個体
     * 検証契約: シジル装着と脱着は支払いcommit内で個体更新し、脱着返却callbackを実行する。
     */
    @Test void attachesAndDetachesSigilInsidePaymentCommit() {
        UUID a=UUID.randomUUID(),o=UUID.randomUUID(),m=UUID.randomUUID(); InventoryService i=inventory(a); LearnedSkillInstance k=skill(a); LearnedSkillService s=service(a,i,List.of(k)); LearnedSkillSigil g=new LearnedSkillSigil(UUID.randomUUID(),"sigil","group",0);
        assertTrue(s.attachSigilLocally(a,k.getLearnedSkillId(),o,g,m,x->{},x->{})); assertTrue(s.detachSigilLocally(a,k.getLearnedSkillId(),o,g.getLearnedSkillSigilId(),()->{},x->{},x->{})); assertTrue(s.findInstance(a,k.getLearnedSkillId()).getSigils().isEmpty());
    }
    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-サービス.md
     * 章・見出し: # 13_3-サービス > ## 習得済みスキル個体
     * 検証契約: 旧ACKは後発忘却のexpectedVersionを進め、不完全ACKはdirtyを解除しない。
     */
    @Test void advancesDeleteVersionAndRejectsIncompleteAck() {
        UUID a=UUID.randomUUID(); LearnedSkillInstance k=skill(a); LearnedSkillService s=service(a,inventory(a),List.of(k));
        s.levelUpFromManagerWithPaymentsAsync(a,k.getLearnedSkillId(),a,Map.of(),x->{},x->{},()->{}); var sent=s.snapshotPlayerState(a); s.forgetAsync(a,k.getLearnedSkillId(),a,x->{},x->{});
        JsonObject ack=new JsonObject(); ack.addProperty("clientRevision",sent.payload().getAsJsonObject().get("clientRevision").getAsLong()); JsonArray es=new JsonArray(); JsonObject e=new JsonObject(); e.addProperty("learnedSkillId",k.getLearnedSkillId().toString()); e.addProperty("version",9); es.add(e); ack.add("entries",es); ack.add("deletedIds",new JsonArray()); sent.acknowledge().accept(ack);
        var pending=s.snapshotPlayerState(a); assertEquals(9,pending.payload().getAsJsonObject().getAsJsonArray("deletedSkills").get(0).getAsJsonObject().get("expectedVersion").getAsInt()); pending.acknowledge().accept(new JsonObject()); assertNotNull(s.snapshotPlayerState(a));
    }
    private static LearnedSkillService service(UUID a,InventoryService i,List<LearnedSkillInstance> l){LearnedSkillService s=new LearnedSkillService(mock(Plugin.class),mock(LearnedSkillRepository.class),i);s.applyInitialSkills(a,l);return s;}
    private static LearnedSkillInstance skill(UUID a){return new LearnedSkillInstance(UUID.randomUUID(),a,"skill",1,List.of(),4,null,null);}
    private static InventoryService inventory(UUID a){InventoryService i=mock(InventoryService.class);doAnswer(x->x.<java.util.function.Supplier<?>>getArgument(1).get()).when(i).executeLocalPlayerMutation(eq(a),any());when(i.reserveLocalMutationPayment(eq(a),any(),any())).thenReturn(true);doAnswer(x->{x.<Runnable>getArgument(2).run();return true;}).when(i).commitLocalOrbOperationPayment(eq(a),any(),any());return i;}
}
