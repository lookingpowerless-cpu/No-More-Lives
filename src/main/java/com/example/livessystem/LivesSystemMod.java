package com.example.livessystem;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class LivesSystemMod implements ModInitializer {

    private static final int START_LIVES = 10;
    private static final int DIAMONDS_PER_LIFE = 10;

    private static final Map<UUID, Integer> lives = new HashMap<>();
    private static final Map<UUID, Integer> deathCount = new HashMap<>();
    private static final Map<UUID, Boolean> oldPlayer = new HashMap<>();

    @Override
    public void onInitialize() {

        /* ===== PLAYER JOIN ===== */
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayerEntity player = handler.player;
            UUID id = player.getUuid();

            lives.putIfAbsent(id, 0);
            deathCount.putIfAbsent(id, 0);
            oldPlayer.putIfAbsent(id, false);

            if (!oldPlayer.get(id) && lives.get(id) == 0) {
                lives.put(id, START_LIVES);
                oldPlayer.put(id, true);
                player.sendMessage(Text.of("§aYou received " + START_LIVES + " lives."), false);
            }

            if (oldPlayer.get(id) && lives.get(id) <= 0) {
                banPlayer(player, "Out of lives");
            }
        });

        /* ===== PLAYER DEATH ===== */
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (entity instanceof ServerPlayerEntity player) {
                UUID id = player.getUuid();

                deathCount.put(id, deathCount.getOrDefault(id, 0) + 1);

                if (deathCount.get(id) > 0) {
                    int newLives = lives.getOrDefault(id, 0) - 1;
                    lives.put(id, newLives);
                    deathCount.put(id, 0);

                    if (newLives <= 0 && oldPlayer.getOrDefault(id, false)) {
                        banPlayer(player, "Out of lives");
                    }
                }
            }
        });

        /* ===== COMMANDS ===== */
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, env) -> {

            /* /withdraw <amount> lives to <player> */
            dispatcher.register(
                CommandManager.literal("withdraw")
                    .then(CommandManager.argument("amount", IntegerArgumentType.integer(1))
                        .then(CommandManager.literal("lives")
                            .then(CommandManager.literal("to")
                                .then(CommandManager.argument("player", EntityArgumentType.player())
                                    .executes(ctx -> {
                                        ServerPlayerEntity sender = ctx.getSource().getPlayer();
                                        ServerPlayerEntity target = EntityArgumentType.getPlayer(ctx, "player");
                                        int amount = IntegerArgumentType.getInteger(ctx, "amount");

                                        UUID s = sender.getUuid();
                                        UUID t = target.getUuid();

                                        int senderLives = lives.getOrDefault(s, 0);

                                        if (sender == target || senderLives < amount) {
                                            sender.sendMessage(Text.of("§cLives not enough"), false);
                                            return 0;
                                        }

                                        lives.put(s, senderLives - amount);
                                        lives.put(t, lives.getOrDefault(t, 0) + amount);

                                        if (senderLives - amount <= 0 && oldPlayer.getOrDefault(s, false)) {
                                            banPlayer(sender, "Out of lives");
                                        }
                                        return 1;
                                    }))))));

            /* /craft <amount> lives */
            dispatcher.register(
                CommandManager.literal("craft")
                    .then(CommandManager.argument("amount", IntegerArgumentType.integer(1))
                        .then(CommandManager.literal("lives")
                            .executes(ctx -> {
                                ServerPlayerEntity player = ctx.getSource().getPlayer();
                                int amount = IntegerArgumentType.getInteger(ctx, "amount");

                                int diamondsNeeded = amount * DIAMONDS_PER_LIFE;

                                if (player.getInventory().count(net.minecraft.item.Items.DIAMOND) < diamondsNeeded) {
                                    player.sendMessage(Text.of("§cNot enough resources"), false);
                                    return 0;
                                }

                                player.getInventory().remove(
                                        stack -> stack.getItem() == net.minecraft.item.Items.DIAMOND,
                                        diamondsNeeded,
                                        player.playerScreenHandler.getCraftingInput()
                                );

                                UUID id = player.getUuid();
                                lives.put(id, lives.getOrDefault(id, 0) + amount);
                                player.sendMessage(Text.of("§aCrafted " + amount + " lives"), false);
                                return 1;
                            }))));

            /* /give <amount> lives to <player> (ADMIN) */
            dispatcher.register(
                CommandManager.literal("give")
                    .requires(src -> src.hasPermissionLevel(4))
                    .then(CommandManager.argument("amount", IntegerArgumentType.integer(1))
                        .then(CommandManager.literal("lives")
                            .then(CommandManager.literal("to")
                                .then(CommandManager.argument("player", EntityArgumentType.player())
                                    .executes(ctx -> {
                                        ServerPlayerEntity target = EntityArgumentType.getPlayer(ctx, "player");
                                        int amount = IntegerArgumentType.getInteger(ctx, "amount");

                                        UUID id = target.getUuid();
                                        lives.put(id, lives.getOrDefault(id, 0) + amount);
                                        target.sendMessage(Text.of("§aAdmin gave you " + amount + " lives"), false);
                                        return 1;
                                    }))))));

            /* /giveall <amount> lives (ADMIN) */
            dispatcher.register(
                CommandManager.literal("giveall")
                    .requires(src -> src.hasPermissionLevel(4))
                    .then(CommandManager.argument("amount", IntegerArgumentType.integer(1))
                        .then(CommandManager.literal("lives")
                            .executes(ctx -> {
                                int amount = IntegerArgumentType.getInteger(ctx, "amount");

                                for (ServerPlayerEntity p : ctx.getSource().getServer()
                                        .getPlayerManager().getPlayerList()) {
                                    UUID id = p.getUuid();
                                    lives.put(id, lives.getOrDefault(id, 0) + amount);
                                    p.sendMessage(Text.of("§aAdmin gave everyone " + amount + " lives"), false);
                                }
                                return 1;
                            }))));

        });
    }

    /* ===== BAN METHOD (1.21 SAFE) ===== */
    private static void banPlayer(ServerPlayerEntity player, String reason) {
    var server = player.getServer();
    if (server == null) return;

    var banList = server.getPlayerManager().getUserBanList();

    banList.add(new net.minecraft.server.BannedPlayerEntry(
            player.getGameProfile(),
            null,
            reason,
            null,
            null
    ));

    player.networkHandler.disconnect(Text.of(reason));
}


}

