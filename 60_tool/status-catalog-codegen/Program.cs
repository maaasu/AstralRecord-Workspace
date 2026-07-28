using System.Text;
using System.Text.Encodings.Web;
using System.Text.Json;
using System.Text.RegularExpressions;
using YamlDotNet.Serialization;
using YamlDotNet.Serialization.NamingConventions;

var options = GeneratorOptions.Parse(args);
var sourcePath = Path.Combine(options.RepositoryRoot, "40_filebase", "75.shared.status", "v1.status_types.yml");
if (!File.Exists(sourcePath))
    throw new FileNotFoundException($"Status catalog was not found: {sourcePath}");

var deserializer = new DeserializerBuilder()
    .WithNamingConvention(CamelCaseNamingConvention.Instance)
    .Build();
var catalog = deserializer.Deserialize<StatusCatalog>(await File.ReadAllTextAsync(sourcePath))
    ?? throw new InvalidDataException("Status catalog root is empty.");
CatalogValidator.Validate(catalog);

var targets = new Dictionary<string, string>
{
    [Path.Combine(
        options.RepositoryRoot,
        "10_plugin", "AstralRecord", "src", "main", "java", "io", "github", "maaasu", "astralRecord",
        "feature", "status", "model", "StatusType.kt")] = CodeTemplates.Kotlin(catalog),
    [Path.Combine(
        options.RepositoryRoot,
        "20_api", "AstralRecordApi", "AstralRecordApi", "Models", "StatusType.generated.cs")] =
        CodeTemplates.CSharp(catalog),
    [Path.Combine(
        options.RepositoryRoot,
        "60_tool", "skilltree-editor", "src", "SkillTreeEditor.Client", "src", "data", "statusTypes.generated.ts")] =
        CodeTemplates.TypeScript(catalog),
};

var staleTargets = new List<string>();
foreach (var (target, generated) in targets)
{
    var normalized = generated.Replace("\r\n", "\n", StringComparison.Ordinal);
    if (!normalized.EndsWith('\n'))
        normalized += "\n";

    var existing = File.Exists(target)
        ? (await File.ReadAllTextAsync(target)).Replace("\r\n", "\n", StringComparison.Ordinal)
        : null;
    if (string.Equals(existing, normalized, StringComparison.Ordinal))
        continue;

    if (options.CheckOnly)
    {
        staleTargets.Add(Path.GetRelativePath(options.RepositoryRoot, target));
        continue;
    }

    Directory.CreateDirectory(Path.GetDirectoryName(target)!);
    await File.WriteAllTextAsync(target, normalized, new UTF8Encoding(encoderShouldEmitUTF8Identifier: false));
    Console.WriteLine($"generated: {Path.GetRelativePath(options.RepositoryRoot, target)}");
}

if (staleTargets.Count > 0)
{
    Console.Error.WriteLine("Status type generated files are stale:");
    foreach (var target in staleTargets)
        Console.Error.WriteLine($"  - {target}");
    Console.Error.WriteLine("Run .\\60_tool\\generate-status-types.ps1 and commit the generated files.");
    Environment.ExitCode = 2;
}
else if (options.CheckOnly)
{
    Console.WriteLine("Status type generated files are up to date.");
}

internal sealed record GeneratorOptions(string RepositoryRoot, bool CheckOnly)
{
    public static GeneratorOptions Parse(string[] arguments)
    {
        string? repositoryRoot = null;
        var checkOnly = false;
        for (var index = 0; index < arguments.Length; index++)
        {
            switch (arguments[index])
            {
                case "--repo-root" when index + 1 < arguments.Length:
                    repositoryRoot = arguments[++index];
                    break;
                case "--check":
                    checkOnly = true;
                    break;
                default:
                    throw new ArgumentException($"Unknown or incomplete argument: {arguments[index]}");
            }
        }

        if (string.IsNullOrWhiteSpace(repositoryRoot))
            throw new ArgumentException("--repo-root is required.");
        return new GeneratorOptions(Path.GetFullPath(repositoryRoot), checkOnly);
    }
}

