package io.github.maaasu.astralRecord.shared.display;

import io.github.maaasu.astralRecord.infrastructure.util.ColorCodeUtil;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
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
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/**
 * 実体 TextDisplay を用いて、固定表示・追従表示・アニメーション表示を統一管理するサービスです。
 */
public final class DisplayTextService {

    private static final long UPDATE_INTERVAL_TICKS = 1L;

    private final ConcurrentHashMap<UUID, ManagedDisplayState> displays = new ConcurrentHashMap<>();
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
        ManagedDisplayState state = new ManagedDisplayState(anchor, options);
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
        String prefix = critical ? "&e✦ " : "&c";
        String text = prefix + String.format(java.util.Locale.ROOT, "%.0f", Math.max(0.0D, amount));
        ManagedTextDisplay display = create(DisplayAnchor.fixed(origin), DisplayTextOptions.damage(text));

        double xDrift = ThreadLocalRandom.current().nextDouble(-0.18D, 0.18D);
        double zDrift = ThreadLocalRandom.current().nextDouble(-0.18D, 0.18D);
        List<DisplayAnimationFrame> frames = new ArrayList<>();
        for (int index = 0; index < 10; index++) {
            double yOffset = 0.2D + (index * 0.12D);
            frames.add(new DisplayAnimationFrame(text, new Vector(xDrift, yOffset, zDrift), 2L));
        }
        display.playAnimation(frames, false, true);
        return display;
    }

    /**
     * Mob 討伐時のリザルト表示を一定時間ワールド上へ表示します。
     *
     * @param origin 表示基準座標
     * @param text   表示するリザルト本文
     * @return 管理ハンドル
     */
    public @NotNull ManagedTextDisplay spawnResultText(@NotNull Location origin, @NotNull String text) {
        ManagedTextDisplay display = create(
                DisplayAnchor.fixed(origin),
                DisplayTextOptions.defaults(text)
                        .withSeeThrough(true)
                        .withShadowed(true)
                        .withLineWidth(260)
                        .withViewRange(48.0F)
                        .withInterpolationDuration(2)
                        .withTeleportDuration(2)
        );

        List<DisplayAnimationFrame> frames = new ArrayList<>();
        for (int index = 0; index < 40; index++) {
            frames.add(new DisplayAnimationFrame(text, new Vector(0.0D, index * 0.01D, 0.0D), 2L));
        }
        display.playAnimation(frames, false, true);
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
        DisplayAnimationFrame frame = resolveFrame(state);
        Entity attachment = state.anchor.attachment();
        Vector attachedOffset = resolveAttachedOffset(state, frame);
        Location targetLocation = attachment != null
                ? attachment.getLocation()
                : anchorLocation.clone().add(state.options.offset()).add(frame.offset());
        TextDisplay entity = ensureEntity(state, targetLocation);
        if (entity == null) {
            return;
        }

        applyOptions(entity, state.options);
        applyFrame(entity, state, frame);
        if (attachment != null) {
            attachIfNeeded(entity, state, attachment, attachedOffset);
        } else {
            detachIfNeeded(entity, state);
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
        });
        state.lastLocation = null;
        state.lastText = null;
        state.lastAttachmentOffset = null;
        return state.entity;
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

    private void detachIfNeeded(@NotNull TextDisplay entity, @NotNull ManagedDisplayState state) {
        if (entity.isInsideVehicle()) {
            entity.leaveVehicle();
        }
        applyAttachmentTransform(entity, state, new Vector());
    }

    private void applyAttachmentTransform(
            @NotNull TextDisplay entity,
            @NotNull ManagedDisplayState state,
            @NotNull Vector offset
    ) {
        if (state.lastAttachmentOffset != null && state.lastAttachmentOffset.equals(offset)) {
            return;
        }
        entity.setTransformation(new Transformation(
                new Vector3f((float) offset.getX(), (float) offset.getY(), (float) offset.getZ()),
                new Quaternionf(),
                new Vector3f(1.0F, 1.0F, 1.0F),
                new Quaternionf()
        ));
        state.lastAttachmentOffset = offset.clone();
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

    private void destroyEntity(@NotNull ManagedDisplayState state) {
        Entity entity = state.entity;
        if (entity != null && entity.isValid()) {
            entity.remove();
        }
        state.entity = null;
        state.lastLocation = null;
        state.lastText = null;
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
        private DisplayAnchor anchor;
        private DisplayTextOptions options;
        private List<DisplayAnimationFrame> frames = List.of();
        private boolean loopAnimation;
        private boolean destroyAfterAnimation;
        private long animationAge;
        private boolean destroyed;
        private @Nullable TextDisplay entity;
        private @Nullable String lastText;
        private @Nullable Location lastLocation;
        private @Nullable Vector lastAttachmentOffset;
        private @Nullable Supplier<String> textSupplier;

        private ManagedDisplayState(@NotNull DisplayAnchor anchor, @NotNull DisplayTextOptions options) {
            this.anchor = anchor;
            this.options = options;
        }
    }
}
