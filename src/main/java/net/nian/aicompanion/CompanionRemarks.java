package net.nian.aicompanion;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Optional companion comments. They run every two seconds, only when something changes,
 * and only if Ambient_Remarks is true. Villager chats do not use this path.
 */
public final class CompanionRemarks {
	private static final int CHECK_EVERY_TICKS = 40;
	private static final int COOLDOWN_TICKS = 20 * 90;
	private static final ConcurrentHashMap<UUID, Memory> MEMORY = new ConcurrentHashMap<>();

	private static int tick;

	private CompanionRemarks() {
	}

	public static void register(OpenAIConversations conversations) {
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (!ConfigLoader.ambientRemarks() || ConfigLoader.apiKey().isEmpty()) {
				return;
			}
			if ((++tick % CHECK_EVERY_TICKS) != 0) {
				return;
			}
			for (ServerPlayer player : server.getPlayerList().getPlayers()) {
				if (player.isSpectator() || player.isDeadOrDying()) {
					continue;
				}
				String reason = changed(player);
				if (reason == null) {
					continue;
				}
				UUID speakerId = player.getUUID();
				String line = player.getName().getString() + " " + reason;
				conversations.ask(server, ConversationStore.COMPANION_KEY, instructions(), line, reply -> {
					ServerPlayer speaker = server.getPlayerList().getPlayer(speakerId);
					if (speaker == null || reply == null) {
						return;
					}
					speaker.sendSystemMessage(Component.literal("<" + ConfigLoader.companionName() + "> ").withStyle(ChatFormatting.AQUA)
							.append(Component.literal(reply)));
				});
			}
		});

		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> MEMORY.remove(handler.player.getUUID()));
	}

	private static String instructions() {
		return "You are " + ConfigLoader.companionName() + ", traveling with every player in this Minecraft world. "
				+ ConfigLoader.personality()
				+ " A message describes something that just changed for the named player. "
				+ "Answer with one short spoken sentence, or stay quiet in your own words if it is nothing. "
				+ "Never mention being an AI.";
	}

	private static String changed(ServerPlayer player) {
		ServerLevel level = player.level();
		long now = level.getGameTime();
		Memory memory = MEMORY.computeIfAbsent(player.getUUID(), ignored -> new Memory());
		if (memory.readyAt > now) {
			observe(player, level, memory, false);
			return null;
		}
		String reason = observe(player, level, memory, true);
		if (reason != null) {
			memory.readyAt = now + COOLDOWN_TICKS;
		}
		return reason;
	}

	private static String observe(ServerPlayer player, ServerLevel level, Memory memory, boolean reportEdges) {
		boolean hurt = player.getHealth() / player.getMaxHealth() < 0.3f;
		boolean hungry = player.getFoodData().getFoodLevel() < 6;
		boolean burning = player.isOnFire() || player.hasEffect(MobEffects.POISON);
		boolean falling = !player.onGround() && player.getDeltaMovement().y < -1.0;
		boolean cold = level.getBlockState(player.blockPosition()).is(Blocks.POWDER_SNOW);
		long day = level.getDefaultClockTime() % 24000;
		boolean night = day >= 13000 && day < 23000;
		boolean rain = level.isRaining();
		boolean targeted = false;
		for (Entity entity : level.getEntities(player, player.getBoundingBox().inflate(16, 6, 16), entity -> entity instanceof Enemy)) {
			if (entity instanceof Mob mob && mob.getTarget() == player) {
				targeted = true;
				break;
			}
		}
		boolean lava = nearLava(level, player.blockPosition());
		Holder<Biome> biome = level.getBiome(player.blockPosition());
		boolean biomeChanged = memory.seenBiome && !biome.equals(memory.biome);

		String reason = null;
		if (reportEdges) {
			if (hurt && !memory.hurt) {
				reason = "is badly hurt.";
			} else if (hungry && !memory.hungry) {
				reason = "is getting hungry.";
			} else if (burning && !memory.burning) {
				reason = "is hurt and it is not stopping.";
			} else if (falling && !memory.falling) {
				reason = "is falling from high up.";
			} else if (cold && !memory.cold) {
				reason = "stepped into powder snow.";
			} else if (night && !memory.night) {
				reason = "notices night falling.";
			} else if (rain && !memory.rain) {
				reason = "notices the weather turning bad.";
			} else if (biomeChanged) {
				reason = "walks into a new place.";
			} else if (targeted && !memory.targeted) {
				reason = "is being targeted by a hostile mob.";
			} else if (lava && !memory.lava) {
				reason = "is close to lava.";
			}
		}

		memory.hurt = hurt;
		memory.hungry = hungry;
		memory.burning = burning;
		memory.falling = falling;
		memory.cold = cold;
		memory.night = night;
		memory.rain = rain;
		memory.targeted = targeted;
		memory.lava = lava;
		memory.biome = biome;
		memory.seenBiome = true;
		return reason;
	}

	private static boolean nearLava(ServerLevel level, BlockPos center) {
		for (int dx = -2; dx <= 2; dx++) {
			for (int dy = -1; dy <= 1; dy++) {
				for (int dz = -2; dz <= 2; dz++) {
					if (level.getBlockState(center.offset(dx, dy, dz)).is(Blocks.LAVA)) {
						return true;
					}
				}
			}
		}
		return false;
	}

	private static final class Memory {
		private long readyAt;
		private boolean hurt;
		private boolean hungry;
		private boolean burning;
		private boolean falling;
		private boolean cold;
		private boolean night;
		private boolean rain;
		private boolean targeted;
		private boolean lava;
		private boolean seenBiome;
		private Holder<Biome> biome;
	}
}
