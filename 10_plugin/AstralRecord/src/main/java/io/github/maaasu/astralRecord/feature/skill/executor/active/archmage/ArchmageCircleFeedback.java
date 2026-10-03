package io.github.maaasu.astralRecord.feature.skill.executor.active.archmage;

import io.github.maaasu.astralRecord.feature.skill.active.service.SkillEffectService;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/** 魔法陣の効果が成立した対象に、短く上限のある粒子図形と効果音を表示します。 */
final class ArchmageCircleFeedback {

    private ArchmageCircleFeedback() {
    }

    /**
     * 回復したプレイヤーの周囲を上昇する二色の光で包みます。
     *
     * @param effects 近傍への表示と再生を担当するサービス
     * @param base 実際に回復したプレイヤーの足元
     */
    static void heal(@NotNull SkillEffectService effects, @NotNull Location base) {
        List<Location> mint = new ArrayList<>(24);
        List<Location> cyan = new ArrayList<>(24);
        for (int index = 0; index < 24; index++) {
            double angle = index * Math.PI / 6.0D;
            double height = 0.25D + index * 0.075D;
            mint.add(base.clone().add(Math.cos(angle) * 0.65D, height, Math.sin(angle) * 0.65D));
            cyan.add(base.clone().add(Math.cos(angle + Math.PI) * 0.65D,
                    height, Math.sin(angle + Math.PI) * 0.65D));
        }
        effects.points(base, mint, SharedParticleDefinitions.SKILL_ARCHMAGE_HEAL_CIRCLE_MINT);
        effects.points(base, cyan, SharedParticleDefinitions.SKILL_ARCHMAGE_HEAL_CIRCLE_CYAN);
        effects.point(base.clone().add(0.0D, 1.4D, 0.0D),
                SharedParticleDefinitions.MAGE_HEAL_AURA_HEAL);
        effects.sound(base, Sound.ENTITY_PLAYER_LEVELUP, 0.65F, 1.7F);
    }

    /**
     * 解除したプレイヤーの周囲へ青白い二重の浄化環を表示します。
     *
     * @param effects 近傍への表示と再生を担当するサービス
     * @param base 実際に状態異常を解除したプレイヤーの足元
     */
    static void clear(@NotNull SkillEffectService effects, @NotNull Location base) {
        List<Location> cyan = new ArrayList<>(24);
        List<Location> white = new ArrayList<>(24);
        for (int index = 0; index < 24; index++) {
            double angle = index * Math.PI / 12.0D;
            cyan.add(base.clone().add(Math.cos(angle) * 0.78D, 0.45D, Math.sin(angle) * 0.78D));
            white.add(base.clone().add(Math.cos(angle) * 0.78D, 1.65D, Math.sin(angle) * 0.78D));
        }
        effects.points(base, cyan, SharedParticleDefinitions.SKILL_ARCHMAGE_CLEAR_CIRCLE_CYAN);
        effects.points(base, white, SharedParticleDefinitions.SKILL_ARCHMAGE_CLEAR_CIRCLE_WHITE);
        effects.sound(base, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.8F, 1.5F);
    }

    /**
     * 復活したプレイヤーへ金色の上昇光と頭上の環を表示します。
     *
     * @param effects 近傍への表示と再生を担当するサービス
     * @param base 実際に復活したプレイヤーの足元
     */
    static void reborn(@NotNull SkillEffectService effects, @NotNull Location base) {
        List<Location> gold = new ArrayList<>(32);
        List<Location> bright = new ArrayList<>(24);
        for (int index = 0; index < 32; index++) {
            double angle = index * Math.PI / 8.0D;
            gold.add(base.clone().add(Math.cos(angle) * 0.7D,
                    0.2D + index * 0.07D, Math.sin(angle) * 0.7D));
        }
        for (int index = 0; index < 24; index++) {
            double angle = index * Math.PI / 12.0D;
            bright.add(base.clone().add(Math.cos(angle) * 0.9D, 2.35D, Math.sin(angle) * 0.9D));
        }
        effects.points(base, gold, SharedParticleDefinitions.SKILL_REBORN_PROTECT_GOLD);
        effects.points(base, bright, SharedParticleDefinitions.SKILL_REBORN_PROTECT_BRIGHT);
        effects.point(base.clone().add(0.0D, 1.1D, 0.0D),
                SharedParticleDefinitions.SKILL_REBORN_PROTECT_LIGHT);
        effects.sound(base, Sound.BLOCK_BEACON_ACTIVATE, 0.9F, 1.4F);
    }

    /**
     * 実際に円のバフを受けているプレイヤーへ短い星冠を表示します。
     *
     * @param effects 近傍への表示を担当するサービス
     * @param bases 同じワールドで有効バフを受けているプレイヤーの足元
     */
    static void celestial(@NotNull SkillEffectService effects, @NotNull List<Location> bases) {
        List<Location> azure = new ArrayList<>(bases.size() * 16);
        List<Location> violet = new ArrayList<>(bases.size() * 8);
        for (Location base : bases) {
            for (int index = 0; index < 16; index++) {
                double angle = index * Math.PI / 8.0D;
                azure.add(base.clone().add(Math.cos(angle) * 0.75D, 1.9D,
                        Math.sin(angle) * 0.75D));
                if (index % 2 == 0) {
                    violet.add(base.clone().add(Math.cos(angle) * 0.55D, 2.15D,
                            Math.sin(angle) * 0.55D));
                }
            }
        }
        effects.pointsNearViewers(azure, SharedParticleDefinitions.SKILL_ARCHMAGE_CELESTIAL_AZURE);
        effects.pointsNearViewers(violet, SharedParticleDefinitions.SKILL_ARCHMAGE_CELESTIAL_VIOLET);
    }
}
