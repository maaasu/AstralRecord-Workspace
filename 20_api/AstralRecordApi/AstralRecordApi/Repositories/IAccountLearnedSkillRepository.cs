using AstralRecordApi.Models;

namespace AstralRecordApi.Repositories;

public interface IAccountLearnedSkillRepository
{
    Task<IReadOnlyList<AccountLearnedSkillResponse>> GetByAccountIdAsync(Guid accountId);
    Task<AccountLearnedSkillMutationResult> LearnAsync(Guid accountId, AccountLearnedSkillLearnRequest request);
    Task<AccountLearnedSkillMutationResult> LevelUpAsync(Guid accountId, Guid learnedSkillId, AccountLearnedSkillLevelUpRequest request);
    Task<AccountLearnedSkillMutationResult> AttachSigilAsync(Guid accountId, Guid learnedSkillId, AccountLearnedSkillAttachSigilRequest request);
    Task<AccountLearnedSkillMutationResult> ForgetAsync(Guid accountId, Guid learnedSkillId, AccountLearnedSkillForgetRequest request);
}
