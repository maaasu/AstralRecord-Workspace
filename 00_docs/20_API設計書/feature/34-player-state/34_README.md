# 34_player-state

Plugin ローカルで確定した player state を、再計算なしで一 transaction に保存する snapshot API を定義する。通常の個別 inventory / equipment / skill API は外部 mutation 用に維持するが、Plugin の save lane は本 API を使用する。

1. [[0-概要/34_0.00-概要]]
2. [[1-モデル定義/34_1.00-モデル定義]]
3. [[3-エンドポイント仕様/34_3.00-索引]]
