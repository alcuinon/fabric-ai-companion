package net.nian.aicompanion;

import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.registry.RegistryKey;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class PlayerInfoHandler {

    public static String[] GetPlayerAllInfo(MinecraftClient client) {
        if (client.player == null || client.world == null) return new String[] {};

        ClientPlayerEntity player = client.player;
        ClientWorld world = player.clientWorld;
        List<String> infos = new ArrayList<>();

        Consumer<String> add = s -> {
            if (s != null && !s.trim().isEmpty() && !s.endsWith(": null")) {
                infos.add(s);
            }
        };

        // === GENERAL INFO ===
        add.accept("[Player]");
        add.accept("Name: " + player.getName().getString());
        add.accept("Position: " + player.getBlockPos());
        RegistryKey<World> dim = world.getRegistryKey();
        add.accept("Dimension: " + (dim != null ? dim.getValue().toString() : "unknown"));
        add.accept("Mood: " + getPlayerMood(player));
        add.accept("Facing: " + player.getHorizontalFacing().toString());



        // === STATS ===
        add.accept("[Stats]");
        add.accept("Health: " + player.getHealth() + "/" + player.getMaxHealth());
        add.accept("Hunger: " + player.getHungerManager().getFoodLevel() + "/20");
        add.accept("Holding: " + player.getMainHandStack().getName().getString());
        add.accept("Is Sprinting: " + player.isSprinting());
        add.accept("Is Swimming: " + player.isSwimming());
        add.accept("Is Sneaking: " + player.isSneaking());
        add.accept("Is Using Item: " + player.isUsingItem());
        add.accept("Inventory Used Slots: " + getUsedSlot(player) + "/36");

        // === ENVIRONMENT ===
        add.accept("[Environment]");
        add.accept("Is Night: " + world.isNight());
        add.accept("Is Raining: " + world.isRaining());
        add.accept("Is Submerged In Water: " + player.isSubmergedInWater());
        add.accept("Is In Lava: " + player.isInLava());
        add.accept("Is On Fire: " + player.isOnFire());
        add.accept("Is Wet: " + player.isWet());BlockPos blockBelow = player.getBlockPos().down();
        add.accept("World Time: " + world.getTimeOfDay() % 24000 + " ticks");


        BlockState blockState = world.getBlockState(blockBelow);
        add.accept("Standing on: " + blockState.getBlock().getName().getString());


        String biome = world.getBiome(player.getBlockPos()).getKey().get().getValue().toString();
        add.accept("Current Biome: " + biome);

        // === COMBAT ===
        add.accept("[Combat]");
        if (player.getAttacker() != null)
            add.accept("Attacked By: " + player.getAttacker().getName().getString());
        if (player.getAttacking() != null)
            add.accept("Attacking: " + player.getAttacking().getName().getString());
        if (player.getRecentDamageSource() != null)
            add.accept("Last Damage Source: " + player.getRecentDamageSource().getName());
        if (player.explodedBy != null)
            add.accept("Exploded By: " + player.explodedBy.getName().getString());

        // === STATUS EFFECTS ===
        add.accept("[Status]");
        add.accept("Is Dead: " + player.isDead());
        add.accept("Is Frozen: " + player.isFrozen());
        add.accept("Is In Powder Snow: " + player.inPowderSnow);
        add.accept("Fall Distance: " + player.fallDistance);
        add.accept("Nausea Intensity: " + player.nauseaIntensity);

        // === ENTITY DETECTION ===
        Box box = player.getBoundingBox().expand(20, 5, 20);
        List<Entity> nearby = client.world.getOtherEntities(player, box);
        if (!nearby.isEmpty()) {
            Map<String, Integer> entityCounts = new HashMap<>();
            for (Entity e : nearby) {
                String name = e.getName().getString();
                entityCounts.put(name, entityCounts.getOrDefault(name, 0) + 1);
            }

            add.accept("[Nearby Entities]");
            for (Map.Entry<String, Integer> entry : entityCounts.entrySet()) {
                add.accept(entry.getKey() + ": " + entry.getValue());
            }
        }

        // === CROSSHAIR TARGET ===
        add.accept("[Looking At]");
        HitResult hit = client.crosshairTarget;
        if (hit instanceof BlockHitResult blockHit) {
            BlockPos pos = blockHit.getBlockPos();
            String blockName = world.getBlockState(pos).getBlock().getName().getString();
            add.accept("Block: " + blockName + " at " + pos);
        } else if (hit instanceof EntityHitResult entityHit) {
            Entity entity = entityHit.getEntity();
            add.accept("Entity: " + entity.getName().getString() + " at " + entity.getBlockPos());
        } else {
            add.accept("Nothing targeted");
        }

        BlockPos center = player.getBlockPos();
        Map<String, Integer> surroundingBlockCounts = new HashMap<>();

        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) continue; // skip player block
                    BlockPos pos = center.add(dx, dy, dz);
                    String blockName = world.getBlockState(pos).getBlock().getName().getString();
                    surroundingBlockCounts.put(blockName, surroundingBlockCounts.getOrDefault(blockName, 0) + 1);
                }
            }
        }

        if (!surroundingBlockCounts.isEmpty()) {
            String blockSummary = surroundingBlockCounts.entrySet().stream()
                    .map(entry -> entry.getKey() + " x" + entry.getValue())
                    .collect(Collectors.joining(", "));
            add.accept("Nearby blocks: " + blockSummary);
        }

        return infos.toArray(new String[0]);
    }

    private static String getPlayerMood(ClientPlayerEntity player) {
        float health = player.getHealth();
        int hunger = player.getHungerManager().getFoodLevel();
        boolean underAttack = player.getAttacker() != null;

        if (player.isDead()) return "Dead";
        if (health < 5 || hunger < 5) return "Stressed";
        if (underAttack) return "Anxious";
        if (player.isInLava() || player.isOnFire()) return "Panicked";
        if (player.isSwimming()) return "Playful";
        if (player.isSprinting()) return "Energetic";
        return "Calm";
    }

    public static int getUsedSlot(ClientPlayerEntity player) {

        PlayerInventory inventory = player.getInventory();
        int cnt = 0;
        for (int i = 0; i < inventory.main.size(); i++) {
            if (!inventory.main.get(i).isEmpty()) {
                cnt++;
            }
        }
        return cnt;
    }
}

