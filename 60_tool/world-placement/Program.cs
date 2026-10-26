using System.Text.Json;
using System.Text.RegularExpressions;
using Microsoft.Data.SqlClient;

// Explicit bootstrap only. The API never creates or resets placement databases.
if (args.Length != 6 || args[0] != "--settings" || args[2] != "--environment" || args[4] != "--schema")
{
    Console.Error.WriteLine("Usage: --settings <private API appsettings.json> --environment dev|channels --schema <init.sql>");
    return 2;
}
var environment = args[3];
var expected = environment switch
{
    "dev" => (Key: "WorldPlacementDev", Database: "WorldPlacementDevDB"),
    "channels" => (Key: "WorldPlacement", Database: "WorldPlacementDB"),
    _ => throw new ArgumentException("Environment must be dev or channels.")
};
using var settings = JsonDocument.Parse(File.ReadAllText(Path.GetFullPath(args[1])));
var connection = settings.RootElement.GetProperty("ConnectionStrings").GetProperty(expected.Key).GetString();
if (string.IsNullOrWhiteSpace(connection)) throw new InvalidOperationException("Explicit placement connection is required.");
var builder = new SqlConnectionStringBuilder(connection);
if (!string.Equals(builder.InitialCatalog, expected.Database, StringComparison.OrdinalIgnoreCase))
    throw new InvalidOperationException($"Connection must target {expected.Database}.");
var schema = File.ReadAllText(Path.GetFullPath(args[5]));
if (!schema.Contains("world_placement_snapshot", StringComparison.Ordinal)
    || !schema.Contains("DB_NAME()", StringComparison.Ordinal))
    throw new InvalidOperationException("Expected the guarded placement init.sql.");
var masterBuilder = new SqlConnectionStringBuilder(connection) { InitialCatalog = "master" };
await using (var master = new SqlConnection(masterBuilder.ConnectionString))
{
    await master.OpenAsync();
    await using var create = master.CreateCommand();
    create.CommandText = $"IF DB_ID(@name) IS NULL CREATE DATABASE [{expected.Database}];";
    create.Parameters.AddWithValue("@name", expected.Database);
    await create.ExecuteNonQueryAsync();
}
await using var database = new SqlConnection(connection);
await database.OpenAsync();
await using var transaction = (SqlTransaction)await database.BeginTransactionAsync();
foreach (var batch in Regex.Split(schema, @"^\s*GO\s*;?\s*$", RegexOptions.Multiline | RegexOptions.IgnoreCase))
{
    if (string.IsNullOrWhiteSpace(batch)) continue;
    await using var command = database.CreateCommand();
    command.Transaction = transaction;
    command.CommandText = batch;
    await command.ExecuteNonQueryAsync();
}
await transaction.CommitAsync();
Console.WriteLine($"Ready: {expected.Database}. Existing placements retained; no other database modified.");
return 0;
