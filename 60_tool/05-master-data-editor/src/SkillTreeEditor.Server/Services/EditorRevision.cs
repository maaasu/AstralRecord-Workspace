using System.Security.Cryptography;

namespace SkillTreeEditor.Server.Services;

public static class EditorRevision
{
    public static string Hash(byte[] bytes) => Convert.ToHexString(SHA256.HashData(bytes)).ToLowerInvariant();
    public static string ETag(string revision) => "\"" + revision + "\"";
    public static bool Matches(HttpRequest request, string revision)
        => request.Headers.IfMatch.ToString() == ETag(revision);
    public static IResult Conflict() => Results.Conflict(new
    {
        message = "ファイルが変更されているか、読込時の版がありません。編集中の内容を控えてから再読み込みし、差分を確認してください。"
    });
}
