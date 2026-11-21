# AstralArchitectの作業ルール

対象は `10_plugin/AstralArchitect/` です。独立 Paper プラグインの実装・検証・配置や、建築チケットの候補編集を行うときに参照します。AstralArchitect は局所建築チケットと Minecraft ワールドの間を安全に接続します。

## Read Next

- 利用手順、チケット契約、状態遷移は [AstralArchitect README](../../../10_plugin/AstralArchitect/README.md) で確認する。
- 候補の編集には [`$astralarchitect-builder`](../../../.codex/skills/astralarchitect-builder/SKILL.md) を使う。
- 共通 Java 実装規約は [Pluginガイド](../../../PLUGIN_GUIDE.md) と [`$astralrecord-code`](../../../.codex/skills/astralrecord-code/SKILL.md) を参照する。

## 固定方針

- `FastAsyncWorldEdit` 2.15.2を必須依存とする。
- Java 21 / Paper 1.21.11を対象とする。
- プラグインはAI設計を行わず、範囲保存、候補検証、差分適用、ロールバックを担当する。
- `source.schem`は不変とし、作業者は`candidate.schem`だけを専用CLI経由で編集する。
- 適用時にもプラグイン側でハッシュ、形式、差分、現在ワールドとの競合を再検証する。
- プレイヤー操作はクリエイティブモードと権限を必須とする。
- 新規public型・publicメソッドには日本語JavaDocを付ける。

## 検証

`10_plugin/AstralArchitect/` から次を実行します。

```powershell
mvn clean test package
python -m unittest discover -s tools -p 'test_*.py'
```

実サーバーへ配置する場合は [AstralArchitect の配置スクリプト](../../../60_tool/09-astralarchitect-build-deploy.bat) を使います。実行入口は`60_tool`で管理し、スクリプトはサーバーを停止・再起動しません。
