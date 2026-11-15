namespace SkillTreeEditor.Server.Services;

/// <summary>一覧用の読込が、同じファイルへの保存・置換・削除を妨げないように開く。</summary>
internal static class SharedFileReader
{
    public static async Task<byte[]> ReadAllBytesAsync(string path, CancellationToken token)
    {
        await using var stream = new FileStream(path, FileMode.Open, FileAccess.Read,
            FileShare.Read | FileShare.Delete, bufferSize: 1, FileOptions.Asynchronous | FileOptions.SequentialScan);
        var bytes = new byte[stream.Length];
        await stream.ReadExactlyAsync(bytes, token);
        return bytes;
    }
}
