using AstralRecordApi.Models;
using AstralRecordApi.Options;
using AstralRecordApi.Utilities;
using Microsoft.Extensions.Options;
using System.Text;
using YamlDotNet.Serialization;
using YamlDotNet.Serialization.NamingConventions;

namespace AstralRecordApi.Repositories;

public class RecipeRepository : IRecipeRepository
{
    private static readonly StringComparer KeyComparer = StringComparer.OrdinalIgnoreCase;

    private readonly IReadOnlyDictionary<string, RecipeResponse> _recipes;
    private readonly IReadOnlyList<RecipeSummaryResponse> _recipeSummaries;

    public RecipeRepository(IOptions<FileDatabaseOptions> options, ILogger<RecipeRepository> logger)
    {
        var rootPath = options.Value.RootPath;
        if (string.IsNullOrWhiteSpace(rootPath))
            throw new InvalidOperationException("FileDatabase:RootPath is not configured.");

        logger.LogInformation("レシピデータの読み込みを開始します (RootPath: {RootPath})", rootPath);
        _recipes = LoadRecipes(rootPath, logger);
        _recipeSummaries = _recipes.Values
            .Select(recipe => new RecipeSummaryResponse
            {
                Id = recipe.Id,
                Type = recipe.Type,
                Category = recipe.Category,
                Name = recipe.Name
            })
            .OrderBy(recipe => recipe.Category, KeyComparer)
            .ThenBy(recipe => recipe.Id, KeyComparer)
            .ToArray();
        logger.LogInformation("レシピデータの読み込みが完了しました (件数: {Count})", _recipeSummaries.Count);
    }

    public IReadOnlyList<RecipeSummaryResponse> GetAllSummaries()
        => _recipeSummaries;

    public RecipeResponse? GetById(string recipeId)
        => _recipes.TryGetValue(recipeId, out var recipe) ? recipe : null;

    private static IReadOnlyDictionary<string, RecipeResponse> LoadRecipes(string rootPath, ILogger logger)
    {
        var resolver = FileDatabaseConfigResolver.Load(rootPath);
        if (!resolver.TryGetDatabaseDirectory("recipe", out var recipeRootPath))
            return new Dictionary<string, RecipeResponse>(KeyComparer);
        if (!Directory.Exists(recipeRootPath))
            throw new DirectoryNotFoundException($"Recipe directory was not found: {recipeRootPath}");

        var deserializer = new DeserializerBuilder()
            .WithNamingConvention(CamelCaseNamingConvention.Instance)
            .IgnoreUnmatchedProperties()
            .Build();

        var recipes = new Dictionary<string, RecipeResponse>(KeyComparer);

        foreach (var filePath in Directory.EnumerateFiles(recipeRootPath, "*.yml", SearchOption.TopDirectoryOnly))
        {
            try
            {
                var yaml = ReadYamlWithNormalizedAmpersandScalars(filePath, resolver, logger);
                var yamlRecipe = deserializer.Deserialize<RecipeYamlDocument>(yaml)
                    ?? throw new InvalidOperationException($"Failed to deserialize recipe YAML (null result): {filePath}");
                var recipe = yamlRecipe.ToResponse(filePath);
                if (!recipes.TryAdd(recipe.Id, recipe))
                {
                    logger.LogWarning("重複するレシピ ID '{RecipeId}' をスキップします: {FilePath}", recipe.Id, filePath);
                    continue;
                }
            }
            catch (InvalidOperationException ex)
            {
                logger.LogWarning("必須項目が不足しているためスキップします: {Message}", ex.Message);
            }
            catch (Exception ex)
            {
                logger.LogWarning(ex, "レシピファイルの読み込みに失敗しました。スキップします: {FilePath}", filePath);
            }
        }

        return recipes;
    }

