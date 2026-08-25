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
        options.Converters.Add(new ItemEquipmentRequiredClassConverter());
        options.Converters.Add(new MobSkillBindingResponseConverter());
        options.Converters.Add(new FlexibleStringConverter());
        return options;
    }

    /// <summary>
    /// Mob スキルの旧文字列ID形式と現行オブジェクト形式を読み取る。
    /// API の返却は常に <see cref="MobSkillBindingResponse"/> のオブジェクト形式に正規化する。
    /// </summary>
    private sealed class MobSkillBindingResponseConverter : JsonConverter<MobSkillBindingResponse>
    {
        public override MobSkillBindingResponse Read(
            ref Utf8JsonReader reader, Type typeToConvert, JsonSerializerOptions options)
        {
            if (reader.TokenType == JsonTokenType.String)
            {
                var stringSkillId = reader.GetString();
                if (string.IsNullOrWhiteSpace(stringSkillId))
                    throw new JsonException("Mob skill ID cannot be blank.");
                return new MobSkillBindingResponse { Id = stringSkillId };
            }

            if (reader.TokenType != JsonTokenType.StartObject)
                throw new JsonException($"Unexpected token for mob skill: {reader.TokenType}");

            string? id = null;
            double? activationRange = null;
            long? cooldownTicks = null;
            long? castTimeTicks = null;
            IReadOnlyDictionary<string, double> parameters = new Dictionary<string, double>();

            while (reader.Read())
            {
                if (reader.TokenType == JsonTokenType.EndObject)
                    break;
                if (reader.TokenType != JsonTokenType.PropertyName)
                    continue;

                var propertyName = reader.GetString();
                reader.Read();
                if (string.Equals(propertyName, "id", StringComparison.OrdinalIgnoreCase))
                {
                    id = reader.TokenType == JsonTokenType.String ? reader.GetString() : null;
                }
                else if (string.Equals(propertyName, "activationRange", StringComparison.OrdinalIgnoreCase))
                {
                    activationRange = ReadNullableDouble(ref reader);
                }
                else if (string.Equals(propertyName, "cooldownTicks", StringComparison.OrdinalIgnoreCase))
                {
                    cooldownTicks = ReadNullableLong(ref reader);
                }
                else if (string.Equals(propertyName, "castTimeTicks", StringComparison.OrdinalIgnoreCase))
                {
                    castTimeTicks = ReadNullableLong(ref reader);
                }
                else if (string.Equals(propertyName, "params", StringComparison.OrdinalIgnoreCase))
                {
                    parameters = JsonSerializer.Deserialize<Dictionary<string, double>>(ref reader, options)
                        ?? new Dictionary<string, double>();
                }
                else
                {
                    reader.Skip();
                }
            }

            if (string.IsNullOrWhiteSpace(id))
                throw new JsonException("Mob skill id is required.");

            return new MobSkillBindingResponse
            {
                Id = id,
                ActivationRange = activationRange,
                CooldownTicks = cooldownTicks,
                CastTimeTicks = castTimeTicks,
                Params = parameters,
            };
        }

        public override void Write(Utf8JsonWriter writer, MobSkillBindingResponse value, JsonSerializerOptions options)
        {
            writer.WriteStartObject();
            writer.WriteString("id", value.Id);
            if (value.ActivationRange is { } activationRange)
                writer.WriteNumber("activationRange", activationRange);
            if (value.CooldownTicks is { } cooldownTicks)
                writer.WriteNumber("cooldownTicks", cooldownTicks);
            if (value.CastTimeTicks is { } castTimeTicks)
                writer.WriteNumber("castTimeTicks", castTimeTicks);
            writer.WritePropertyName("params");
            JsonSerializer.Serialize(writer, value.Params, options);
            writer.WriteEndObject();
        }

        private static double? ReadNullableDouble(ref Utf8JsonReader reader) => reader.TokenType switch
        {
            JsonTokenType.Null => null,
            JsonTokenType.Number => reader.GetDouble(),
            JsonTokenType.String when double.TryParse(reader.GetString(), out var value) => value,
            _ => throw new JsonException($"Expected a number or null, but got {reader.TokenType}.")
        };

        private static long? ReadNullableLong(ref Utf8JsonReader reader) => reader.TokenType switch
        {
            JsonTokenType.Null => null,
            JsonTokenType.Number => reader.GetInt64(),
            JsonTokenType.String when long.TryParse(reader.GetString(), out var value) => value,
            _ => throw new JsonException($"Expected an integer or null, but got {reader.TokenType}.")
        };
    }

    /// <summary>
    /// equipment.requiredClasses の旧文字列形式と、クラスレベルを持つオブジェクト形式を読み取る。
    /// </summary>
    private sealed class ItemEquipmentRequiredClassConverter : JsonConverter<ItemEquipmentRequiredClassResponse>
    {
        public override ItemEquipmentRequiredClassResponse? Read(
            ref Utf8JsonReader reader, Type typeToConvert, JsonSerializerOptions options)
        {
            if (reader.TokenType == JsonTokenType.String)
            {
                return new ItemEquipmentRequiredClassResponse
                {
                    ClassId = reader.GetString() ?? string.Empty,
                    Level = 1,
                };
            }

            if (reader.TokenType != JsonTokenType.StartObject)
                throw new JsonException($"Unexpected token for required class: {reader.TokenType}");

            string? classId = null;
            var level = 1;
            while (reader.Read())
            {
                if (reader.TokenType == JsonTokenType.EndObject)
                    break;
                if (reader.TokenType != JsonTokenType.PropertyName)
                    continue;

                var propertyName = reader.GetString();
                reader.Read();
                if (string.Equals(propertyName, "classId", StringComparison.OrdinalIgnoreCase)
                    || string.Equals(propertyName, "class", StringComparison.OrdinalIgnoreCase))
                {
                    classId = reader.TokenType == JsonTokenType.String ? reader.GetString() : null;
                }
                else if (string.Equals(propertyName, "level", StringComparison.OrdinalIgnoreCase))
                {
                    level = reader.TokenType == JsonTokenType.Number ? reader.GetInt32() : 1;
                }
                else
                {
                    reader.Skip();
                }
            }

            if (string.IsNullOrWhiteSpace(classId))
                throw new JsonException("classId is required for equipment.requiredClasses.");

            return new ItemEquipmentRequiredClassResponse
            {
                ClassId = classId,
                Level = Math.Max(1, level),
            };
        }

        public override void Write(
            Utf8JsonWriter writer, ItemEquipmentRequiredClassResponse value, JsonSerializerOptions options)
        {
            writer.WriteStartObject();
            writer.WriteString("classId", value.ClassId);
            writer.WriteNumber("level", value.Level);
            writer.WriteEndObject();
        }
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
    /// 文字列型の DTO プロパティに対し、数値スカラや以下のオブジェクト形式も受け付ける Converter。
    /// <list type="bullet">
    ///   <item><c>{ random: "1~3" }</c> — ドロップ数量などのランダム範囲表現</item>
    ///   <item><c>{ ref: "item:rusty_sword" }</c> — filebase の参照値表現。<c>ref</c> 値をそのまま返す</item>
    /// </list>
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
                    string? refValue = null;
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
                        else if (string.Equals(propertyName, "ref", StringComparison.OrdinalIgnoreCase))
                            refValue = reader.TokenType == JsonTokenType.String ? reader.GetString() : null;
                        else
                            reader.Skip();
                    }
                    // ref が指定されていれば最優先で返す。次に random、最後に "0" フォールバック。
                    return refValue ?? random ?? "0";

                default:
                    throw new JsonException($"Unexpected token for string field: {reader.TokenType}");
            }
        }

        public override void Write(
            Utf8JsonWriter writer, string value, JsonSerializerOptions options)
            => writer.WriteStringValue(value);
    }
}
