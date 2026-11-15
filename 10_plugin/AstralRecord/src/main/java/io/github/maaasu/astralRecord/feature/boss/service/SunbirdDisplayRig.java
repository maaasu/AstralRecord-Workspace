package io.github.maaasu.astralRecord.feature.boss.service;

import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Clamped, continuous Sunbird poses and parent-relative joint transforms for the Java V2 resource pack. */
final class SunbirdDisplayRig {
    private static final long BLEND_TICKS = 6L;

    private int motion;
    private long changedAt = Long.MIN_VALUE;
    private Angles previous;

    Frame sample(int nextMotion, long tick, int lookAhead) {
        if (changedAt == Long.MIN_VALUE) {
            changedAt = tick;
            motion = nextMotion;
        } else if (nextMotion != motion) {
            previous = angles(tick);
            motion = nextMotion;
            changedAt = tick;
        }
        return new Frame(angles(tick + lookAhead));
    }

    private Angles angles(long tick) {
        Angles target = target(motion, tick, tick - changedAt);
        if (previous == null) return target;
        long blendTicks = motion == 3 ? 2L : BLEND_TICKS;
        float p = smooth((tick - changedAt) / (float) blendTicks);
        return previous.blend(target, p);
    }

    private static Angles target(int motion, long tick, long age) {
        // Phase stays continuous across the 5-tick state updates; wing tips lag the roots.
        double time = tick * 0.05D;
        return switch (motion) {
            case 1 -> flap(time, 0.68D, 10, 39, 25, 0.20F, 7);
            case 2 -> flap(time, 1.24D, 31, 11, 9, 0.26F, -10);
            case 3 -> {
                float sweep = smooth(age / 4.0F);
                // Feathers finish clear of the floor even when the carrier is at ground height.
                yield new Angles(0.24F + 0.18F * sweep, radians(-10 + 17 * sweep),
                    radians(31 - 56 * sweep), radians(10 - 25 * sweep), radians(14 + 12 * sweep));
            }
            default -> flap(time, 1.60D, 8, 19, 14, 0.16F, -2);
        };
    }

    private static Angles flap(double time, double period, int lift, int magnitude, int tip,
                               float height, int pitch) {
        double phase = (time % period) * Math.TAU / period;
        double wave = Math.sin(phase);
        return new Angles(height + (float) wave * 0.085F,
            radians(pitch + wave * 3), radians(lift + magnitude * wave),
            radians(tip * Math.sin(phase - 0.88D)), radians(8 + wave * 5));
    }

    private static float smooth(float value) {
        float x = Math.clamp(value, 0.0F, 1.0F);
        return x * x * (3.0F - 2.0F * x);
    }

    private static float radians(double degrees) {
        return (float) Math.toRadians(degrees);
    }

    /** Each client item uses a FIXED, unit-scale geometry baked about that part's origin. */
    enum Part {
        BODY("sunbird_body_v2", 0, false),
        LEFT_WING("sunbird_wing_left_v2", 1, false),
        RIGHT_WING("sunbird_wing_right_v2", -1, false),
        LEFT_TIP("sunbird_tip_left_v2", 1, true),
        RIGHT_TIP("sunbird_tip_right_v2", -1, true);

        private final String model;
        private final int side;
        private final boolean tip;

        Part(String model, int side, boolean tip) {
            this.model = model;
            this.side = side;
            this.tip = tip;
        }

        String model() {
            return model;
        }
    }

    private record Angles(float elevation, float pitch, float wing, float tip, float sweep) {
        Angles blend(Angles target, float progress) {
            return new Angles(lerp(elevation, target.elevation, progress), lerp(pitch, target.pitch, progress),
                lerp(wing, target.wing, progress), lerp(tip, target.tip, progress),
                lerp(sweep, target.sweep, progress));
        }

        private static float lerp(float a, float b, float progress) {
            return a + (b - a) * progress;
        }
    }

    static final class Frame {
        private final Angles angles;

        private Frame(Angles angles) {
            this.angles = angles;
        }

        Transformation transform(Part part) {
            Quaternionf rotation = new Quaternionf().rotateX(angles.pitch());
            Vector3f translation = new Vector3f(0.0F, angles.elevation(), 0.0F);
            if (part.side != 0) {
                translation.add(rotation.transform(new Vector3f(part.side * 0.4375F, 1.5625F, 0.0625F)));
                rotation.rotateY(part.side * -angles.sweep()).rotateZ(part.side * angles.wing());
                if (part.tip) {
                    translation.add(rotation.transform(new Vector3f(part.side * 1.0625F, -0.0625F, 0.3125F)));
                    rotation.rotateZ(part.side * angles.tip());
                }
            }
            return new Transformation(translation, rotation, new Vector3f(1.0F), new Quaternionf());
        }
    }
}
