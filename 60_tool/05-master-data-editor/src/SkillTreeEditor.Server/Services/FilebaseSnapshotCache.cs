using System.Text;
using System.Text.Json.Nodes;
using SkillTreeEditor.Server.Models;

namespace SkillTreeEditor.Server.Services;

/// <summary>
/// 一覧系の処理が共有する、読込済みのマスター文書。<see cref="Content"/> は複数の要求から同時に読まれるため、
/// 利用側は変更せず、別の JSON へ組み込むときは複製する。
/// </summary>
public sealed record FilebaseDocument(string Path, string FullPath, long Length, DateTime LastWriteTimeUtc,
    string Raw, string Revision, JsonNode? Content, IReadOnlyList<MasterDataIssue> Issues,
    string? PathError, string? DecodeError)
{
    public string Format => MasterDataPaths.Format(Path);
    public bool IsReadOnly => MasterDataPaths.IsReadOnly(Path);

    /// <summary>1件ずつ厳密に読んだときと同じ条件で、書式違反のパスと UTF-8 として不正な内容を拒否する。</summary>
    public FilebaseDocument RequireReadable()
    {
        if (PathError is not null) throw new ArgumentException(PathError);
        if (DecodeError is not null) throw new DecoderFallbackException(DecodeError);
        return this;
    }
}

/// <summary>一度の走査で得たファイルとマスター文書。全体取得では変化がなければ同じインスタンスを返す。</summary>
public sealed class FilebaseSnapshot(IReadOnlyList<MasterDataEntry> entries, IReadOnlyList<FilebaseDocument> documents)
{
    /// <summary>リンクを除く全ファイル。<see cref="MasterDataPaths.Enumerate"/> と同じ順序。</summary>
    public IReadOnlyList<MasterDataEntry> Entries { get; } = entries;

    /// <summary>編集対象の文書（.yml / .yaml / .json）。<see cref="Entries"/> と同じ順序。</summary>
    public IReadOnlyList<FilebaseDocument> Documents { get; } = documents;

    /// <summary>指定ディレクトリ配下の文書を、そのディレクトリから列挙したときと同じ順序で返す。</summary>
    public IEnumerable<FilebaseDocument> Under(string directory)
    {
        var prefix = directory.TrimEnd(System.IO.Path.DirectorySeparatorChar) + System.IO.Path.DirectorySeparatorChar;
        return Documents.Where(document => document.FullPath.StartsWith(prefix, MasterDataPaths.Comparison));
    }
}

/// <summary>
/// Filebase を列挙し、長さと更新時刻が前回と同じファイルは読込と解析を省く。
/// 一覧・絞り込み索引・参照候補・参照元検索で、変更されていない文書の解析結果を共有する。
/// </summary>
public sealed class FilebaseSnapshotCache(MasterDataPaths paths, MasterDataCodec codec)
{
    // 更新時刻の刻みより短い間隔で同じ長さのまま書き換えられると属性では区別できない。
    // 書き換えから間もないうちに読んだファイルは、属性が同じでも次回もう一度読む。
    internal static readonly TimeSpan RecentWriteWindow = TimeSpan.FromSeconds(2);
    private static readonly UTF8Encoding StrictUtf8 = new(encoderShouldEmitUTF8Identifier: false, throwOnInvalidBytes: true);

    private readonly SemaphoreSlim _gate = new(1, 1);
    private readonly Dictionary<string, Slot> _slots = new(StringComparer.Ordinal);
    private FilebaseSnapshot? _current;
    private FilebaseSnapshot? _currentWithoutSchemas;

    private sealed record Slot(FilebaseDocument Document, DateTime ReadAtUtc)
    {
        public bool Matches(MasterDataEntry entry) =>
            Document.Length == entry.Length && Document.LastWriteTimeUtc == entry.LastWriteTimeUtc;
        public bool Trusted => ReadAtUtc - Document.LastWriteTimeUtc > RecentWriteWindow;
    }

