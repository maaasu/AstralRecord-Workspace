using AstralRecordApi.Data.Entities;
using Microsoft.EntityFrameworkCore;

namespace AstralRecordApi.Data;

public class MasterDataDbContext(DbContextOptions<MasterDataDbContext> options) : DbContext(options)
{
    public DbSet<MasterDataSourceEntity> Sources => Set<MasterDataSourceEntity>();
    public DbSet<MasterDataEntryEntity> Entries => Set<MasterDataEntryEntity>();
    public DbSet<MasterDataReferenceEntity> References => Set<MasterDataReferenceEntity>();
    public DbSet<MasterDataSeedRunEntity> SeedRuns => Set<MasterDataSeedRunEntity>();

    protected override void OnModelCreating(ModelBuilder modelBuilder)
    {
        modelBuilder.Entity<MasterDataSourceEntity>(entity =>
        {
            entity.ToTable("master_data_source", "dbo");
            entity.HasKey(source => source.SourceId);

            entity.Property(source => source.SourceId).HasColumnName("source_id");
            entity.Property(source => source.SourceKey).HasColumnName("source_key");
            entity.Property(source => source.SourcePath).HasColumnName("source_path");
            entity.Property(source => source.SourceKind).HasColumnName("source_kind");
            entity.Property(source => source.SchemaVersion).HasColumnName("schema_version");
            entity.Property(source => source.IsEnabled).HasColumnName("is_enabled");
            entity.Property(source => source.CreatedAt).HasColumnName("created_at");
            entity.Property(source => source.UpdatedAt).HasColumnName("updated_at");
            entity.Property(source => source.CreatedBy).HasColumnName("created_by");
            entity.Property(source => source.UpdatedBy).HasColumnName("updated_by");
            entity.Property(source => source.IsDeleted).HasColumnName("is_deleted");
        });

        modelBuilder.Entity<MasterDataEntryEntity>(entity =>
        {
            entity.ToTable("master_data_entry", "dbo");
            entity.HasKey(entry => entry.EntryId);

            entity.Property(entry => entry.EntryId).HasColumnName("entry_id");
            entity.Property(entry => entry.SourceId).HasColumnName("source_id");
            entity.Property(entry => entry.MasterType).HasColumnName("master_type");
            entity.Property(entry => entry.MasterId).HasColumnName("master_id");
            entity.Property(entry => entry.Category).HasColumnName("category");
            entity.Property(entry => entry.Type).HasColumnName("type");
            entity.Property(entry => entry.SchemaVersion).HasColumnName("schema_version");
            entity.Property(entry => entry.DisplayName).HasColumnName("display_name");
            entity.Property(entry => entry.SourceFilePath).HasColumnName("source_file_path");
            entity.Property(entry => entry.SourceFileHash).HasColumnName("source_file_hash")
                .HasColumnType("char(64)").IsFixedLength();
            entity.Property(entry => entry.PayloadJson).HasColumnName("payload_json");
            entity.Property(entry => entry.PayloadVersion).HasColumnName("payload_version");
            entity.Property(entry => entry.EffectiveFrom).HasColumnName("effective_from");
            entity.Property(entry => entry.CreatedAt).HasColumnName("created_at");
            entity.Property(entry => entry.UpdatedAt).HasColumnName("updated_at");
            entity.Property(entry => entry.CreatedBy).HasColumnName("created_by");
            entity.Property(entry => entry.UpdatedBy).HasColumnName("updated_by");
            entity.Property(entry => entry.IsDeleted).HasColumnName("is_deleted");
        });

        modelBuilder.Entity<MasterDataReferenceEntity>(entity =>
        {
            entity.ToTable("master_data_reference", "dbo");
            entity.HasKey(reference => reference.ReferenceId);

            entity.Property(reference => reference.ReferenceId).HasColumnName("reference_id");
            entity.Property(reference => reference.FromEntryId).HasColumnName("from_entry_id");
            entity.Property(reference => reference.FromMasterType).HasColumnName("from_master_type");
            entity.Property(reference => reference.FromMasterId).HasColumnName("from_master_id");
            entity.Property(reference => reference.ReferenceType).HasColumnName("reference_type");
            entity.Property(reference => reference.ReferenceIdValue).HasColumnName("reference_id_value");
            entity.Property(reference => reference.ReferencePath).HasColumnName("reference_path");
            entity.Property(reference => reference.IsRequired).HasColumnName("is_required");
            entity.Property(reference => reference.SortOrder).HasColumnName("sort_order");
            entity.Property(reference => reference.CreatedAt).HasColumnName("created_at");
            entity.Property(reference => reference.UpdatedAt).HasColumnName("updated_at");
            entity.Property(reference => reference.CreatedBy).HasColumnName("created_by");
            entity.Property(reference => reference.UpdatedBy).HasColumnName("updated_by");
            entity.Property(reference => reference.IsDeleted).HasColumnName("is_deleted");
        });

        modelBuilder.Entity<MasterDataSeedRunEntity>(entity =>
        {
            entity.ToTable("master_data_seed_run", "dbo");
            entity.HasKey(run => run.SeedRunId);

            entity.Property(run => run.SeedRunId).HasColumnName("seed_run_id");
            entity.Property(run => run.TriggerType).HasColumnName("trigger_type");
            entity.Property(run => run.Status).HasColumnName("status");
            entity.Property(run => run.SourceRootPath).HasColumnName("source_root_path");
            entity.Property(run => run.StartedAt).HasColumnName("started_at");
            entity.Property(run => run.FinishedAt).HasColumnName("finished_at");
            entity.Property(run => run.FileCount).HasColumnName("file_count");
            entity.Property(run => run.UpsertedCount).HasColumnName("upserted_count");
            entity.Property(run => run.DeletedCount).HasColumnName("deleted_count");
            entity.Property(run => run.SkippedCount).HasColumnName("skipped_count");
            entity.Property(run => run.ErrorMessage).HasColumnName("error_message");
            entity.Property(run => run.CreatedAt).HasColumnName("created_at");
            entity.Property(run => run.UpdatedAt).HasColumnName("updated_at");
            entity.Property(run => run.CreatedBy).HasColumnName("created_by");
            entity.Property(run => run.UpdatedBy).HasColumnName("updated_by");
        });
    }
}
