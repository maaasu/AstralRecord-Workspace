---
name: astralrecord-skill-author
description: AstralRecord の Minecraft 内で使うアクティブ・パッシブスキルを新規追加または仕様変更する専門 skill。職業スキル、攻撃・回復・補助・移動・範囲効果、スキルジェム、管理者付与、ショップ販売、演出、DPS を含む戦闘スキルの設計・filebase・Plugin 実装・テスト・設計書同期が必要な依頼で使う。依頼情報が不足する場合は実装前に必要事項を質問する。
---

# AstralRecord Minecraft Skill Author

Minecraft 内のゲームスキルを扱う。Codex 自体の skill 作成には使わず、`$skill-creator` を使う。

## Intake

1. 次のどれかが未確定で、既存仕様から安全に決められない場合は、ファイルを変更せず不足項目だけを質問する。
   - 対象職業、スキル名、既存スキルとの関係（新規 / 変更）、おおまかな役割
   - 発動方法・対象条件・対象数・射程または範囲
   - 主効果と副効果（ダメージ、回復、状態異常、ノックバックなど）
   - リソース消費、クールダウン、詠唱時間、成長方式。未定の場合は「既存スキルを基準に提案してよいか」を確認する
   - 入手経路（初期習得、スキルジェム、ショップ、管理者利用）
2. 演出だけが曖昧な場合は、既存スキルと近いテーマに合わせて提案してよいかを確認する。粒子の方向・着弾・範囲の変化を指定できる。
3. 数値が未定でも、比較対象または「提案可」が明示されていれば進める。PvP / PvE 適用、対象優先順位、無敵時間などの戦闘規則に影響する未決事項は推測しない。
4. 依頼文の作成例は [references/request-template.md](references/request-template.md) を読む。

## Workflow

1. 通常は `$astralrecord-code-version-commit-develop` の task worktree / 品質ゲートを使う。すでに準備済みの task worktree ではこの skill を worker として実行する。
2. `AGENTS.md`、`PLUGIN_GUIDE.md`、`$astralrecord-code` の `references/plugin-code.md`、対象職業の戦闘バランス設計書、`40_filebase/AGENTS.md`、関連する既存 skill / gem / shop YAML を読む。
3. 近い既存スキルを確認して、ID、executor、発動判定、ダメージ計算、particle / sound、管理者公開、ジェムとショップ、テストの既存パターンを再利用する。
4. 最小の一貫した変更を実装する。
   - skill YAML、職業登録、必要な管理者登録、入手用ジェム・ショップを追加または更新する。
   - Plugin executor、catalog / registration、メッセージ・共通演出定義、必要なテストを更新する。
   - 指定された設計書にスキル効果、Lv1 / 最大Lvの性能、DPS 算出前提、対象数・追加効果、演出、入手経路を記載する。
5. Particle は `ParticleDisplayService` と共有定義を使う。Plugin の damage / target / thread / message 規約を崩さない。
6. 変更に応じて filebase 参照、Plugin resource check、設計トレーサビリティ、対象テストを検証する。品質ゲートのレビュー・修正・再レビューと commit / develop merge は統合入口の手順に従う。

## Scope Decision

- 実装済みの仕組みだけで表現できる場合は、filebase と設計書の変更を優先する。
- executor、判定、ダメージ、演出、GUI、管理者操作などの新しい振る舞いが必要なら Plugin 実装を含める。
- スキルジェム販売や管理者使用は、依頼で指定された場合、または既存職業スキルの標準配布方針に従う場合だけ追加する。勝手に公開範囲を広げない。
- 未決事項は「未対応事項」ではなく、実装前の質問として返す。推測で戦闘バランスを確定しない。

## Report

日本語で、追加したスキルの効果・数値・DPS 前提・入手経路・演出・検証結果・未決事項を簡潔に報告する。