    private static string ReadYamlWithNormalizedAmpersandScalars(string filePath, FileDatabaseConfigResolver resolver, ILogger logger)
    {
        var rawLines = File.ReadAllLines(filePath);
        var lines = NormalizeRefObjects(rawLines, resolver, filePath, logger);
        var builder = new StringBuilder();

        foreach (var line in lines)
        {
            builder.AppendLine(NormalizeAmpersandScalar(line));
        }

        return builder.ToString();
    }

    private static string[] NormalizeRefObjects(string[] lines, FileDatabaseConfigResolver resolver, string filePath, ILogger logger)
    {
        var result = new List<string>(lines.Length);
        for (int i = 0; i < lines.Length; i++)
        {
            var line = lines[i];
            var trimmed = line.TrimStart();

            if (trimmed.EndsWith(':') && !trimmed.StartsWith('-') && !trimmed.StartsWith('#'))
            {
                if (i + 1 < lines.Length)
                {
                    var nextTrimmed = lines[i + 1].TrimStart();
                    if (nextTrimmed.StartsWith("ref: ", StringComparison.OrdinalIgnoreCase))
                    {
                        var refValue = nextTrimmed["ref: ".Length..].Trim();
                        if (!resolver.TryResolveReferenceId(refValue, out var id))
                        {
                            logger.LogWarning("ref の解決に失敗しました: ref={RefValue} (ファイル: {FilePath})", refValue, filePath);
                            result.Add(line);
                            continue;
                        }

                        var indent = line[..(line.Length - trimmed.Length)];
                        var key = trimmed.TrimEnd(':');
                        result.Add($"{indent}{key}: {id}");
                        i++;
                        continue;
                    }
                }
            }

            result.Add(line);
        }
        return result.ToArray();
    }

    private static string NormalizeAmpersandScalar(string line)
    {
        var commentIndex = line.IndexOf(" #", StringComparison.Ordinal);
        var content = commentIndex >= 0 ? line[..commentIndex] : line;
        var comment = commentIndex >= 0 ? line[commentIndex..] : string.Empty;

        var colonIndex = content.IndexOf(':');
        if (colonIndex >= 0)
        {
            var valuePart = content[(colonIndex + 1)..];
            var trimmedValue = valuePart.TrimStart();
            if (trimmedValue.StartsWith('&') && !trimmedValue.StartsWith("\"") && !trimmedValue.StartsWith("'"))
            {
                var leadingWhitespaceLength = valuePart.Length - trimmedValue.Length;
                var leadingWhitespace = valuePart[..leadingWhitespaceLength];
                var escaped = trimmedValue.Replace("\\", "\\\\").Replace("\"", "\\\"");
                return $"{content[..(colonIndex + 1)]}{leadingWhitespace}\"{escaped}\"{comment}";
            }
        }

        var trimmedContent = content.TrimStart();
        if (trimmedContent.StartsWith("- "))
        {
            var itemValue = trimmedContent[2..].TrimStart();
            if (itemValue.StartsWith('&') && !itemValue.StartsWith("\"") && !itemValue.StartsWith("'"))
            {
                var indentLength = content.Length - trimmedContent.Length;
                var indent = content[..indentLength];
                var afterDash = trimmedContent[2..];
                var leadingWhitespaceLength = afterDash.Length - itemValue.Length;
                var leadingWhitespace = afterDash[..leadingWhitespaceLength];
                var escaped = itemValue.Replace("\\", "\\\\").Replace("\"", "\\\"");
                return $"{indent}- {leadingWhitespace}\"{escaped}\"{comment}";
            }
        }

        return line;
    }

    private sealed class RecipeYamlDocument
    {
        public int? SchemaVersion { get; init; }

        public string? Id { get; init; }

        public string? Type { get; init; }

        public string? Category { get; init; }

        public string? Name { get; init; }

        public List<string>? Lore { get; init; }

        public List<string>? Tags { get; init; }

        public RecipeResultYamlDocument? Result { get; init; }

        public List<RecipeIngredientYamlDocument>? Ingredients { get; init; }

