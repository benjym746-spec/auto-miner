package com.example.addon;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import meteordevelopment.meteorclient.commands.Command;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.client.multiplayer.ClientSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * .miner pos1 [x y z]   set corner 1 (looked-at block, or your feet)
 * .miner pos2 [x y z]   set corner 2
 * .miner start | stop   run / stop the miner
 * .miner clear          forget both corners
 * .miner info           show the current region
 */
public class MinerCommand extends Command {
    public MinerCommand() {
        super("miner", "Set the Auto Miner region and start or stop it.");
    }

    @Override
    public void build(LiteralArgumentBuilder<ClientSuggestionProvider> builder) {
        builder.then(literal("pos1")
            .executes(ctx -> {
                setCorner(1, targetedOrFeet());
                return SINGLE_SUCCESS;
            })
            .then(argument("x", IntegerArgumentType.integer())
                .then(argument("y", IntegerArgumentType.integer())
                    .then(argument("z", IntegerArgumentType.integer())
                        .executes(ctx -> {
                            setCorner(1, new BlockPos(
                                IntegerArgumentType.getInteger(ctx, "x"),
                                IntegerArgumentType.getInteger(ctx, "y"),
                                IntegerArgumentType.getInteger(ctx, "z")));
                            return SINGLE_SUCCESS;
                        })))));

        builder.then(literal("pos2")
            .executes(ctx -> {
                setCorner(2, targetedOrFeet());
                return SINGLE_SUCCESS;
            })
            .then(argument("x", IntegerArgumentType.integer())
                .then(argument("y", IntegerArgumentType.integer())
                    .then(argument("z", IntegerArgumentType.integer())
                        .executes(ctx -> {
                            setCorner(2, new BlockPos(
                                IntegerArgumentType.getInteger(ctx, "x"),
                                IntegerArgumentType.getInteger(ctx, "y"),
                                IntegerArgumentType.getInteger(ctx, "z")));
                            return SINGLE_SUCCESS;
                        })))));

        builder.then(literal("start").executes(ctx -> {
            AutoMiner miner = miner();
            if (!miner.hasRegion()) {
                error("Set both corners first (.miner pos1 / .miner pos2).");
            } else if (!miner.isActive()) {
                miner.toggle();
            } else {
                info("Already running.");
            }
            return SINGLE_SUCCESS;
        }));

        builder.then(literal("stop").executes(ctx -> {
            AutoMiner miner = miner();
            if (miner.isActive()) miner.toggle();
            info("Stopped.");
            return SINGLE_SUCCESS;
        }));

        builder.then(literal("clear").executes(ctx -> {
            AutoMiner miner = miner();
            if (miner.isActive()) miner.toggle();
            miner.pos1 = null;
            miner.pos2 = null;
            info("Region cleared.");
            return SINGLE_SUCCESS;
        }));

        builder.then(literal("info").executes(ctx -> {
            AutoMiner miner = miner();
            info("pos1: %s", miner.pos1 == null ? "not set" : miner.pos1.toShortString());
            info("pos2: %s", miner.pos2 == null ? "not set" : miner.pos2.toShortString());
            info("running: %s", miner.isActive());
            return SINGLE_SUCCESS;
        }));
    }

    private AutoMiner miner() {
        return Modules.get().get(AutoMiner.class);
    }

    private BlockPos targetedOrFeet() {
        if (mc.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK) {
            return hit.getBlockPos();
        }
        return mc.player.blockPosition();
    }

    private void setCorner(int which, BlockPos pos) {
        AutoMiner miner = miner();
        if (which == 1) miner.pos1 = pos;
        else miner.pos2 = pos;
        info("Corner %d set to %s.", which, pos.toShortString());
    }
}
