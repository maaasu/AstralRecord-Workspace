using System.Text.Json;
using System.Text.Json.Serialization;
using System.Globalization;
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
        options.Converters.Add(new ItemEquipmentStatConverter());
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
    /// Keep the legacy value shape while validating and exposing the optional scalar offset roll.
    /// </summary>
    private sealed class ItemEquipmentStatConverter : JsonConverter<ItemEquipmentStatResponse>
    {
        public override ItemEquipmentStatResponse Read(
            ref Utf8JsonReader reader, Type typeToConvert, JsonSerializerOptions options)
        {
            using var document = JsonDocument.ParseValue(ref reader);
            var stat = document.RootElement;
            if (stat.ValueKind != JsonValueKind.Object)
                throw new JsonException("Equipment stat must be an object.");

            var valueElement = Property(stat, "value");
            var randomElement = Property(stat, "random");
            var value = valueElement is { } rawValue
                ? rawValue.Deserialize<ItemEquipmentStatValueResponse>(options)
                : null;
            ItemEquipmentStatRandomResponse? random = null;
            if (randomElement is { } rawRandom)
            {
                if (rawRandom.ValueKind != JsonValueKind.Object)
                    throw new JsonException("Equipment stat random must be an object.");
                if (valueElement is not { ValueKind: JsonValueKind.Number or JsonValueKind.String }
                    || value is null || value.Min != value.Max
                    || (valueElement.Value.ValueKind == JsonValueKind.String
                        && valueElement.Value.GetString()?.Contains('~') == true)
                    || Property(stat, "min") is not null || Property(stat, "max") is not null)
                    throw new JsonException("Equipment stat random requires a scalar value, not a range.");

                // Validate the raw scalar before the legacy value converter can round a JSON number.
                var basisText = valueElement.Value.ValueKind == JsonValueKind.Number
                    ? valueElement.Value.GetRawText()
                    : valueElement.Value.GetString() ?? string.Empty;
                var basis = ParseFourPlaces(basisText, "value");
                var normalizedBasis = basis.ToString("0.####", CultureInfo.InvariantCulture);
                value = new ItemEquipmentStatValueResponse { Min = normalizedBasis, Max = normalizedBasis };
                var offsetMin = ReadFourPlaces(rawRandom, "offsetMin");
                var offsetMax = ReadFourPlaces(rawRandom, "offsetMax");
                var step = ReadFourPlaces(rawRandom, "step");
                var zeroChanceElement = Property(rawRandom, "zeroChance");
                var zeroChance = zeroChanceElement is { } chanceValue
                    && chanceValue.ValueKind == JsonValueKind.Number
                    ? chanceValue.GetDouble()
                    : zeroChanceElement is null ? 0d : double.NaN;

                if (offsetMin > offsetMax || step <= 0 || !double.IsFinite(zeroChance)
                    || zeroChance is < 0 or > 1)
                    throw new JsonException("Invalid equipment stat random bounds, step, or zeroChance.");

                decimal low;
                decimal high;
                decimal intervals;
                try
                {
                    low = checked(basis + offsetMin);
                    high = checked(basis + offsetMax);
                    intervals = checked(offsetMax - offsetMin) / step;
                }
                catch (OverflowException exception)
                {
                    throw new JsonException("Equipment stat random endpoints or interval exceed supported precision.", exception);
                }

                if (intervals != decimal.Truncate(intervals) || intervals > 999_999)
                    throw new JsonException("Equipment stat random step must divide the interval with at most one million candidates.");
                if (zeroChance > 0 && low <= 0 && high >= 0)
                    throw new JsonException("zeroChance requires an ordinary interval excluding zero.");

                var lowDouble = RequireRoundTripDouble(low);
                var highDouble = RequireRoundTripDouble(high);
                if (intervals > 0)
                {
                    var magnitude = Math.Max(Math.Abs(lowDouble), Math.Abs(highDouble));
                    var ulp = Math.BitIncrement(magnitude) - magnitude;
                    if ((double)step < ulp)
                        throw new JsonException("Equipment stat random step is smaller than the endpoint double precision.");
                }

                random = new ItemEquipmentStatRandomResponse
                {
                    OffsetMin = (double)offsetMin,
                    OffsetMax = (double)offsetMax,
                    Step = (double)step,
                    ZeroChance = zeroChance,
                };
            }

            return new ItemEquipmentStatResponse
            {
                Status = ReadOptionalString(stat, "status"),
                Type = ReadOptionalString(stat, "type"),
                Value = value,
                Random = random,
            };
        }

        public override void Write(Utf8JsonWriter writer, ItemEquipmentStatResponse value, JsonSerializerOptions options)
        {
            writer.WriteStartObject();
            if (value.Status is not null) writer.WriteString("status", value.Status);
            if (value.Type is not null) writer.WriteString("type", value.Type);
            if (value.Value is not null)
            {
                writer.WritePropertyName("value");
                JsonSerializer.Serialize(writer, value.Value, options);
            }
            if (value.Random is not null)
            {
                writer.WritePropertyName("random");
                JsonSerializer.Serialize(writer, value.Random, options);
            }
            writer.WriteEndObject();
        }

        private static JsonElement? Property(JsonElement element, string name)
        {
            foreach (var property in element.EnumerateObject())
                if (string.Equals(property.Name, name, StringComparison.OrdinalIgnoreCase))
                    return property.Value;
            return null;
        }

        private static string? ReadOptionalString(JsonElement element, string name)
            => Property(element, name) is { ValueKind: JsonValueKind.String } value ? value.GetString() : null;

        private static decimal ReadFourPlaces(JsonElement element, string name)
        {
            var value = Property(element, name);
            if (value is not { ValueKind: JsonValueKind.Number })
                throw new JsonException($"Equipment stat random {name} is required and must be numeric.");
            return ParseFourPlaces(value.Value.GetRawText(), name);
        }

        private static decimal ParseFourPlaces(string text, string name)
        {
            if (!decimal.TryParse(text, NumberStyles.Float, CultureInfo.InvariantCulture, out var value)
                || value != decimal.Round(value, 4)
                || (value == 0 && text.Split('e', 'E')[0].Any(c => c is >= '1' and <= '9')))
                throw new JsonException($"Equipment stat random {name} must be finite with at most four decimal places.");
            RequireRoundTripDouble(value);
            return value;
        }

        private static double RequireRoundTripDouble(decimal value)
        {
            var converted = (double)value;
            if (!double.IsFinite(converted)
                || !decimal.TryParse(converted.ToString("R", CultureInfo.InvariantCulture),
                    NumberStyles.Float, CultureInfo.InvariantCulture, out var roundTrip)
                || roundTrip != value)
                throw new JsonException("Equipment stat random endpoint cannot be preserved as a double.");
            return converted;
        }
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
