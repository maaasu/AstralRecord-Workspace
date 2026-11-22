# 冒険者領域の再生成

リポジトリルートで次を実行すると、ノード `2541`〜`2660` と構造 `remake` を再生成し、配置の検証結果と象限ごとの効果合計を表示します。

```powershell
& 'C:/Users/user/.local/bin/python3.12.exe' -X utf8 '99_work/skilltree-remake/generate_adventurer.py'
```

## ソードマン領域の再生成

リポジトリルートで次を実行すると、`swordsman_design.json` を読み、ノード `2661`〜`2900` と既存スキルノード5個を `remake` に配置します。実行のたびにこの245個の配置とそれらにつながる接続を置き直します。冒険者の配置・接続を引き継ぎ、構造全体の検証結果、象限別の効果合計、冒険者の各道標からの最短到達コストを表示します。

```powershell
& 'C:/Users/user/.local/bin/python3.12.exe' -X utf8 '99_work/skilltree-remake/generate_swordsman.py'
```

両方の領域を再生成する場合は、先に冒険者の生成コマンドで `remake` の土台を作り、続けてソードマンの生成コマンドを実行します。