    public async Task<FilebaseSnapshot> GetAsync(CancellationToken token, string? category = null, bool reload = false, bool includeSchemas = true)
    {
        await _gate.WaitAsync(token);
        try
        {
            var previous = category is null ? (includeSchemas ? _current : _currentWithoutSchemas) : null;
            var next = await BuildAsync(category, reload, includeSchemas, previous, token);
            if (category is null)
            {
                if (includeSchemas) _current = next;
                else _currentWithoutSchemas = next;
            }
            return next;
        }
        finally
        {
            _gate.Release();
        }
    }

    private async Task<FilebaseSnapshot> BuildAsync(
        string? category, bool reload, bool includeSchemas, FilebaseSnapshot? previous, CancellationToken token)
    {
        var entries = paths.EnumerateEntries(category).ToArray();
        var dataEntries = entries.Where(entry => MasterDataPaths.IsData(entry.FullPath)
            && (includeSchemas || !MasterDataPaths.IsSchema(entry.FullPath))).ToArray();
        var documents = new FilebaseDocument[dataEntries.Length];
        var stale = new List<int>();
        for (var index = 0; index < dataEntries.Length; index++)
        {
            var entry = dataEntries[index];
            if (!reload && _slots.TryGetValue(entry.FullPath, out var slot) && slot.Matches(entry) && slot.Trusted)
                documents[index] = slot.Document;
            else
                stale.Add(index);
        }

        var readAt = DateTime.UtcNow;
        if (stale.Count > 0)
        {
            await Parallel.ForEachAsync(stale,
                new ParallelOptions { CancellationToken = token, MaxDegreeOfParallelism = Math.Clamp(Environment.ProcessorCount, 2, 8) },
                async (index, cancellation) => documents[index] = await LoadAsync(dataEntries[index], cancellation));
            foreach (var index in stale)
            {
                var loaded = documents[index];
                // 読み直しても内容が同じなら以前の文書を保ち、スナップショットを作り直さない。
                if (_slots.TryGetValue(loaded.FullPath, out var slot) && slot.Matches(dataEntries[index])
                    && slot.Document.Revision == loaded.Revision)
                    documents[index] = slot.Document;
                _slots[loaded.FullPath] = new(documents[index], readAt);
            }
        }

        if (category is null)
        {
            var live = entries.Select(entry => entry.FullPath).ToHashSet(StringComparer.Ordinal);
            foreach (var path in _slots.Keys.Where(path => !live.Contains(path)).ToArray()) _slots.Remove(path);
        }

        if (previous is not null && previous.Entries.SequenceEqual(entries)
            && previous.Documents.SequenceEqual(documents, ReferenceEqualityComparer.Instance))
            return previous;
        return new(entries, documents);
    }

    private async Task<FilebaseDocument> LoadAsync(MasterDataEntry entry, CancellationToken token)
    {
        paths.RequireNoLinks(entry.FullPath);
        var bytes = await SharedFileReader.ReadAllBytesAsync(entry.FullPath, token);
        var relative = paths.Relative(entry.FullPath);
        string raw;
        string? decodeError = null;
        try
        {
            raw = StrictUtf8.GetString(bytes);
        }
        catch (DecoderFallbackException exception)
        {
            decodeError = exception.Message;
            raw = Encoding.UTF8.GetString(bytes);
        }
        if (raw.StartsWith('\uFEFF')) raw = raw[1..];
        var parsed = codec.Parse(relative, raw);
        Materialize(parsed.Content);
        return new(relative, entry.FullPath, entry.Length, entry.LastWriteTimeUtc, raw,
            EditorRevision.Hash(bytes), parsed.Content, parsed.Issues,
            MasterDataPaths.IsRelativeSyntax(relative) ? null : MasterDataPaths.RelativeSyntaxMessage, decodeError);
    }

    /// <summary>
    /// JSON から読んだノードは初回アクセス時に子要素を組み立てる。共有する前にここで組み立て切り、
    /// 以後の同時読取を読み取り専用の操作だけにする。
    /// </summary>
    private static void Materialize(JsonNode? node)
    {
        if (node is JsonObject obj)
            foreach (var pair in obj) Materialize(pair.Value);
        else if (node is JsonArray array)
            foreach (var child in array) Materialize(child);
    }
}
