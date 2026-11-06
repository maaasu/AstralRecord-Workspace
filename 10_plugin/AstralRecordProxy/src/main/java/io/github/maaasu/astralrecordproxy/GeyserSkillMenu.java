package io.github.maaasu.astralrecordproxy;

import org.geysermc.api.Geyser;
import org.geysermc.cumulus.form.SimpleForm;
import org.geysermc.geyser.api.GeyserApi;

import java.util.List;
import java.util.UUID;
import java.util.function.IntConsumer;

/** Geyser に接続している統合版プレイヤーへスキル選択フォームを送ります。 */
final class GeyserSkillMenu {
    private GeyserSkillMenu() {
    }

    static boolean open(UUID playerId, List<String> labels, IntConsumer result) {
        if (!Geyser.isRegistered()) {
            return false;
        }
        var connection = GeyserApi.api().connectionByUuid(playerId);
        if (connection == null) {
            return false;
        }

        var form = SimpleForm.builder()
            .title("スキル選択")
            .content("選択後、攻撃で発動します。");
        labels.forEach(form::button);
        return connection.sendForm(form
            .validResultHandler(response -> {
                int index = response.clickedButtonId();
                result.accept(index >= 0 && index < labels.size() ? index : BackendProtocol.SKILL_MENU_NO_SELECTION);
            })
            .closedOrInvalidResultHandler(() -> result.accept(BackendProtocol.SKILL_MENU_NO_SELECTION)));
    }
}
