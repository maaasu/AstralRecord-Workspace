# Quest 設計

## 役割

Quest は、既存の戦闘、採集、探索、制作へ目的と報酬を与える進行定義です。

## 設計方針

- 目的、達成条件、報酬、前提、繰り返し可否を明確にします。
- quest のためだけに孤立した対象を増やさず、既存のプレイ循環へ接続します。
- 未定義の mob、item、gathering、world を参照しません。
- 必須進行と任意報酬を区別します。

## progression

受注ではなく、標準的に達成できる段階を記載します。報酬は同値を基本とし、次の目的を提示する更新報酬は `+1` を許容します。

## 正本参照

- YAML: `E:\AstralRecord-Workspace\40_filebase\47.features.quest\docs.quest.YAMLスキーマ定義.md`
