# Quality Gate

Standard gateまたはworkspace skill logicの変更でだけ読む。Light gateの一ファイル非動作変更では、対象workerの検証と独立reviewer一人に留める。

Light reviewerに指摘があれば、対応するfix skillで自動修正可能な指摘だけを直し、対象checkと一度のtargeted confirmationを実行する。`要確認`や設計判断は勝手に確定しない。

## Standard gate

- worker検証後にRound 1 reviewを行う。
- 指摘があり、自動修正可能ならfix skillを一度だけ実行し、対象checkを再実行する。
- Round 2はfixerと独立したreviewerで行う。新規の自動修正可能な回帰だけ、Fix Pass 2と対象IDの確認を一度だけ許可する。
- 指摘がないレビュー記録は作成しない。指摘がある場合だけtask worktree内にcanonical recordを一つ作り、validatorを通す。
- `[高]`、`[中]`、未解決の自動修正可能指摘を残したままfinalizeしない。
- `[低]`、`[情報]`、または設計判断を要する未解決事項は、非ブロッキング理由を記録して最終報告に明記する。

## Build Warning Gate

意味のあるbuild、compile、test、static-analysis commandがあるStandard gateでは、標準エラーを含む完全な出力を保存して警告も確認する。

1. 警告をtask起因、既存、外部/toolchain起因に分類する。
2. task起因で修正可能な警告は修正し、同じcheckを再実行する。
3. 既存警告は可能なら現在のlocal `develop`でも同じcheckを行う。
4. 未説明の新規警告、確認不能な警告、未承認のtask起因警告があればfinalizeしない。

Light gateでは、この規則だけを理由にfull buildを実行しない。ただし対象projectの既存規則がbuildを要求する場合は従い、出力した警告は確認する。

## Plugin test traceability

次のいずれかが変更された場合だけ、task rootから次の検証を実行する。

- Plugin test source
- `10_plugin/AstralRecord/pom.xml`
- 許可されたdesign input
- test-policy path

```powershell
python <task-root>/.codex/skills/astralrecord-plugin-test/scripts/validate_test_traceability.py
```

test traceability検証は、差分に上記のpathがないtaskでは起動しない。
