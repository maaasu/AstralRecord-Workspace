package io.github.maaasu.astralRecord.feature.hud.model;

import io.github.maaasu.astralRecord.feature.buff.model.ActiveBuff;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** サイドバーの表示ページと、前回確認したデバフの付与単位を保持します。メインスレッド専用です。 */
public final class SidebarBuffPageState {
    private int pageIndex;
    private int pendingDirection;
    private Set<DebuffKey> previousDebuffs = Set.of();

    /**
     * 次回描画で行うページ移動を予約します。
     * @param direction 前ページは負、次ページは正。0は移動しない
     */
    public void move(int direction) {
        pendingDirection += Integer.signum(direction);
    }

    /**
     * 現在の一覧と表示枠へページを補正し、表示対象を返します。新規デバフは先頭ページを優先します。
     * @param buffs 獲得順の有効な効果
     * @param showBuffs 通常バフを含めるか。デバフは常に含める
     * @param pageSize 現在確保できる効果行数。0以下では効果行を返さない
     * @return 件数・ページ番号と、そのページに属する効果
     */
    public Page select(List<ActiveBuff> buffs, boolean showBuffs, int pageSize) {
        List<ActiveBuff> visible = buffs.stream()
                .filter(buff -> showBuffs || buff.getType().isDebuff())
                .sorted(Comparator.comparing(buff -> !buff.getType().isDebuff()))
                .toList();
        Set<DebuffKey> debuffs = new HashSet<>();
        for (ActiveBuff buff : visible) {
            if (buff.getType().isDebuff()) {
                debuffs.add(new DebuffKey(buff.getType().getId(), buff.getStartedAt()));
            }
        }
        boolean newDebuff = !previousDebuffs.containsAll(debuffs);
        previousDebuffs = debuffs;
        int pageCount = pageSize <= 0 ? 1 : Math.max(1, (visible.size() + pageSize - 1) / pageSize);
        pageIndex = newDebuff ? 0 : Math.min(pageIndex, pageCount - 1);
        if (!newDebuff) {
            pageIndex = Math.floorMod(pageIndex + pendingDirection, pageCount);
        }
        pendingDirection = 0;
        int start = pageSize <= 0 ? 0 : pageIndex * pageSize;
        int end = pageSize <= 0 ? 0 : Math.min(start + pageSize, visible.size());
        return new Page(visible.subList(start, end), visible.size(), pageIndex, pageCount, start);
    }

    private record DebuffKey(String typeId, LocalDateTime startedAt) {
    }

    /** 表示対象と0始まりのページ番号・一覧内の開始位置です。 */
    public record Page(List<ActiveBuff> buffs, int totalCount, int pageIndex, int pageCount, int startIndex) {
    }
}
