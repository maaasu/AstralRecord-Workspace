package io.github.maaasu.astralRecord.feature.pet.command;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.pet.model.PetJson;
import io.github.maaasu.astralRecord.feature.player.AccountModeGuard;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.infrastructure.command.AstTabCompleter;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/** 管理者用ペットコマンドの操作名と、有効な目標成長度を補完します。 */
public final class PetTabCompleter extends AstTabCompleter {
    /** プレイヤー専用の補完として登録します。 */
    public PetTabCompleter() {
        super(true);
    }

    /**
     * 読み込み済み個体とマスターから補完候補を返します。通信は行いません。
     * @param player コマンド実行者
     * @param args 現在の引数
     * @return 管理者の通常プレイ時だけ、操作名または現在より高い成長度の候補
     */
    @Override
    protected List<String> getPlayerCompletions(@NotNull AstPlayer player, @NotNull String[] args) {
        if (!player.hasAdminPermission() || !AccountModeGuard.isGameplayPlayer(player)) return List.of();
        if (args.length == 1) return List.of("name", "level");
        if (args.length != 2 || !args[0].equalsIgnoreCase("level")) return List.of();

        var service = AstralRecord.getInstance().getPetService();
        if (service == null) return List.of();
        var accountId = player.getAccount().getUuid();
        var id = service.equippedId(accountId);
        var pet = id == null ? null : service.find(accountId, id);
        if (pet == null || pet.isEgg() || service.dead(pet)) return List.of();
        int current = (int) PetJson.number(pet.details(), "level", 1);
        int max = (int) PetJson.number(service.master().rules(), "maxLevel", 100);
        if (current >= max) return List.of();
        return getNumberCompletions(current + 1, max);
    }
}
