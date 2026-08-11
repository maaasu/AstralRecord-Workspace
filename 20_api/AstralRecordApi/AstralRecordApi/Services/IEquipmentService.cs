using AstralRecordApi.Models;

namespace AstralRecordApi.Services;

public interface IEquipmentService
{
    /// <summary>
    /// マスタデータをもとに装備インスタンスを生成して DB に保存する。
    /// <para><paramref name="request"/> の <c>EquipmentId</c> が存在しない、または equipment カテゴリでない場合は <c>null</c> を返す。</para>
    /// </summary>
    Task<EquipmentInstanceResponse?> CreateAsync(EquipmentCreateRequest request);

    /// <summary>
    /// 指定した装備インスタンス ID のデータを取得する。論理削除済みは返さない。
    /// </summary>
    Task<EquipmentInstanceResponse?> GetByInstanceIdAsync(Guid instanceId);

    /// <summary>オーブ支払いと装備更新を冪等な単一トランザクションとして実行する。</summary>
    Task<EquipmentOrbOperationResponse> ApplyOrbAsync(EquipmentOrbOperationRequest request);

    /// <summary>保存済みオーブ操作結果を所有者照合付きで取得する。</summary>
    Task<EquipmentOrbOperationResponse?> FindOrbOperationAsync(Guid operationId, Guid accountId);

    /// <summary>所有装備の指定スロットからエンチャントを削除する。</summary>
    /// <param name="request">削除リクエスト</param>
    /// <returns>削除後装備。対象不正・所有者不一致時は <c>null</c></returns>
    Task<EquipmentInstanceResponse?> DeleteEnchantAsync(EquipmentEnchantDeleteRequest request);

    Task<EquipmentInstanceResponse?> UpdateDurabilityAsync(EquipmentDurabilityUpdateRequest request);

    Task<bool> DeleteAsync(Guid instanceId);

    Task<EquipmentInstanceResponse?> AttachRuneAsync(EquipmentRuneAttachRequest request);

    Task<EquipmentInstanceResponse?> DetachRuneAsync(EquipmentRuneDetachRequest request);
}
