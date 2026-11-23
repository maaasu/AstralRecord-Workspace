package io.github.maaasu.astralRecord.feature.menu.view.screen;

import io.github.maaasu.astralRecord.feature.guide.model.GuideEntry;
import io.github.maaasu.astralRecord.feature.guide.model.GuideStep;
import io.github.maaasu.astralRecord.feature.guide.service.GuideService;
import io.github.maaasu.astralRecord.shared.gui.GuiResourcePackIcons;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.UUID;

/** 第1章の一覧・詳細。6つの小枠は表示順、進行度は保存済み手順数を表す。 */
public final class GuideScreenView extends BaseMenuScreenView {
    public static final int REWARD_SLOT = 51;
    public static final int TUTORIAL_ACTION_SLOT = 22;
    public static final int ALL_GUIDES_SLOT = 45;
    public static final String TUTORIAL_PREFIX = "$tutorial:";
    public static final String TUTORIAL_LOADING = TUTORIAL_PREFIX + "loading";
    public static final String TUTORIAL_LOAD_FAILED = TUTORIAL_PREFIX + "load_failed";
    public static final String TUTORIAL_UNAVAILABLE = TUTORIAL_PREFIX + "unavailable";
    public static final String TUTORIAL_COMPLETE = TUTORIAL_PREFIX + "complete";

    private static final String CHAPTER_TITLE = "グラスボアを1体倒そう";
    private static final int SUMMARY_SLOT = 4;
    private static final int FIRST_STEP_SLOT = 28;

    public static @NotNull String title(boolean overview, @NotNull String contentId, int completed, @Nullable GuideEntry chapter) {
        if (completed < 0) return overview ? "チャプター一覧" : "第1章";
        if (overview) return "第1章 " + CHAPTER_TITLE + " " + completed + "/6";
        if (TUTORIAL_COMPLETE.equals(contentId)) return "第1章 6/6 チャプター完了";
        for (int i = 0; chapter != null && i < chapter.steps().size(); i++) {
            if (contentId.equals(TUTORIAL_PREFIX + chapter.steps().get(i).id())) {
                return "第1章 " + completed + "/6 ▶ " + (i + 1) + " " + stepLabel(contentId);
            }
        }
        return "第1章";
    }

    private static String stepLabel(String contentId) {
        return switch (contentId) {
            case TUTORIAL_PREFIX + "open_menu" -> "メニューを開く";
            case TUTORIAL_PREFIX + "receive_welcome_mail" -> "メールを開く";
            case TUTORIAL_PREFIX + "open_initial_bundle" -> "支給箱を開ける";
            case TUTORIAL_PREFIX + "buy_starter_weapon" -> "ミナで武器を買う";
            case TUTORIAL_PREFIX + "equip_starter_weapon" -> "武器を装備する";
            case TUTORIAL_PREFIX + "defeat_grassboar" -> "グラスボアを倒す";
            default -> "進行を確認";
        };
    }

    public void renderChapter(@NotNull Inventory inventory, boolean overview, @NotNull String contentId,
                              @Nullable GuideEntry chapter, @NotNull GuideService service, @Nullable UUID accountId,
                              int completed, boolean rewardClaimable) {
        inventory.clear();
        inventory.setItem(BACK_SLOT, backItem());
        if (completed < 0 || chapter == null) {
            inventory.setItem(TUTORIAL_ACTION_SLOT, action(contentId));
            return;
        }
        boolean complete = TUTORIAL_COMPLETE.equals(contentId);
        String status = complete ? "チャプター完了" : "達成した手順 " + completed + "/6";
        ItemStack book = GuiResourcePackIcons.apply(item(Material.KNOWLEDGE_BOOK,
            "第1章 " + CHAPTER_TITLE, NamedTextColor.GOLD, status,
            overview ? "選ぶと手順を表示" : "番号は手順の順番です"), "guide");

        inventory.setItem(overview ? TUTORIAL_ACTION_SLOT : SUMMARY_SLOT, book);
        if (!overview) {
            inventory.setItem(TUTORIAL_ACTION_SLOT, action(contentId));
            inventory.setItem(ALL_GUIDES_SLOT, GuiResourcePackIcons.apply(item(
                Material.KNOWLEDGE_BOOK, "チャプター一覧", NamedTextColor.AQUA, "一覧に戻る"), "guide"));
            for (int i = 0; i < chapter.steps().size(); i++) {
                GuideStep step = chapter.steps().get(i);
                boolean done = service.isStepCompleted(accountId, chapter.id(), step.id());
                boolean current = contentId.equals(TUTORIAL_PREFIX + step.id());
                String state = done ? "✓ 完了" : current ? "▶ 現在" : "◇ 今後";
                String label = stepLabel(TUTORIAL_PREFIX + step.id());
                ItemStack marker = item(done ? Material.LIME_DYE : current ? Material.NETHER_STAR : Material.GRAY_DYE,
                    state + " " + (i + 1) + ". " + label,
                    done ? NamedTextColor.GREEN : current ? NamedTextColor.GOLD : NamedTextColor.GRAY,
                    current ? "選ぶと次の操作へ" : done ? "達成済みの手順" : "このあと行う手順");
                marker.setAmount(i + 1);
                inventory.setItem(FIRST_STEP_SLOT + i, marker);
            }
        }
        if (rewardClaimable) {
            inventory.setItem(REWARD_SLOT, item(Material.CHEST, "達成報酬を受け取る",
                NamedTextColor.GOLD, "選ぶと報酬がメールに届きます"));
        }
    }

