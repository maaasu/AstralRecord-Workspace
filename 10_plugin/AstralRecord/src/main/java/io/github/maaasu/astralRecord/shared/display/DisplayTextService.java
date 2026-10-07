package io.github.maaasu.astralRecord.shared.display;

import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgResource;
import io.github.maaasu.astralRecord.infrastructure.util.ColorCodeUtil;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * 実体 TextDisplay を用いて、固定表示・追従表示・アニメーション表示を統一管理するサービスです。
 */
public final class DisplayTextService {

    private static final long UPDATE_INTERVAL_TICKS = 1L;
    private static final int MAX_DAMAGE_DISPLAYS = 80;
    private static final int DAMAGE_ANIMATION_FRAMES = 6;
    private static final long DAMAGE_ANIMATION_FRAME_TICKS = 4L;
    private static final int MAX_RESULTS_PER_VIEWER = 3;
    private static final long RESULT_FRAME_TICKS = 4L;
    private static final long RESULT_ENTRANCE_TICKS = 12L;
    private static final long RESULT_EXIT_TICKS = 12L;
    private static final long RESULT_REVEAL_TICKS = 36L;
    private static final int MAX_RESULT_LINES = 16;
    private static final int RESULT_HEADER_LINES = 3;

    private final ConcurrentHashMap<UUID, ManagedDisplayState> displays = new ConcurrentHashMap<>();
    private final AtomicLong displaySequence = new AtomicLong();
    private Plugin plugin;
    private BukkitTask task;

