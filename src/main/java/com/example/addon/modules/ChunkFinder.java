package com.example.addon.modules;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.RenderUtils;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;

import java.util.*;

/**
 * Scans loaded chunks for amethyst geodes and for blocks that don't generate
 * naturally in bulk (chests, furnaces, redstone, etc.) and highlights the
 * chunks that cross a sensitivity-controlled threshold.
 */
public class ChunkFinder extends Module {
    public enum DistanceMode { Simulation, Custom }
    public enum RenderMode { Column, Flat }

    private record Result(int activity, int amethyst, boolean geode, boolean flagged, int bottomY, int topY) {}

    private final SettingGroup sgScan = settings.createGroup("Scanning");
    private final SettingGroup sgDetect = settings.createGroup("Detection");
    private final SettingGroup sgRender = settings.createGroup("Render");

    // ---- Scanning ----
    private final Setting<Integer> sensitivity = sgScan.add(new IntSetting.Builder()
        .name("sensitivity")
        .description("1 = only obvious bases/geodes, 10 = flag chunks with a single hint.")
        .defaultValue(5).range(1, 10).sliderRange(1, 10)
        .build());

    private final Setting<DistanceMode> distanceMode = sgScan.add(new EnumSetting.Builder<DistanceMode>()
        .name("distance-mode")
        .description("Simulation uses your game's simulation distance; Custom uses the value below.")
        .defaultValue(DistanceMode.Simulation)
        .build());

    private final Setting<Integer> customDistance = sgScan.add(new IntSetting.Builder()
        .name("custom-distance")
        .description("Scan radius in chunks.")
        .defaultValue(8).range(2, 32).sliderRange(2, 32)
        .visible(() -> distanceMode.get() == DistanceMode.Custom)
        .build());

    private final Setting<Integer> chunksPerTick = sgScan.add(new IntSetting.Builder()
        .name("chunks-per-tick")
        .description("Scan speed. Lower it if you notice FPS drops.")
        .defaultValue(2).range(1, 16).sliderRange(1, 16)
        .build());

    private final Setting<Integer> rescanInterval = sgScan.add(new IntSetting.Builder()
        .name("rescan-interval")
        .description("Ticks to wait after a full pass before scanning again.")
        .defaultValue(200).range(20, 1200).sliderRange(20, 1200)
        .build());

    // ---- Detection ----
    private final Setting<Boolean> geodes = sgDetect.add(new BoolSetting.Builder()
        .name("amethyst-geodes")
        .description("Flag chunks containing an amethyst geode.")
        .defaultValue(true)
        .build());

    private final Setting<List<Block>> indicatorBlocks = sgDetect.add(new BlockListSetting.Builder()
        .name("activity-blocks")
        .description("Blocks that hint at player activity. Each one found adds 1 to the chunk score.")
        .defaultValue(
            Blocks.CHEST, Blocks.TRAPPED_CHEST, Blocks.ENDER_CHEST, Blocks.BARREL,
            Blocks.FURNACE, Blocks.BLAST_FURNACE, Blocks.SMOKER,
            Blocks.CRAFTING_TABLE, Blocks.ENCHANTING_TABLE, Blocks.ANVIL, Blocks.BEACON,
            Blocks.HOPPER, Blocks.DISPENSER, Blocks.DROPPER, Blocks.OBSERVER,
            Blocks.PISTON, Blocks.STICKY_PISTON, Blocks.REPEATER, Blocks.COMPARATOR,
            Blocks.RED_BED, Blocks.WHITE_BED, Blocks.BLACK_BED,
            Blocks.SHULKER_BOX, Blocks.GLASS, Blocks.GLASS_PANE,
            Blocks.OAK_PLANKS, Blocks.SPRUCE_PLANKS, Blocks.COBBLESTONE_STAIRS, Blocks.STONE_BRICKS,
            Blocks.TORCH, Blocks.LANTERN, Blocks.FARMLAND
        )
        .build());

    private final Setting<Boolean> notify = sgDetect.add(new BoolSetting.Builder()
        .name("notify")
        .description("Send a chat message the first time a chunk is flagged.")
        .defaultValue(true)
        .build());

