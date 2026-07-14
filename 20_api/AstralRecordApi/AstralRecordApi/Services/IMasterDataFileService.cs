using AstralRecordApi.Models;

namespace AstralRecordApi.Services;

public interface IMasterDataFileService
{
    IReadOnlyList<MasterDataFileSummaryResponse> List(string? directory);
    MasterDataFileResponse? Get(string relativePath);
    MasterDataFileResponse Put(string relativePath, string content);
    bool Delete(string relativePath);
}