        public int? RequiredLevel { get; init; }

        public List<string>? RequiredClasses { get; init; }

        public int? RequiredCurrency { get; init; }

        public string? StationId { get; init; }

        public double? SuccessRate { get; init; }

        public string? FailAction { get; init; }

        public long? CooldownTicks { get; init; }

        public RecipeEffectYamlDocument? OnSuccess { get; init; }

        public RecipeEffectYamlDocument? OnFail { get; init; }

        public RecipeResponse ToResponse(string filePath)
        {
            if (SchemaVersion is null)
                throw new InvalidOperationException($"schemaVersion is required: {filePath}");

            return SchemaVersion.Value switch
            {
                1 => ToResponseV1(filePath),
                _ => throw new InvalidOperationException($"Unsupported schemaVersion '{SchemaVersion.Value}': {filePath}")
            };
        }

        private RecipeResponse ToResponseV1(string filePath)
        {
            if (string.IsNullOrWhiteSpace(Id))
                throw new InvalidOperationException($"id is required: {filePath}");
            if (string.IsNullOrWhiteSpace(Type))
                throw new InvalidOperationException($"type is required: {filePath}");
            if (string.IsNullOrWhiteSpace(Category))
                throw new InvalidOperationException($"category is required: {filePath}");
            if (Ingredients is null || Ingredients.Count == 0)
                throw new InvalidOperationException($"ingredients is required: {filePath}");

            var isEnhance = string.Equals(Category, "ENHANCE", StringComparison.OrdinalIgnoreCase);
            if (!isEnhance && Result is null)
                throw new InvalidOperationException($"result is required when category is not ENHANCE: {filePath}");

            return new RecipeResponse
            {
                SchemaVersion = SchemaVersion!.Value,
                Id = Id,
                Type = Type,
                Category = Category,
                Name = Name,
                Lore = Lore ?? [],
                Tags = Tags ?? [],
                Result = Result?.ToResponse(filePath),
                Ingredients = Ingredients.Select(ingredient => ingredient.ToResponse(filePath)).ToArray(),
                RequiredLevel = RequiredLevel ?? 0,
                RequiredClasses = RequiredClasses ?? [],
                RequiredCurrency = RequiredCurrency ?? 0,
                StationId = StationId,
                SuccessRate = SuccessRate ?? 100.0,
                FailAction = string.IsNullOrWhiteSpace(FailAction) ? "NONE" : FailAction,
                CooldownTicks = CooldownTicks ?? 0,
                OnSuccess = OnSuccess?.ToResponse(),
                OnFail = OnFail?.ToResponse()
            };
        }
    }

    private sealed class RecipeResultYamlDocument
    {
        public string? ItemId { get; init; }

        public int? Amount { get; init; }

        public RecipeResultResponse ToResponse(string filePath)
        {
            if (string.IsNullOrWhiteSpace(ItemId))
                throw new InvalidOperationException($"result.itemId is required: {filePath}");

            return new RecipeResultResponse
            {
                ItemId = ItemId,
                Amount = Amount ?? 1
            };
        }
    }

    private sealed class RecipeIngredientYamlDocument
    {
        public string? ItemId { get; init; }

        public int? Amount { get; init; }

        public RecipeIngredientResponse ToResponse(string filePath)
        {
            if (string.IsNullOrWhiteSpace(ItemId))
                throw new InvalidOperationException($"ingredients[].itemId is required: {filePath}");
            if (Amount is null)
                throw new InvalidOperationException($"ingredients[].amount is required: {filePath}");

            return new RecipeIngredientResponse
            {
                ItemId = ItemId,
                Amount = Amount.Value
            };
        }
    }

    private sealed class RecipeEffectYamlDocument
    {
        public string? Sound { get; init; }

        public string? Particle { get; init; }

        public RecipeEffectResponse ToResponse()
            => new()
            {
                Sound = Sound,
                Particle = Particle
            };
    }
}
