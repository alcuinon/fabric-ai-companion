package net.nian.aicompanion.monitor;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.world.biome.Biome;
import net.nian.aicompanion.AICompanionClient;
import net.nian.aicompanion.ConfigLoader;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class PlayerStateMonitor {
    private static final Map<UUID, Long> lastMessageTime = new HashMap<>();
    private static final int MESSAGE_COOLDOWN_TICKS = 20 * 30; // 10 seconds
    private static final Map<UUID, RegistryEntry<Biome>> lastKnownBiome = new HashMap<>();

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {

            if (client.player == null || client.world == null) return; // Avoid null pointer exceptions

            checkPlayerState(client);
        });
    }

    private static void checkPlayerState(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        assert client.player != null;
        UUID uuid = client.player.getUuid();
        assert client.world != null;
        long currentTime = client.world.getTime();
        World world = player.getWorld();
        BlockPos pos = player.getBlockPos();
        boolean sentMessage = false;
        RegistryEntry<Biome> currentBiome = world.getBiome(pos);
        List<Entity> nearbyEntities = world.getOtherEntities(player, player.getBoundingBox().expand(15));

        // Cooldown
        if (lastMessageTime.getOrDefault(uuid, 0L) > currentTime - MESSAGE_COOLDOWN_TICKS) return;


        if (player.getHealth() / player.getMaxHealth() < 0.3f) {
            //sendMessage(player, "You're hurt... Maybe eat something?");
            AICompanionClient.callAI(client, "", "Player is low health");
            sentMessage = true;
        } else if (player.getHungerManager().getFoodLevel() < 6) {
            //sendMessage(player, "Your tummy’s rumbling again...");
            AICompanionClient.callAI(client, "", "Player is hungry");
            sentMessage = true;
        } else if (player.hasStatusEffect(StatusEffects.POISON) || player.isOnFire()) {
            //sendMessage(player, "Ahh! You're burning—water, quick!");
            AICompanionClient.callAI(client, "", "Player is hurt over time");
            sentMessage = true;
        } else if (!player.isOnGround() && player.getVelocity().y < -1.0) {
            //sendMessage(player, "That’s a big drop—are you sure?");
            AICompanionClient.callAI(client, "", "Player is falling from high ground");
            sentMessage = true;
        } else if (world.getBlockState(pos).isOf(net.minecraft.block.Blocks.POWDER_SNOW)) {
            //sendMessage(player, "Brrr… it’s really cold here!");
            AICompanionClient.callAI(client, "", "Player is cold");
            sentMessage = true;
        }
        // Nighttime begins
        else if (world.getTimeOfDay() % 24000 > 13000 && world.getTimeOfDay() % 24000 < 13100) {
            //sendMessage(player, "It’s getting dark… should we find shelter?");
            AICompanionClient.callAI(client, "", "It is night!");
            sentMessage = true;
        }
        // Rain or storm
        else if ((world.isRaining() || world.isThundering()) && world.getTimeOfDay() % 24000 % 100 == 0) {
            //sendMessage(player, "Rain’s coming. Do you like the sound of it?");
            AICompanionClient.callAI(client, "", "The weather is bad");
            sentMessage = true;
        }
        // Enters a cave (dark and underground)
        else if (pos.getY() < 20 && world.getLightLevel(pos) < 7) {
            //sendMessage(player, "It's dark down here... be careful.");
            AICompanionClient.callAI(client, "", "Player is in the cave");
            sentMessage = true;
        }
        // New biome
        else if (!currentBiome.equals(lastKnownBiome.get(uuid))) {
            //sendMessage(player, "Ooh, pretty trees here! What's this place?");
            AICompanionClient.callAI(client, "", "Player is in new biome");
            lastKnownBiome.put(uuid, currentBiome);
            sentMessage = true;
        }

        if(!sentMessage){
            for (Entity e : nearbyEntities) {
                if (e instanceof HostileEntity hostile && hostile.getTarget() == player) {
                    //sendMessage(player, "Umm... you might want to run!");
                    AICompanionClient.callAI(client, "", "Player is targeted by hostile mobs");
                    sentMessage = true;
                    break;
                }
            }
        }

        if(!sentMessage){
            long hostileCount = nearbyEntities.stream()
                    .filter(e -> e instanceof HostileEntity)
                    .count();

            if (hostileCount >= 10) {
                //sendMessage(player, "Uh oh, we’re outnumbered!");
                AICompanionClient.callAI(client, "", "Player is surrounded by a lot of hostile mobs");
                sentMessage = true;
            }
        }

        if(!sentMessage){
            for (Entity e : nearbyEntities) {
                if (e instanceof HostileEntity hostile && hostile.isAlive()) {
                    //sendMessage(player, "I see something nasty moving...");
                    AICompanionClient.callAI(client, "", "Player is near on hostile mobs");
                    sentMessage = true;
                    break;
                }
            }
        }

        if(!sentMessage){
            // Lava or danger nearby
            for (BlockPos nearbyPos : BlockPos.iterateOutwards(pos, 5, 3, 5)) {
                if (world.getBlockState(nearbyPos).isOf(Blocks.LAVA)) {
                    //sendMessage(player, "Smells like something’s burning...");
                    AICompanionClient.callAI(client, "", "Player is in near Lava");
                    sentMessage = true;
                    break;
                }
            }
        }

        if (sentMessage) {
            lastMessageTime.put(uuid, currentTime);
        }
    }

    private static void sendMessage(ClientPlayerEntity player, String message) {
        player.sendMessage(Text.of("<"+ ConfigLoader.config.Settings.AI_Companion_Name +"> " + message), false); // change this to whisper if needed
    }
}