internal sealed class StatusCatalog
{
    public int SchemaVersion { get; init; }
    public List<StatusCategoryDefinition> Categories { get; init; } = [];
    public List<StatusDefinition> Statuses { get; init; } = [];
}

internal sealed class StatusCategoryDefinition
{
    public string Id { get; init; } = string.Empty;
    public string DisplayName { get; init; } = string.Empty;
}

internal sealed class StatusDefinition
{
    public string Id { get; init; } = string.Empty;
    public string DisplayName { get; init; } = string.Empty;
    public string Description { get; init; } = string.Empty;
    public string Category { get; init; } = string.Empty;
    public string Suffix { get; init; } = string.Empty;
    public int DecimalPlaces { get; init; }
    public bool SupportsRange { get; init; } = true;
}

internal static partial class CatalogValidator
{
    [GeneratedRegex("^[A-Z][A-Z0-9]*(?:_[A-Z0-9]+)*$", RegexOptions.CultureInvariant)]
    private static partial Regex IdentifierPattern();

    public static void Validate(StatusCatalog catalog)
    {
        if (catalog.SchemaVersion != 1)
            throw new InvalidDataException($"Unsupported schemaVersion: {catalog.SchemaVersion}");
        if (catalog.Categories.Count == 0)
            throw new InvalidDataException("categories must contain at least one definition.");
        if (catalog.Statuses.Count == 0)
            throw new InvalidDataException("statuses must contain at least one definition.");

        var categoryIds = new HashSet<string>(StringComparer.Ordinal);
        foreach (var category in catalog.Categories)
        {
            ValidateIdentifier(category.Id, "category");
            if (string.IsNullOrWhiteSpace(category.DisplayName))
                throw new InvalidDataException($"Category '{category.Id}' requires displayName.");
            if (!categoryIds.Add(category.Id))
                throw new InvalidDataException($"Duplicate category id: {category.Id}");
        }

        var statusIds = new HashSet<string>(StringComparer.Ordinal);
        foreach (var status in catalog.Statuses)
        {
            ValidateIdentifier(status.Id, "status");
            if (!statusIds.Add(status.Id))
                throw new InvalidDataException($"Duplicate status id: {status.Id}");
            if (string.IsNullOrWhiteSpace(status.DisplayName))
                throw new InvalidDataException($"Status '{status.Id}' requires displayName.");
            if (string.IsNullOrWhiteSpace(status.Description))
                throw new InvalidDataException($"Status '{status.Id}' requires description.");
            if (!categoryIds.Contains(status.Category))
                throw new InvalidDataException($"Status '{status.Id}' references unknown category '{status.Category}'.");
            if (status.DecimalPlaces is < 0 or > 6)
                throw new InvalidDataException($"Status '{status.Id}' decimalPlaces must be between 0 and 6.");
        }
    }

    private static void ValidateIdentifier(string id, string kind)
    {
        if (!IdentifierPattern().IsMatch(id))
            throw new InvalidDataException($"Invalid {kind} id '{id}'. Use upper snake case.");
    }
}

internal static class CodeTemplates
{
    private static readonly JsonSerializerOptions JsonOptions = new()
    {
        Encoder = JavaScriptEncoder.UnsafeRelaxedJsonEscaping
    };