    /** 新しいJava版パネルの帯と文字を、表示中のスロット操作に対応付けます。 */
    public static int panelControlSlot(int rawSlot, @Nullable String contentId) {
        if (rawSlot < 0 || rawSlot >= SIZE) return rawSlot;
        boolean overview = contentId == null;
        if (!overview && (TUTORIAL_LOADING.equals(contentId) || TUTORIAL_LOAD_FAILED.equals(contentId)
            || TUTORIAL_UNAVAILABLE.equals(contentId))) return rawSlot;
        int row = rawSlot / 9;
        int column = rawSlot % 9;
        if (column >= 1 && column <= 7 && (overview ? row <= 3 : row == 2)) return TUTORIAL_ACTION_SLOT;
        if (rawSlot == 50) return BACK_SLOT;
        if (!overview && rawSlot == 46) return ALL_GUIDES_SLOT;
        // 手順名は各アイコンの直下の行に描きます。現在の手順だけが操作できます。
        if (!overview && row == 4 && column >= 1 && column <= 6) return rawSlot - 9;
        return rawSlot;
    }

    public static boolean isCurrentStepSlot(int slot, @Nullable GuideEntry chapter, @NotNull String contentId) {
        int index = slot - FIRST_STEP_SLOT;
        return chapter != null && index >= 0 && index < chapter.steps().size()
            && contentId.equals(TUTORIAL_PREFIX + chapter.steps().get(index).id());
    }

    private @NotNull ItemStack action(@NotNull String contentId) {
        return switch (contentId) {
            case TUTORIAL_PREFIX + "open_menu" -> item(Material.COMPASS, "メニューを開く", NamedTextColor.GOLD,
                "案内具で開く画面へ移動");
            case TUTORIAL_PREFIX + "receive_welcome_mail" -> item(Material.PAPER, "メールを開く", NamedTextColor.GOLD,
                "「初ログイン記念の贈り物」を", "選んで受け取ろう");
            case TUTORIAL_PREFIX + "open_initial_bundle" -> item(Material.BUNDLE, "支給箱を開ける", NamedTextColor.GOLD,
                "持ち物の初ログインバンドルを", "ホットバーに入れて手に持とう", "画面を閉じて使うと開封");
            case TUTORIAL_PREFIX + "buy_starter_weapon" -> item(Material.GOLD_INGOT, "雑貨商ミナへ", NamedTextColor.GOLD,
                "選ぶとミナの場所を表示", "簡素な武器は20ゴールド");
            case TUTORIAL_PREFIX + "equip_starter_weapon" -> item(Material.WOODEN_SWORD, "装備を開く", NamedTextColor.GOLD,
                "下のバッグにある", "「簡素な武器」を選ぼう");
            case TUTORIAL_PREFIX + "defeat_grassboar" -> item(Material.STONE_SWORD, "グラスボアの場所", NamedTextColor.GOLD,
                "選ぶと狩り場を案内", "グラスボアを1体倒そう");
            case TUTORIAL_COMPLETE -> item(Material.EMERALD, "チャプター完了", NamedTextColor.GREEN,
                "第1章の6手順を達成しました");
            case TUTORIAL_LOADING -> item(Material.CLOCK, "読み込み中", NamedTextColor.GRAY, "進行を確認しています");
            case TUTORIAL_LOAD_FAILED -> item(Material.PAPER, "読み込みに失敗", NamedTextColor.RED, "選ぶと再読み込み");
            default -> item(Material.BOOK, "第1章を表示できません", NamedTextColor.GRAY, "案内データを確認できません");
        };
    }

    private @NotNull ItemStack item(Material material, String title, NamedTextColor color, String... lore) {
        return createItem(material, Component.text(title, color, TextDecoration.BOLD),
            Arrays.stream(lore).<Component>map(line -> Component.text(line, NamedTextColor.GRAY)).toList());
    }
}