    /**
     * 定期更新を開始します。
     *
     * @param plugin scheduler を起動するプラグイン
     */
    public void start(@NotNull Plugin plugin) {
        if (task != null) {
            return;
        }
        this.plugin = plugin;
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, (Runnable) this::tick, 1L, UPDATE_INTERVAL_TICKS);
    }

    /**
     * すべての TextDisplay を破棄して定期更新を停止します。
     */
    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        plugin = null;
        for (ManagedDisplayState state : displays.values()) {
            destroyEntity(state);
        }
        displays.clear();
    }

    /**
     * 汎用 TextDisplay を生成します。
     *
     * @param anchor  表示基準位置
     * @param options 表示オプション
     * @return 管理ハンドル
     */
    public @NotNull ManagedTextDisplay create(@NotNull DisplayAnchor anchor, @NotNull DisplayTextOptions options) {
        return create(anchor, options, false);
    }

    private @NotNull ManagedTextDisplay create(
            @NotNull DisplayAnchor anchor,
            @NotNull DisplayTextOptions options,
            boolean damageDisplay
    ) {
        ManagedDisplayState state = new ManagedDisplayState(anchor, options, damageDisplay, displaySequence.incrementAndGet());
        displays.put(state.id, state);
        return new ManagedTextDisplay(state.id);
    }

    /**
     * ダメージ数値向けの浮遊表示を生成します。
     *
     * @param origin   表示起点
     * @param amount   表示ダメージ量
     * @param critical クリティカル演出扱いとするか
     * @return 管理ハンドル
     */
    public @NotNull ManagedTextDisplay spawnDamageNumber(@NotNull Location origin, double amount, boolean critical) {
        pruneDamageDisplays();
        return spawnFloatingNumber(origin, amount, critical ? "&e✦ " : "&c");
    }

    /**
     * シールドダメージ向けの水色ダメージ数値を表示します。
     *
     * @param origin 表示起点
     * @param amount 表示するシールドダメージ量
     * @return 管理ハンドル
     */
    public @NotNull ManagedTextDisplay spawnShieldDamageNumber(@NotNull Location origin, double amount) {
        pruneDamageDisplays();
        return spawnFloatingNumber(origin, amount, "&b");
    }

    /**
     * HP 回復向けの黄緑色の浮遊数値を表示します。
     *
     * @param origin 表示起点
     * @param amount 表示する実回復量
     * @return 管理ハンドル
     */
    public @NotNull ManagedTextDisplay spawnHealingNumber(@NotNull Location origin, double amount) {
        pruneDamageDisplays();
        return spawnFloatingNumber(origin, amount, "&a+");
    }

    /**
     * 回避成立時の浮遊表示を生成します。
     *
     * @param origin 表示起点
     * @return 管理ハンドル
     */
    public @NotNull ManagedTextDisplay spawnEvadedText(@NotNull Location origin) {
        pruneDamageDisplays();
        return spawnFloatingText(origin, "&7Miss");
    }

    private @NotNull ManagedTextDisplay spawnFloatingNumber(@NotNull Location origin, double amount, @NotNull String prefix) {
        String text = floatingNumberText(prefix, amount);
        return spawnFloatingText(origin, text);
    }

    /** 浮遊数値の色コード・符号・整数丸めを共通化します。 */
    static @NotNull String floatingNumberText(@NotNull String prefix, double amount) {
        return prefix + String.format(Locale.ROOT, "%.0f", Math.max(0.0D, amount));
    }

    /**
     * TextDisplay 1 体だけを使う浮遊テキストを生成します。
     *
     * @param origin 表示起点
     * @param text 表示文字列
     * @return 管理ハンドル
     */
    private @NotNull ManagedTextDisplay spawnFloatingText(@NotNull Location origin, @NotNull String text) {
        ManagedTextDisplay display = create(
                DisplayAnchor.fixed(origin),
                DisplayTextOptions.damage(text),
                true
        );

        double xDrift = ThreadLocalRandom.current().nextDouble(-0.32D, 0.32D);
        double zDrift = ThreadLocalRandom.current().nextDouble(-0.22D, 0.22D);
        display.playTransformAnimation(damageAnimationFrames(text, xDrift, zDrift), false, true);
        return display;
    }

    static @NotNull List<DisplayAnimationFrame> damageAnimationFrames(
            @NotNull String text,
            double xDrift,
            double zDrift
    ) {
        List<DisplayAnimationFrame> frames = new ArrayList<>(DAMAGE_ANIMATION_FRAMES);
        for (int index = 0; index < DAMAGE_ANIMATION_FRAMES; index++) {
            double progress = index / (double) (DAMAGE_ANIMATION_FRAMES - 1);
            double easedRise = 1.0D - Math.pow(1.0D - progress, 2.0D);
            double lateralEase = 1.0D - Math.pow(1.0D - progress, 3.0D);
            double pop = Math.sin(progress * Math.PI) * 0.16D;
            frames.add(new DisplayAnimationFrame(
                    text,
                    new Vector(xDrift * lateralEase, 0.08D + easedRise * 0.88D + pop, zDrift * lateralEase),
                    DAMAGE_ANIMATION_FRAME_TICKS
            ));
        }
        return List.copyOf(frames);
    }

    private void pruneDamageDisplays() {
        long damageDisplays = displays.values().stream()
                .filter(state -> state.damageDisplay && !state.destroyed)
                .count();
        if (damageDisplays < MAX_DAMAGE_DISPLAYS) {
            return;
        }

        long removeCount = damageDisplays - MAX_DAMAGE_DISPLAYS + 1;
        displays.values().stream()
                .filter(state -> state.damageDisplay && !state.destroyed)
                .sorted((left, right) -> Long.compare(left.sequence, right.sequence))
                .limit(removeCount)
                .forEach(state -> {
                    state.destroyed = true;
                    destroyEntity(state);
                    displays.remove(state.id);
                });
    }

    /**
     * 共通の登場・順次表示・退場演出でリザルトを6秒間ワールド上へ表示します。
     *
     * @param origin 表示基準座標
     * @param text   表示するリザルト本文
     * @return 管理ハンドル
     */
    public @NotNull ManagedTextDisplay spawnResultText(@NotNull Location origin, @NotNull String text) {
        return createResultText(origin, text, null, 120L);
    }

    /**
     * リザルトを拡大・上昇して登場させ、行を順に開示し、終了前にフェードアウトします。
     * 長い本文は空行で区切った参加者・報酬のブロックを保ち、最大16行のページへ分割します。
     * 個人表示は生成時から他者へ非表示とし、同じ閲覧者の古い結果を3件までに整理します。
     *
     * @param origin 最終的な表示基準位置
     * @param text 初期の結果本文。動的本文は返されたハンドルに設定できます
     * @param viewer 個人表示の閲覧者。nullの場合は共有表示
     * @param durationTicks 表示寿命。登場・退場を含む正のサーバーtick数
     * @return セッション終了時にも破棄できる管理ハンドル
     * @throws IllegalArgumentException 寿命が0以下の場合
     * @implNote Bukkitメインスレッドから呼び出します。Plugin停止時は全表示を回収します。
     */
    public @NotNull ManagedTextDisplay createResultText(
            @NotNull Location origin,
            @NotNull String text,
            @Nullable Player viewer,
            long durationTicks
    ) {
        if (durationTicks <= 0L) {
            throw new IllegalArgumentException("Result duration must be positive.");
        }
        UUID viewerId = viewer == null ? null : viewer.getUniqueId();
        if (viewerId != null) {
            List<ManagedDisplayState> previous = displays.values().stream()
                    .filter(state -> !state.destroyed && state.resultAnimation != null
                            && viewerId.equals(state.resultAnimation.viewerId()))
                    .sorted(Comparator.comparingLong(state -> state.sequence))
                    .toList();
            for (int index = 0; index <= previous.size() - MAX_RESULTS_PER_VIEWER; index++) {
                ManagedDisplayState state = previous.get(index);
                state.destroyed = true;
                destroyEntity(state);
                displays.remove(state.id);
            }
        }
        ManagedTextDisplay display = create(
                DisplayAnchor.fixed(origin),
                new DisplayTextOptions(text, new Vector(), Display.Billboard.CENTER,
                        440, 64.0F, (byte) 0xFF, false, true, false,
                        Color.fromARGB(176, 8, 8, 24), (int) RESULT_FRAME_TICKS, 0,
                        false, 15, 15)
        );
        display.requireState().resultAnimation = new ResultAnimation(viewerId, durationTicks);
        return display;
    }

    private void tick() {
        for (ManagedDisplayState state : displays.values()) {
            if (state.destroyed) {
                displays.remove(state.id);
                continue;
            }
            tick(state);
        }
    }

    private void tick(@NotNull ManagedDisplayState state) {
        Location anchorLocation = state.anchor.resolve();
        if (anchorLocation == null || anchorLocation.getWorld() == null) {
            destroyEntity(state);
            return;
        }

        refreshDynamicText(state);
        if (state.resultAnimation != null) {
            tickResult(state, anchorLocation);
            return;
        }
        DisplayAnimationFrame frame = resolveFrame(state);
        Entity attachment = state.anchor.attachment();
        Vector attachedOffset = resolveAttachedOffset(state, frame);
        Location targetLocation = attachment != null
                ? attachment.getLocation()
                : anchorLocation.clone()
                        .add(state.options.offset())
                        .add(state.transformAnimation ? new Vector() : frame.offset());
        TextDisplay entity = ensureEntity(state, targetLocation);
        if (entity == null) {
            return;
        }

        applyOptionsIfNeeded(entity, state);
        applyFrame(entity, state, frame);
        if (attachment != null) {
            attachIfNeeded(entity, state, attachment, attachedOffset);
        } else {
            detachIfNeeded(entity);
            applyTranslationTransform(
                    entity,
                    state,
                    state.transformAnimation ? frame.offset() : new Vector(),
                    state.transformAnimation ? frame.durationTicks() : state.options.interpolationDuration()
            );
            teleportIfNeeded(entity, state, targetLocation);
        }

        if (!state.frames.isEmpty()) {
            state.animationAge++;
            if (state.destroyAfterAnimation && state.animationAge >= totalAnimationDuration(state.frames)) {
                state.destroyed = true;
                destroyEntity(state);
                displays.remove(state.id);
            }
        }
    }

    /**
     * 結果表示の本文・補間・透過を更新し、寿命や閲覧者の退出時に回収します。
     * 安定表示中は変換を再送せず、登場・退場の変換更新も4tickごとに制限します。
     * @param state 結果表示の状態
     * @param anchorLocation 解決済みの表示座標
     */
    private void tickResult(@NotNull ManagedDisplayState state, @NotNull Location anchorLocation) {
        ResultAnimation animation = state.resultAnimation;
        Player viewer = animation.viewerId() == null ? null : Bukkit.getPlayer(animation.viewerId());
        if (state.animationAge >= animation.durationTicks()
                || (animation.viewerId() != null && (viewer == null || !viewer.isOnline()
                || !viewer.getWorld().equals(anchorLocation.getWorld())))) {
            state.destroyed = true;
            destroyEntity(state);
            displays.remove(state.id);
            return;
        }
        TextDisplay entity = ensureEntity(state, anchorLocation);
        if (entity == null) return;
        applyOptionsIfNeeded(entity, state);
        if (!Objects.equals(state.resultFullText, state.options.text())) {
            state.resultFullText = state.options.text();
            state.resultPages = resultPages(state.resultFullText);
        }
        long pageDuration = Math.min(120L, Math.max(40L,
                (animation.durationTicks() - RESULT_EXIT_TICKS) / state.resultPages.size()));
        int pageIndex = (int) (state.animationAge / pageDuration % state.resultPages.size());
        List<String> lines = state.resultPages.get(pageIndex);
        long pageAge = state.resultPages.size() == 1 ? state.animationAge : state.animationAge % pageDuration;
        long revealDuration = Math.min(RESULT_REVEAL_TICKS, Math.max(1L, lines.size() * 2L));
        int visibleLines = (int) Math.min(lines.size(), 1L + pageAge * lines.size() / revealDuration);
        String revealed = String.join("\n", lines.subList(0, visibleLines))
                + "\n".repeat(lines.size() - visibleLines);
        applyFrame(entity, state, new DisplayAnimationFrame(revealed, new Vector(), 1L));
        if (state.animationAge % RESULT_FRAME_TICKS == 0L) {
            double entrance = Math.min(1.0D, state.animationAge / (double) RESULT_ENTRANCE_TICKS);
            double eased = 1.0D - Math.pow(1.0D - entrance, 3.0D);
            double exit = Math.clamp((state.animationAge - animation.durationTicks() + RESULT_EXIT_TICKS)
                    / (double) RESULT_EXIT_TICKS, 0.0D, 1.0D);
            float scale = (float) (0.55D + 0.27D * eased + 0.08D * Math.sin(entrance * Math.PI));
            Vector offset = new Vector(0.0D, -0.24D * (1.0D - eased) + exit * 0.18D, 0.0D);
            if (!offset.equals(state.lastTransformOffset) || Float.compare(scale, state.lastResultScale) != 0) {
                entity.setInterpolationDelay(0);
                entity.setInterpolationDuration((int) RESULT_FRAME_TICKS);
                entity.setTransformation(new Transformation(
                        new Vector3f(0.0F, (float) offset.getY(), 0.0F), new Quaternionf(),
                        new Vector3f(scale, scale, scale), new Quaternionf()));
                state.lastTransformOffset = offset;
                state.lastResultScale = scale;
            }
            double opacity = (0.25D + 0.75D * eased) * (1.0D - exit);
            entity.setTextOpacity((byte) Math.max(4, Math.round(255.0D * opacity)));
            entity.setBackgroundColor(Color.fromARGB((int) Math.round(176.0D * opacity), 8, 8, 24));
        }
        state.animationAge++;
    }

    /**
     * 空行で区切った情報単位を保って本文をページ化し、全ページに見出しとページ番号を付けます。
     * @param text 見出し3行と、空行区切りの戦績・報酬本文
     * @return 高さを揃えたページ行。短い本文は空行だけを除去した1ページ
     */
    private @NotNull List<List<String>> resultPages(@NotNull String text) {
        List<String> allLines = text.lines().filter(line -> !line.isBlank()).toList();
        if (allLines.size() <= MAX_RESULT_LINES) return List.of(allLines);
        List<String> header = allLines.subList(0, RESULT_HEADER_LINES);
        String[] rawLines = text.split("\n", -1);
        int bodyStart = 0;
        int headerCount = 0;
        while (bodyStart < rawLines.length && headerCount < RESULT_HEADER_LINES) {
            if (!rawLines[bodyStart].isBlank()) headerCount++;
            bodyStart++;
        }
        int bodyLimit = MAX_RESULT_LINES - RESULT_HEADER_LINES - 1;
        List<List<String>> bodies = new ArrayList<>();
        List<String> body = new ArrayList<>();
        String remaining = String.join("\n", List.of(rawLines).subList(bodyStart, rawLines.length));
        for (String block : remaining.split("\n\s*\n")) {
            List<String> blockLines = block.lines().filter(line -> !line.isBlank()).toList();
            if (!body.isEmpty() && body.size() + blockLines.size() > bodyLimit) {
                bodies.add(List.copyOf(body));
                body.clear();
            }
            for (String line : blockLines) {
                if (body.size() == bodyLimit) {
                    bodies.add(List.copyOf(body));
                    body.clear();
                }
                body.add(line);
            }
        }
        if (!body.isEmpty()) bodies.add(List.copyOf(body));
        List<List<String>> pages = new ArrayList<>();
        for (int index = 0; index < bodies.size(); index++) {
            List<String> page = new ArrayList<>(header);
            page.addAll(bodies.get(index));
            while (page.size() < MAX_RESULT_LINES - 1) page.add("");
            page.add(PlayerMsgResource.format(PlayerMsgId.P_6562.getId(), index + 1, bodies.size()));
            pages.add(List.copyOf(page));
        }
        return List.copyOf(pages);
    }

    private @Nullable TextDisplay ensureEntity(@NotNull ManagedDisplayState state, @NotNull Location location) {
        TextDisplay entity = state.entity;
        if (entity != null && entity.isValid() && entity.getWorld().equals(location.getWorld())) {
            return entity;
        }

        destroyEntity(state);
        World world = location.getWorld();
        if (world == null) {
            return null;
        }

        state.entity = world.spawn(location, TextDisplay.class, display -> {
            display.setPersistent(state.options.persistent());
            display.setGravity(false);
            display.setInvulnerable(true);
            display.setSilent(true);
            if (state.resultAnimation != null) {
                applyOptions(display, state.options);
                display.setAlignment(TextDisplay.TextAlignment.LEFT);
                display.setVisibleByDefault(state.resultAnimation.viewerId() == null);
                display.setTransformation(new Transformation(
                        new Vector3f(0.0F, -0.24F, 0.0F), new Quaternionf(),
                        new Vector3f(0.55F, 0.55F, 0.55F), new Quaternionf()));
                display.setTextOpacity((byte) 64);
                display.setBackgroundColor(Color.fromARGB(44, 8, 8, 24));
            }
        });
        if (state.resultAnimation != null && state.resultAnimation.viewerId() != null && plugin != null) {
            Player viewer = Bukkit.getPlayer(state.resultAnimation.viewerId());
            if (viewer != null) viewer.showEntity(plugin, state.entity);
        }
        state.lastLocation = location.clone();
        state.lastText = null;
        state.lastTransformOffset = null;
        state.lastResultScale = Float.NaN;
        state.lastAppliedOptions = null;
        return state.entity;
    }

    /**
     * 見た目のオプションが変化した場合だけBukkit実体へ反映します。
     * 本文だけの更新では、アニメーション中の透過度・背景・補間を上書きしません。
     *
     * @param entity TextDisplay 実体
     * @param state 管理状態
     */
    private void applyOptionsIfNeeded(@NotNull TextDisplay entity, @NotNull ManagedDisplayState state) {
        if (state.options.equals(state.lastAppliedOptions)) {
            return;
        }
        if (state.lastAppliedOptions != null
                && state.options.withText(state.lastAppliedOptions.text()).equals(state.lastAppliedOptions)) {
            state.lastAppliedOptions = state.options;
            return;
        }
        applyOptions(entity, state.options);
        state.lastAppliedOptions = state.options;
    }

    private void refreshDynamicText(@NotNull ManagedDisplayState state) {
        Supplier<String> supplier = state.textSupplier;
        if (supplier == null) {
            return;
        }

        String text = supplier.get();
        if (Objects.equals(state.options.text(), text)) {
            return;
        }

        state.options = state.options.withText(text);
        if (!state.frames.isEmpty()) {
            List<DisplayAnimationFrame> updatedFrames = new ArrayList<>(state.frames.size());
            for (DisplayAnimationFrame frame : state.frames) {
                updatedFrames.add(new DisplayAnimationFrame(text, frame.offset(), frame.durationTicks()));
            }
            state.frames = List.copyOf(updatedFrames);
        }
    }

    private void applyOptions(@NotNull TextDisplay entity, @NotNull DisplayTextOptions options) {
        entity.setBillboard(options.billboard());
        entity.setLineWidth(options.lineWidth());
        entity.setViewRange(options.viewRange());
        entity.setTextOpacity(options.textOpacity());
        entity.setSeeThrough(options.seeThrough());
        entity.setShadowed(options.shadowed());
        entity.setDefaultBackground(options.defaultBackground());
        entity.setInterpolationDuration(options.interpolationDuration());
        entity.setTeleportDuration(options.teleportDuration());

        if (options.backgroundColor() != null) {
            entity.setBackgroundColor(options.backgroundColor());
        } else if (!options.defaultBackground()) {
            entity.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
        }

        if (options.brightnessBlock() != null && options.brightnessSky() != null) {
            entity.setBrightness(new Display.Brightness(options.brightnessBlock(), options.brightnessSky()));
        } else {
            entity.setBrightness(null);
        }
    }

    private void applyFrame(
            @NotNull TextDisplay entity,
            @NotNull ManagedDisplayState state,
            @NotNull DisplayAnimationFrame frame
    ) {
        String appliedText = ColorCodeUtil.translateAlternateColorCodes(frame.text());
        if (!Objects.equals(state.lastText, appliedText)) {
            entity.text(LegacyComponentSerializer.legacySection().deserialize(appliedText));
            state.lastText = appliedText;
        }
    }

    private void teleportIfNeeded(
            @NotNull TextDisplay entity,
            @NotNull ManagedDisplayState state,
            @NotNull Location targetLocation
    ) {
        if (state.lastLocation == null || !isSameLocation(state.lastLocation, targetLocation)) {
            entity.teleport(targetLocation);
            state.lastLocation = targetLocation.clone();
        }
    }

    private @NotNull Vector resolveAttachedOffset(
            @NotNull ManagedDisplayState state,
            @NotNull DisplayAnimationFrame frame
    ) {
        return state.anchor.attachmentOffset()
                .add(state.options.offset())
                .add(frame.offset());
    }

    private void attachIfNeeded(
            @NotNull TextDisplay entity,
            @NotNull ManagedDisplayState state,
            @NotNull Entity attachment,
            @NotNull Vector offset
    ) {
        Entity currentVehicle = entity.getVehicle();
        if (currentVehicle != null && !currentVehicle.equals(attachment)) {
            entity.leaveVehicle();
        }
        if (!attachment.getPassengers().contains(entity)) {
            attachment.addPassenger(entity);
        }
        applyAttachmentTransform(entity, state, offset);
        state.lastLocation = null;
    }

    private void detachIfNeeded(@NotNull TextDisplay entity) {
        if (entity.isInsideVehicle()) {
            entity.leaveVehicle();
        }
    }

    private void applyAttachmentTransform(
            @NotNull TextDisplay entity,
            @NotNull ManagedDisplayState state,
            @NotNull Vector offset
    ) {
        applyTranslationTransform(entity, state, offset, state.options.interpolationDuration());
    }

    /**
     * ローカル平行移動をクライアント補間付きで反映します。
     *
     * @param entity TextDisplay 実体
     * @param state 管理状態
     * @param offset ローカル移動量
     * @param durationTicks 補間時間
     */
    private void applyTranslationTransform(
            @NotNull TextDisplay entity,
            @NotNull ManagedDisplayState state,
            @NotNull Vector offset,
            long durationTicks
    ) {
        if (state.lastTransformOffset != null && state.lastTransformOffset.equals(offset)) {
            return;
        }
        entity.setInterpolationDelay(0);
        entity.setInterpolationDuration((int) Math.max(0L, Math.min(59L, durationTicks)));
        entity.setTransformation(new Transformation(
                new Vector3f((float) offset.getX(), (float) offset.getY(), (float) offset.getZ()),
                new Quaternionf(),
                new Vector3f(1.0F, 1.0F, 1.0F),
                new Quaternionf()
        ));
        state.lastTransformOffset = offset.clone();
    }

    private @NotNull DisplayAnimationFrame resolveFrame(@NotNull ManagedDisplayState state) {
        if (state.frames.isEmpty()) {
            return new DisplayAnimationFrame(state.options.text(), new Vector(), Long.MAX_VALUE);
        }

        long totalDuration = totalAnimationDuration(state.frames);
        long frameTick = state.loopAnimation && totalDuration > 0L
                ? state.animationAge % totalDuration
                : Math.min(state.animationAge, Math.max(0L, totalDuration - 1L));

        long cursor = 0L;
        for (DisplayAnimationFrame frame : state.frames) {
            cursor += frame.durationTicks();
            if (frameTick < cursor) {
                return frame;
            }
        }

        return state.frames.get(state.frames.size() - 1);
    }

    private long totalAnimationDuration(@NotNull List<DisplayAnimationFrame> frames) {
        long total = 0L;
        for (DisplayAnimationFrame frame : frames) {
            total += frame.durationTicks();
        }
        return total;
    }

    /**
     * 管理中の TextDisplay を削除し、表示状態を初期化します。
     *
     * <p>生成直後などに {@code isValid()} が一時的に false を返しても、
     * 実体への参照を失う前に削除を試みます。</p>
     *
     * @param state 削除対象の表示状態
     */
    private void destroyEntity(@NotNull ManagedDisplayState state) {
        Entity entity = state.entity;
        if (entity != null) {
            entity.remove();
        }
        state.entity = null;
        state.lastLocation = null;
        state.lastText = null;
        state.lastTransformOffset = null;
        state.lastResultScale = Float.NaN;
        state.lastAppliedOptions = null;
    }

    private boolean isSameLocation(@NotNull Location current, @NotNull Location target) {
        if (!Objects.equals(current.getWorld(), target.getWorld())) {
            return false;
        }
        return Math.abs(current.getX() - target.getX()) < 1.0E-4D
                && Math.abs(current.getY() - target.getY()) < 1.0E-4D
                && Math.abs(current.getZ() - target.getZ()) < 1.0E-4D
                && Math.abs(current.getYaw() - target.getYaw()) < 0.01F
                && Math.abs(current.getPitch() - target.getPitch()) < 0.01F;
    }

    /**
     * 生成済み TextDisplay を操作する管理ハンドルです。
     */
    public final class ManagedTextDisplay {
        private final UUID id;

        private ManagedTextDisplay(@NotNull UUID id) {
            this.id = id;
        }

        /**
         * 表示文字列を差し替えます。
         *
         * @param text 新しい文字列
         */
        public void setText(@NotNull String text) {
            ManagedDisplayState state = requireState();
            state.options = state.options.withText(text);
            state.frames = List.of();
            state.animationAge = 0L;
            state.destroyAfterAnimation = false;
            state.textSupplier = null;
            state.transformAnimation = false;
        }

        /**
         * 表示文字列を毎 tick 再計算します。
         *
         * @param textSupplier 新しい表示文字列を返す supplier
         */
        public void setDynamicText(@NotNull Supplier<String> textSupplier) {
            ManagedDisplayState state = requireState();
            state.textSupplier = textSupplier;
        }

        /**
         * 表示基準位置を差し替えます。
         *
         * @param anchor 新しいアンカー
         */
        public void setAnchor(@NotNull DisplayAnchor anchor) {
            ManagedDisplayState state = requireState();
            state.anchor = anchor;
        }

        /**
         * 表示オプションを差し替えます。
         *
         * @param options 新しいオプション
         */
        public void setOptions(@NotNull DisplayTextOptions options) {
            ManagedDisplayState state = requireState();
            state.options = options;
        }

        /**
         * アニメーションを再生します。
         *
         * @param frames                 フレーム列
         * @param loopAnimation          ループ再生するか
         * @param destroyAfterAnimation  再生完了後に破棄するか
         */
        public void playAnimation(
                @NotNull List<DisplayAnimationFrame> frames,
                boolean loopAnimation,
                boolean destroyAfterAnimation
        ) {
            ManagedDisplayState state = requireState();
            state.frames = List.copyOf(frames);
            state.animationAge = 0L;
            state.loopAnimation = loopAnimation;
            state.destroyAfterAnimation = destroyAfterAnimation;
            state.transformAnimation = false;
        }

        /**
         * 座標 teleport を使わず、TextDisplay のローカル変換をクライアント補間して再生します。
         *
         * @param frames フレーム列
         * @param loopAnimation ループ再生するか
         * @param destroyAfterAnimation 再生完了後に破棄するか
         */
        public void playTransformAnimation(
                @NotNull List<DisplayAnimationFrame> frames,
                boolean loopAnimation,
                boolean destroyAfterAnimation
        ) {
            ManagedDisplayState state = requireState();
            state.frames = List.copyOf(frames);
            state.animationAge = 0L;
            state.loopAnimation = loopAnimation;
            state.destroyAfterAnimation = destroyAfterAnimation;
            state.transformAnimation = true;
        }

        /**
         * 表示を破棄します。
         */
        public void destroy() {
            ManagedDisplayState state = displays.remove(id);
            if (state == null) {
                return;
            }
            state.destroyed = true;
            destroyEntity(state);
        }

        private @NotNull ManagedDisplayState requireState() {
            ManagedDisplayState state = displays.get(id);
            if (state == null || state.destroyed) {
                throw new IllegalStateException("Display has already been destroyed.");
            }
            return state;
        }
    }

    private static final class ManagedDisplayState {
        private final UUID id = UUID.randomUUID();
        private final boolean damageDisplay;
        private final long sequence;
        private DisplayAnchor anchor;
        private DisplayTextOptions options;
        private List<DisplayAnimationFrame> frames = List.of();
        private boolean loopAnimation;
        private boolean destroyAfterAnimation;
        private boolean transformAnimation;
        private long animationAge;
        private boolean destroyed;
        private @Nullable TextDisplay entity;
        private @Nullable String lastText;
        private @Nullable Location lastLocation;
        private @Nullable Vector lastTransformOffset;
        private @Nullable DisplayTextOptions lastAppliedOptions;
        private @Nullable Supplier<String> textSupplier;
        private @Nullable ResultAnimation resultAnimation;
        private @Nullable String resultFullText;
        private List<List<String>> resultPages = List.of();
        private float lastResultScale = Float.NaN;

        private ManagedDisplayState(
                @NotNull DisplayAnchor anchor,
                @NotNull DisplayTextOptions options,
                boolean damageDisplay,
                long sequence
        ) {
            this.anchor = anchor;
            this.options = options;
            this.damageDisplay = damageDisplay;
            this.sequence = sequence;
        }
    }

    /**
     * @param viewerId 個人表示の閲覧者。共有表示の場合はnull
     * @param durationTicks 表示寿命
     */
    private record ResultAnimation(@Nullable UUID viewerId, long durationTicks) { }
}
