using System.Data;
using System.Text.Json;
using AstralRecordApi.Data.Entities;
using AstralRecordApi.Models;
using Microsoft.Data.SqlClient;

namespace AstralRecordApi.Repositories;

public sealed class WorldPlacementRepository(IConfiguration configuration) : IWorldPlacementRepository
{
    private static readonly JsonSerializerOptions JsonOptions = new(JsonSerializerDefaults.Web);
    private const string Table = "[dbo].[world_placement_snapshot]";

    public async Task<WorldPlacementSnapshot> GetAsync(string environment, CancellationToken cancellationToken)
    {
        await using var connection = new SqlConnection(Connection(environment));
        await connection.OpenAsync(cancellationToken);
        var row = await ReadAsync(connection, null, Table, false, cancellationToken);
        return Snapshot(row);
    }

    public Task<WorldPlacementSnapshot?> ReplaceDevAsync(WorldPlacementReplaceRequest request, CancellationToken cancellationToken)
        => MutateDevAsync(request.ExpectedRevision, _ => request.Placements.ToArray(), cancellationToken);

    public Task<WorldPlacementSnapshot?> UpsertDevAsync(WorldPlacementUpsertRequest request, CancellationToken cancellationToken)
        => MutateDevAsync(request.ExpectedRevision, placements =>
        {
            var index = Array.FindIndex(placements, x => x.PlacementId == request.Placement.PlacementId);
            if (index >= 0) placements[index] = request.Placement;
            else placements = [.. placements, request.Placement];
            return placements;
        }, cancellationToken);

    public Task<WorldPlacementSnapshot?> DeleteDevAsync(string placementId, string expectedRevision, CancellationToken cancellationToken)
        => MutateDevAsync(expectedRevision, placements => placements.Where(x => x.PlacementId != placementId).ToArray(), cancellationToken);