    public static string Kotlin(StatusCatalog catalog)
    {
        var builder = new StringBuilder();
        builder.AppendLine("// <auto-generated>");
        builder.AppendLine("// 40_filebase/75.shared.status/v1.status_types.yml から生成されます。直接編集しないでください。");
        builder.AppendLine("// </auto-generated>");
        builder.AppendLine("package io.github.maaasu.astralRecord.feature.status.model");
        builder.AppendLine();
        builder.AppendLine("import io.github.maaasu.astralRecord.infrastructure.util.ColorCodeUtil");
        builder.AppendLine("import net.kyori.adventure.text.format.NamedTextColor");
        builder.AppendLine("import java.util.Locale");
        builder.AppendLine();
        builder.AppendLine("/** 共有ステータスカタログから生成されたステータス種別です。 */");
        builder.AppendLine("enum class StatusType(");
        builder.AppendLine("    val id: String,");
        builder.AppendLine("    val displayName: String,");
        builder.AppendLine("    val description: String,");
        builder.AppendLine("    val category: Category,");
        builder.AppendLine("    val suffix: String = \"\",");
        builder.AppendLine("    val decimalPlaces: Int = 0,");
        builder.AppendLine("    val supportsRange: Boolean = true,");
        builder.AppendLine(") {");
        foreach (var status in catalog.Statuses)
        {
            builder.Append("    ").Append(status.Id).Append('(')
                .Append(Quote(status.Id)).Append(", ")
                .Append(Quote(status.DisplayName)).Append(", ")
                .Append(Quote(status.Description)).Append(", Category.").Append(status.Category).Append(", ")
                .Append(Quote(status.Suffix)).Append(", ")
                .Append(status.DecimalPlaces).Append(", ")
                .Append(status.SupportsRange ? "true" : "false").AppendLine("),");
        }
        builder.AppendLine("    ;");
        builder.AppendLine();
        builder.AppendLine("    /** 共有ステータスカテゴリです。 */");
        builder.AppendLine("    enum class Category(val displayName: String) {");
        foreach (var category in catalog.Categories)
            builder.Append("        ").Append(category.Id).Append('(').Append(Quote(category.DisplayName)).AppendLine("),");
        builder.AppendLine("    }");
        builder.AppendLine();
        builder.AppendLine("    /**");
        builder.AppendLine("     * ステータス値をsuffix付きの表示用文字列へ変換します。");
        builder.AppendLine("     *");
        builder.AppendLine("     * @param value 表示対象の値");
        builder.AppendLine("     * @return カタログで定義された小数桁数とsuffixを適用した文字列");
        builder.AppendLine("     */");
        builder.AppendLine("    fun formatValue(value: Double): String =");
        builder.AppendLine("        String.format(Locale.US, \"%,.${decimalPlaces}f\", value) + suffix");
        builder.AppendLine();
        builder.AppendLine("    /**");
        builder.AppendLine("     * `%`単位で表示するステータスかどうかを返します。");
        builder.AppendLine("     *");
        builder.AppendLine("     * @return suffixが`%`の場合はtrue");
        builder.AppendLine("     */");
        builder.AppendLine("    fun isPercentage(): Boolean = suffix == \"%\"");
        builder.AppendLine();
        builder.AppendLine("    /**");
        builder.AppendLine("     * 正の値へ`+`を付けた補正表示へ変換します。");
        builder.AppendLine("     *");
        builder.AppendLine("     * @param value 表示対象の補正値");
        builder.AppendLine("     * @return 符号とsuffixを含む表示用文字列");
        builder.AppendLine("     */");
        builder.AppendLine("    fun formatSignedValue(value: Double): String = (if (value > 0.0) \"+\" else \"\") + formatValue(value)");
        builder.AppendLine();
        builder.AppendLine("    /**");
        builder.AppendLine("     * 同値なら単一値、それ以外なら最小値と最大値を表示します。");
        builder.AppendLine("     *");
        builder.AppendLine("     * @param minValue 表示する下限");
        builder.AppendLine("     * @param maxValue 表示する上限");
        builder.AppendLine("     * @return 単一値または範囲文字列");
        builder.AppendLine("     */");
        builder.AppendLine("    fun formatRange(minValue: Double, maxValue: Double): String =");
        builder.AppendLine("        if (minValue == maxValue) formatValue(minValue) else \"${formatValue(minValue)} ～ ${formatValue(maxValue)}\"");
        builder.AppendLine();
        builder.AppendLine("    /**");
        builder.AppendLine("     * 同値なら単一補正、それ以外なら符号付き範囲を表示します。");
        builder.AppendLine("     *");
        builder.AppendLine("     * @param minValue 表示する補正下限");
        builder.AppendLine("     * @param maxValue 表示する補正上限");
        builder.AppendLine("     * @return 符号付きの単一値または範囲文字列");
        builder.AppendLine("     */");
        builder.AppendLine("    fun formatSignedRange(minValue: Double, maxValue: Double): String =");
        builder.AppendLine("        if (minValue == maxValue) formatSignedValue(minValue)");
        builder.AppendLine("        else \"${formatSignedValue(minValue)} ～ ${formatSignedValue(maxValue)}\"");
        builder.AppendLine();
        builder.AppendLine("    /**");
        builder.AppendLine("     * Plugin UIで使う共通表示色を返します。");
        builder.AppendLine("     *");
        builder.AppendLine("     * @return カテゴリに対応するAdventure色");
        builder.AppendLine("     */");
        builder.AppendLine("    fun namedColor(): NamedTextColor = when (category) {");
        builder.AppendLine("        Category.RESOURCE -> NamedTextColor.GOLD");
        builder.AppendLine("        Category.PRIMARY -> NamedTextColor.GREEN");
        builder.AppendLine("        Category.OFFENSE -> NamedTextColor.RED");
        builder.AppendLine("        Category.DEFENSE -> NamedTextColor.BLUE");
        builder.AppendLine("        Category.ELEMENT -> NamedTextColor.LIGHT_PURPLE");
        builder.AppendLine("        Category.CONDITION -> NamedTextColor.DARK_PURPLE");
        builder.AppendLine("        Category.UTILITY -> NamedTextColor.YELLOW");
        builder.AppendLine("    }");
        builder.AppendLine();
        builder.AppendLine("    /**");
        builder.AppendLine("     * Plugin UIで使うlegacy color codeを返します。");
        builder.AppendLine("     *");
        builder.AppendLine("     * @return カテゴリに対応するlegacy color code");
        builder.AppendLine("     */");
        builder.AppendLine("    fun legacyColor(): String = when (category) {");
        builder.AppendLine("        Category.RESOURCE -> ColorCodeUtil.GOLD");
        builder.AppendLine("        Category.PRIMARY -> ColorCodeUtil.GREEN");
        builder.AppendLine("        Category.OFFENSE -> ColorCodeUtil.RED");
        builder.AppendLine("        Category.DEFENSE -> ColorCodeUtil.BLUE");
        builder.AppendLine("        Category.ELEMENT -> ColorCodeUtil.LIGHT_PURPLE");
        builder.AppendLine("        Category.CONDITION -> ColorCodeUtil.DARK_PURPLE");
        builder.AppendLine("        Category.UTILITY -> ColorCodeUtil.YELLOW");
        builder.AppendLine("    }");
        builder.AppendLine();
        builder.AppendLine("    companion object {");
        builder.AppendLine("        private val byId = entries.associateBy(StatusType::id)");
        builder.AppendLine();
        builder.AppendLine("        /**");
        builder.AppendLine("         * 指定カテゴリに属するステータスを返します。");
        builder.AppendLine("         *");
        builder.AppendLine("         * @param category 対象カテゴリ");
        builder.AppendLine("         * @return 対象カテゴリのステータス一覧");
        builder.AppendLine("         */");
        builder.AppendLine("        @JvmStatic");
        builder.AppendLine("        fun byCategory(category: Category): List<StatusType> = entries.filter { it.category == category }");
        builder.AppendLine();
        builder.AppendLine("        /**");
        builder.AppendLine("         * 不変IDに対応するステータスを返します。");
        builder.AppendLine("         *");
        builder.AppendLine("         * @param id 大文字スネークケースの不変ID");
        builder.AppendLine("         * @return 対応するステータス。未定義の場合はnull");
        builder.AppendLine("         */");
        builder.AppendLine("        @JvmStatic");
        builder.AppendLine("        fun fromId(id: String): StatusType? = byId[id]");
        builder.AppendLine("    }");
        builder.AppendLine("}");
        return builder.ToString();
    }

