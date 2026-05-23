using System.Text.Json;
using System.Text.Json.Serialization;
using AstralRecordApi.Models;

namespace AstralRecordApi.Repositories;

/// <summary>
/// MasterDataDB の <c>master_data_entry.payload_json</c> を Response DTO に変換するための
/// 共通 <see cref="JsonSerializerOptions"/>。
/// </summary>
internal static class MasterDataPayloadJson
{
    public static readonly JsonSerializerOptions Options = CreateOptions();

    public static T? Deserialize<T>(string payloadJson)
        => JsonSerializer.Deserialize<T>(payloadJson, Options);

    private static JsonSerializerOptions CreateOptions()
    {
        var options = new JsonSerializerOptions
        {
            PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
            PropertyNameCaseInsensitive = true,
            ReadCommentHandling = JsonCommentHandling.Skip,
            NumberHandling = JsonNumberHandling.AllowReadingFromString,
        };

        options.Converters.Add(new ItemEquipmentStatValueConverter());
        options.Converters.Add(new FlexibleStringConverter());
        return options;
    }

    /// <summary>
    /// <c>value</c> をスカラ（<c>"10~20"</c> など）またはオブジェクト（<c>{min, max}</c>）どちらでも受け付ける Converter。
    /// </summary>
    private sealed class ItemEquipmentStatValueConverter : JsonConverter<ItemEquipmentStatValueResponse>
    {
        public override ItemEquipmentStatValueResponse? Read(
            ref Utf8JsonReader reader, Type typeToConvert, JsonSerializerOptions options)
        {
            switch (reader.TokenType)
            {
                case JsonTokenType.Null:
                    return null;

                case JsonTokenType.String:
                    return FromScalar(reader.GetString() ?? string.Empty);

                case JsonTokenType.Number:
                    return FromScalar(reader.TryGetInt64(out var longValue)
                        ? longValue.ToString()
                        : reader.GetDouble().ToString());

                case JsonTokenType.StartObject:
                    string? min = null;
                    string? max = null;
                    while (reader.Read())
                    {
                        if (reader.TokenType == JsonTokenType.EndObject)
                            break;

                        if (reader.TokenType != JsonTokenType.PropertyName)
                            continue;

                        var propertyName = reader.GetString();
                        reader.Read();
                        var stringValue = ReadAsString(ref reader);
                        if (string.Equals(propertyName, "min", StringComparison.OrdinalIgnoreCase))
                            min = stringValue;
                        else if (string.Equals(propertyName, "max", StringComparison.OrdinalIgnoreCase))
                            max = stringValue;
                    }
                    return new ItemEquipmentStatValueResponse
                    {
                        Min = min ?? string.Empty,
                        Max = max ?? min ?? string.Empty,
                    };

                default:
                    throw new JsonException($"Unexpected token for stat value: {reader.TokenType}");
            }
        }

        public override void Write(
            Utf8JsonWriter writer, ItemEquipmentStatValueResponse value, JsonSerializerOptions options)
        {
            writer.WriteStartObject();
            writer.WriteString("min", value.Min);
            writer.WriteString("max", value.Max);
            writer.WriteEndObject();
        }

        private static ItemEquipmentStatValueResponse FromScalar(string raw)
        {
            var trimmed = raw.Trim();
            var tildeIndex = trimmed.IndexOf('~');
            return tildeIndex >= 0
                ? new ItemEquipmentStatValueResponse
                {
                    Min = trimmed[..tildeIndex].Trim(),
                    Max = trimmed[(tildeIndex + 1)..].Trim(),
                }
                : new ItemEquipmentStatValueResponse
                {
                    Min = trimmed,
                    Max = trimmed,
                };
        }

        private static string ReadAsString(ref Utf8JsonReader reader) => reader.TokenType switch
        {
            JsonTokenType.String => reader.GetString() ?? string.Empty,
            JsonTokenType.Number => reader.TryGetInt64(out var v) ? v.ToString() : reader.GetDouble().ToString(),
            JsonTokenType.True => "true",
            JsonTokenType.False => "false",
            JsonTokenType.Null => string.Empty,
            _ => string.Empty,
        };
    }

    /// <summary>
    /// 文字列型の DTO プロパティに対し、数値スカラ / オブジェクト <c>{random: "1~3"}</c> も受け付ける Converter。
    /// </summary>
    private sealed class FlexibleStringConverter : JsonConverter<string>
    {
        public override bool CanConvert(Type typeToConvert) => typeToConvert == typeof(string);

        public override string? Read(
            ref Utf8JsonReader reader, Type typeToConvert, JsonSerializerOptions options)
        {
            switch (reader.TokenType)
            {
                case JsonTokenType.Null:
                    return null;

                case JsonTokenType.String:
                    return reader.GetString();

                case JsonTokenType.Number:
                    return reader.TryGetInt64(out var longValue)
                        ? longValue.ToString()
                        : reader.GetDouble().ToString();

                case JsonTokenType.True:
                    return "true";

                case JsonTokenType.False:
                    return "false";

                case JsonTokenType.StartObject:
                    string? random = null;
                    while (reader.Read())
                    {
                        if (reader.TokenType == JsonTokenType.EndObject)
                            break;

                        if (reader.TokenType != JsonTokenType.PropertyName)
                            continue;

                        var propertyName = reader.GetString();
                        reader.Read();
                        if (string.Equals(propertyName, "random", StringComparison.OrdinalIgnoreCase))
                            random = reader.TokenType == JsonTokenType.String ? reader.GetString() : null;
                        else
                            reader.Skip();
                    }
                    return random ?? "0";

                default:
                    throw new JsonException($"Unexpected token for string field: {reader.TokenType}");
            }
        }

        public override void Write(
            Utf8JsonWriter writer, string value, JsonSerializerOptions options)
            => writer.WriteStringValue(value);
    }
}
