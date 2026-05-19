using AstralRecordApi.Data.Entities;
using Microsoft.EntityFrameworkCore;

namespace AstralRecordApi.Data;

public class AstralRecordDbContext(DbContextOptions<AstralRecordDbContext> options) : DbContext(options)
{
    public DbSet<UserEntity> Users => Set<UserEntity>();
    public DbSet<AccountEntity> Accounts => Set<AccountEntity>();
    public DbSet<InventoryEntity> Inventories => Set<InventoryEntity>();
    public DbSet<InventoryEntryEntity> InventoryEntries => Set<InventoryEntryEntity>();
    public DbSet<EquipmentInstanceEntity> EquipmentInstances => Set<EquipmentInstanceEntity>();
    public DbSet<EquipmentLoadoutEntity> EquipmentLoadouts => Set<EquipmentLoadoutEntity>();
    public DbSet<EquipmentLoadoutSlotEntity> EquipmentLoadoutSlots => Set<EquipmentLoadoutSlotEntity>();
    public DbSet<EquipmentInstanceStatRollEntity> EquipmentInstanceStatRolls => Set<EquipmentInstanceStatRollEntity>();
    public DbSet<EquipmentInstanceEnchantEntity> EquipmentInstanceEnchants => Set<EquipmentInstanceEnchantEntity>();
    public DbSet<EquipmentInstanceRuneEntity> EquipmentInstanceRunes => Set<EquipmentInstanceRuneEntity>();
    public DbSet<RuneInstanceEntity> RuneInstances => Set<RuneInstanceEntity>();
    public DbSet<RuneInstanceStatRollEntity> RuneInstanceStatRolls => Set<RuneInstanceStatRollEntity>();
    public DbSet<EquipmentInstanceEnchantPoolEntity> EquipmentInstanceEnchantPools => Set<EquipmentInstanceEnchantPoolEntity>();