    public static string CSharp(StatusCatalog catalog)
    {
        var builder = new StringBuilder();
        builder.AppendLine("// <auto-generated>");
        builder.AppendLine("// 40_filebase/75.shared.status/v1.status_types.yml から生成されます。直接編集しないでください。");
        builder.AppendLine("// </auto-generated>");
        builder.AppendLine("#nullable enable");
        builder.AppendLine("using System.Collections.ObjectModel;");
        builder.AppendLine();
        builder.AppendLine("namespace AstralRecordApi.Models;");
        builder.AppendLine();
        builder.AppendLine("/// <summary>共有ステータスID。</summary>");
        builder.AppendLine("public enum StatusType");
        builder.AppendLine("{");
        foreach (var status in catalog.Statuses)
            builder.Append("    ").Append(status.Id).AppendLine(",");
        builder.AppendLine("}");
        builder.AppendLine();
        builder.AppendLine("/// <summary>共有ステータスカテゴリ。</summary>");
        builder.AppendLine("public enum StatusCategory");
        builder.AppendLine("{");
        foreach (var category in catalog.Categories)
            builder.Append("    ").Append(category.Id).AppendLine(",");
        builder.AppendLine("}");
        builder.AppendLine();
        builder.AppendLine("/// <summary>共有ステータスの表示メタデータ。</summary>");
        builder.AppendLine("public sealed record StatusTypeDefinition(");
        builder.AppendLine("    StatusType Type,");
        builder.AppendLine("    string Id,");
        builder.AppendLine("    string DisplayName,");
        builder.AppendLine("    string Description,");
        builder.AppendLine("    StatusCategory Category,");
        builder.AppendLine("    string Suffix,");
        builder.AppendLine("    int DecimalPlaces,");
        builder.AppendLine("    bool SupportsRange);");
        builder.AppendLine();
        builder.AppendLine("/// <summary>共有ステータスカタログ。</summary>");
        builder.AppendLine("public static class StatusTypes");
        builder.AppendLine("{");
        builder.AppendLine("    private static readonly IReadOnlyDictionary<string, StatusTypeDefinition> Definitions =");
        builder.AppendLine("        new ReadOnlyDictionary<string, StatusTypeDefinition>(");
        builder.AppendLine("            new Dictionary<string, StatusTypeDefinition>(StringComparer.Ordinal)");
        builder.AppendLine("            {");
        foreach (var status in catalog.Statuses)
        {
            builder.Append("                [").Append(Quote(status.Id)).Append("] = new(StatusType.")
                .Append(status.Id).Append(", ").Append(Quote(status.Id)).Append(", ")
                .Append(Quote(status.DisplayName)).Append(", ").Append(Quote(status.Description))
                .Append(", StatusCategory.").Append(status.Category).Append(", ")
                .Append(Quote(status.Suffix)).Append(", ").Append(status.DecimalPlaces).Append(", ")
                .Append(status.SupportsRange ? "true" : "false").AppendLine("),");
        }
        builder.AppendLine("            });");
        builder.AppendLine();
        builder.AppendLine("    /// <summary>IDをキーとする全ステータス定義。</summary>");
        builder.AppendLine("    public static IReadOnlyDictionary<string, StatusTypeDefinition> All => Definitions;");
        builder.AppendLine();
        builder.AppendLine("    /// <summary>不変IDに対応するステータス定義を取得します。</summary>");
        builder.AppendLine("    public static bool TryGet(string id, out StatusTypeDefinition? definition) =>");
        builder.AppendLine("        Definitions.TryGetValue(id, out definition);");
        builder.AppendLine("}");
        return builder.ToString();
    }

