package io.github.maaasu.astralRecord.feature.playerclass;

import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.feature.account.model.AccountModel;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlayerClassServiceTabNameTest {
    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/10-hud/3-メソッド仕様/10_3-View.md
     * 章・見出し: # 10_3-View > ## 5. tab list 描画
     * 検証契約: ADMINアカウントのローカルTabはクラス・レベル・AFKを付けずアカウント名だけ表示する。
     */
    @Test
    void adminAccountModeUsesOnlyAccountNameInLocalTab() {
        AccountModel account = mock(AccountModel.class);
        when(account.getMode()).thenReturn(AccountMode.ADMIN);
        when(account.getAccountName()).thenReturn("admin");
        when(account.getSlotIndex()).thenReturn(2);
        Player bukkit = mock(Player.class);
        AstPlayer astPlayer = mock(AstPlayer.class);
        when(astPlayer.getAccount()).thenReturn(account);
        when(astPlayer.getBukkit()).thenReturn(bukkit);

        new PlayerClassService().updatePlayerListName(astPlayer);

        verify(bukkit).playerListName(
            Component.text("admin").append(Component.text("#2", NamedTextColor.GRAY)));
    }
}
