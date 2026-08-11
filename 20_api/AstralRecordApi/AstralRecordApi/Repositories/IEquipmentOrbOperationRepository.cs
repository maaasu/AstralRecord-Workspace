using AstralRecordApi.Models;

namespace AstralRecordApi.Repositories;

/// <summary>
/// オーブ支払いと装備更新を単一トランザクションで確定するリポジトリ。
/// </summary>
public interface IEquipmentOrbOperationRepository
{
    /// <summary>要求を冪等に実行する。operationId の内容不一致は OPERATION_CONFLICT を返す。</summary>
    Task<EquipmentOrbOperationResponse> ExecuteAsync(EquipmentOrbOperationRequest request);

    /// <summary>所有アカウントを照合して保存済み結果を取得する。</summary>
    Task<EquipmentOrbOperationResponse?> FindAsync(Guid operationId, Guid accountId);
}
