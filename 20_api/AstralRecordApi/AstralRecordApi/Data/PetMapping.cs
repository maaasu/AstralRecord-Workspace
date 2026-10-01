using AstralRecordApi.Data.Entities;
using Microsoft.EntityFrameworkCore;

namespace AstralRecordApi.Data;

internal static class PetMapping
{
    internal static void Configure(ModelBuilder model)
    {
        model.Entity<InventoryEntryEntity>().HasIndex(e => e.InstanceId).IsUnique()
            .HasFilter("[is_deleted] = 0 AND [instance_type] IN ('PET', 'PET_EGG')")
            .HasDatabaseName("UX_inventory_entry_pet_instance");
        model.Entity<PetInstanceEntity>(e =>
        {
            e.ToTable("pet_instance", "dbo"); e.HasKey(p => p.InstanceId);
            e.Property(p => p.InstanceId).HasColumnName("instance_id");
            e.Property(p => p.AccountId).HasColumnName("account_id");
            e.Property(p => p.SpeciesId).HasColumnName("species_id").HasMaxLength(64);
            e.Property(p => p.ItemId).HasColumnName("item_id").HasMaxLength(100);
            e.Property(p => p.IsEgg).HasColumnName("is_egg");
            e.Property(p => p.Origin).HasColumnName("origin").HasMaxLength(8);
            e.Property(p => p.DetailsJson).HasColumnName("details_json");
            e.Property(p => p.MaleParentId).HasColumnName("male_parent_id");
            e.Property(p => p.FemaleParentId).HasColumnName("female_parent_id");
            e.Property(p => p.Version).HasColumnName("version").IsConcurrencyToken();
            e.Property(p => p.CreatedAt).HasColumnName("created_at").HasColumnType("datetime2(3)");
            e.Property(p => p.UpdatedAt).HasColumnName("updated_at").HasColumnType("datetime2(3)");
            e.Property(p => p.CreatedBy).HasColumnName("created_by");
            e.Property(p => p.UpdatedBy).HasColumnName("updated_by");
            e.Property(p => p.IsDeleted).HasColumnName("is_deleted");
            e.HasOne<AccountEntity>().WithMany().HasForeignKey(p => p.AccountId).OnDelete(DeleteBehavior.NoAction);
            e.HasIndex(p => new { p.AccountId, p.IsDeleted }).HasDatabaseName("IX_pet_instance_owner");
        });
        model.Entity<AccountPetStateEntity>(e =>
        {
            e.ToTable("account_pet_state", "dbo"); e.HasKey(p => p.AccountId);
            e.Property(p => p.AccountId).HasColumnName("account_id");
            e.Property(p => p.EquippedPetId).HasColumnName("equipped_pet_id");
            e.Property(p => p.UpdatedAt).HasColumnName("updated_at").HasColumnType("datetime2(3)");
            e.Property(p => p.UpdatedBy).HasColumnName("updated_by");
            e.HasOne<AccountEntity>().WithMany().HasForeignKey(p => p.AccountId).OnDelete(DeleteBehavior.NoAction);
            e.HasOne<PetInstanceEntity>().WithMany().HasForeignKey(p => p.EquippedPetId).OnDelete(DeleteBehavior.NoAction);
        });
        model.Entity<PetOperationEntity>(e =>
        {
            e.ToTable("pet_operation", "dbo"); e.HasKey(p => p.OperationId);
            e.Property(p => p.OperationId).HasColumnName("operation_id");
            e.Property(p => p.AccountId).HasColumnName("account_id");
            e.Property(p => p.RequestHash).HasColumnName("request_hash").HasColumnType("char(64)").IsFixedLength();
            e.Property(p => p.ResponseJson).HasColumnName("response_json");
            e.Property(p => p.AffectedEntryIdsJson).HasColumnName("affected_entry_ids_json");
            e.Property(p => p.CompletedAt).HasColumnName("completed_at").HasColumnType("datetime2(3)");
            e.HasOne<AccountEntity>().WithMany().HasForeignKey(p => p.AccountId).OnDelete(DeleteBehavior.NoAction);
            e.HasIndex(p => new { p.AccountId, p.CompletedAt }).HasDatabaseName("IX_pet_operation_owner_completed");
        });
    }
}
