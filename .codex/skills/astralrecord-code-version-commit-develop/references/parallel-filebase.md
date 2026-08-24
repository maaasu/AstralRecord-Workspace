# Parallel Filebase Flow

`40_filebase` を実際に並列編集するときだけ読む。

1. YAML一ファイル単位ではなく、area / combat / economyなど独立検証できるpackage単位で分ける。
2. Prepare前に、package名、owned paths、予約ID/ID prefix、shared-file owner、依存、finalize順を決める。
3. packageごとにtask branchと専用worktreeを作る。同じworkspaceの作業ツリー、Git index、HEADを共有しない。
4. packageごとにworker検証、品質ゲート、scoped commitまで完了し、finalize前はworktreeを保持する。
5. finalizeは依存順に一件ずつ行い、最新local `develop`へrebase後に全体ID重複と変更参照を再検証する。
6. worktreeを作らない並列作業は、読み取り専用のYAML案・ID/reference manifestに限る。反映とcommitは単一の統合taskが直列で行う。
