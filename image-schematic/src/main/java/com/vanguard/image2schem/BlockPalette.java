package com.vanguard.image2schem;

import java.util.List;

public final class BlockPalette {
    public record Entry(String block, int r, int g, int b) {}

    public static final List<Entry> DEFAULT = List.of(
            new Entry("minecraft:white_concrete", 207, 213, 214),
            new Entry("minecraft:light_gray_concrete", 125, 125, 115),
            new Entry("minecraft:gray_concrete", 55, 58, 62),
            new Entry("minecraft:black_concrete", 8, 10, 15),
            new Entry("minecraft:red_concrete", 142, 33, 33),
            new Entry("minecraft:orange_concrete", 224, 97, 0),
            new Entry("minecraft:yellow_concrete", 241, 175, 21),
            new Entry("minecraft:lime_concrete", 94, 169, 24),
            new Entry("minecraft:green_concrete", 73, 91, 36),
            new Entry("minecraft:cyan_concrete", 21, 119, 136),
            new Entry("minecraft:light_blue_concrete", 36, 137, 199),
            new Entry("minecraft:blue_concrete", 44, 46, 143),
            new Entry("minecraft:purple_concrete", 100, 31, 156),
            new Entry("minecraft:magenta_concrete", 169, 48, 159),
            new Entry("minecraft:pink_concrete", 214, 101, 143),
            new Entry("minecraft:brown_concrete", 96, 60, 32),
            new Entry("minecraft:smooth_stone", 158, 158, 158),
            new Entry("minecraft:stone", 125, 125, 125),
            new Entry("minecraft:deepslate", 80, 80, 83),
            new Entry("minecraft:quartz_block", 235, 229, 222),
            new Entry("minecraft:oak_planks", 162, 130, 79),
            new Entry("minecraft:spruce_planks", 114, 84, 48),
            new Entry("minecraft:dark_oak_planks", 67, 43, 20),
            new Entry("minecraft:glass", 190, 220, 220)
    );

    private BlockPalette() {}

    public static Entry nearest(int r, int g, int b) {
        Entry best = DEFAULT.getFirst();
        long bestDist = Long.MAX_VALUE;
        for (Entry e : DEFAULT) {
            long dr = r - e.r();
            long dg = g - e.g();
            long db = b - e.b();
            long d = dr * dr * 3L + dg * dg * 4L + db * db * 2L;
            if (d < bestDist) {
                bestDist = d;
                best = e;
            }
        }
        return best;
    }
}