    public static string TypeScript(StatusCatalog catalog)
    {
        var builder = new StringBuilder();
        builder.AppendLine("// <auto-generated>");
        builder.AppendLine("// 40_filebase/75.shared.status/v1.status_types.yml から生成されます。直接編集しないでください。");
        builder.AppendLine("// </auto-generated>");
        builder.AppendLine();
        builder.AppendLine("export const STATUS_TYPE_IDS = [");
        foreach (var status in catalog.Statuses)
            builder.Append("  ").Append(Quote(status.Id)).AppendLine(",");
        builder.AppendLine("] as const");
        builder.AppendLine();
        builder.AppendLine("export type StatusTypeId = typeof STATUS_TYPE_IDS[number]");
        builder.AppendLine();
        builder.Append("export type StatusCategoryId = ")
            .Append(string.Join(" | ", catalog.Categories.Select(category => Quote(category.Id))))
            .AppendLine();
        builder.AppendLine();
        builder.AppendLine("export interface StatusTypeDefinition {");
        builder.AppendLine("  readonly id: StatusTypeId");
        builder.AppendLine("  readonly displayName: string");
        builder.AppendLine("  readonly description: string");
        builder.AppendLine("  readonly category: StatusCategoryId");
        builder.AppendLine("  readonly suffix: string");
        builder.AppendLine("  readonly decimalPlaces: number");
        builder.AppendLine("  readonly supportsRange: boolean");
        builder.AppendLine("}");
        builder.AppendLine();
        builder.AppendLine("export const STATUS_TYPES: readonly StatusTypeDefinition[] = [");
        foreach (var status in catalog.Statuses)
        {
            builder.Append("  { id: ").Append(Quote(status.Id))
                .Append(", displayName: ").Append(Quote(status.DisplayName))
                .Append(", description: ").Append(Quote(status.Description))
                .Append(", category: ").Append(Quote(status.Category))
                .Append(", suffix: ").Append(Quote(status.Suffix))
                .Append(", decimalPlaces: ").Append(status.DecimalPlaces)
                .Append(", supportsRange: ").Append(status.SupportsRange ? "true" : "false")
                .AppendLine(" },");
        }
        builder.AppendLine("]");
        builder.AppendLine();
        builder.AppendLine("export const STATUS_TYPE_BY_ID = new Map<StatusTypeId, StatusTypeDefinition>(");
        builder.AppendLine("  STATUS_TYPES.map((status) => [status.id, status]),");
        builder.AppendLine(")");
        builder.AppendLine();
        builder.AppendLine("export function isStatusTypeId(value: string): value is StatusTypeId {");
        builder.AppendLine("  return STATUS_TYPE_BY_ID.has(value as StatusTypeId)");
        builder.AppendLine("}");
        builder.AppendLine();
        builder.AppendLine("export function formatStatusValue(status: StatusTypeDefinition, value: number): string {");
        builder.AppendLine("  return `${value.toLocaleString('en-US', {");
        builder.AppendLine("    minimumFractionDigits: status.decimalPlaces,");
        builder.AppendLine("    maximumFractionDigits: status.decimalPlaces,");
        builder.AppendLine("  })}${status.suffix}`");
        builder.AppendLine("}");
        builder.AppendLine();
        builder.AppendLine("export function formatStatusModifier(");
        builder.AppendLine("  status: StatusTypeDefinition,");
        builder.AppendLine("  modifierType: string,");
        builder.AppendLine("  value: number,");
        builder.AppendLine("): string {");
        builder.AppendLine("  if (modifierType === 'SCALAR') {");
        builder.AppendLine("    const percentage = value * 100");
        builder.AppendLine("    const formatted = percentage.toLocaleString('en-US', { maximumFractionDigits: 2 })");
        builder.AppendLine("    return `${percentage > 0 ? '+' : ''}${formatted}%`");
        builder.AppendLine("  }");
        builder.AppendLine("  return `${value > 0 ? '+' : ''}${formatStatusValue(status, value)}`");
        builder.AppendLine("}");
        return builder.ToString();
    }

    private static string Quote(string value) => JsonSerializer.Serialize(value, JsonOptions);
}
