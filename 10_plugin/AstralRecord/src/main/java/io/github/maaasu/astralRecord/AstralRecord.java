package io.github.maaasu.astralRecord;

import io.github.maaasu.astralRecord.core.CommandRegister;
import io.github.maaasu.astralRecord.core.event.EventManager;
import io.github.maaasu.astralRecord.feature.adventurerecord.event.AdventureRecordGuiEventHandler;
import io.github.maaasu.astralRecord.feature.adventurerecord.gui.AdventureRecordGui;
import io.github.maaasu.astralRecord.feature.adventurerecord.repository.AdventureRecordRepository;
import io.github.maaasu.astralRecord.feature.adventurerecord.service.AdventureRecordService;
import io.github.maaasu.astralRecord.feature.account.repository.AccountRepository;
import io.github.maaasu.astralRecord.feature.account.service.AccountClassProgressSaveTask;
import io.github.maaasu.astralRecord.feature.account.service.AccountModeApplicationService;
import io.github.maaasu.astralRecord.feature.account.service.AccountService;
import io.github.maaasu.astralRecord.feature.boss.event.BossEntryEventHandler;
import io.github.maaasu.astralRecord.feature.boss.event.BossChallengeCancelEventHandler;
import io.github.maaasu.astralRecord.feature.boss.event.BossPlayerEventHandler;
import io.github.maaasu.astralRecord.feature.boss.gui.BossChallengeCancelGui;
import io.github.maaasu.astralRecord.feature.boss.service.BossChallengeService;
import io.github.maaasu.astralRecord.feature.boss.service.BossFieldInstanceService;
import io.github.maaasu.astralRecord.feature.boss.service.BossMechanicService;
import io.github.maaasu.astralRecord.feature.combat.event.CombatDamageEventHandler;
import io.github.maaasu.astralRecord.feature.combat.service.DamageService;
import io.github.maaasu.astralRecord.feature.combat.service.CombatDpsTrackerService;
import io.github.maaasu.astralRecord.feature.dungeon.event.DungeonWorldEventHandler;
import io.github.maaasu.astralRecord.feature.dungeon.event.DungeonInteractionEventHandler;
import io.github.maaasu.astralRecord.feature.dungeon.repository.DungeonDefinitionRepository;
import io.github.maaasu.astralRecord.feature.dungeon.service.DungeonService;
import io.github.maaasu.astralRecord.feature.discord.service.DiscordSrvChatBridge;
import io.github.maaasu.astralRecord.feature.discord.service.GlobalChatBridge;
import io.github.maaasu.astralRecord.feature.whitelist.event.WhitelistConnectionEventHandler;
import io.github.maaasu.astralRecord.feature.whitelist.service.WhitelistService;
import io.github.maaasu.astralRecord.feature.condition.display.ConditionDisplayService;
import io.github.maaasu.astralRecord.feature.condition.event.ConditionPlayerEventHandler;
import io.github.maaasu.astralRecord.feature.condition.service.ConditionService;
import io.github.maaasu.astralRecord.feature.condition.service.ConditionTickService;
import io.github.maaasu.astralRecord.feature.condition.task.ConditionCleanupTask;
import io.github.maaasu.astralRecord.feature.condition.task.ConditionDisplayTask;
import io.github.maaasu.astralRecord.feature.condition.task.ConditionTickTask;
import io.github.maaasu.astralRecord.feature.currency.service.CurrencyService;
import io.github.maaasu.astralRecord.feature.currency.event.CurrencyExchangeGuiEventHandler;
import io.github.maaasu.astralRecord.feature.buff.service.BuffAcquisitionDisplayService;
import io.github.maaasu.astralRecord.feature.gathering.event.GatheringInteractionEventHandler;
import io.github.maaasu.astralRecord.feature.gathering.repository.GatheringDefinitionRepository;
import io.github.maaasu.astralRecord.feature.gathering.service.GatheringService;
import io.github.maaasu.astralRecord.feature.gathering.spawner.event.GatheringSpawnerBlockEventHandler;
import io.github.maaasu.astralRecord.feature.gathering.spawner.repository.GatheringSpawnerDefinitionRepository;
import io.github.maaasu.astralRecord.feature.gathering.spawner.repository.GatheringSpawnerLocationRepository;
import io.github.maaasu.astralRecord.feature.gathering.spawner.service.GatheringSpawnerService;
import io.github.maaasu.astralRecord.feature.guide.repository.GuideRepository;
import io.github.maaasu.astralRecord.feature.guide.repository.GuideProgressRepository;
import io.github.maaasu.astralRecord.feature.guide.model.GuideConditionType;
import io.github.maaasu.astralRecord.feature.guide.service.GuideActionService;
import io.github.maaasu.astralRecord.feature.guide.service.GuideService;
import io.github.maaasu.astralRecord.feature.guide.service.GuideReminderTask;
import io.github.maaasu.astralRecord.shared.gui.event.GuiClickCooldownEventHandler;
import io.github.maaasu.astralRecord.shared.gui.navigation.GuiNavigationEventHandler;
import io.github.maaasu.astralRecord.shared.gui.navigation.GuiNavigationService;
import io.github.maaasu.astralRecord.shared.gui.session.GuiSessionTransitionEventHandler;
import io.github.maaasu.astralRecord.shared.gui.session.GuiSessionTransitionService;
import io.github.maaasu.astralRecord.shared.timing.MovementCancelableWaitService;
import io.github.maaasu.astralRecord.feature.hud.event.AdminMessageBossBarEventHandler;
import io.github.maaasu.astralRecord.feature.hud.service.AdminMessageBossBarService;
import io.github.maaasu.astralRecord.feature.hud.service.PlayerHudService;
import io.github.maaasu.astralRecord.feature.item.event.HookshotInteractionEventHandler;
import io.github.maaasu.astralRecord.feature.item.event.ItemInteractionBlockEventHandler;
import io.github.maaasu.astralRecord.feature.item.event.ItemAdminGuiEventHandler;
import io.github.maaasu.astralRecord.feature.item.event.ItemChatShareEventHandler;
import io.github.maaasu.astralRecord.feature.item.event.ItemWeaponAttackEventHandler;
import io.github.maaasu.astralRecord.feature.item.gui.ItemAdminGuiView;
import io.github.maaasu.astralRecord.feature.item.executor.WeaponAttackSkillExecutor;
import io.github.maaasu.astralRecord.feature.item.service.BuiltInWeaponAttackDefinitions;
import io.github.maaasu.astralRecord.feature.item.service.BundleUseEffectService;
import io.github.maaasu.astralRecord.feature.item.service.BundleUseService;
import io.github.maaasu.astralRecord.feature.item.service.EquipmentDurabilityService;
import io.github.maaasu.astralRecord.feature.item.service.HookshotUseService;
import io.github.maaasu.astralRecord.feature.item.service.ItemDropAnimationService;
import io.github.maaasu.astralRecord.feature.item.service.ItemChatShareService;
import io.github.maaasu.astralRecord.feature.item.service.ItemWeaponAttackService;
import io.github.maaasu.astralRecord.feature.item.service.OrbService;
import io.github.maaasu.astralRecord.feature.item.service.PotionUseService;
import io.github.maaasu.astralRecord.feature.inventory.repository.InventoryRepository;
import io.github.maaasu.astralRecord.feature.inventory.repository.EquipmentLoadoutRepository;
import io.github.maaasu.astralRecord.feature.inventory.event.InventoryEquipmentGuiEventHandler;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryAutoSaveTask;
import io.github.maaasu.astralRecord.feature.inventory.service.InventorySaveCoordinator;
import io.github.maaasu.astralRecord.feature.inventory.service.InventorySaveTask;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.inventory.state.InventoryPersistence;
import io.github.maaasu.astralRecord.feature.inventory.state.PlayerInventoryStateRegistry;
import io.github.maaasu.astralRecord.feature.item.service.ItemService;
import io.github.maaasu.astralRecord.feature.item.service.ItemStackFactory;
import io.github.maaasu.astralRecord.feature.item.view.ItemStackPacketAdapter;
import io.github.maaasu.astralRecord.feature.loginbonus.event.LoginBonusGuiEventHandler;
import io.github.maaasu.astralRecord.feature.loginbonus.repository.LoginBonusClaimRepository;
import io.github.maaasu.astralRecord.feature.loginbonus.service.LoginBonusService;
import io.github.maaasu.astralRecord.feature.loginbonus.view.LoginBonusGui;
import io.github.maaasu.astralRecord.feature.loot.service.LootService;
import io.github.maaasu.astralRecord.feature.mail.event.MailGuiEventHandler;
import io.github.maaasu.astralRecord.feature.mail.gui.MailGuiView;
import io.github.maaasu.astralRecord.feature.mail.repository.MailRepository;
import io.github.maaasu.astralRecord.feature.mail.service.MailService;
import io.github.maaasu.astralRecord.feature.market.event.MarketGuiEventHandler;
import io.github.maaasu.astralRecord.feature.market.repository.MarketRepository;
import io.github.maaasu.astralRecord.feature.market.service.MarketService;
import io.github.maaasu.astralRecord.feature.menu.event.MenuOpenEventHandler;
import io.github.maaasu.astralRecord.feature.menu.service.MenuGuiTransitionService;
import io.github.maaasu.astralRecord.feature.menu.service.MenuToolJoinGrantService;
import io.github.maaasu.astralRecord.feature.menu.service.PlayerGuiRenderContextFactory;
import io.github.maaasu.astralRecord.feature.menu.service.TrashService;
import io.github.maaasu.astralRecord.feature.menu.player.PlayerBrowserGuiEventHandler;
import io.github.maaasu.astralRecord.feature.menu.player.PlayerDetailGui;
import io.github.maaasu.astralRecord.feature.menu.player.PlayerListGui;
import io.github.maaasu.astralRecord.feature.menu.view.MenuView;
import io.github.maaasu.astralRecord.feature.mob.repository.MobRepository;
import io.github.maaasu.astralRecord.feature.mob.event.MobInteractionEventHandler;
import io.github.maaasu.astralRecord.feature.mob.event.MobVanillaDamageBlockEventHandler;
import io.github.maaasu.astralRecord.feature.mob.event.NpcPlacementWorldEventHandler;
import io.github.maaasu.astralRecord.feature.mob.repository.NpcPlacementRepository;
import io.github.maaasu.astralRecord.feature.mob.service.MobAiService;
import io.github.maaasu.astralRecord.feature.mob.service.MobProjectileService;
import io.github.maaasu.astralRecord.feature.mob.skill.MobSkillRegistry;
import io.github.maaasu.astralRecord.feature.mob.skill.skeletonarcher.SkeletonArcherBowShotMobSkillExecutor;
import io.github.maaasu.astralRecord.feature.mob.skill.twilightcolossus.TwilightColossusGateSlamSkillExecutor;
import io.github.maaasu.astralRecord.feature.mob.skill.twilightcolossus.TwilightColossusRuneBoltSkillExecutor;
import io.github.maaasu.astralRecord.feature.mob.service.MobSkillService;
import io.github.maaasu.astralRecord.feature.mob.service.MobCombatService;
import io.github.maaasu.astralRecord.feature.mob.service.MobDropPresentationService;
import io.github.maaasu.astralRecord.feature.mob.service.MobDropService;
import io.github.maaasu.astralRecord.feature.mob.service.MobKnockbackService;
import io.github.maaasu.astralRecord.feature.mob.service.MobService;
import io.github.maaasu.astralRecord.feature.mob.service.MobVanillaEffectProtectionService;
import io.github.maaasu.astralRecord.feature.mob.service.NpcPlacementService;
import io.github.maaasu.astralRecord.feature.spawner.event.MobSpawnerBlockEventHandler;
import io.github.maaasu.astralRecord.feature.spawner.repository.MobSpawnerDefinitionRepository;
import io.github.maaasu.astralRecord.feature.spawner.repository.MobSpawnerLocationRepository;
import io.github.maaasu.astralRecord.feature.spawner.service.MobSpawnerService;
import io.github.maaasu.astralRecord.feature.party.event.PartyGuiEventHandler;
import io.github.maaasu.astralRecord.feature.party.event.PartyQuitEventHandler;
import io.github.maaasu.astralRecord.feature.party.gui.PartyGui;
import io.github.maaasu.astralRecord.feature.party.gui.PartyMemberActionGui;
import io.github.maaasu.astralRecord.feature.party.service.PartyService;
import io.github.maaasu.astralRecord.feature.player.event.PlayerJoinEventHandler;
import io.github.maaasu.astralRecord.feature.player.event.ManagedChatEventHandler;
import io.github.maaasu.astralRecord.feature.player.event.PlayerModeEventHandler;
import io.github.maaasu.astralRecord.feature.player.event.PlayerInputEventHandler;
import io.github.maaasu.astralRecord.feature.player.event.PlayerSneakEventHandler;
import io.github.maaasu.astralRecord.feature.player.event.PlayerVanillaDamageBlockEventHandler;
import io.github.maaasu.astralRecord.feature.player.death.PlayerDeathEventHandler;
import io.github.maaasu.astralRecord.feature.player.death.PlayerDeathService;
import io.github.maaasu.astralRecord.feature.player.save.PlayerSaveCoordinator;
import io.github.maaasu.astralRecord.feature.player.service.AirActionService;
import io.github.maaasu.astralRecord.feature.player.service.DodgeService;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.feature.player.service.PlayerRegionService;
import io.github.maaasu.astralRecord.feature.player.service.PlayerService;
import io.github.maaasu.astralRecord.feature.player.service.StoneButtonReachService;
import io.github.maaasu.astralRecord.feature.playerclass.PlayerClassService;
import io.github.maaasu.astralRecord.feature.quest.event.QuestGuiEventHandler;
import io.github.maaasu.astralRecord.feature.quest.gui.QuestGui;
import io.github.maaasu.astralRecord.feature.quest.repository.QuestBoardRepository;
import io.github.maaasu.astralRecord.feature.quest.repository.QuestDefinitionRepository;
import io.github.maaasu.astralRecord.feature.quest.repository.QuestPlayerStateRepository;
import io.github.maaasu.astralRecord.feature.quest.service.QuestService;
import io.github.maaasu.astralRecord.feature.playersetting.cache.PlayerSettingCache;
import io.github.maaasu.astralRecord.feature.playersetting.event.PlayerSettingGuiEventHandler;
import io.github.maaasu.astralRecord.feature.playersetting.event.PlayerSettingJoinEventHandler;
import io.github.maaasu.astralRecord.feature.playersetting.gui.PlayerSettingGui;
import io.github.maaasu.astralRecord.feature.playersetting.repository.PlayerSettingRepository;
import io.github.maaasu.astralRecord.feature.playersetting.service.PlayerSettingDefaults;
import io.github.maaasu.astralRecord.feature.playersetting.service.PlayerSettingService;
import io.github.maaasu.astralRecord.feature.resourcepack.event.ResourcePackJoinEventHandler;
import io.github.maaasu.astralRecord.feature.resourcepack.event.ResourcePackStatusEventHandler;
import io.github.maaasu.astralRecord.feature.resourcepack.service.ResourcePackService;
import io.github.maaasu.astralRecord.feature.sell.service.SellService;
import io.github.maaasu.astralRecord.feature.skill.active.event.ActiveSkillLifecycleEventHandler;
import io.github.maaasu.astralRecord.feature.skill.active.service.ActiveSkillLifecycleService;
import io.github.maaasu.astralRecord.feature.skill.active.service.ActiveSkillServices;
import io.github.maaasu.astralRecord.feature.skill.active.service.SkillCombatService;
import io.github.maaasu.astralRecord.feature.skill.active.service.SkillEffectService;
import io.github.maaasu.astralRecord.feature.skill.active.service.SkillMovementService;
import io.github.maaasu.astralRecord.feature.skill.active.service.SkillProjectileService;
import io.github.maaasu.astralRecord.feature.skill.active.service.SkillTargetingService;
import io.github.maaasu.astralRecord.feature.skill.active.service.SkillTaskService;
import io.github.maaasu.astralRecord.feature.skill.active.service.TemporarySkillEffectService;
import io.github.maaasu.astralRecord.feature.skill.event.SkillActionRingEventHandler;
import io.github.maaasu.astralRecord.feature.skill.event.MeditationSkillEventHandler;
import io.github.maaasu.astralRecord.feature.skill.service.SkillService;
import io.github.maaasu.astralRecord.feature.skill.event.SkillBindGuiEventHandler;
import io.github.maaasu.astralRecord.feature.skill.event.SkillGemLearnEventHandler;
import io.github.maaasu.astralRecord.feature.skill.event.SkillForgetGuiEventHandler;
import io.github.maaasu.astralRecord.feature.skill.executor.FireBoostSkillExecutor;
import io.github.maaasu.astralRecord.feature.skill.executor.IronWillSkillExecutor;
import io.github.maaasu.astralRecord.feature.skill.executor.AdministratorShieldRechargeSkillExecutor;
import io.github.maaasu.astralRecord.feature.skill.executor.MeditationSkillExecutor;
import io.github.maaasu.astralRecord.feature.skill.executor.StatusPassiveSkillExecutor;
import io.github.maaasu.astralRecord.feature.skill.executor.active.ActiveSkillExecutorCatalog;
import io.github.maaasu.astralRecord.feature.skill.executor.active.swordsman.SwordsmanBladeCounterRuntimeService;
import io.github.maaasu.astralRecord.feature.skill.gui.SkillBindGui;
import io.github.maaasu.astralRecord.feature.skill.gui.SkillForgetGui;
import io.github.maaasu.astralRecord.feature.skill.registry.SkillRegistry;
import io.github.maaasu.astralRecord.feature.skill.repository.SkillBindPresetRepository;
import io.github.maaasu.astralRecord.feature.skill.repository.LearnedSkillRepository;
import io.github.maaasu.astralRecord.feature.skill.repository.SkillRepository;
import io.github.maaasu.astralRecord.feature.skill.service.LearnedSkillResolver;
import io.github.maaasu.astralRecord.feature.skill.service.LearnedSkillService;
import io.github.maaasu.astralRecord.feature.skill.service.MeditationSkillRuntimeService;
import io.github.maaasu.astralRecord.feature.skill.service.PassiveSkillService;
import io.github.maaasu.astralRecord.feature.skill.service.SkillActionRingHoldService;
import io.github.maaasu.astralRecord.feature.skill.service.SkillActionRingService;
import io.github.maaasu.astralRecord.feature.skill.service.SkillBindPresetService;
import io.github.maaasu.astralRecord.feature.skill.service.SkillCooldownBossBarService;
import io.github.maaasu.astralRecord.feature.skill.service.SkillOwnershipService;
import io.github.maaasu.astralRecord.feature.skill.service.SkillPermissionService;
import io.github.maaasu.astralRecord.feature.skilltree.event.SkillTreeEventHandler;
import io.github.maaasu.astralRecord.feature.skilltree.repository.SkillTreeNodeRepository;
import io.github.maaasu.astralRecord.feature.skilltree.repository.SkillTreePlayerStateRepository;
import io.github.maaasu.astralRecord.feature.skilltree.repository.SkillTreeStructureRepository;
import io.github.maaasu.astralRecord.feature.skilltree.service.SkillTreeService;
import io.github.maaasu.astralRecord.feature.shop.event.ShopGuiEventHandler;
import io.github.maaasu.astralRecord.feature.shop.gui.ShopGui;
import io.github.maaasu.astralRecord.feature.shop.repository.ShopRecipeRepository;
import io.github.maaasu.astralRecord.feature.shop.repository.ShopRepository;
import io.github.maaasu.astralRecord.feature.shop.service.ShopService;
import io.github.maaasu.astralRecord.feature.status.service.StatusRegenTask;
import io.github.maaasu.astralRecord.feature.status.service.StatusService;
import io.github.maaasu.astralRecord.feature.storage.service.StorageService;
import io.github.maaasu.astralRecord.feature.status.event.PlayerHeldItemStatusEventHandler;
import io.github.maaasu.astralRecord.feature.textdisplay.event.TextDisplayPlacementWorldEventHandler;
import io.github.maaasu.astralRecord.feature.textdisplay.repository.TextDisplayPlacementRepository;
import io.github.maaasu.astralRecord.feature.textdisplay.service.TextDisplayPlacementService;
import io.github.maaasu.astralRecord.feature.teleporter.event.TeleporterGuiEventHandler;
import io.github.maaasu.astralRecord.feature.teleporter.event.TeleporterInteractEventHandler;
import io.github.maaasu.astralRecord.feature.teleporter.event.TeleporterPlayerEventHandler;
import io.github.maaasu.astralRecord.feature.teleporter.gui.TeleporterGui;
import io.github.maaasu.astralRecord.feature.teleporter.repository.AccountWaystoneRepository;
import io.github.maaasu.astralRecord.feature.teleporter.repository.WaystoneDefinitionRepository;
import io.github.maaasu.astralRecord.feature.teleporter.service.TeleporterService;
import io.github.maaasu.astralRecord.feature.teleporter.service.WaystoneHitBoxResolver;
import io.github.maaasu.astralRecord.feature.teleporter.view.WaystonePacketView;
import io.github.maaasu.astralRecord.feature.trade.event.TradeGuiEventHandler;
import io.github.maaasu.astralRecord.feature.trade.gui.TradeCancelConfirmGui;
import io.github.maaasu.astralRecord.feature.trade.gui.TradeGui;
import io.github.maaasu.astralRecord.feature.trade.service.TradeService;
import io.github.maaasu.astralRecord.feature.trainingdummy.event.TrainingDummyGuiEventHandler;
import io.github.maaasu.astralRecord.feature.trainingdummy.event.TrainingDummyInputResolver;
import io.github.maaasu.astralRecord.feature.trainingdummy.gui.TrainingDummyGui;
import io.github.maaasu.astralRecord.feature.trainingdummy.repository.TrainingDummyRepository;
import io.github.maaasu.astralRecord.feature.trainingdummy.service.TrainingDummyService;
import io.github.maaasu.astralRecord.shared.gui.gold.GoldAmountSettingGui;
import io.github.maaasu.astralRecord.feature.user.event.UserLoginEventHandler;
import io.github.maaasu.astralRecord.feature.user.repository.UserRepository;
import io.github.maaasu.astralRecord.feature.user.service.UserService;
import io.github.maaasu.astralRecord.feature.world.config.PluginJoinSpawnWorldConfig;
import io.github.maaasu.astralRecord.feature.world.event.BaseWorldGatewayEventHandler;
import io.github.maaasu.astralRecord.feature.world.event.BaseWorldSpawnTeleportEventHandler;
import io.github.maaasu.astralRecord.feature.world.event.ChallengeWaitingHubEventHandler;
import io.github.maaasu.astralRecord.feature.world.event.OverworldTeleportGuiEventHandler;
import io.github.maaasu.astralRecord.feature.world.event.OverworldSpawnReturnEventHandler;
import io.github.maaasu.astralRecord.feature.world.event.WorldChangeTitleEventHandler;
import io.github.maaasu.astralRecord.feature.world.event.WorldJoinSpawnEventHandler;
import io.github.maaasu.astralRecord.feature.world.event.WorldNaturalSpawnBlockEventHandler;
import io.github.maaasu.astralRecord.feature.world.gui.OverworldTeleportGui;
import io.github.maaasu.astralRecord.feature.world.repository.WorldRepository;
import io.github.maaasu.astralRecord.feature.world.service.OverworldTeleportService;
import io.github.maaasu.astralRecord.feature.world.service.ReturnToBaseService;
import io.github.maaasu.astralRecord.feature.world.service.WorldService;
import io.github.maaasu.astralRecord.feature.world.service.WorldSpawnParticleTask;
import io.github.maaasu.astralRecord.infrastructure.command.CommandManager;
import io.github.maaasu.astralRecord.infrastructure.config.ConfigManager;
import io.github.maaasu.astralRecord.infrastructure.config.ConfigProperties;
import io.github.maaasu.astralRecord.infrastructure.api.ApiHealthChecker;
import io.github.maaasu.astralRecord.infrastructure.database.file.FileDatabaseManager;
import io.github.maaasu.astralRecord.infrastructure.database.file.yaml.config.YamlDbConfig;
import io.github.maaasu.astralRecord.infrastructure.database.file.yaml.config.YamlDbConfigUtil;
import io.github.maaasu.astralRecord.infrastructure.database.sqlserver.SqlServerManager;
import io.github.maaasu.astralRecord.infrastructure.logging.AuditLogger;
import io.github.maaasu.astralRecord.infrastructure.logging.AuditLoggerRegistry;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import io.github.maaasu.astralRecord.infrastructure.util.AsyncTaskUtil;
import io.github.maaasu.astralRecord.shared.display.DisplayTextService;
import io.github.maaasu.astralRecord.shared.challenge.InstanceCreationQueue;
import io.github.maaasu.astralRecord.shared.challenge.InstanceCreationQueueConfig;
import io.github.maaasu.astralRecord.shared.display.OverheadDisplayService;
import io.github.maaasu.astralRecord.shared.effect.ParticleDisplayService;
import io.github.maaasu.astralRecord.shared.interaction.PlayerInteractionGatewayEventHandler;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public final class AstralRecord extends JavaPlugin {

    private static AstralRecord instance;

    // feature services
    private ItemService itemService;
    private LootService lootService;
    private ItemStackFactory itemStackFactory;
    private AccountService accountService;
    private AccountModeApplicationService accountModeApplicationService;
    private UserService userService;
    private PlayerService playerService;
    private PlayerMessageService playerMessageService;
    private GlobalChatBridge globalChatBridge;
    private PlayerRegionService playerRegionService;
    private InventoryService inventoryService;
    private InventorySaveCoordinator inventorySaveCoordinator;
    private InventoryPersistence inventoryPersistence;
    private PlayerInventoryStateRegistry inventoryStateRegistry;
    private InventoryAutoSaveTask inventoryAutoSaveTask;
    private CurrencyService currencyService;
    private CurrencyExchangeGuiEventHandler currencyExchangeGuiEventHandler;
    private StatusService statusService;
    private StatusRegenTask statusRegenTask;
    private DodgeService dodgeService;
    private AirActionService airActionService;
    private PlayerHudService playerHudService;
    private PlayerDeathService playerDeathService;
    private ResourcePackService resourcePackService;
    private GuideService guideService;
    private GuideActionService guideActionService;
    private GuideReminderTask guideReminderTask;
    private PlayerGuiRenderContextFactory playerGuiRenderContextFactory;
    private MenuView menuView;
    private MenuOpenEventHandler menuOpenEventHandler;
    private MenuGuiTransitionService menuGuiTransitionService;
    private GuiSessionTransitionService guiSessionTransitionService;
    private GuiNavigationService guiNavigationService;
    private TrashService trashService;
    private SellService sellService;
    private StorageService storageService;
    private PlayerListGui playerListGui;
    private PlayerDetailGui playerDetailGui;
    private PlayerBrowserGuiEventHandler playerBrowserGuiEventHandler;
    private MobService mobService;
    private TrainingDummyService trainingDummyService;
    private TrainingDummyGui trainingDummyGui;
    private MobSpawnerService mobSpawnerService;
    private GatheringService gatheringService;
    private GatheringSpawnerService gatheringSpawnerService;
    private NpcPlacementService npcPlacementService;
    private MobAiService mobAiService;
    private MobCombatService mobCombatService;
    private MobSkillService mobSkillService;
    private MobProjectileService mobProjectileService;
    private MobVanillaEffectProtectionService mobVanillaEffectProtectionService;
    private MobDropPresentationService mobDropPresentationService;
    private EventManager eventManager;
    private ParticleDisplayService particleDisplayService;
    private DisplayTextService displayTextService;
    private AdminMessageBossBarService adminMessageBossBarService;
    private TextDisplayPlacementService textDisplayPlacementService;
    private TeleporterService teleporterService;
    private TeleporterGui teleporterGui;
    private TeleporterGuiEventHandler teleporterGuiEventHandler;
    private WaystonePacketView waystonePacketView;
    private WaystoneHitBoxResolver waystoneHitBoxResolver;
    private OverheadDisplayService overheadDisplayService;
    private PlayerSettingService playerSettingService;
    private PlayerSettingGui playerSettingGui;
    private ItemStackPacketAdapter itemStackPacketAdapter;
    private SkillService skillService;
    private SkillActionRingService skillActionRingService;
    private SkillActionRingHoldService skillActionRingHoldService;
    private SkillCooldownBossBarService skillCooldownBossBarService;
    private PassiveSkillService passiveSkillService;
    private MeditationSkillRuntimeService meditationSkillRuntimeService;
    private SkillTreeService skillTreeService;
    private SkillBindPresetService skillBindPresetService;
    private LearnedSkillService learnedSkillService;
    private SkillOwnershipService skillOwnershipService;
    private SkillPermissionService skillPermissionService;
    private SkillBindGui skillBindGui;
    private SkillBindGuiEventHandler skillBindGuiEventHandler;
    private ActiveSkillLifecycleService activeSkillLifecycleService;
    private SkillTaskService activeSkillTaskService;
    private TemporarySkillEffectService temporarySkillEffectService;
    private SwordsmanBladeCounterRuntimeService swordsmanBladeCounterRuntimeService;
    private DamageService damageService;
    private CombatDpsTrackerService combatDpsTrackerService;
    private ConditionService conditionService;
    private ConditionDisplayService conditionDisplayService;
    private ConditionTickTask conditionTickTask;
    private ConditionDisplayTask conditionDisplayTask;
    private ConditionCleanupTask conditionCleanupTask;
    private BundleUseService bundleUseService;
    private BundleUseEffectService bundleUseEffectService;
    private ItemDropAnimationService itemDropAnimationService;
    private MovementCancelableWaitService movementCancelableWaitService;
    private BuffAcquisitionDisplayService buffAcquisitionDisplayService;
    private PotionUseService potionUseService;
    private PlayerClassService playerClassService;
    private ItemWeaponAttackService itemWeaponAttackService;
    private EquipmentDurabilityService equipmentDurabilityService;
    private HookshotUseService hookshotUseService;
    private OrbService orbService;
    private WorldService worldService;
    private OverworldTeleportService overworldTeleportService;
    private OverworldTeleportGui overworldTeleportGui;
    private OverworldTeleportGuiEventHandler overworldTeleportGuiEventHandler;
    private ReturnToBaseService returnToBaseService;
    private WorldSpawnParticleTask worldSpawnParticleTask;
    private StoneButtonReachService stoneButtonReachService;
    private PartyService partyService;
    private PartyGui partyGui;
    private PartyMemberActionGui partyMemberActionGui;
    private LoginBonusService loginBonusService;
    private MailService mailService;
    private MailGuiEventHandler mailGuiEventHandler;
    private ItemAdminGuiEventHandler itemAdminGuiEventHandler;
    private AdventureRecordService adventureRecordService;
    private AdventureRecordGuiEventHandler adventureRecordGuiEventHandler;
    private ShopService shopService;
    private ShopGui shopGui;
    private ShopGuiEventHandler shopGuiEventHandler;
    private QuestService questService;
    private QuestGui questGui;
    private QuestGuiEventHandler questGuiEventHandler;
    private TradeService tradeService;
    private TradeGui tradeGui;
    private TradeCancelConfirmGui tradeCancelConfirmGui;
    private GoldAmountSettingGui goldAmountSettingGui;
    private MarketService marketService;
    private MarketGuiEventHandler marketGuiEventHandler;
    private BossFieldInstanceService bossFieldInstanceService;
    private BossChallengeService bossChallengeService;
    private BossMechanicService bossMechanicService;
    private BossChallengeCancelGui bossChallengeCancelGui;
    private DungeonService dungeonService;
    private String joinSpawnWorldId;
    private final AtomicReference<CompletableFuture<Integer>> masterDataReloadInFlight = new AtomicReference<>();
    private final AtomicLong masterDataReloadGeneration = new AtomicLong();

    @Override
    public void onLoad() {
        instance = this;
        adminMessageBossBarService = new AdminMessageBossBarService(this);
        itemService = new ItemService();
        lootService = new LootService();
        itemStackFactory = new ItemStackFactory(lootService, itemService);
        mobService = new MobService(this, new MobRepository());
        trainingDummyService = new TrainingDummyService(this, mobService, new TrainingDummyRepository(this));
        trainingDummyGui = new TrainingDummyGui();
        npcPlacementService = new NpcPlacementService(this, mobService, new NpcPlacementRepository(this));
        textDisplayPlacementService = new TextDisplayPlacementService(this, new TextDisplayPlacementRepository(this));
        teleporterService = new TeleporterService(this, new WaystoneDefinitionRepository(this), new AccountWaystoneRepository());
        worldService = new WorldService(new WorldRepository());
        playerRegionService = new PlayerRegionService(this, worldService);
        mobSpawnerService = new MobSpawnerService(
                this,
                mobService,
                playerRegionService,
                new MobSpawnerDefinitionRepository(),
                new MobSpawnerLocationRepository(this)
        );
        gatheringService = new GatheringService(
                this,
                new GatheringDefinitionRepository(),
                new MobDropService(lootService),
                itemService,
                null
        );
        gatheringSpawnerService = new GatheringSpawnerService(
                this,
                gatheringService,
                new GatheringSpawnerDefinitionRepository(),
                new GatheringSpawnerLocationRepository(this)
        );
        skillTreeService = new SkillTreeService(
                this,
                worldService,
                null,
                new SkillTreeNodeRepository(),
                new SkillTreeStructureRepository(),
                new SkillTreePlayerStateRepository(this)
        );
        joinSpawnWorldId = PluginJoinSpawnWorldConfig.load(this);
        // CommandManager は Paper Lifecycle API の制約に合わせて
        // onLoad() で初期化し、コマンド登録クラスを先に生成する。
        new CommandRegister(
                itemService,
                itemStackFactory,
                mobService,
                mobSpawnerService,
                npcPlacementService,
                worldService,
                skillTreeService,
                gatheringService,
                gatheringSpawnerService,
                textDisplayPlacementService,
                teleporterService,
                trainingDummyService,
                trainingDummyGui,
                adminMessageBossBarService,
                () -> particleDisplayService
        );
        CommandManager.getInstance().initialize(this);
    }

    @Override
    public void onEnable() {
        // AuditLogger を初期化
        AuditLogger.initDefault();

        // すべての LogEntry 実装クラスを走査し、AuditLogger を登録する
        AuditLoggerRegistry.init("io.github.maaasu.astralRecord");

        if (!setupInfrastructure()) {
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // 3. feature を初期化
        eventManager = new EventManager(this);
        eventManager.registerHandler(
            new NpcPlacementWorldEventHandler(this, npcPlacementService),
            getServer().getPluginManager()
        );
        eventManager.registerHandler(
            new TextDisplayPlacementWorldEventHandler(this, textDisplayPlacementService),
            getServer().getPluginManager()
        );
        setupFeature();
        setupDiscordChatBridge();

        // 4. イベントとコマンドを登録
        registerPluginFeatures();
    }

    @Override
    public void onDisable() {
        if (globalChatBridge != null) {
            globalChatBridge.close();
            globalChatBridge = null;
        }
        masterDataReloadGeneration.incrementAndGet();
        CompletableFuture<Integer> pendingMasterDataReload = masterDataReloadInFlight.getAndSet(null);
        if (pendingMasterDataReload != null && !pendingMasterDataReload.isDone()) {
            pendingMasterDataReload.completeExceptionally(
                new IllegalStateException("マスターデータ再読込中にプラグインが停止しました")
            );
        }
        if (inventoryAutoSaveTask != null) {
            inventoryAutoSaveTask.stop();
        }
        if (guideReminderTask != null) {
            guideReminderTask.stop();
        }
        if (questService != null) {
            questService.stop();
        }
        if (accountService != null) {
            accountService.stop();
        }
        if (tradeService != null) {
            tradeService.cancelAll();
        }
        if (orbService != null) {
            orbService.prepareAllForShutdown();
        }
        if (inventorySaveCoordinator != null) {
            // accepted済み操作・正本照合を先に待つ。main threadへ戻った後に現在装備表示を再構築し、
            // 解決済みaccountだけの停止snapshotを取得する。
            inventorySaveCoordinator.awaitPendingWrites(5000L);
        }
        if (stoneButtonReachService != null) {
            stoneButtonReachService.stop();
        }
        if (playerService != null) {
            // accepted済みorb operationの後ろへ停止保存を全件登録してからlane受付を閉じる。
            // timeout/失敗時は正本未照合stateを直接保存・clearしない。
            playerService.saveAllOnlinePlayersAndClear();
        }
        if (inventorySaveCoordinator != null) {
            inventorySaveCoordinator.beginClosing();
            inventorySaveCoordinator.awaitPendingWrites(5000L);
        }
        if (inventoryService != null) {
            inventoryService.awaitPendingWrites(5000L);
        }
        if (airActionService != null) {
            airActionService.stop();
        }
        if (playerHudService != null) {
            playerHudService.stop();
        }
        if (skillCooldownBossBarService != null) {
            skillCooldownBossBarService.stop();
        }
        if (adminMessageBossBarService != null) {
            adminMessageBossBarService.stop();
        }
        if (statusRegenTask != null) {
            statusRegenTask.stop();
        }
        if (overheadDisplayService != null) {
            overheadDisplayService.stop();
        }
        if (bossMechanicService != null) {
            bossMechanicService.stop();
        }
        if (dungeonService != null) {
            dungeonService.stop();
        }
        if (bossChallengeService != null) {
            bossChallengeService.stop();
        }
        if (playerDeathService != null) {
            playerDeathService.stop();
        }
        if (conditionTickTask != null) {
            conditionTickTask.stop();
        }
        if (conditionDisplayTask != null) {
            conditionDisplayTask.stop();
        }
        if (conditionCleanupTask != null) {
            conditionCleanupTask.stop();
        }
        if (conditionService != null) {
            conditionService.clearAllRuntimeState();
        }
        if (damageService != null) {
            damageService.stop();
        }
        if (textDisplayPlacementService != null) {
            textDisplayPlacementService.saveIfDirty();
            textDisplayPlacementService.stop();
        }
        if (teleporterService != null) {
            teleporterService.stop();
        }
        if (displayTextService != null) {
            displayTextService.stop();
        }
        if (mobAiService != null) {
            mobAiService.stop();
        }
        if (mobSkillService != null) {
            mobSkillService.stop();
        }
        if (mobProjectileService != null) {
            mobProjectileService.stop();
        }
        if (trainingDummyService != null) {
            trainingDummyService.stop();
        }
        if (mobSpawnerService != null) {
            mobSpawnerService.stop();
        }
        if (gatheringSpawnerService != null) {
            gatheringSpawnerService.stop();
        }
        if (gatheringService != null) {
            gatheringService.stop();
        }
        if (npcPlacementService != null) {
            npcPlacementService.saveIfDirty();
        }
        if (worldSpawnParticleTask != null) {
            worldSpawnParticleTask.stop();
        }
        if (partyService != null) {
            partyService.clearAll();
        }
        if (returnToBaseService != null) {
            returnToBaseService.cancelAll();
        }
        if (hookshotUseService != null) {
            hookshotUseService.shutdown();
        }
        if (activeSkillTaskService != null) {
            activeSkillTaskService.stop();
        }
        if (temporarySkillEffectService != null) {
            temporarySkillEffectService.clearAll();
        }
        if (swordsmanBladeCounterRuntimeService != null) {
            swordsmanBladeCounterRuntimeService.stop();
        }
        if (skillActionRingHoldService != null) {
            skillActionRingHoldService.stop();
        }
        if (skillActionRingService != null) {
            skillActionRingService.stop();
        }
        if (passiveSkillService != null) {
            passiveSkillService.stop();
        }
        if (meditationSkillRuntimeService != null) {
            meditationSkillRuntimeService.clearAll();
        }
        if (skillTreeService != null) {
            skillTreeService.stop();
        }
        if (skillService != null) {
            skillService.stop();
        }
        if (mobService != null) {
            mobService.destroyAll();
        }
        // AuditLogger をシャットダウン
        AuditLogger.shutdownDefault();
        AuditLoggerRegistry.shutdownAll();
        // コマンドマネージャーをシャットダウン
        CommandManager.getInstance().shutdown();
        getServer().getScheduler().cancelTasks(this);
        if (ConfigProperties.getInstance().isSqlserverEnabled()) {
            SqlServerManager.getInstance().shutdown();
        }
    }

    /**
     * 設定ファイル、データベース接続、基盤サービス初期化をセットアップします。
     *
     * @return 成功した場合は true
     */
    private boolean setupInfrastructure() {
        try {
            // 設定ファイルを初期化
            ConfigManager.getInstance().initialize();


            // DB を初期化
            if (ConfigProperties.getInstance().isSqlserverEnabled()) {
                SqlServerManager.getInstance().initialize();
            } else {
                Logger.log(LogId.I_1104);
            }

            // フォルダ型データベースを初期化
            FileDatabaseManager.getInstance();

            // YamlDB 設定を初期化
            YamlDbConfigUtil.INSTANCE.reload();

            // AstralRecord API 疎通確認を非同期で開始
            ApiHealthChecker.checkAsync();

            return true;
        }catch (Exception e) {
            Logger.log(LogId.E_900, e);
            return false;
        }
    }

    /**
     * プラグインの feature 群をセットアップします。
     */
    private void setupFeature() {
        // account
        var accountRepository = new AccountRepository();
        accountService = new AccountService(this, accountRepository);

        // user
        var userRepository = new UserRepository();
        userService = new UserService(userRepository, accountService);
        partyService = new PartyService(this, userService);
        partyGui = new PartyGui(partyService);

        // inventory
        var inventoryRepository = new InventoryRepository();
        var equipmentLoadoutRepository = new EquipmentLoadoutRepository();
        inventoryStateRegistry = new PlayerInventoryStateRegistry();
        inventoryPersistence = new InventoryPersistence(inventoryRepository, equipmentLoadoutRepository, itemService);
        inventorySaveCoordinator = new InventorySaveCoordinator(
            inventoryPersistence,
            inventoryStateRegistry,
            task -> getServer().getScheduler().runTaskAsynchronously(this, task)
        );
        inventoryService = new InventoryService(
            inventoryRepository,
            equipmentLoadoutRepository,
            itemService,
            itemStackFactory,
            inventoryStateRegistry,
            inventoryPersistence,
            inventorySaveCoordinator
        );
        accountModeApplicationService = new AccountModeApplicationService(accountService, inventoryService);
        skillTreeService.setInventoryService(inventoryService);
        currencyService = new CurrencyService(inventoryService, itemService);
        currencyExchangeGuiEventHandler = new CurrencyExchangeGuiEventHandler(currencyService);
        playerSettingService = new PlayerSettingService(
            new PlayerSettingRepository(),
            new PlayerSettingDefaults(),
            new PlayerSettingCache()
        );
        // class
        playerClassService = new PlayerClassService(accountService);
        itemStackFactory.setPlayerClassService(playerClassService);
        playerClassService.setSkillTreeService(skillTreeService);
        skillTreeService.setPlayerClassService(playerClassService);

        playerMessageService = new PlayerMessageService();
        inventoryAutoSaveTask = new InventoryAutoSaveTask(
            inventoryService,
            inventorySaveCoordinator,
            inventoryStateRegistry,
            playerSettingService,
            playerMessageService
        );
        adventureRecordService = new AdventureRecordService(
            this,
            new AdventureRecordRepository(),
            mobService,
            playerSettingService
        );
        particleDisplayService = new ParticleDisplayService(playerSettingService);
        hookshotUseService = new HookshotUseService(this, inventoryService, itemService, particleDisplayService);
        mobSpawnerService.setParticleDisplayService(particleDisplayService);
        gatheringSpawnerService.setParticleDisplayService(particleDisplayService);
        displayTextService = new DisplayTextService();
        textDisplayPlacementService.setDisplayTextService(displayTextService);
        waystonePacketView = new WaystonePacketView(teleporterService);
        teleporterGui = new TeleporterGui(teleporterService);
        teleporterGuiEventHandler = new TeleporterGuiEventHandler(teleporterGui, teleporterService, inventoryService);
        waystoneHitBoxResolver = new WaystoneHitBoxResolver(teleporterService);
        teleporterService.setRuntimeServices(inventoryService, worldService, waystonePacketView, teleporterGui, teleporterGuiEventHandler, particleDisplayService);
        overworldTeleportService = new OverworldTeleportService(this, worldService);
        overworldTeleportGui = new OverworldTeleportGui();
        overworldTeleportGuiEventHandler = new OverworldTeleportGuiEventHandler(overworldTeleportGui, overworldTeleportService);
        movementCancelableWaitService = new MovementCancelableWaitService(this);
        bundleUseEffectService = new BundleUseEffectService();
        itemDropAnimationService = new ItemDropAnimationService(this, itemStackFactory, particleDisplayService);
        bundleUseService = new BundleUseService(
            movementCancelableWaitService,
            itemService,
            lootService,
            inventoryService,
            itemStackFactory,
            itemDropAnimationService,
            bundleUseEffectService,
            particleDisplayService
        );
        bundleUseService.setBundleOpenedListener((player, bundleId) ->
            guideService.recordCondition(player, GuideConditionType.BUNDLE_OPENED, bundleId)
        );

        // status
        statusService = new StatusService(itemService, inventoryService);
        statusService.setPlayerClassService(playerClassService);
        statusService.setSkillTreeService(skillTreeService);
        buffAcquisitionDisplayService = new BuffAcquisitionDisplayService(displayTextService);
        potionUseService = new PotionUseService(
            movementCancelableWaitService,
            inventoryService,
            statusService,
            buffAcquisitionDisplayService,
            particleDisplayService
        );
        statusRegenTask = new StatusRegenTask(statusService);
        overheadDisplayService = new OverheadDisplayService(
                displayTextService,
                statusService,
                mobService,
                playerClassService,
                skillTreeService::isSkillTreeWorld
        );

        // combat
        mobDropPresentationService = new MobDropPresentationService(
                this,
                itemService,
                inventoryService,
                itemStackFactory,
                itemDropAnimationService,
                playerSettingService
        );
        gatheringService.setDropPresentationService(mobDropPresentationService);
        var mobKnockbackService = new MobKnockbackService(mobService);
        mobVanillaEffectProtectionService = new MobVanillaEffectProtectionService();
        mobCombatService = new MobCombatService(
                mobService,
                new MobDropService(lootService),
                mobDropPresentationService,
                partyService,
                adventureRecordService,
                accountService,
                playerClassService,
                statusService,
                skillTreeService,
                particleDisplayService
        );
        playerDeathService = new PlayerDeathService(
            this,
            accountService,
            statusService,
            mobService,
            worldService,
            displayTextService,
            joinSpawnWorldId
        );
        mobCombatService.setPlayerDeathService(playerDeathService);
        damageService = new DamageService(
            statusService,
            mobService,
            mobCombatService,
            mobKnockbackService,
            displayTextService,
            playerSettingService,
            particleDisplayService,
            playerDeathService,
            this
        );
        mobCombatService.setDamageService(damageService);
        combatDpsTrackerService = new CombatDpsTrackerService();
        damageService.setCombatDpsTrackerService(combatDpsTrackerService);
        conditionDisplayService = new ConditionDisplayService(particleDisplayService, mobVanillaEffectProtectionService);
        conditionService = new ConditionService(conditionDisplayService, playerDeathService);
        conditionService.setStatusService(statusService);
        statusService.setConditionService(conditionService);
        var conditionTickService = new ConditionTickService(conditionService, damageService);
        conditionTickTask = new ConditionTickTask(conditionService, conditionTickService);
        conditionDisplayTask = new ConditionDisplayTask(conditionService, conditionDisplayService);
        conditionCleanupTask = new ConditionCleanupTask(conditionService);
        damageService.setConditionService(conditionService);
        mobService.setConditionService(conditionService);

        var playerSaveCoordinator = new PlayerSaveCoordinator(
            java.util.List.of(
                new AccountClassProgressSaveTask(accountService),
                new InventorySaveTask(inventoryService, inventoryStateRegistry, inventoryPersistence)
            )
        );
        // player
        playerService = new PlayerService(
            userService,
            accountService,
            inventoryService,
            inventorySaveCoordinator,
            inventoryPersistence,
            inventoryStateRegistry,
            statusService,
            playerSaveCoordinator,
            playerRegionService
        );
        bossFieldInstanceService = new BossFieldInstanceService(this, worldService);
        String bossHubWorldId = getConfig().getString(
            "boss.hubWorldId",
            getConfig().getString("plugin.boss.hubWorldId", "skyhaven_isle")
        );
        InstanceCreationQueueConfig instanceCreationQueueConfig =
                InstanceCreationQueueConfig.from(getConfig());
        bossChallengeService = new BossChallengeService(
            this,
            mobService,
            worldService,
            partyService,
            playerMessageService,
            bossFieldInstanceService,
            particleDisplayService,
            displayTextService,
            playerDeathService,
            bossHubWorldId,
            new InstanceCreationQueue(instanceCreationQueueConfig.boss())
        );
        damageService.setBossChallengeService(bossChallengeService);
        dungeonService = new DungeonService(
            this,
            new DungeonDefinitionRepository(),
            worldService,
            partyService,
            mobService,
            playerMessageService,
            particleDisplayService,
            displayTextService,
            playerDeathService,
            new MobDropService(lootService),
            inventoryService,
            itemService,
            itemStackFactory,
            lootService,
            new AdventureRecordRepository(),
            bossHubWorldId,
            new InstanceCreationQueue(instanceCreationQueueConfig.dungeon())
        );
        partyService.addMembershipChangeListener(bossChallengeService::handlePartyMembershipChanged);
        partyService.addMembershipChangeListener(dungeonService::handlePartyMembershipChanged);
        damageService.setDungeonService(dungeonService);
        damageService.setMobDeathListener(dungeonService::handleMobDefeated);
        returnToBaseService = new ReturnToBaseService(
            this,
            movementCancelableWaitService,
            worldService,
            inventoryService,
            particleDisplayService,
            dungeonService,
            bossChallengeService,
            joinSpawnWorldId
        );
        bossMechanicService = new BossMechanicService(
            this,
            mobService,
            damageService,
            dungeonService,
            particleDisplayService
        );
        bossChallengeCancelGui = new BossChallengeCancelGui();
        playerHudService = new PlayerHudService(
            statusService,
            playerClassService,
            accountService,
            playerSettingService,
            conditionService,
            bossChallengeService,
            worldService
        );
        playerHudService.setCombatDpsTrackerService(combatDpsTrackerService);
        playerHudService.setDungeonService(dungeonService);
        skillTreeService.setPlayerHudService(playerHudService);
        hookshotUseService.setPlayerHudService(playerHudService);

        // dodge
        dodgeService = new DodgeService(this, statusService, playerHudService, particleDisplayService);
        airActionService = new AirActionService(this, playerHudService, particleDisplayService);

        // resource pack
        resourcePackService = new ResourcePackService(ConfigProperties.getInstance());
        guideService = new GuideService(
            this,
            new GuideRepository(),
            new GuideProgressRepository(),
            itemService,
            playerClassService,
            worldService,
            playerMessageService
        );
        guideActionService = new GuideActionService(this, mobService, npcPlacementService, playerMessageService);
        guideReminderTask = new GuideReminderTask(playerMessageService);

        // menu
        guiSessionTransitionService = new GuiSessionTransitionService();
        guiNavigationService = new GuiNavigationService(this);
        playerGuiRenderContextFactory = new PlayerGuiRenderContextFactory(
            currencyService,
            statusService,
            skillTreeService
        );
        menuView = new MenuView(this, guideService);
        menuGuiTransitionService =
            new MenuGuiTransitionService(this, menuView, inventoryService);
        trashService = new TrashService(this, menuView, inventoryService, menuGuiTransitionService);
        sellService = new SellService(this, menuView, inventoryService, menuGuiTransitionService);
        storageService = new StorageService(
            menuView,
            inventoryService,
            inventorySaveCoordinator,
            menuGuiTransitionService
        );
        orbService = new OrbService(
            this,
            inventoryService,
            inventorySaveCoordinator,
            inventoryStateRegistry,
            itemService,
            itemStackFactory
        );
        orbService.setStatusService(statusService);
        equipmentDurabilityService = new EquipmentDurabilityService(inventoryService, itemService);
        equipmentDurabilityService.setStatusService(statusService);
        damageService.setEquipmentDurabilityService(equipmentDurabilityService);
        gatheringService.setEquipmentDurabilityService(equipmentDurabilityService);
        playerListGui = new PlayerListGui(worldService);
        playerDetailGui = new PlayerDetailGui(worldService);
        playerSettingGui = new PlayerSettingGui(playerSettingService);
        adventureRecordGuiEventHandler = new AdventureRecordGuiEventHandler(
            new AdventureRecordGui(itemService),
            adventureRecordService,
            inventoryService
        );
        loginBonusService = new LoginBonusService(
            this,
            new LoginBonusGui(),
            inventoryService,
            itemService,
            new LoginBonusClaimRepository()
        );
        loginBonusService.setClaimSuccessListener(player ->
            guideService.recordCondition(player, GuideConditionType.LOGIN_BONUS_CLAIMED, null)
        );
        partyMemberActionGui = new PartyMemberActionGui();
        mailService = new MailService(this, new MailRepository(), itemService, inventoryService);
        mailService.setMailReceivedListener((player, mailId) ->
            guideService.recordCondition(player, GuideConditionType.MAIL_RECEIVED, mailId)
        );
        shopService = new ShopService(
            new ShopRepository(),
            new ShopRecipeRepository(),
            itemService,
            inventoryService,
            currencyService
        );
        shopService.setPurchaseListener((player, entryId) ->
            guideService.recordCondition(player, GuideConditionType.SHOP_PURCHASED, entryId)
        );
        shopService.setPurchaseSavedListener((player, entryId) -> {
            if (marketService != null) {
                marketService.clearCache();
            }
        });
        itemAdminGuiEventHandler = new ItemAdminGuiEventHandler(
            new ItemAdminGuiView(this, itemStackFactory),
            itemService,
            inventoryService
        );
        shopGui = new ShopGui(this, shopService, itemStackFactory);
        shopGuiEventHandler = new ShopGuiEventHandler(shopGui, shopService, inventoryService);
        questService = new QuestService(
            this,
            new QuestDefinitionRepository(),
            new QuestBoardRepository(),
            new QuestPlayerStateRepository(this),
            itemService,
            inventoryService,
            accountService,
            playerClassService,
            statusService,
            particleDisplayService
        );
        questService.setSkillTreeService(skillTreeService);
        gatheringService.setProgressionServices(
            accountService,
            playerClassService,
            skillTreeService,
            particleDisplayService
        );
        questGui = new QuestGui(this, questService);
        questGuiEventHandler = new QuestGuiEventHandler(questGui, questService, inventoryService);
        mobCombatService.setQuestService(questService);
        mobCombatService.setMobDefeatedLevelListener((player, defeated) ->
            guideService.recordCondition(
                player, GuideConditionType.MOB_DEFEATED, defeated.mobId(), defeated.level()
            )
        );
        gatheringService.setQuestService(questService);
        gatheringService.setGatheringCompleteListener((player, spawnerOrGatheringId) ->
            guideService.recordCondition(player, GuideConditionType.GATHERING_COMPLETED, spawnerOrGatheringId)
        );
        tradeGui = new TradeGui();
        tradeCancelConfirmGui = new TradeCancelConfirmGui();
        goldAmountSettingGui = new GoldAmountSettingGui();
        tradeService = new TradeService(
            this,
            tradeGui,
            tradeCancelConfirmGui,
            goldAmountSettingGui,
            inventoryService,
            currencyService,
            playerMessageService,
            itemService,
            inventorySaveCoordinator
        );
        marketService = new MarketService(new MarketRepository());
        marketGuiEventHandler = new MarketGuiEventHandler(
            this,
            itemService,
            itemStackFactory,
            marketService,
            inventoryService,
            inventorySaveCoordinator,
            currencyService,
            playerMessageService,
            goldAmountSettingGui
        );

        // skill
        skillService = new SkillService(new SkillRepository(), new SkillRegistry(), this);
        skillBindPresetService = new SkillBindPresetService(this, new SkillBindPresetRepository());
        skillCooldownBossBarService = new SkillCooldownBossBarService(skillService);
        skillService.setConditionService(conditionService);
        skillService.setPlayerHudService(playerHudService);
        meditationSkillRuntimeService = new MeditationSkillRuntimeService(particleDisplayService);
        skillService.registerExecutor(new MeditationSkillExecutor(meditationSkillRuntimeService));
        skillService.registerExecutor(new AdministratorShieldRechargeSkillExecutor(statusService, particleDisplayService));
        skillService.registerExecutor(new FireBoostSkillExecutor(particleDisplayService));
        skillService.registerExecutor(new IronWillSkillExecutor());
        skillService.registerExecutor(new StatusPassiveSkillExecutor());
        skillService.registerExecutor(new WeaponAttackSkillExecutor(particleDisplayService, damageService, conditionService));
        mobProjectileService = new MobProjectileService(mobService, particleDisplayService);
        var mobSkillRegistry = new MobSkillRegistry();
        mobSkillRegistry.register(new SkeletonArcherBowShotMobSkillExecutor(damageService, mobProjectileService));
        mobSkillRegistry.register(new TwilightColossusGateSlamSkillExecutor(damageService, particleDisplayService));
        mobSkillRegistry.register(new TwilightColossusRuneBoltSkillExecutor(damageService, particleDisplayService));
        mobSkillService = new MobSkillService(mobService, mobSkillRegistry);
        mobSkillService.setConditionService(conditionService);
        mobService.setDestroyListener(mobInstanceId -> {
            skillService.clearCasterState(mobInstanceId);
            mobSkillService.clearCasterState(mobInstanceId);
            mobProjectileService.clearCasterState(mobInstanceId);
        });
        var activeSkillTargetingService = new SkillTargetingService(mobService);
        var activeSkillEffectService = new SkillEffectService(particleDisplayService);
        swordsmanBladeCounterRuntimeService = new SwordsmanBladeCounterRuntimeService(
            this,
            damageService,
            activeSkillEffectService
        );
        activeSkillTaskService = new SkillTaskService(this);
        temporarySkillEffectService = new TemporarySkillEffectService();
        mobKnockbackService.setAdditionalKnockbackMultiplier(
            temporarySkillEffectService::knockbackMultiplier
        );
        activeSkillLifecycleService = new ActiveSkillLifecycleService(
            skillService,
            activeSkillTaskService,
            temporarySkillEffectService
        );
        activeSkillLifecycleService.setAdditionalClearer(swordsmanBladeCounterRuntimeService::clear);
        playerDeathService.setDeathStartedListener(activeSkillLifecycleService::clearAll);
        var activeSkillServices = new ActiveSkillServices(
            activeSkillTargetingService,
            new SkillCombatService(damageService, conditionService, mobKnockbackService, statusService),
            activeSkillEffectService,
            new SkillProjectileService(
                activeSkillTargetingService,
                activeSkillEffectService,
                activeSkillTaskService
            ),
            new SkillMovementService(conditionService),
            temporarySkillEffectService,
            activeSkillTaskService
        );
        ActiveSkillExecutorCatalog.create(activeSkillServices, swordsmanBladeCounterRuntimeService)
            .forEach(skillService::registerExecutor);
        damageService.setTemporarySkillEffectService(temporarySkillEffectService);
        damageService.setDirectDamageModifier(swordsmanBladeCounterRuntimeService::modifyIncomingDirectDamage);
        skillService.registerBuiltInDefinitions(BuiltInWeaponAttackDefinitions.definitions());
        learnedSkillService = new LearnedSkillService(this, new LearnedSkillRepository(), inventoryService);
        skillOwnershipService = new SkillOwnershipService(learnedSkillService);
        skillPermissionService = new SkillPermissionService(playerClassService, skillTreeService);
        playerDetailGui.setSkillServices(
            skillService,
            skillBindPresetService,
            learnedSkillService,
            skillPermissionService
        );
        skillService.setOwnershipService(skillOwnershipService);
        skillService.setPermissionService(skillPermissionService);
        var learnedSkillResolver = new LearnedSkillResolver(itemService);
        skillService.setLearnedSkillResolver(learnedSkillResolver);
        passiveSkillService = new PassiveSkillService(
            this,
            skillService,
            skillBindPresetService,
            skillOwnershipService,
            skillPermissionService,
            learnedSkillResolver
        );
        passiveSkillService.setStatusService(statusService);
        statusService.setPassiveSkillService(passiveSkillService);
        skillService.setPlayerSkillUseListener(
            (player, skillId) -> meditationSkillRuntimeService.interrupt(player.getBukkit().getUniqueId())
        );
        playerClassService.setClassChangeListener(player -> passiveSkillService.reconcileNow(player));
        damageService.setPlayerDamageListener(
            player -> meditationSkillRuntimeService.interrupt(player.getBukkit().getUniqueId())
        );
        skillTreeService.setStatusService(statusService);
        skillTreeService.setNodeUnlockListener((player, nodeId) ->
            guideService.recordCondition(player, GuideConditionType.SKILLTREE_NODE_UNLOCKED, nodeId)
        );
        skillTreeService.setSkillService(skillService);
        skillTreeService.setPassiveSkillService(passiveSkillService);
        skillActionRingService = new SkillActionRingService(
            this, skillBindPresetService, skillService, skillOwnershipService, skillPermissionService
        );
        skillActionRingService.setOpenListener(player ->
            guideService.recordCondition(player, GuideConditionType.ACTION_RING_OPENED, null)
        );
        skillService.setPlayerCastSuccessListener((player, skillId) ->
            guideService.recordCondition(player, GuideConditionType.SKILL_CAST, skillId)
        );
        skillBindGui = new SkillBindGui(this, itemService, skillService);
        itemWeaponAttackService = new ItemWeaponAttackService(inventoryService, skillService);
        itemWeaponAttackService.setEquipmentDurabilityService(equipmentDurabilityService);
        itemWeaponAttackService.setAttackAttemptListener(
            swordsmanBladeCounterRuntimeService::onNormalAttack
        );
        skillActionRingService.setItemWeaponAttackService(itemWeaponAttackService);
        skillActionRingHoldService = new SkillActionRingHoldService(
            this,
            skillActionRingService,
            playerSettingService
        );
        skillActionRingService.setCloseListener(skillActionRingHoldService::cancel);

        // item, loot, skill, class 等のマスターデータを非同期ロード
        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            lootService.loadAll();
            itemService.loadAll();
            var skillDefinitions = skillService.loadDefinitions();
            var skillTreeSnapshot = skillTreeService.loadMasterDataSnapshot();
            playerClassService.loadAll();
            guideService.loadAll();
            shopService.warmCaches();
            getServer().getScheduler().runTask(this, () -> {
                skillService.replaceDefinitions(skillDefinitions);
                skillTreeService.replaceMasterDataSnapshot(skillTreeSnapshot);
            });
        });

        // mob
        mobService.loadAll();
        trainingDummyService.loadAll();
        npcPlacementService.loadAll();
        textDisplayPlacementService.loadAll();
        mobSpawnerService.loadAll();
        gatheringService.loadAll();
        gatheringSpawnerService.loadAll();
        questService.loadAll();
        teleporterService.loadAll();
        // world
        worldService.loadAll();
        dungeonService.loadAll();
        dungeonService.start();
        mobAiService = new MobAiService(mobService, mobCombatService, mobSkillService, playerDeathService, particleDisplayService, conditionService);
        mobAiService.start();
        trainingDummyService.start();
        worldSpawnParticleTask = new WorldSpawnParticleTask(this, worldService, particleDisplayService, displayTextService);
        stoneButtonReachService = new StoneButtonReachService(this, worldService);

        // item: ProtocolLib パケットアダプター（icon 差し替え）登録
        itemStackPacketAdapter = new ItemStackPacketAdapter(this, playerSettingService, skillActionRingService);
        itemStackPacketAdapter.register();

    }

    /**
     * DiscordSRVが利用できる場合に全体チャット中継を初期化します。
     */
    private void setupDiscordChatBridge() {
        WhitelistService whitelistService = WhitelistService.getInstance();
        DiscordSrvChatBridge.setServerLifecycleMessagesSuppressed(
            ConfigProperties.getInstance().isPluginWhitelistEnabled()
        );
        if (!ConfigProperties.getInstance().isDiscordEnabled()) {
            whitelistService.setGlobalChatBridge(null);
            return;
        }
        try {
            globalChatBridge = DiscordSrvChatBridge.create(this, playerMessageService);
            playerMessageService.setGlobalChatBridge(globalChatBridge);
            whitelistService.setGlobalChatBridge(globalChatBridge);
        } catch (LinkageError | RuntimeException exception) {
            Logger.log(LogId.W_7102, exception, exception.getClass().getSimpleName());
        }
    }

    /**
     * イベントやコマンドなどの機能を登録します。
     */
    private void registerPluginFeatures() {
        eventManager.registerHandler(
            new GuiClickCooldownEventHandler(inventoryService),
            getServer().getPluginManager()
        );
        eventManager.registerHandler(
            new ItemChatShareEventHandler(new ItemChatShareService()),
            getServer().getPluginManager()
        );
        eventManager.registerHandler(
            new GuiSessionTransitionEventHandler(this, guiSessionTransitionService),
            getServer().getPluginManager()
        );
        eventManager.registerHandler(
            new GuiNavigationEventHandler(guiNavigationService),
            getServer().getPluginManager()
        );
        eventManager.registerHandler(
            new UserLoginEventHandler(userService),
            getServer().getPluginManager()
        );
        eventManager.registerHandler(
            new WhitelistConnectionEventHandler(WhitelistService.getInstance()),
            getServer().getPluginManager()
        );
        var menuToolJoinGrantService = new MenuToolJoinGrantService(itemService, inventoryService);
        var playerJoinEventHandler = new PlayerJoinEventHandler(
            this,
            playerService,
            skillTreeService,
            questService,
            skillBindPresetService,
            learnedSkillService,
            loginBonusService,
            mailService,
            guideService,
            menuToolJoinGrantService
        );
        eventManager.registerHandler(playerJoinEventHandler, getServer().getPluginManager());
        eventManager.registerHandler(
            new AdminMessageBossBarEventHandler(adminMessageBossBarService),
            getServer().getPluginManager()
        );
        eventManager.registerHandler(
            new ManagedChatEventHandler(this),
            getServer().getPluginManager()
        );
        eventManager.registerHandler(
            new LoginBonusGuiEventHandler(loginBonusService),
            getServer().getPluginManager()
        );
        eventManager.registerHandler(
            new WorldJoinSpawnEventHandler(this, joinSpawnWorldId, worldService),
            getServer().getPluginManager()
        );
        eventManager.registerHandler(
            new WorldChangeTitleEventHandler(worldService, playerRegionService),
            getServer().getPluginManager()
        );
        eventManager.registerHandler(
            new WorldNaturalSpawnBlockEventHandler(this, worldService, mobService),
            getServer().getPluginManager()
        );
        eventManager.registerHandler(
            overworldTeleportGuiEventHandler,
            getServer().getPluginManager()
        );
        eventManager.registerHandler(
            new BaseWorldGatewayEventHandler(this, overworldTeleportService, overworldTeleportGuiEventHandler),
            getServer().getPluginManager()
        );
        var baseWorldSpawnTeleportEventHandler = new BaseWorldSpawnTeleportEventHandler(
            worldService,
            overworldTeleportService,
            overworldTeleportGuiEventHandler
        );
        var overworldSpawnReturnEventHandler = new OverworldSpawnReturnEventHandler(
            worldService,
            returnToBaseService
        );
        var bossEntryEventHandler = new BossEntryEventHandler(bossChallengeService);
        var dungeonInteractionEventHandler = new DungeonInteractionEventHandler(dungeonService, inventoryService);
        var challengeWaitingHubEventHandler = new ChallengeWaitingHubEventHandler(
            worldService,
            bossChallengeService,
            dungeonService
        );
        eventManager.registerHandler(
            new BossPlayerEventHandler(bossChallengeService),
            getServer().getPluginManager()
        );
        eventManager.registerHandler(
            new DungeonWorldEventHandler(dungeonService),
            getServer().getPluginManager()
        );
        eventManager.registerHandler(challengeWaitingHubEventHandler, getServer().getPluginManager());
        eventManager.registerHandler(dungeonInteractionEventHandler, getServer().getPluginManager());
        var bossChallengeCancelEventHandler = new BossChallengeCancelEventHandler(
            bossChallengeService,
            bossChallengeCancelGui
        );
        eventManager.registerHandler(bossChallengeCancelEventHandler, getServer().getPluginManager());
        eventManager.registerHandler(
            new ResourcePackJoinEventHandler(resourcePackService),
            getServer().getPluginManager()
        );
        eventManager.registerHandler(
            new ResourcePackStatusEventHandler(resourcePackService),
            getServer().getPluginManager()
        );
        var itemInteractionBlockEventHandler = new ItemInteractionBlockEventHandler(
            inventoryService,
            bundleUseService,
            potionUseService
        );
        var hookshotInteractionEventHandler = new HookshotInteractionEventHandler(hookshotUseService);
        eventManager.registerHandler(itemInteractionBlockEventHandler, getServer().getPluginManager());
        eventManager.registerHandler(hookshotInteractionEventHandler, getServer().getPluginManager());
        menuOpenEventHandler = new MenuOpenEventHandler(
            this,
            menuView,
            inventoryService,
            currencyService,
            currencyExchangeGuiEventHandler,
            statusService,
            playerGuiRenderContextFactory,
            menuGuiTransitionService,
            trashService,
            sellService,
            storageService,
            returnToBaseService
        );
        eventManager.registerHandler(menuOpenEventHandler, getServer().getPluginManager());
        eventManager.registerHandler(currencyExchangeGuiEventHandler, getServer().getPluginManager());
        mailGuiEventHandler = new MailGuiEventHandler(new MailGuiView(this, itemService), mailService, inventoryService);
        eventManager.registerHandler(
            mailGuiEventHandler,
            getServer().getPluginManager()
        );
        eventManager.registerHandler(
            itemAdminGuiEventHandler,
            getServer().getPluginManager()
        );
        eventManager.registerHandler(
            shopGuiEventHandler,
            getServer().getPluginManager()
        );
        eventManager.registerHandler(
            questGuiEventHandler,
            getServer().getPluginManager()
        );
        eventManager.registerHandler(
            new TradeGuiEventHandler(
                this,
                tradeGui,
                tradeCancelConfirmGui,
                goldAmountSettingGui,
                tradeService,
                inventoryService,
                playerMessageService
            ),
            getServer().getPluginManager()
        );
        eventManager.registerHandler(marketGuiEventHandler, getServer().getPluginManager());
        playerBrowserGuiEventHandler = new PlayerBrowserGuiEventHandler(
            this,
            playerListGui,
            playerDetailGui,
            partyService,
            statusService,
            inventoryService,
            tradeService,
            currencyService,
            playerClassService
        );
        eventManager.registerHandler(
            playerBrowserGuiEventHandler,
            getServer().getPluginManager()
        );
        var skillGemLearnEventHandler = new SkillGemLearnEventHandler(
            this,
            inventoryService,
            learnedSkillService,
            skillService,
            passiveSkillService
        );
        skillGemLearnEventHandler.setSkillLearnedListener(
            (player, skillId) -> guideService.recordCondition(player, GuideConditionType.SKILL_LEARNED, skillId)
        );
        eventManager.registerHandler(skillGemLearnEventHandler, getServer().getPluginManager());
        eventManager.registerHandler(
            new InventoryEquipmentGuiEventHandler(
                menuView,
                inventoryService,
                currencyService,
                statusService,
                passiveSkillService,
                orbService,
                menuGuiTransitionService,
                menuOpenEventHandler,
                skillGemLearnEventHandler
            ),
            getServer().getPluginManager()
        );
        eventManager.registerHandler(
            new PlayerHeldItemStatusEventHandler(this, statusService),
            getServer().getPluginManager()
        );
        eventManager.registerHandler(
            new PlayerSettingJoinEventHandler(
                this,
                playerSettingService,
                itemStackPacketAdapter
            ),
            getServer().getPluginManager()
        );
        eventManager.registerHandler(
            new PlayerSettingGuiEventHandler(
                playerSettingGui,
                playerSettingService,
                inventoryService,
                itemStackPacketAdapter
            ),
            getServer().getPluginManager()
        );
        eventManager.registerHandler(
            adventureRecordGuiEventHandler,
            getServer().getPluginManager()
        );
        eventManager.registerHandler(
            teleporterGuiEventHandler,
            getServer().getPluginManager()
        );
        var teleporterInteractEventHandler = new TeleporterInteractEventHandler(
            teleporterService,
            waystoneHitBoxResolver
        );
        eventManager.registerHandler(
            new TeleporterPlayerEventHandler(this, teleporterService),
            getServer().getPluginManager()
        );
        skillBindGuiEventHandler = new SkillBindGuiEventHandler(
            this,
            skillBindGui,
            skillService,
            skillBindPresetService,
            skillOwnershipService,
            skillPermissionService,
            learnedSkillService,
            passiveSkillService,
            inventoryService
        );
        skillBindGuiEventHandler.setSkillEnhancedListener((player, skillId) ->
            guideService.recordCondition(player, GuideConditionType.SKILL_ENHANCED, skillId)
        );
        eventManager.registerHandler(
            skillBindGuiEventHandler,
            getServer().getPluginManager()
        );
        var skillForgetGui = new SkillForgetGui(this);
        var skillForgetGuiEventHandler = new SkillForgetGuiEventHandler(
            this,
            skillForgetGui,
            skillService,
            skillOwnershipService,
            learnedSkillService,
            passiveSkillService,
            inventoryService,
            itemService
        );
        eventManager.registerHandler(
            skillForgetGuiEventHandler,
            getServer().getPluginManager()
        );
        eventManager.registerHandler(skillActionRingHoldService, getServer().getPluginManager());
        var skillActionRingEventHandler = new SkillActionRingEventHandler(
            skillActionRingService,
            inventoryService,
            playerSettingService,
            skillActionRingHoldService
        );
        eventManager.registerHandler(skillActionRingEventHandler, getServer().getPluginManager());
        eventManager.registerHandler(
            new ActiveSkillLifecycleEventHandler(activeSkillLifecycleService),
            getServer().getPluginManager()
        );
        eventManager.registerHandler(
            new MeditationSkillEventHandler(meditationSkillRuntimeService),
            getServer().getPluginManager()
        );
        var playerModeEventHandler = new PlayerModeEventHandler(accountModeApplicationService);
        eventManager.registerHandler(playerModeEventHandler, getServer().getPluginManager());
        eventManager.registerHandler(
            new PlayerInputEventHandler(airActionService),
            getServer().getPluginManager()
        );
        var playerSneakEventHandler = new PlayerSneakEventHandler(airActionService, dodgeService);
        eventManager.registerHandler(
            new PlayerVanillaDamageBlockEventHandler(worldService),
            getServer().getPluginManager()
        );
        eventManager.registerHandler(
            new PlayerDeathEventHandler(playerDeathService),
            getServer().getPluginManager()
        );
        eventManager.registerHandler(
            new ConditionPlayerEventHandler(conditionService),
            getServer().getPluginManager()
        );
        eventManager.registerHandler(
            new CombatDamageEventHandler(damageService, skillActionRingService),
            getServer().getPluginManager()
        );
        eventManager.registerHandler(
            new MobVanillaDamageBlockEventHandler(mobService, mobVanillaEffectProtectionService),
            getServer().getPluginManager()
        );
        var mobSpawnerBlockEventHandler = new MobSpawnerBlockEventHandler(mobSpawnerService);
        var gatheringSpawnerBlockEventHandler = new GatheringSpawnerBlockEventHandler(gatheringSpawnerService);
        var gatheringInteractionEventHandler = new GatheringInteractionEventHandler(gatheringService);
        var skillTreeEventHandler = new SkillTreeEventHandler(skillTreeService);
        eventManager.registerHandler(skillTreeEventHandler, getServer().getPluginManager());
        var mobInteractionEventHandler = new MobInteractionEventHandler(
            mobService,
            statusService,
            shopGuiEventHandler,
            menuView,
            playerClassService,
            storageService,
            questGuiEventHandler,
            currencyExchangeGuiEventHandler,
            loginBonusService,
            skillForgetGuiEventHandler,
            marketGuiEventHandler
        );
        eventManager.registerHandler(
            new TrainingDummyGuiEventHandler(trainingDummyGui, trainingDummyService),
            getServer().getPluginManager()
        );
        var itemWeaponAttackEventHandler = new ItemWeaponAttackEventHandler(
            itemWeaponAttackService,
            skillActionRingService,
            skillTreeService,
            conditionService
        );
        eventManager.registerHandler(
            new PlayerInteractionGatewayEventHandler(
                this,
                java.util.List.of(
                    stoneButtonReachService,
                    bossChallengeCancelEventHandler,
                    bossEntryEventHandler,
                    dungeonInteractionEventHandler,
                    challengeWaitingHubEventHandler,
                    baseWorldSpawnTeleportEventHandler,
                    overworldSpawnReturnEventHandler,
                    hookshotInteractionEventHandler,
                    itemInteractionBlockEventHandler,
                    menuOpenEventHandler,
                    teleporterInteractEventHandler,
                    skillActionRingEventHandler,
                    playerModeEventHandler,
                    playerSneakEventHandler,
                    mobSpawnerBlockEventHandler,
                    gatheringSpawnerBlockEventHandler,
                    gatheringInteractionEventHandler,
                    skillTreeEventHandler,
                    mobInteractionEventHandler,
                    new TrainingDummyInputResolver(mobService, trainingDummyService, trainingDummyGui),
                    itemWeaponAttackEventHandler
                ),
                playerJoinEventHandler::isLoading,
                skillActionRingService::isOpen,
                skillActionRingService::close
            ),
            getServer().getPluginManager()
        );
        eventManager.registerHandler(
            new PartyGuiEventHandler(
                partyGui,
                partyMemberActionGui,
                partyService,
                playerBrowserGuiEventHandler,
                inventoryService
            ),
            getServer().getPluginManager()
        );
        eventManager.registerHandler(
            new PartyQuitEventHandler(partyService),
            getServer().getPluginManager()
        );
        playerHudService.start(this);
        skillCooldownBossBarService.start(this);
        statusRegenTask.start(this);
        displayTextService.start(this);
        overheadDisplayService.start(this);
        playerDeathService.start();
        conditionTickTask.start(this);
        conditionDisplayTask.start(this);
        conditionCleanupTask.start(this);
        worldSpawnParticleTask.start();
        stoneButtonReachService.start();
        mobSpawnerService.start();
        gatheringService.start();
        gatheringSpawnerService.start();
        questService.start();
        bossChallengeService.start();
        bossMechanicService.start();
        passiveSkillService.start();
        skillTreeService.start();
        // インベントリオートセーブ（60 秒）を開始
        inventoryAutoSaveTask.start(this, InventoryAutoSaveTask.DEFAULT_INTERVAL_TICKS);
        guideReminderTask.start(this, GuideReminderTask.DEFAULT_INTERVAL_TICKS);
    }
    /**
     * AstralRecord のインスタンスを取得します。
     * 他クラスからプラグインインスタンスを取得する必要がある場合に使用します。
     */
    public static AstralRecord getInstance() {
        return instance;
    }

    /**
     * {@link ItemStackFactory} のインスタンスを取得します。
     *
     * @return ItemStackFactory
     */
    public ItemStackFactory getItemStackFactory() {
        return itemStackFactory;
    }

    public ItemService getItemService() {
        return itemService;
    }

    public InventoryService getInventoryService() {
        return inventoryService;
    }

    /**
     * プレイヤーメッセージ送信サービスを取得する。
     *
     * @return プレイヤーメッセージ送信サービス
     */
    public PlayerMessageService getPlayerMessageService() {
        return playerMessageService;
    }

    /**
     * 通貨サービスを取得します。
     *
     * @return 通貨サービス
     */
    public CurrencyService getCurrencyService() {
        return currencyService;
    }

    /**
     * ログインボーナスサービスを取得します。
     *
     * @return ログインボーナスサービス
     */
    public LoginBonusService getLoginBonusService() {
        return loginBonusService;
    }

    /**
     * メニュー GUI 表示ビューを取得します。
     *
     * @return メニュー GUI 表示ビュー
     */
    public MenuView getMenuView() {
        return menuView;
    }

    /**
     * プレイヤー依存 GUI の描画コンテキスト生成ファクトリを取得します。
     *
     * @return GUI 描画コンテキスト生成ファクトリ
     */
    public PlayerGuiRenderContextFactory getPlayerGuiRenderContextFactory() {
        return playerGuiRenderContextFactory;
    }

    public MenuOpenEventHandler getMenuOpenEventHandler() {
        return menuOpenEventHandler;
    }

    public GuideService getGuideService() {
        return guideService;
    }

    /**
     * ガイド詳細画面の案内アクションサービスを取得します。
     *
     * @return ガイド案内アクションサービス
     */
    public GuideActionService getGuideActionService() {
        return guideActionService;
    }

    public TrashService getTrashService() {
        return trashService;
    }

    public SellService getSellService() {
        return sellService;
    }

    public EquipmentDurabilityService getEquipmentDurabilityService() {
        return equipmentDurabilityService;
    }

    public StorageService getStorageService() {
        return storageService;
    }

    public PlayerListGui getPlayerListGui() {
        return playerListGui;
    }

    public PlayerDetailGui getPlayerDetailGui() {
        return playerDetailGui;
    }

    public PlayerBrowserGuiEventHandler getPlayerBrowserGuiEventHandler() {
        return playerBrowserGuiEventHandler;
    }

    /**
     * GUI セッション履歴サービスを取得します。
     *
     * @return GUI セッション履歴サービス
     */
    public GuiNavigationService getGuiNavigationService() {
        return guiNavigationService;
    }

    public UserService getUserService() {
        return userService;
    }

    public AccountService getAccountService() {
        return accountService;
    }

    public AccountModeApplicationService getAccountModeApplicationService() {
        return accountModeApplicationService;
    }

    public StatusService getStatusService() {
        return statusService;
    }

    /**
     * TextDisplay 基盤サービスを返します。
     *
     * @return TextDisplay 基盤サービス
     */
    public DisplayTextService getDisplayTextService() {
        return displayTextService;
    }

    public OverheadDisplayService getOverheadDisplayService() {
        return overheadDisplayService;
    }

    public MobService getMobService() {
        return mobService;
    }

    /**
     * Mob のバニラ由来可視状態保護サービスを取得します。
     *
     * @return Mob バニラ可視状態保護サービス
     */
    public MobVanillaEffectProtectionService getMobVanillaEffectProtectionService() {
        return mobVanillaEffectProtectionService;
    }

    /**
     * Mob スポナーサービスを取得します。
     *
     * @return Mob スポナーサービス
     */
    public MobSpawnerService getMobSpawnerService() {
        return mobSpawnerService;
    }

    public PlayerSettingService getPlayerSettingService() {
        return playerSettingService;
    }

    public PlayerSettingGui getPlayerSettingGui() {
        return playerSettingGui;
    }

    /**
     * プレイヤー向け ItemStack パケット表示アダプタを取得します。
     *
     * @return 登録済み ItemStack パケット表示アダプタ
     */
    public ItemStackPacketAdapter getItemStackPacketAdapter() {
        return itemStackPacketAdapter;
    }

    /**
     * スキルサービスを取得します。
     *
     * @return スキルサービス
     */
    public SkillService getSkillService() {
        return skillService;
    }

    /**
     * スキルツリーサービスを返します。
     *
     * @return スキルツリーサービス
     */
    public SkillTreeService getSkillTreeService() {
        return skillTreeService;
    }

    /**
     * スキルバインド GUI イベントハンドラを返します。
     *
     * @return スキルバインド GUI イベントハンドラ
     */
    public SkillBindGuiEventHandler getSkillBindGuiEventHandler() {
        return skillBindGuiEventHandler;
    }

    /**
     * 戦闘ダメージサービスを取得します。
     *
     * @return 戦闘ダメージサービス
     */
    public DamageService getDamageService() {
        return damageService;
    }

    /**
     * 職業サービスを取得します。
     *
     * @return 職業サービス
     */
    public PlayerClassService getPlayerClassService() {
        return playerClassService;
    }

    /**
     * WorldMasterData サービスを取得します。
     *
     * @return WorldMasterData サービス
     */
    public WorldService getWorldService() {
        return worldService;
    }

    /**
     * API/filebase 由来のマスターデータ再読込を開始します。
     *
     * <p>Repository/file I/O は非同期 task で実行し、Bukkit API と実行時キャッシュへの公開だけを
     * メインスレッドへ戻します。同時に実行できる再読込は 1 件だけです。</p>
     *
     * @return 開始結果と完了 future
     */
    public @org.jetbrains.annotations.NotNull MasterDataReloadStart reloadMasterData() {
        CompletableFuture<Integer> completion = new CompletableFuture<>();
        while (true) {
            CompletableFuture<Integer> current = masterDataReloadInFlight.get();
            if (current != null && !current.isDone()) {
                return new MasterDataReloadStart(false, current);
            }
            if (current != null && !masterDataReloadInFlight.compareAndSet(current, null)) {
                continue;
            }
            if (masterDataReloadInFlight.compareAndSet(null, completion)) {
                break;
            }
        }

        long generation = masterDataReloadGeneration.incrementAndGet();
        try {
            AsyncTaskUtil.supplyAsync(this, this::prepareMasterDataReload)
                .whenComplete((plan, throwable) -> {
                    if (throwable != null) {
                        if (isCurrentMasterDataReload(completion, generation)) {
                            completion.completeExceptionally(throwable);
                            masterDataReloadInFlight.compareAndSet(completion, null);
                        } else {
                            completion.completeExceptionally(
                                new IllegalStateException("古いマスターデータ再読込の完了結果です", throwable)
                            );
                        }
                        return;
                    }
                    try {
                        AsyncTaskUtil.runSync(this, () -> publishMasterDataReload(completion, generation, plan));
                    } catch (RuntimeException schedulingFailure) {
                        if (isCurrentMasterDataReload(completion, generation)) {
                            completion.completeExceptionally(schedulingFailure);
                            masterDataReloadInFlight.compareAndSet(completion, null);
                        } else {
                            completion.completeExceptionally(
                                new IllegalStateException("古いマスターデータ再読込の完了結果です", schedulingFailure)
                            );
                        }
                    }
                });
        } catch (RuntimeException schedulingFailure) {
            masterDataReloadInFlight.compareAndSet(completion, null);
            completion.completeExceptionally(schedulingFailure);
        }
        return new MasterDataReloadStart(true, completion);
    }

    private @org.jetbrains.annotations.NotNull MasterDataReloadPlan prepareMasterDataReload() {
        FileDatabaseManager fileDatabaseManager = FileDatabaseManager.getInstance();
        FileDatabaseManager.ReloadSnapshot fileDatabaseSnapshot = fileDatabaseManager.loadReloadSnapshot();
        YamlDbConfig yamlDbConfig = YamlDbConfigUtil.INSTANCE.loadSnapshot(
            fileDatabaseSnapshot.rootDirectory()
        );
        if (yamlDbConfig == null) {
            throw new IllegalStateException("filebase.config");
        }

        return fileDatabaseManager.withReloadSnapshot(
            fileDatabaseSnapshot,
            () -> YamlDbConfigUtil.INSTANCE.withSnapshot(
                yamlDbConfig,
                () -> loadMasterDataReloadPlan(fileDatabaseSnapshot, yamlDbConfig)
            )
        );
    }

    private @org.jetbrains.annotations.NotNull MasterDataReloadPlan loadMasterDataReloadPlan(
        @org.jetbrains.annotations.NotNull FileDatabaseManager.ReloadSnapshot fileDatabaseSnapshot,
        @org.jetbrains.annotations.NotNull YamlDbConfig yamlDbConfig
    ) {
        var lootSnapshot = lootService.loadSnapshot();
        int loaded = lootSnapshot.size();
        List<Runnable> publications = new ArrayList<>();
        List<MasterDataActivation> activations = new ArrayList<>();
        publications.add(() -> lootService.replaceSnapshot(lootSnapshot));

        var itemSnapshot = itemService.loadMasterDataSnapshot();
        loaded += itemSnapshot.size();
        publications.add(() -> {
            itemService.replaceMasterDataSnapshot(itemSnapshot);
            itemStackFactory.clearCache();
        });

        var skillDefinitions = skillService.loadDefinitions();
        loaded += skillDefinitions.size();
        publications.add(() -> skillService.replaceDefinitions(skillDefinitions));

        var skillTreeSnapshot = skillTreeService.loadMasterDataSnapshot();
        loaded += skillTreeSnapshot.nodes().size();

        var classSnapshot = playerClassService.loadSnapshot();
        loaded += classSnapshot.size();
        publications.add(() -> playerClassService.replaceSnapshot(classSnapshot));
        publications.add(() -> skillTreeService.replaceMasterDataSnapshot(skillTreeSnapshot));
        var guideSnapshot = guideService.loadEntrySnapshot();
        loaded += guideSnapshot.size();
        publications.add(() -> guideService.replaceEntrySnapshot(guideSnapshot));

        var mobSnapshot = mobService.loadTemplateSnapshot();
        loaded += mobSnapshot.size();
        publications.add(() -> mobService.replaceTemplateSnapshot(mobSnapshot));

        var npcSnapshot = npcPlacementService.loadPlacementSnapshot();
        loaded += npcSnapshot.size();
        publications.add(() -> npcPlacementService.replacePlacementSnapshot(npcSnapshot));
        activations.add(new MasterDataActivation("npc", npcPlacementService::activatePlacementSnapshot));

        var mobSpawnerSnapshot = mobSpawnerService.loadMasterDataSnapshot();
        loaded += mobSpawnerSnapshot.definitions().size();
        publications.add(() -> mobSpawnerService.replaceMasterDataSnapshot(mobSpawnerSnapshot));

        var gatheringSnapshot = gatheringService.loadDefinitionSnapshot();
        loaded += gatheringSnapshot.size();
        publications.add(() -> gatheringService.replaceDefinitionSnapshot(gatheringSnapshot));
        activations.add(new MasterDataActivation("gathering", gatheringService::activateDefinitionSnapshot));

        var gatheringSpawnerSnapshot = gatheringSpawnerService.loadMasterDataSnapshot();
        loaded += gatheringSpawnerSnapshot.definitions().size();
        publications.add(() -> gatheringSpawnerService.replaceMasterDataSnapshot(gatheringSpawnerSnapshot));

        var questSnapshot = questService.loadMasterDataSnapshot();
        loaded += questSnapshot.quests().size();
        publications.add(() -> questService.replaceMasterDataSnapshot(questSnapshot));

        var teleporterSnapshot = teleporterService.loadDefinitionSnapshot();
        loaded += teleporterSnapshot.size();
        publications.add(() -> teleporterService.replaceDefinitionSnapshot(teleporterSnapshot));

        var worldSnapshot = worldService.loadDefinitionSnapshot();
        loaded += worldSnapshot.worlds().size();
        publications.add(() -> worldService.replaceDefinitionSnapshot(worldSnapshot));
        activations.add(new MasterDataActivation(
            "world",
            () -> worldService.activateDefinitionSnapshot(worldSnapshot)
        ));

        var dungeonSnapshot = dungeonService.loadDefinitionSnapshot(
            mobSnapshot,
            worldSnapshot.worldsById()
        );
        loaded += dungeonSnapshot.loadedById().size();
        publications.add(() -> dungeonService.replaceDefinitionSnapshot(dungeonSnapshot));

        var shopSnapshot = shopService.loadCacheSnapshot();
        loaded += shopSnapshot.size();
        publications.add(() -> shopService.replaceCacheSnapshot(shopSnapshot));
        return new MasterDataReloadPlan(
            loaded,
            fileDatabaseSnapshot,
            yamlDbConfig,
            List.copyOf(publications),
            List.copyOf(activations)
        );
    }

    private void publishMasterDataReload(
        @org.jetbrains.annotations.NotNull CompletableFuture<Integer> completion,
        long generation,
        @org.jetbrains.annotations.NotNull MasterDataReloadPlan plan
    ) {
        if (!isCurrentMasterDataReload(completion, generation) || !isEnabled()) {
            completion.completeExceptionally(new IllegalStateException("古いマスターデータ再読込の完了結果です"));
            masterDataReloadInFlight.compareAndSet(completion, null);
            return;
        }

        try {
            for (Runnable publication : plan.publications()) {
                publication.run();
            }
            FileDatabaseManager.getInstance().replaceReloadSnapshot(plan.fileDatabaseSnapshot());
            YamlDbConfigUtil.INSTANCE.replaceSnapshot(plan.yamlDbConfig());
            for (MasterDataActivation activation : plan.activations()) {
                try {
                    activation.action().run();
                } catch (RuntimeException activationFailure) {
                    Logger.log(LogId.W_1550, activationFailure, activation.target());
                }
            }
            completion.complete(plan.loadedCount());
        } catch (RuntimeException publicationFailure) {
            completion.completeExceptionally(publicationFailure);
        } finally {
            masterDataReloadInFlight.compareAndSet(completion, null);
        }
    }

    private boolean isCurrentMasterDataReload(
        @org.jetbrains.annotations.NotNull CompletableFuture<Integer> completion,
        long generation
    ) {
        return masterDataReloadGeneration.get() == generation
            && masterDataReloadInFlight.get() == completion;
    }

    /** マスターデータ再読込の開始結果です。 */
    public record MasterDataReloadStart(
        boolean started,
        @org.jetbrains.annotations.NotNull CompletableFuture<Integer> completion
    ) {
    }

    private record MasterDataReloadPlan(
        int loadedCount,
        @org.jetbrains.annotations.NotNull FileDatabaseManager.ReloadSnapshot fileDatabaseSnapshot,
        @org.jetbrains.annotations.NotNull YamlDbConfig yamlDbConfig,
        @org.jetbrains.annotations.NotNull List<Runnable> publications,
        @org.jetbrains.annotations.NotNull List<MasterDataActivation> activations
    ) {
    }

    private record MasterDataActivation(
        @org.jetbrains.annotations.NotNull String target,
        @org.jetbrains.annotations.NotNull Runnable action
    ) {
    }

    public BossChallengeService getBossChallengeService() {
        return bossChallengeService;
    }

    public DungeonService getDungeonService() {
        return dungeonService;
    }

    public PartyService getPartyService() {
        return partyService;
    }

    public PartyGui getPartyGui() {
        return partyGui;
    }

    public PartyMemberActionGui getPartyMemberActionGui() {
        return partyMemberActionGui;
    }

    public MailGuiEventHandler getMailGuiEventHandler() {
        return mailGuiEventHandler;
    }

    /**
     * 管理者用アイテム一覧 GUI のイベントハンドラを返します。
     *
     * @return 管理者用アイテム一覧 GUI イベントハンドラ
     */
    public ItemAdminGuiEventHandler getItemAdminGuiEventHandler() {
        return itemAdminGuiEventHandler;
    }

    public AdventureRecordGuiEventHandler getAdventureRecordGuiEventHandler() {
        return adventureRecordGuiEventHandler;
    }

    public AdventureRecordService getAdventureRecordService() {
        return adventureRecordService;
    }

    public ShopService getShopService() {
        return shopService;
    }

    public ShopGuiEventHandler getShopGuiEventHandler() {
        return shopGuiEventHandler;
    }

    public QuestGuiEventHandler getQuestGuiEventHandler() {
        return questGuiEventHandler;
    }

    /**
     * トレードサービスを取得する。
     *
     * @return トレードサービス
     */
    public TradeService getTradeService() {
        return tradeService;
    }

    public MarketGuiEventHandler getMarketGuiEventHandler() {
        return marketGuiEventHandler;
    }
}
