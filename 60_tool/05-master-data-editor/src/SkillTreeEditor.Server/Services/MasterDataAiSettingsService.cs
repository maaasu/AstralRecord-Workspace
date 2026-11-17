using System.Text.RegularExpressions;
using Microsoft.Extensions.Options;
using SkillTreeEditor.Server.Models;
using SkillTreeEditor.Server.Options;

namespace SkillTreeEditor.Server.Services;

/// <summary>UI のキーとモデルはプロセスの終了とともに破棄する。秘密値を DTO に含めない。</summary>
public sealed class MasterDataAiSettingsService(IConfiguration configuration, IOptions<MasterDataAiOptions> options)
{
    private readonly object _sync = new();
    private string? _sessionKey;
    private string _model = RequireModel(options.Value.Model);

    public MasterDataAiSettings Read()
    {
        lock (_sync) return Describe();
    }

    public MasterDataAiSettings Update(MasterDataAiSettingsRequest request)
    {
        var model = RequireModel(request.Model);
        if (request.ClearApiKey && !string.IsNullOrWhiteSpace(request.ApiKey))
            throw new ArgumentException("キーの設定と解除は同時に指定できません。");
        if (request.ApiKey is { } key && (key.Length > 512 || key.Any(char.IsControl) || key.Any(char.IsWhiteSpace)))
            throw new ArgumentException("API キーの書式を確認してください。");
        lock (_sync)
        {
            _model = model;
            if (request.ClearApiKey) _sessionKey = null;
            else if (!string.IsNullOrWhiteSpace(request.ApiKey)) _sessionKey = request.ApiKey;
            return Describe();
        }
    }

    internal (string Key, string Model) Credentials()
    {
        lock (_sync)
        {
            var (key, _) = ResolveKey();
            if (string.IsNullOrWhiteSpace(key))
                throw new MasterDataAiException(409, "OpenAI API キーを設定してください。");
            if (key.Length > 512 || key.Any(char.IsControl) || key.Any(char.IsWhiteSpace))
                throw new MasterDataAiException(409, "OpenAI API キーの書式を確認してください。");
            return (key, _model);
        }
    }

    private MasterDataAiSettings Describe()
    {
        var (key, source) = ResolveKey();
        var configured = !string.IsNullOrWhiteSpace(key);
        return new(configured, _model, source, configured ? null : "OpenAI API キーが設定されていません。");
    }

    private (string? Key, string Source) ResolveKey()
    {
        if (!string.IsNullOrWhiteSpace(_sessionKey)) return (_sessionKey, "session");
        var environment = configuration["OPENAI_API_KEY"];
        if (!string.IsNullOrWhiteSpace(environment)) return (environment, "environment");
        var configured = configuration[MasterDataAiOptions.SectionName + ":ApiKey"];
        return string.IsNullOrWhiteSpace(configured) ? (null, "none") : (configured, "configuration");
    }

    private static string RequireModel(string? model)
    {
        if (model is null || !Regex.IsMatch(model, @"\A[A-Za-z0-9][A-Za-z0-9._:-]{0,99}\z"))
            throw new ArgumentException("モデル名は100文字以内の英数字・.・_・:・-で指定してください。");
        return model;
    }
}
