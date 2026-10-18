using System.Text.Json;
using System.Text.Json.Serialization;

namespace AstralRecordControl.Contracts;

// P0-P2 Agents sent an empty metrics array. Keep that v1 wire shape usable during
// independent Web/Agent updates; actual P3 samples use the typed object contract.
public sealed class HeartbeatMetricsConverter : JsonConverter<AgentMetricsBatch>
{
    public override AgentMetricsBatch? Read(ref Utf8JsonReader reader, Type typeToConvert, JsonSerializerOptions options)
    {
        if (reader.TokenType == JsonTokenType.StartArray)
        {
            if (!reader.Read() || reader.TokenType != JsonTokenType.EndArray) throw new JsonException("Unsupported legacy metrics payload.");
            return null;
        }
        return JsonSerializer.Deserialize<AgentMetricsBatch>(ref reader, options);
    }
    public override void Write(Utf8JsonWriter writer, AgentMetricsBatch value, JsonSerializerOptions options) => JsonSerializer.Serialize(writer, value, options);
}
