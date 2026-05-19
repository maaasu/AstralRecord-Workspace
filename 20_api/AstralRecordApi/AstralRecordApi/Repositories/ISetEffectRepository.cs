using AstralRecordApi.Models;

namespace AstralRecordApi.Repositories;

public interface ISetEffectRepository
{
    IReadOnlyList<SetEffectResponse> GetAll();

    SetEffectResponse? GetById(string setId);
}
