package io.github.maaasu.astralRecord.feature.mob.event;

import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.feature.account.model.AccountModel;
import io.github.maaasu.astralRecord.feature.currency.event.CurrencyExchangeGuiEventHandler;
import io.github.maaasu.astralRecord.feature.loginbonus.service.LoginBonusService;
import io.github.maaasu.astralRecord.feature.market.event.MarketGuiEventHandler;
import io.github.maaasu.astralRecord.feature.menu.view.MenuView;
import io.github.maaasu.astralRecord.feature.mob.model.IdleBehavior;
import io.github.maaasu.astralRecord.feature.mob.model.MobBaseStat;
import io.github.maaasu.astralRecord.feature.mob.model.MobCategory;
import io.github.maaasu.astralRecord.feature.mob.model.MobEquipmentConfig;
import io.github.maaasu.astralRecord.feature.mob.model.MobInstance;
import io.github.maaasu.astralRecord.feature.mob.model.MobInteractionActionConfig;
import io.github.maaasu.astralRecord.feature.mob.model.MobInteractionsConfig;
import io.github.maaasu.astralRecord.feature.mob.model.MobIdleConfig;
import io.github.maaasu.astralRecord.feature.mob.model.MobShieldConfig;
import io.github.maaasu.astralRecord.feature.mob.model.MobTemplate;
import io.github.maaasu.astralRecord.feature.mob.service.MobService;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.feature.playerclass.PlayerClassService;
import io.github.maaasu.astralRecord.feature.quest.event.QuestGuiEventHandler;
import io.github.maaasu.astralRecord.feature.shop.event.ShopGuiEventHandler;
import io.github.maaasu.astralRecord.feature.skill.event.SkillForgetGuiEventHandler;
import io.github.maaasu.astralRecord.feature.status.model.HealthRecoveryContext;
import io.github.maaasu.astralRecord.feature.status.service.StatusService;
import io.github.maaasu.astralRecord.feature.storage.service.StorageService;
import io.github.maaasu.astralRecord.shared.interaction.InputFamily;
import io.github.maaasu.astralRecord.shared.interaction.InputSource;
import io.github.maaasu.astralRecord.shared.interaction.PlayerInputCandidate;
import io.github.maaasu.astralRecord.shared.interaction.PlayerInputContext;
import io.github.maaasu.astralRecord.shared.interaction.PlayerInteractionRayTrace;
import io.github.maaasu.astralRecord.shared.interaction.PlayerInteractionSnapshot;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.block.Action;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

class MobInteractionEventHandlerTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/12-mob/12_4-統合フロー.md
     * 章・見出し: # 12_4-統合フロー > ## 6. NPC・spawner 入力調停 > ### 処理要点
     * 検証契約: PLAYER の右クリックNPC interactionで restore_status actionを勝者処理し、StatusService.restoreAllへ委譲する。
     */
    @Test
    void rightClickRestoreStatusDelegatesToStatusService() {
        UUID playerId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID entityId = UUID.fromString("00000000-0000-0000-0000-000000000002");
        UUID instanceId = UUID.fromString("00000000-0000-0000-0000-000000000003");

        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(playerId);
        Entity target = mock(Entity.class);
        when(target.getUniqueId()).thenReturn(entityId);
        when(target.getBoundingBox()).thenReturn(new BoundingBox(-0.5D, 63.5D, 1.0D, 0.5D, 65.5D, 2.0D));

        MobTemplate template = new MobTemplate(
            1,
            "test_npc",
            MobCategory.NPC,
            "Test NPC",
            null,
            1,
            EntityType.DOLPHIN,
            true,
            null,
            List.of(),
            List.of("npc"),
            null,
            MobEquipmentConfig.EMPTY,
            List.of(new MobBaseStat("MAX_HEALTH", 100.0D)),
            MobShieldConfig.EMPTY,
            new MobIdleConfig(IdleBehavior.STATIONARY, 0.0D, 0.0D),
            true,
            new MobInteractionsConfig(
                List.of(),
                List.of(new MobInteractionActionConfig("restore_status", Map.of()))
            ),
            null,
            null,
            null
        );
        MobInstance instance = new MobInstance(instanceId, template, new Location(null, 0.0D, 64.0D, 0.0D));

        MobService mobService = mock(MobService.class);
        when(mobService.getNpcInstanceByEntity(entityId)).thenReturn(instance);
        StatusService statusService = mock(StatusService.class);
        MobInteractionEventHandler handler = new MobInteractionEventHandler(
            mobService,
            statusService,
            mock(ShopGuiEventHandler.class),
            mock(MenuView.class),
            mock(PlayerClassService.class),
            mock(StorageService.class),
            mock(QuestGuiEventHandler.class),
            mock(CurrencyExchangeGuiEventHandler.class),
            mock(LoginBonusService.class),
            mock(SkillForgetGuiEventHandler.class),
            mock(MarketGuiEventHandler.class)
        );

        PlayerInteractionSnapshot snapshot = new PlayerInteractionSnapshot(
            player,
            mock(Event.class),
            EquipmentSlot.HAND,
            Action.RIGHT_CLICK_AIR,
            target,
            null,
            null,
            false,
            PlayerInteractionRayTrace.create(new Vector(0.0D, 64.0D, 0.0D), new Vector(0.0D, 0.0D, 1.0D), 8.0D),
            8.0D
        );
        PlayerInputContext<PlayerInteractionSnapshot> context = new PlayerInputContext<>(
            playerId,
            1L,
            InputFamily.RIGHT_CLICK,
            InputSource.SYNTHETIC,
            snapshot
        );

        AstPlayer astPlayer = mock(AstPlayer.class);
        AccountModel account = mock(AccountModel.class);
        when(astPlayer.getAccount()).thenReturn(account);
        when(account.getMode()).thenReturn(AccountMode.PLAYER);
        PlayerMessageService messages = mock(PlayerMessageService.class);

        try (MockedStatic<AstPlayerCache> cache = mockStatic(AstPlayerCache.class);
             MockedStatic<PlayerMessageService> messageService = mockStatic(PlayerMessageService.class)) {
            cache.when(() -> AstPlayerCache.get(player)).thenReturn(astPlayer);
            messageService.when(PlayerMessageService::getInstance).thenReturn(messages);

            PlayerInputCandidate candidate = handler.resolve(context).stream().findFirst().orElseThrow();
            candidate.executor().run();

            verify(statusService).restoreAll(eq(astPlayer), any(HealthRecoveryContext.class));
            verify(messages).send(player, PlayerMsgId.P_5114);
        }
    }
}
