package io.github.maaasu.astralRecord.feature.mob.service;

import io.github.maaasu.astralRecord.feature.mob.model.MobCategory;
import io.github.maaasu.astralRecord.feature.mob.model.MobInstance;
import io.github.maaasu.astralRecord.feature.mob.model.MobTargetingConfig;
import io.github.maaasu.astralRecord.feature.mob.model.MobTemplate;
import io.github.maaasu.astralRecord.feature.mob.model.MobThreatTable;
import io.github.maaasu.astralRecord.feature.mob.model.TargetStrategy;
import io.github.maaasu.astralRecord.feature.pet.service.PetRuntimeService;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.LivingEntity;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MobCombatPetThreatTest {
    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/12-mob/3-メソッド仕様/12_3-戦闘.md
     * 章・見出し: # 12_3-戦闘 > ## 1. MobCombatService メソッド仕様 > ### ターゲット選定
     * 検証契約: 反撃型の現在targetが空でも、被害を与えた有効な召喚ペットの脅威記録があればペットUUIDをtargetへ設定する。
     */
    @Test
    void retaliateOnlyMobSelectsPetThreatWithoutCurrentTarget() {
        UUID petId=UUID.randomUUID();
        World world=mock(World.class);
        MobInstance mob=mock(MobInstance.class);
        MobTemplate template=mock(MobTemplate.class);
        MobThreatTable threats=new MobThreatTable();
        threats.add(petId,5.0D);
        LivingEntity pet=mock(LivingEntity.class);
        PetRuntimeService pets=mock(PetRuntimeService.class);
        MobCombatService combat=new MobCombatService(null,null,null,null,null,null,null,null,null,null);
        combat.setPetRuntimeService(pets);
        when(mob.template()).thenReturn(template);
        when(mob.threatTable()).thenReturn(threats);
        when(mob.currentLocation()).thenReturn(new Location(world,0,64,0));
        when(template.category()).thenReturn(MobCategory.ENEMY);
        when(template.targeting()).thenReturn(new MobTargetingConfig(TargetStrategy.HIGHEST_THREAT,8,16,24,true));
        when(pets.target(petId)).thenReturn(pet);
        when(pet.getWorld()).thenReturn(world);
        when(pet.getLocation()).thenReturn(new Location(world,2,64,0));
        when(pet.getUniqueId()).thenReturn(petId);
        try(MockedStatic<Bukkit> bukkit=mockStatic(Bukkit.class)){
            assertSame(pet,combat.selectTarget(mob));
        }
        verify(mob).targetId(petId);
    }
}
