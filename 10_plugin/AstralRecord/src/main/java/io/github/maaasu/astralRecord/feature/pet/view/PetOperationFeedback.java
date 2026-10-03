package io.github.maaasu.astralRecord.feature.pet.view;

import com.google.gson.JsonObject;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import static io.github.maaasu.astralRecord.feature.pet.model.PetJson.text;

/** APIの拒否コードを既知の日本語通知へ変換し、内部応答を表示しません。 */
public final class PetOperationFeedback {
    private PetOperationFeedback() { }

    /**
     * 確定結果と復旧状態に対応する通知IDを返します。
     * @param result 操作結果。通信失敗時はnull
     * @param failure 通信・保存例外
     * @param unresolved 同じ操作の結果を継続照会しているか
     * @return プレイヤー向け通知ID
     */
    public static PlayerMsgId message(JsonObject result, Throwable failure, boolean unresolved) {
        if (unresolved) return PlayerMsgId.P_9603;
        if (failure != null) return PlayerMsgId.P_9619;
        if (result == null || result.isEmpty()) return PlayerMsgId.P_9600;
        return switch (text(result, "failure", "")) {
            case "" -> PlayerMsgId.P_9601;
            case "insufficient_materials" -> PlayerMsgId.P_9606;
            case "payment_unavailable" -> PlayerMsgId.P_9618;
            case "egg_not_in_bag", "parent_not_in_bag", "pet_not_in_bag", "bag_not_found" -> PlayerMsgId.P_9607;
            case "master_not_found", "species_not_found", "species_or_bag_not_found" -> PlayerMsgId.P_9608;
            case "parents_invalid", "sex_mismatch" -> PlayerMsgId.P_9609;
            case "parent_dead" -> PlayerMsgId.P_9610;
            case "growth_required" -> PlayerMsgId.P_9611;
            case "breed_cooldown" -> PlayerMsgId.P_9612;
            case "pet_alive" -> PlayerMsgId.P_9613;
            case "revive_orb_missing" -> PlayerMsgId.P_9614;
            case "egg_not_found", "pet_not_found", "pet_not_available" -> PlayerMsgId.P_9615;
            case "name_invalid" -> PlayerMsgId.P_9616;
            case "player_editing" -> PlayerMsgId.P_9617;
            case "facility_required", "facility_or_orb_required" -> PlayerMsgId.P_9602;
            default -> PlayerMsgId.P_9600;
        };
    }
}
