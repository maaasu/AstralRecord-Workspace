# 設計駆動実装

ユーザーが設計書、spec、docs feature folder、または `E:\AstralRecord-Workspace\00_docs` 配下の path から実装するよう求めた場合にこの reference を使う。

## 入力の扱い

1. 指定されたすべての path を絶対パスに正規化する。
2. docs path が示す実装 project を特定する。
   - `00_docs/10_Plugin設計書`: 通常は `10_plugin/AstralRecord`。
   - 将来 API/Web docs が追加された場合: docs tree またはユーザー依頼が示す対応 project に割り当てる。
3. docs root README、対象 feature 概要、挙動または contract を定義する直接参照 docs を読む。
4. docs 内の実装 path 名は意図された場所として扱うが、編集前に実際の project layout も確認する。

## 抽出チェックリスト

依頼された実装に必要なものだけを抽出する。

- feature の責務と対象外。
- command、route、screen、event handler、scheduled task、integration entry point。
- model、DTO、entity、repository、filebase key、resource ID、enum、constant、item/material ID。
- state transition、persistence rule、idempotency、concurrency、rollback、migration behavior。
- validation、permission/authentication、error handling、user message、log、observability。
- docs に記載された test または acceptance criteria。
- 未決事項と、まだ行われていない設計判断。

## 判断ルール

- 明示された設計判断を実装し、未決の判断を推測で埋めない。
- ユーザーが狭い変更を依頼した場合、docs が大きな feature を説明していても、その範囲だけを実装する。
- docs と code が一致しない場合は、既存挙動を壊さないために近隣の history/pattern を必要な範囲だけ確認する。重要な不一致は報告する。
- 設計が Plugin/API/Web/Database/Filebase/Resourcepack をまたぐ変更を要求する場合は、project ごとに reasoning を分け、各境界 contract を検証する。
- ユーザーが docs 変更を求めておらず、実装が必要な文書不一致を明らかにしていない限り、設計書を更新しない。

## 検証

project-local check を優先する。

- Plugin: ルート `README.md` / `references/plugin-code.md` に記載された Maven compile/test または module を絞った command。
- API: `references/api-code.md` に従った API project の `dotnet build` / targeted test。
- Web: Web project の `dotnet build` / page-level check。
- Database/Filebase/Resourcepack: 利用できる場合は文書化された validator または syntax check。

check を利用できない場合は blocker を説明し、手動で行った整合性確認の概要を含める。
