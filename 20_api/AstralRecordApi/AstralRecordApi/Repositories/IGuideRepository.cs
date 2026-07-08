using AstralRecordApi.Models;

namespace AstralRecordApi.Repositories;

public interface IGuideRepository
{
    IReadOnlyList<GuideResponse> GetAll();

    GuideResponse? GetById(string guideId);
}
