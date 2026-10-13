using System.Security.Cryptography;
using System.Text.Encodings.Web;
using System.Text.Json;

namespace AstralRecordApi.Models;

/// <summary>同一世代の詳細マスタを上限付きで配信する。</summary>
public sealed record MasterDataPageResponse<T>(string Generation, int Total, IReadOnlyList<T> Items);

/// <summary>単件詳細 HTTP 本文と同じシリアライズ設定によるハッシュを保持する。</summary>
public sealed record MasterDataDetailResponse<T>(T Master, string ResponseBodySha256)
{
    public static MasterDataDetailResponse<T> Create(T master, JsonSerializerOptions options)
    {
        // MVCのSystemTextJsonOutputFormatterはEncoder未指定時だけUnsafeRelaxedを使う。
        var wireOptions = options.Encoder is not null ? options : new JsonSerializerOptions(options)
        {
            Encoder = JavaScriptEncoder.UnsafeRelaxedJsonEscaping,
        };
        return new(master, Convert.ToHexStringLower(SHA256.HashData(JsonSerializer.SerializeToUtf8Bytes(master, wireOptions))));
    }
}