    // ---- Render ----
    private final Setting<RenderMode> renderMode = sgRender.add(new EnumSetting.Builder<RenderMode>()
        .name("render-mode")
        .description("Column = full-height chunk box, Flat = a plane on the chunk grid.")
        .defaultValue(RenderMode.Column)
        .build());

    private final Setting<Integer> planeY = sgRender.add(new IntSetting.Builder()
        .name("plane-y")
        .description("Y level of the flat plane.")
        .defaultValue(64).range(-64, 320).sliderRange(-64, 320)
        .visible(() -> renderMode.get() == RenderMode.Flat)
        .build());

    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode")
        .defaultValue(ShapeMode.Both)
        .build());

    private final Setting<Integer> sideAlpha = sgRender.add(new IntSetting.Builder()
        .name("fill-opacity")
        .description("Opacity of the box fill. Lines are always solid so every chunk lines up the same way.")
        .defaultValue(40).range(0, 255).sliderRange(0, 255)
        .build());

    private final Setting<SettingColor> geodeColor = sgRender.add(new ColorSetting.Builder()
        .name("geode-color")
        .defaultValue(new SettingColor(170, 90, 255, 255))
        .build());

    private final Setting<SettingColor> lowColor = sgRender.add(new ColorSetting.Builder()
        .name("low-activity-color")
        .defaultValue(new SettingColor(255, 220, 0, 255))
        .build());

    private final Setting<SettingColor> highColor = sgRender.add(new ColorSetting.Builder()
        .name("high-activity-color")
        .defaultValue(new SettingColor(255, 40, 40, 255))
        .build());

    private final Setting<Boolean> tracers = sgRender.add(new BoolSetting.Builder()
        .name("tracers")
        .description("Draw a line from your crosshair to each flagged chunk.")
        .defaultValue(false)
        .build());

    // ---- State ----
    private final Map<ChunkPos, Result> results = new HashMap<>();
    private final ArrayDeque<ChunkPos> queue = new ArrayDeque<>();
    private Set<Block> indicators = new HashSet<>();
    private int ticksSincePass = 0;

    public ChunkFinder() {
        super(AddonTemplate.CATEGORY, "chunk-finder",
            "Scans nearby chunks for amethyst geodes and signs of player activity.");
    }

    @Override
    public void onDeactivate() {
        results.clear();
        queue.clear();
        ticksSincePass = 0;
    }

    @Override
    public String getInfoString() {
        long n = results.values().stream().filter(Result::flagged).count();
        return n == 0 ? null : String.valueOf(n);
    }

    // ---------------------------------------------------------------- scanning

    private int scanRadius() {
        int radius = distanceMode.get() == DistanceMode.Simulation
            ? mc.options.getSimulationDistance().getValue()
            : customDistance.get();
        return Math.max(1, radius);
    }

    private int threshold() {
        // sensitivity 10 -> 2 points, 5 -> 5 points, 1 -> 24 points
        return Math.max(1, Math.round(24f / sensitivity.get()));
    }

    private int geodeBlockThreshold() {
        // sensitivity 10 -> 4 amethyst blocks, 5 -> 24, 1 -> 40
        return 4 * (11 - sensitivity.get());
    }

    private void rebuildQueue() {
        int radius = scanRadius();
        ChunkPos me = mc.player.getChunkPos();
        indicators = new HashSet<>(indicatorBlocks.get());

        List<ChunkPos> inRange = new ArrayList<>();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx * dx + dz * dz > radius * radius) continue; // circular area
                inRange.add(new ChunkPos(me.x + dx, me.z + dz));
            }
        }
        inRange.sort(Comparator.comparingInt(p -> (p.x - me.x) * (p.x - me.x) + (p.z - me.z) * (p.z - me.z)));

        Set<ChunkPos> keep = new HashSet<>(inRange);
        results.keySet().removeIf(p -> !keep.contains(p));

        queue.clear();
        queue.addAll(inRange);
    }

    private Result scan(WorldChunk chunk) {
        ChunkSection[] sections = chunk.getSectionArray();
        int bottomY = chunk.getBottomY();
        int topY = bottomY + sections.length * 16;

        int activity = 0;
        int amethyst = 0;
        boolean checkGeodes = geodes.get();

        for (ChunkSection section : sections) {
            if (section == null || section.isEmpty()) continue;

            boolean hasIndicator = section.hasAny(s -> indicators.contains(s.getBlock()));
            boolean hasAmethyst = checkGeodes
                && section.hasAny(s -> s.isOf(Blocks.AMETHYST_BLOCK) || s.isOf(Blocks.BUDDING_AMETHYST));
            if (!hasIndicator && !hasAmethyst) continue; // fast skip

            for (int x = 0; x < 16; x++) {
                for (int y = 0; y < 16; y++) {
                    for (int z = 0; z < 16; z++) {
                        BlockState state = section.getBlockState(x, y, z);
                        if (state.isAir()) continue;
                        Block block = state.getBlock();
                        if (block == Blocks.AMETHYST_BLOCK || block == Blocks.BUDDING_AMETHYST) amethyst++;
                        if (indicators.contains(block)) activity++;
                    }
                }
            }
        }

        boolean geode = checkGeodes && amethyst >= geodeBlockThreshold();
        boolean flagged = geode || activity >= threshold();
        return new Result(activity, amethyst, geode, flagged, bottomY, topY);
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.world == null || mc.player == null) return;

        ticksSincePass++;
        if (queue.isEmpty() && ticksSincePass >= rescanInterval.get()) {
            rebuildQueue();
            ticksSincePass = 0;
        }

        for (int i = 0; i < chunksPerTick.get() && !queue.isEmpty(); i++) {
            ChunkPos pos = queue.poll();
            WorldChunk chunk = mc.world.getChunkManager().getWorldChunk(pos.x, pos.z);
            if (chunk == null) {
                results.remove(pos);
                continue;
            }

            Result result = scan(chunk);
            Result previous = results.put(pos, result);

            if (notify.get() && result.flagged() && (previous == null || !previous.flagged())) {
                String what = result.geode() ? "Amethyst geode" : "Activity (score " + result.activity() + ")";
                info("%s at chunk (highlight) [%d, %d] ~ block (%d, %d)",
                    what, pos.x, pos.z, pos.getStartX(), pos.getStartZ());
            }
        }
    }

    // --------------------------------------------------------------- rendering

    private Color gradient(int activity) {
        // 0 at the threshold, 1 at 3x the threshold
        int t = threshold();
        double f = Math.min(1.0, Math.max(0.0, (activity - t) / (double) (t * 2)));
        Color a = lowColor.get();
        Color b = highColor.get();
        return new Color(
            (int) (a.r + (b.r - a.r) * f),
            (int) (a.g + (b.g - a.g) * f),
            (int) (a.b + (b.b - a.b) * f),
            255
        );
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        int fill = sideAlpha.get();

        for (Map.Entry<ChunkPos, Result> entry : results.entrySet()) {
            Result r = entry.getValue();
            if (!r.flagged()) continue;

            ChunkPos pos = entry.getKey();
            Color base = r.geode() ? geodeColor.get() : gradient(r.activity());
            Color line = new Color(base.r, base.g, base.b, 255);
            Color side = new Color(base.r, base.g, base.b, fill);

            // Always snapped to the exact chunk grid (16x16 from the chunk's corner)
            double x1 = pos.getStartX();
            double z1 = pos.getStartZ();
            double x2 = x1 + 16;
            double z2 = z1 + 16;

            double y1, y2;
            if (renderMode.get() == RenderMode.Column) {
                y1 = r.bottomY();
                y2 = r.topY();
            } else {
                y1 = planeY.get();
                y2 = y1 + 0.01;
            }

            event.renderer.box(x1, y1, z1, x2, y2, z2, side, line, shapeMode.get(), 0);

            if (tracers.get()) {
                event.renderer.line(
                    RenderUtils.center.x, RenderUtils.center.y, RenderUtils.center.z,
                    x1 + 8, (y1 + y2) / 2.0, z1 + 8,
                    line
                );
            }
        }
    }
}