    protected override void OnModelCreating(ModelBuilder modelBuilder)
    {
        modelBuilder.Entity<UserEntity>(entity =>
        {
            entity.ToTable("user", "dbo");
            entity.HasKey(user => user.Uuid);

            entity.Property(user => user.Uuid).HasColumnName("uuid");
            entity.Property(user => user.Mcid).HasColumnName("mcid");
            entity.Property(user => user.JoinDate).HasColumnName("join_date");
            entity.Property(user => user.LastJoinDate).HasColumnName("last_join_date");
            entity.Property(user => user.GlobalIp).HasColumnName("global_ip");
            entity.Property(user => user.AccountId).HasColumnName("account_id");
            entity.Property(user => user.BanIndefinite).HasColumnName("ban_indefinite");
            entity.Property(user => user.BanDate).HasColumnName("ban_date");
            entity.Property(user => user.KickIp).HasColumnName("kick_ip");
            entity.Property(user => user.Permission).HasColumnName("permission");
            entity.Property(user => user.CreatedAt).HasColumnName("created_at");
            entity.Property(user => user.UpdatedAt).HasColumnName("updated_at");
            entity.Property(user => user.CreatedBy).HasColumnName("created_by");
            entity.Property(user => user.UpdatedBy).HasColumnName("updated_by");
            entity.Property(user => user.IsDeleted).HasColumnName("is_deleted");
        });

        modelBuilder.Entity<AccountEntity>(entity =>
        {
            entity.ToTable("account", "dbo");
            entity.HasKey(account => account.Uuid);

            entity.Property(account => account.Uuid).HasColumnName("uuid");
            entity.Property(account => account.UserId).HasColumnName("user_id");
            entity.Property(account => account.AccountName).HasColumnName("account_name");
            entity.Property(account => account.SlotIndex).HasColumnName("slot_index");
            entity.Property(account => account.IsActive).HasColumnName("is_active");
            entity.Property(account => account.Mode).HasColumnName("mode");
            entity.Property(account => account.MenuShortcutsJson).HasColumnName("menu_shortcuts_json");
            entity.Property(account => account.CreatedAt).HasColumnName("created_at");
            entity.Property(account => account.UpdatedAt).HasColumnName("updated_at");
            entity.Property(account => account.CreatedBy).HasColumnName("created_by");
            entity.Property(account => account.UpdatedBy).HasColumnName("updated_by");
            entity.Property(account => account.IsDeleted).HasColumnName("is_deleted");
        });

        modelBuilder.Entity<InventoryEntity>(entity =>
        {
            entity.ToTable("inventory", "dbo");
            entity.HasKey(inventory => inventory.InventoryId);

            entity.Property(inventory => inventory.InventoryId).HasColumnName("inventory_id");
            entity.Property(inventory => inventory.AccountId).HasColumnName("account_id");
            entity.Property(inventory => inventory.InventoryType).HasColumnName("inventory_type");
            entity.Property(inventory => inventory.InventoryProfile).HasColumnName("inventory_profile");
            entity.Property(inventory => inventory.SlotCapacity).HasColumnName("slot_capacity");
            entity.Property(inventory => inventory.IsEnabled).HasColumnName("is_enabled");
            entity.Property(inventory => inventory.MetadataJson).HasColumnName("metadata_json");
            entity.Property(inventory => inventory.CreatedAt).HasColumnName("created_at");
            entity.Property(inventory => inventory.UpdatedAt).HasColumnName("updated_at");
            entity.Property(inventory => inventory.CreatedBy).HasColumnName("created_by");
            entity.Property(inventory => inventory.UpdatedBy).HasColumnName("updated_by");
            entity.Property(inventory => inventory.IsDeleted).HasColumnName("is_deleted");
        });

        modelBuilder.Entity<InventoryEntryEntity>(entity =>
        {
            entity.ToTable("inventory_entry", "dbo");
            entity.HasKey(entry => entry.InventoryEntryId);

            entity.Property(entry => entry.InventoryEntryId).HasColumnName("inventory_entry_id");
            entity.Property(entry => entry.InventoryId).HasColumnName("inventory_id");
            entity.Property(entry => entry.SlotIndex).HasColumnName("slot_index");
            entity.Property(entry => entry.ItemCategory).HasColumnName("item_category");
            entity.Property(entry => entry.ItemId).HasColumnName("item_id");
            entity.Property(entry => entry.InstanceType).HasColumnName("instance_type");
            entity.Property(entry => entry.InstanceId).HasColumnName("instance_id");
            entity.Property(entry => entry.Quantity).HasColumnName("quantity");
            entity.Property(entry => entry.MetadataJson).HasColumnName("metadata_json");
            entity.Property(entry => entry.CreatedAt).HasColumnName("created_at");
            entity.Property(entry => entry.UpdatedAt).HasColumnName("updated_at");
            entity.Property(entry => entry.CreatedBy).HasColumnName("created_by");
            entity.Property(entry => entry.UpdatedBy).HasColumnName("updated_by");
            entity.Property(entry => entry.IsDeleted).HasColumnName("is_deleted");
        });

        modelBuilder.Entity<EquipmentInstanceEntity>(entity =>
        {
            entity.ToTable("equipment_instance", "dbo");
            entity.HasKey(e => e.EquipmentInstanceId);

            entity.Property(e => e.EquipmentInstanceId).HasColumnName("equipment_instance_id");
            entity.Property(e => e.AccountId).HasColumnName("account_id");
            entity.Property(e => e.ItemId).HasColumnName("item_id");
            entity.Property(e => e.EnhanceLevel).HasColumnName("enhance_level");
            entity.Property(e => e.RuneMaxSlots).HasColumnName("rune_max_slots");
            entity.Property(e => e.TranscendenceRank).HasColumnName("transcendence_rank");
            entity.Property(e => e.DurabilityMax).HasColumnName("durability_max");
            entity.Property(e => e.DurabilityValue).HasColumnName("durability_value");
            entity.Property(e => e.CreatedAt).HasColumnName("created_at");
            entity.Property(e => e.UpdatedAt).HasColumnName("updated_at");
            entity.Property(e => e.CreatedBy).HasColumnName("created_by");
            entity.Property(e => e.UpdatedBy).HasColumnName("updated_by");
            entity.Property(e => e.IsDeleted).HasColumnName("is_deleted");
        });

        modelBuilder.Entity<EquipmentLoadoutEntity>(entity =>
        {
            entity.ToTable("equipment_loadout", "dbo");
            entity.HasKey(e => e.EquipmentLoadoutId);

            entity.Property(e => e.EquipmentLoadoutId).HasColumnName("equipment_loadout_id");
            entity.Property(e => e.AccountId).HasColumnName("account_id");
            entity.Property(e => e.LoadoutProfile).HasColumnName("loadout_profile");
            entity.Property(e => e.LoadoutName).HasColumnName("loadout_name");
            entity.Property(e => e.SortOrder).HasColumnName("sort_order");
            entity.Property(e => e.IsActive).HasColumnName("is_active");
            entity.Property(e => e.MetadataJson).HasColumnName("metadata_json");
            entity.Property(e => e.CreatedAt).HasColumnName("created_at");
            entity.Property(e => e.UpdatedAt).HasColumnName("updated_at");
            entity.Property(e => e.CreatedBy).HasColumnName("created_by");
            entity.Property(e => e.UpdatedBy).HasColumnName("updated_by");
            entity.Property(e => e.IsDeleted).HasColumnName("is_deleted");
        });

        modelBuilder.Entity<EquipmentLoadoutSlotEntity>(entity =>
        {
            entity.ToTable("equipment_loadout_slot", "dbo");
            entity.HasKey(e => e.EquipmentLoadoutSlotId);

            entity.Property(e => e.EquipmentLoadoutSlotId).HasColumnName("equipment_loadout_slot_id");
            entity.Property(e => e.EquipmentLoadoutId).HasColumnName("equipment_loadout_id");
            entity.Property(e => e.SlotType).HasColumnName("slot_type");
            entity.Property(e => e.SlotIndex).HasColumnName("slot_index");
            entity.Property(e => e.EquipmentInstanceId).HasColumnName("equipment_instance_id");
            entity.Property(e => e.CreatedAt).HasColumnName("created_at");
            entity.Property(e => e.UpdatedAt).HasColumnName("updated_at");
            entity.Property(e => e.CreatedBy).HasColumnName("created_by");
            entity.Property(e => e.UpdatedBy).HasColumnName("updated_by");
            entity.Property(e => e.IsDeleted).HasColumnName("is_deleted");
        });

        modelBuilder.Entity<EquipmentInstanceStatRollEntity>(entity =>
        {
            entity.ToTable("equipment_instance_stat_roll", "dbo");
            entity.HasKey(e => e.StatRollId);

            entity.Property(e => e.StatRollId).HasColumnName("stat_roll_id");
            entity.Property(e => e.EquipmentInstanceId).HasColumnName("equipment_instance_id");
            entity.Property(e => e.Status).HasColumnName("status");
            entity.Property(e => e.RandomMin).HasColumnName("random_min");
            entity.Property(e => e.RandomMax).HasColumnName("random_max");
            entity.Property(e => e.SortOrder).HasColumnName("sort_order");
            entity.Property(e => e.CreatedAt).HasColumnName("created_at");
            entity.Property(e => e.UpdatedAt).HasColumnName("updated_at");
            entity.Property(e => e.CreatedBy).HasColumnName("created_by");
            entity.Property(e => e.UpdatedBy).HasColumnName("updated_by");
        });

        modelBuilder.Entity<EquipmentInstanceEnchantEntity>(entity =>
        {
            entity.ToTable("equipment_instance_enchant", "dbo");
            entity.HasKey(e => e.EnchantId);

            entity.Property(e => e.EnchantId).HasColumnName("enchant_id");
            entity.Property(e => e.EquipmentInstanceId).HasColumnName("equipment_instance_id");
            entity.Property(e => e.SlotIndex).HasColumnName("slot_index");
            entity.Property(e => e.PoolIndex).HasColumnName("pool_index");
            entity.Property(e => e.Status).HasColumnName("status");
            entity.Property(e => e.Type).HasColumnName("type");
            entity.Property(e => e.Value).HasColumnName("value").HasPrecision(18, 4);
            entity.Property(e => e.CreatedAt).HasColumnName("created_at");
            entity.Property(e => e.UpdatedAt).HasColumnName("updated_at");
            entity.Property(e => e.CreatedBy).HasColumnName("created_by");
            entity.Property(e => e.UpdatedBy).HasColumnName("updated_by");
        });

        modelBuilder.Entity<EquipmentInstanceRuneEntity>(entity =>
        {
            entity.ToTable("equipment_instance_rune", "dbo");
            entity.HasKey(e => e.RuneId);

            entity.Property(e => e.RuneId).HasColumnName("rune_id");
            entity.Property(e => e.EquipmentInstanceId).HasColumnName("equipment_instance_id");
            entity.Property(e => e.RuneInstanceId).HasColumnName("rune_instance_id");
            entity.Property(e => e.SlotIndex).HasColumnName("slot_index");
            entity.Property(e => e.ItemId).HasColumnName("item_id");
            entity.Property(e => e.CreatedAt).HasColumnName("created_at");
            entity.Property(e => e.UpdatedAt).HasColumnName("updated_at");
            entity.Property(e => e.CreatedBy).HasColumnName("created_by");
            entity.Property(e => e.UpdatedBy).HasColumnName("updated_by");
        });

        modelBuilder.Entity<RuneInstanceEntity>(entity =>
        {
            entity.ToTable("rune_instance", "dbo");
            entity.HasKey(e => e.RuneInstanceId);

            entity.Property(e => e.RuneInstanceId).HasColumnName("rune_instance_id");
            entity.Property(e => e.AccountId).HasColumnName("account_id");
            entity.Property(e => e.ItemId).HasColumnName("item_id");
            entity.Property(e => e.CreatedAt).HasColumnName("created_at");
            entity.Property(e => e.UpdatedAt).HasColumnName("updated_at");
            entity.Property(e => e.CreatedBy).HasColumnName("created_by");
            entity.Property(e => e.UpdatedBy).HasColumnName("updated_by");
            entity.Property(e => e.IsDeleted).HasColumnName("is_deleted");
        });

        modelBuilder.Entity<RuneInstanceStatRollEntity>(entity =>
        {
            entity.ToTable("rune_instance_stat_roll", "dbo");
            entity.HasKey(e => e.StatRollId);

            entity.Property(e => e.StatRollId).HasColumnName("stat_roll_id");
            entity.Property(e => e.RuneInstanceId).HasColumnName("rune_instance_id");
            entity.Property(e => e.Status).HasColumnName("status");
            entity.Property(e => e.Type).HasColumnName("type");
            entity.Property(e => e.RandomValue).HasColumnName("random_value");
            entity.Property(e => e.SortOrder).HasColumnName("sort_order");
            entity.Property(e => e.CreatedAt).HasColumnName("created_at");
            entity.Property(e => e.UpdatedAt).HasColumnName("updated_at");
            entity.Property(e => e.CreatedBy).HasColumnName("created_by");
            entity.Property(e => e.UpdatedBy).HasColumnName("updated_by");
            entity.Property(e => e.IsDeleted).HasColumnName("is_deleted");
        });

        modelBuilder.Entity<EquipmentInstanceEnchantPoolEntity>(entity =>
        {
            entity.ToTable("equipment_instance_enchant_pool", "dbo");
            entity.HasKey(e => e.EnchantPoolId);

            entity.Property(e => e.EnchantPoolId).HasColumnName("enchant_pool_id");
            entity.Property(e => e.EquipmentInstanceId).HasColumnName("equipment_instance_id");
            entity.Property(e => e.PoolIndex).HasColumnName("pool_index");
            entity.Property(e => e.RecipeId).HasColumnName("recipe_id");
            entity.Property(e => e.RequiredMaterialItemId).HasColumnName("required_material_item_id");
            entity.Property(e => e.RequiredMaterialAmount).HasColumnName("required_material_amount");
            entity.Property(e => e.RequiredCurrency).HasColumnName("required_currency");
            entity.Property(e => e.CreatedAt).HasColumnName("created_at");
            entity.Property(e => e.UpdatedAt).HasColumnName("updated_at");
            entity.Property(e => e.CreatedBy).HasColumnName("created_by");
            entity.Property(e => e.UpdatedBy).HasColumnName("updated_by");
            entity.Property(e => e.IsDeleted).HasColumnName("is_deleted");
        });
    }
}
