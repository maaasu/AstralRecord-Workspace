package io.github.maaasu.astralRecord.feature.combat.service;

import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.feature.mob.service.MobCombatService;
import io.github.maaasu.astralRecord.feature.mob.service.MobKnockbackService;
import io.github.maaasu.astralRecord.feature.mob.service.MobService;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.feature.playersetting.service.PlayerSettingService;
import io.github.maaasu.astralRecord.feature.status.model.HealthRecoveryNotification;
import io.github.maaasu.astralRecord.feature.status.service.StatusService;
import io.github.maaasu.astralRecord.shared.display.DisplayTextService;
import io.github.maaasu.astralRecord.shared.effect.ParticleDisplayService;
import io.github.maaasu.astralRecord.support.DesignTestFixtures;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import org.bukkit.Location;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DamageServiceHealthRecoveryLogTest extends MockBukkitTestBase {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/14-combat/14_5-例外・ログ・運用.md
     * 章・見出し: # 14_5-例外・ログ・運用 > ## 2. player message
     * 検証契約: プレイヤー間のHP回復では、回復者に対象者、対象者に回復者と回復手段を通知する。
     */
    @Test
    void playerHealingLogIncludesHealerTargetAndSourceForBothPlayers() {
        AstPlayer healer = player("Healer");
        AstPlayer target = player("Target");
        PlayerSettingService settings = mock(PlayerSettingService.class);
        when(settings.isDamageLogDisplayEnabled(any(UUID.class))).thenReturn(false);
        when(settings.isDamageLogMessageEnabled(healer.getUser().getUuid())).thenReturn(true);
        when(settings.isDamageLogMessageEnabled(target.getUser().getUuid())).thenReturn(true);
        PlayerMessageService messages = mock(PlayerMessageService.class);
        DamageService service = damageService(settings);

        try (MockedStatic<PlayerMessageService> messageService = mockStatic(PlayerMessageService.class)) {
            messageService.when(PlayerMessageService::getInstance).thenReturn(messages);

            service.presentPlayerHealthRecovery(new HealthRecoveryNotification(
                    target,
                    12.5D,
                    healer,
                    "ヒールオーラ"
            ));

            verify(messages).send(healer, PlayerMsgId.P_5354, "12.5", "Target", "ヒールオーラ");
            verify(messages).send(target, PlayerMsgId.P_5355, "12.5", "Healer", "ヒールオーラ");
        }
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/14-combat/14_5-例外・ログ・運用.md
     * 章・見出し: # 14_5-例外・ログ・運用 > ## 2. player message
     * 検証契約: 自己回復は対象者・回復者を重複表示せず、回復手段だけを表示する。
     */
    @Test
    void selfHealingLogUsesSingleLineSourceFormat() {
        AstPlayer target = player("Target");
        PlayerSettingService settings = mock(PlayerSettingService.class);
        when(settings.isDamageLogDisplayEnabled(target.getUser().getUuid())).thenReturn(true);
        when(settings.isDamageLogMessageEnabled(target.getUser().getUuid())).thenReturn(true);
        DisplayTextService display = mock(DisplayTextService.class);
        PlayerMessageService messages = mock(PlayerMessageService.class);
        DamageService service = damageService(settings, display);

        try (MockedStatic<PlayerMessageService> messageService = mockStatic(PlayerMessageService.class)) {
            messageService.when(PlayerMessageService::getInstance).thenReturn(messages);

            service.presentPlayerHealthRecovery(new HealthRecoveryNotification(
                    target,
                    20.0D,
                    target,
                    "§a非常に長い回復手段の名称です\n"
            ));

            verify(display).spawnHealingNumber(any(Location.class), eq(20.0D));
            verify(messages).send(
                    target,
                    PlayerMsgId.P_5356,
                    "20",
                    "非常に長い回復手段の名…"
            );
        }
    }

    private AstPlayer player(String name) {
        PlayerMock bukkitPlayer = server().addPlayer(name);
        return DesignTestFixtures.astPlayer(bukkitPlayer, AccountMode.ADMIN);
    }

    private DamageService damageService(PlayerSettingService settings) {
        return damageService(settings, mock(DisplayTextService.class));
    }

    private DamageService damageService(
            PlayerSettingService settings,
            DisplayTextService display
    ) {
        return new DamageService(
                mock(StatusService.class),
                mock(MobService.class),
                mock(MobCombatService.class),
                mock(MobKnockbackService.class),
                display,
                settings,
                mock(ParticleDisplayService.class)
        );
    }
}
