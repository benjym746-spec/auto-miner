package com.example.addon;

import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.pathing.PathManagers;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * Digs out a box between two corners. Works top-down, one layer at a time.
 * It re-scans the world instead of predicting what a 3x3 pickaxe breaks, so
 * it works with any hammer/3x3 tool: whatever the tool clears simply stops
 * showing up as "remaining" on the next scan.
 */
public class AutoMiner extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender = settings.createGroup("Render");

    private final Setting<Double> range = sgGeneral.add(new DoubleSetting.Builder()
        .name("range")
        .description("How far away a block can be before the miner walks closer.")
        .defaultValue(4.5)
        .min(1)
        .sliderMax(6)
        .build());

    private final Setting<Integer> hotbarSlot = sgGeneral.add(new IntSetting.Builder()
        .name("hotbar-slot")
        .description("Hotbar slot (1-9) holding your 3x3 pickaxe. 0 = use whatever you are holding.")
        .defaultValue(0)
        .min(0)
        .max(9)
        .sliderMax(9)
        .build());

    private final Setting<Boolean> rotate = sgGeneral.add(new BoolSetting.Builder()
        .name("rotate")
        .description("Look at the block being mined.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> smoothRotation = sgGeneral.add(new BoolSetting.Builder()
        .name("smooth-rotation")
        .description("Turn the camera gradually toward blocks instead of snapping to them.")
        .defaultValue(true)
        .build());

    private final Setting<Double> rotationSpeed = sgGeneral.add(new DoubleSetting.Builder()
        .name("rotation-speed")
        .description("Max degrees the camera turns per tick when smooth-rotation is on.")
        .defaultValue(18)
        .min(2)
        .sliderMax(60)
        .visible(smoothRotation::get)
        .build());

    private final Setting<Boolean> pathing = sgGeneral.add(new BoolSetting.Builder()
        .name("walk-to-blocks")
        .description("Use Meteor's pathing (Baritone) to walk to blocks that are out of range.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> autoDisable = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-disable")
        .description("Turn off when the whole region has been cleared.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> renderRegion = sgRender.add(new BoolSetting.Builder()
        .name("render-region")
        .description("Draw the selected region and corners.")
        .defaultValue(true)
        .build());

    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode")
        .defaultValue(ShapeMode.Both)
        .build());

    private final Setting<SettingColor> sideColor = sgRender.add(new ColorSetting.Builder()
        .name("side-color")
        .defaultValue(new SettingColor(255, 170, 0, 20))
        .build());

    private final Setting<SettingColor> lineColor = sgRender.add(new ColorSetting.Builder()
        .name("line-color")
        .defaultValue(new SettingColor(255, 170, 0, 255))
        .build());

    // Region corners (set with .miner pos1 / .miner pos2).
    public BlockPos pos1, pos2;

    private int minX, minY, minZ, maxX, maxY, maxZ;
    private int layerY;
    private int scanTimer;
    private List<BlockPos> cache = new ArrayList<>();

    public AutoMiner() {
        super(AddonTemplate.CATEGORY, "auto-miner", "Digs out the box between two points with a 3x3 pickaxe.");
    }

    public boolean hasRegion() {
        return pos1 != null && pos2 != null;
    }

    @Override
    public void onActivate() {
        if (!hasRegion()) {
            error("Set both corners first: .miner pos1 and .miner pos2");
            toggle();
            return;
        }

        minX = Math.min(pos1.getX(), pos2.getX());
        minY = Math.min(pos1.getY(), pos2.getY());
        minZ = Math.min(pos1.getZ(), pos2.getZ());
        maxX = Math.max(pos1.getX(), pos2.getX());
        maxY = Math.max(pos1.getY(), pos2.getY());
        maxZ = Math.max(pos1.getZ(), pos2.getZ());

        layerY = maxY;
        scanTimer = 0;
        cache = new ArrayList<>();

        int volume = (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
        info("Mining %d blocks of space (%dx%dx%d).", volume, maxX - minX + 1, maxY - minY + 1, maxZ - minZ + 1);
    }

    @Override
    public void onDeactivate() {
        PathManagers.get().stop();
        cache.clear();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.level == null) return;

        if (hotbarSlot.get() > 0) InvUtils.swap(hotbarSlot.get() - 1, false);

        // Refresh the current layer every few ticks, move down when it is empty.
        if (--scanTimer <= 0 || cache.isEmpty()) {
            cache = scanLayer(layerY);
            scanTimer = 5;
        }
        while (cache.isEmpty()) {
            layerY--;
            if (layerY < minY) {
                finish();
                return;
            }
            cache = scanLayer(layerY);
            scanTimer = 5;
        }

        // Closest remaining block in this layer.
        Vec3 eye = mc.player.getEyePosition();
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (BlockPos p : cache) {
            if (!isMineable(p)) continue;
            double d = eye.distanceToSqr(Vec3.atCenterOf(p));
            if (d < bestDist) {
                bestDist = d;
                best = p;
            }
        }

        if (best == null) {
            scanTimer = 0; // everything cached is gone, rescan next tick
            return;
        }

        if (bestDist <= range.get() * range.get()) {
            if (PathManagers.get().isPathing()) PathManagers.get().stop();
            mine(best);
        } else if (pathing.get() && !PathManagers.get().isPathing()) {
            PathManagers.get().moveTo(best.above(), false);
        }
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (!renderRegion.get()) return;

        if (hasRegion()) {
            int x1 = Math.min(pos1.getX(), pos2.getX());
            int y1 = Math.min(pos1.getY(), pos2.getY());
            int z1 = Math.min(pos1.getZ(), pos2.getZ());
            int x2 = Math.max(pos1.getX(), pos2.getX()) + 1;
            int y2 = Math.max(pos1.getY(), pos2.getY()) + 1;
            int z2 = Math.max(pos1.getZ(), pos2.getZ()) + 1;
            event.renderer.box(x1, y1, z1, x2, y2, z2, sideColor.get(), lineColor.get(), shapeMode.get(), 0);
        } else {
            if (pos1 != null) event.renderer.box(pos1, sideColor.get(), lineColor.get(), shapeMode.get(), 0);
            if (pos2 != null) event.renderer.box(pos2, sideColor.get(), lineColor.get(), shapeMode.get(), 0);
        }
    }

    private void mine(BlockPos pos) {
        if (!rotate.get()) {
            BlockUtils.breakBlock(pos, true);
            return;
        }

        float targetYaw = (float) Rotations.getYaw(pos);
        float targetPitch = (float) Rotations.getPitch(pos);

        if (smoothRotation.get()) {
            float yaw = mc.player.getYRot();
            float pitch = mc.player.getXRot();
            float dYaw = Mth.wrapDegrees(targetYaw - yaw);
            float dPitch = targetPitch - pitch;
            float max = rotationSpeed.get().floatValue();

            mc.player.setYRot(yaw + Mth.clamp(dYaw, -max, max));
            mc.player.setXRot(Mth.clamp(pitch + Mth.clamp(dPitch, -max, max), -90f, 90f));

            // Keep turning until we are roughly on target, then start mining.
            if (Math.abs(dYaw) > 8 || Math.abs(dPitch) > 8) return;
        }

        Rotations.rotate(targetYaw, targetPitch, () -> BlockUtils.breakBlock(pos, true));
    }

    private List<BlockPos> scanLayer(int y) {
        List<BlockPos> list = new ArrayList<>();
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                p.set(x, y, z);
                if (isMineable(p)) list.add(p.immutable());
            }
        }
        return list;
    }

    private boolean isMineable(BlockPos pos) {
        BlockState state = mc.level.getBlockState(pos);
        if (state.isAir()) return false;
        if (state.getBlock() instanceof LiquidBlock) return false;
        return state.getDestroySpeed(mc.level, pos) >= 0; // skips bedrock and other unbreakables
    }

    private void finish() {
        info("Region cleared.");
        if (autoDisable.get()) toggle();
    }
}