    public async Task<WorldPlacementSnapshot?> SyncAsync(WorldPlacementSyncRequest request, CancellationToken cancellationToken)
    {
        var dev = ConfiguredConnection("dev");
        var channels = ConfiguredConnection("channels");
        // Cross-database SQL uses a single local transaction. The two explicit connections must
        // identify the same SQL Server and login; MSDTC is never required.
        if (!string.Equals(dev.DataSource, channels.DataSource, StringComparison.OrdinalIgnoreCase)
            || !string.Equals(dev.UserID, channels.UserID, StringComparison.Ordinal)
            || !string.Equals(dev.Password, channels.Password, StringComparison.Ordinal)
            || dev.IntegratedSecurity != channels.IntegratedSecurity)
            throw new InvalidOperationException("Placement sync requires both databases on the same SQL Server with the same login.");
        await using var connection = new SqlConnection(dev.ConnectionString);
        await connection.OpenAsync(cancellationToken);
        await using var transaction = (SqlTransaction)await connection.BeginTransactionAsync(IsolationLevel.Serializable, cancellationToken);
        var channelTable = $"[{channels.InitialCatalog.Replace("]", "]]", StringComparison.Ordinal)}].[dbo].[world_placement_snapshot]";
        var source = await ReadAsync(connection, transaction, Table, true, cancellationToken);
        var target = await ReadAsync(connection, transaction, channelTable, true, cancellationToken);
        if (source.Revision.ToString() != request.ExpectedDevRevision || target.Revision.ToString() != request.ExpectedChannelRevision)
            return null;
        await WriteAsync(connection, transaction, channelTable, target.Revision + 1, source.PlacementsJson, cancellationToken);
        await transaction.CommitAsync(cancellationToken);
        return new WorldPlacementSnapshot(1, (target.Revision + 1).ToString(), Deserialize(source.PlacementsJson));
    }

    private async Task<WorldPlacementSnapshot?> MutateDevAsync(
        string expectedRevision, Func<WorldPlacement[], WorldPlacement[]> change, CancellationToken cancellationToken)
    {
        await using var connection = new SqlConnection(Connection("dev"));
        await connection.OpenAsync(cancellationToken);
        await using var transaction = (SqlTransaction)await connection.BeginTransactionAsync(IsolationLevel.Serializable, cancellationToken);
        var row = await ReadAsync(connection, transaction, Table, true, cancellationToken);
        if (row.Revision.ToString() != expectedRevision) return null;
        var previous = Deserialize(row.PlacementsJson);
        var placements = change(previous);
        WorldPlacementValidation.Validate(placements);
        WorldPlacementValidation.ValidateStableWaystoneIds(previous, placements);
        await WriteAsync(connection, transaction, Table, row.Revision + 1, JsonSerializer.Serialize(placements, JsonOptions), cancellationToken);
        await transaction.CommitAsync(cancellationToken);
        return new WorldPlacementSnapshot(1, (row.Revision + 1).ToString(), placements);
    }

    private static async Task<WorldPlacementSnapshotEntity> ReadAsync(
        SqlConnection connection, SqlTransaction? transaction, string table, bool lockRow, CancellationToken cancellationToken)
    {
        await using var command = connection.CreateCommand();
        command.Transaction = transaction;
        command.CommandText = $"SELECT revision, placements_json, updated_at_utc FROM {table}{(lockRow ? " WITH (UPDLOCK, HOLDLOCK)" : "")} WHERE id = 1";
        await using var reader = await command.ExecuteReaderAsync(cancellationToken);
        if (!await reader.ReadAsync(cancellationToken))
            throw new InvalidOperationException("Placement database is not initialized. Apply its init.sql before use.");
        return new WorldPlacementSnapshotEntity
        {
            Id = 1, Revision = reader.GetInt64(0), PlacementsJson = reader.GetString(1), UpdatedAtUtc = reader.GetDateTime(2)
        };
    }

    private static async Task WriteAsync(SqlConnection connection, SqlTransaction transaction, string table,
        long revision, string json, CancellationToken cancellationToken)
    {
        await using var command = connection.CreateCommand();
        command.Transaction = transaction;
        command.CommandText = $"UPDATE {table} SET revision = @revision, placements_json = @json, updated_at_utc = SYSUTCDATETIME() WHERE id = 1";
        command.Parameters.Add("@revision", SqlDbType.BigInt).Value = revision;
        command.Parameters.Add("@json", SqlDbType.NVarChar, -1).Value = json;
        if (await command.ExecuteNonQueryAsync(cancellationToken) != 1)
            throw new InvalidOperationException("Placement snapshot row is missing.");
    }

    private static WorldPlacementSnapshot Snapshot(WorldPlacementSnapshotEntity row)
        => new(1, row.Revision.ToString(), Deserialize(row.PlacementsJson));

    private static WorldPlacement[] Deserialize(string json)
        => JsonSerializer.Deserialize<WorldPlacement[]>(json, JsonOptions) ?? [];

    private string Connection(string environment) => ConfiguredConnection(environment).ConnectionString;

    private SqlConnectionStringBuilder ConfiguredConnection(string environment)
    {
        var name = environment == "dev" ? "WorldPlacementDev" : environment == "channels" ? "WorldPlacement" : throw new ArgumentException("Unknown placement environment.");
        var value = configuration.GetConnectionString(name);
        if (string.IsNullOrWhiteSpace(value))
            throw new InvalidOperationException($"Connection string '{name}' is disabled.");
        var parsed = new SqlConnectionStringBuilder(value);
        var requiredDatabase = environment == "dev" ? "WorldPlacementDevDB" : "WorldPlacementDB";
        if (!string.Equals(parsed.InitialCatalog, requiredDatabase, StringComparison.OrdinalIgnoreCase))
            throw new InvalidOperationException($"Connection string '{name}' must target {requiredDatabase}.");
        foreach (var protectedName in new[] { "SqlServer", "MasterData", "History", "Management" })
        {
            var protectedValue = configuration.GetConnectionString(protectedName);
            if (string.IsNullOrWhiteSpace(protectedValue)) continue;
            var protectedConnection = new SqlConnectionStringBuilder(protectedValue);
            if (string.Equals(parsed.DataSource, protectedConnection.DataSource, StringComparison.OrdinalIgnoreCase)
                && string.Equals(parsed.InitialCatalog, protectedConnection.InitialCatalog, StringComparison.OrdinalIgnoreCase))
                throw new InvalidOperationException($"Placement connection '{name}' aliases '{protectedName}'.");
        }
        return parsed;
    }
}
