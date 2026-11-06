package net.nian.aicompanion;

import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class VillagerTalk {
	private static final double LOOK_RANGE = 5.0;
	private static final double NAME_RANGE = 16.0;
	private static final ConcurrentHashMap<String, Long> RECENT = new ConcurrentHashMap<>();

	private VillagerTalk() {
	}

	public static void register(OpenAIConversations conversations) {
		UseEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
			if (!(entity instanceof Villager villager) || hand != InteractionHand.MAIN_HAND || !player.isShiftKeyDown()) {
				return InteractionResult.PASS;
			}
			if (level.isClientSide() || !(player instanceof ServerPlayer serverPlayer)) {
				return InteractionResult.SUCCESS;
			}
			if (!tooSoon(serverPlayer.getUUID() + ":" + villager.getUUID(), 2500)) {
				String speaker = serverPlayer.getName().getString();
				askVillager(conversations, serverPlayer, villager,
						speaker + " walks up and wants to talk. Greet them. If you have met them before, show that you remember.",
						false);
			}
			return InteractionResult.SUCCESS;
		});

		ServerMessageEvents.ALLOW_CHAT_MESSAGE.register((message, sender, bound) -> {
			String text = message.signedContent().trim();
			if (text.isEmpty() || text.startsWith("/")) {
				return true;
			}
			if (text.length() > 300) {
				text = text.substring(0, 300);
			}

			String companionLine = addressedTo(text, ConfigLoader.companionName());
			if (companionLine != null) {
				if (!companionLine.isEmpty()) {
					askCompanion(conversations, sender, sender.getName().getString() + ": " + companionLine);
				}
				return true;
			}

			if (text.startsWith("@")) {
				int split = indexOfWhitespace(text);
				String token = split < 0 ? text.substring(1) : text.substring(1, split);
				String rest = split < 0 ? "" : text.substring(split).trim();
				Villager named = findNamed(sender, token);
				if (named != null) {
					String speaker = sender.getName().getString();
					String line = rest.isEmpty()
							? speaker + " says hello. Greet them, and show that you remember them if you have talked before."
							: speaker + ": " + rest;
					askVillager(conversations, sender, named, line, true);
				}
				return true;
			}

			if (sender.isShiftKeyDown()) {
				Villager lookedAt = lookingAt(sender);
				if (lookedAt != null) {
					askVillager(conversations, sender, lookedAt, sender.getName().getString() + ": " + text, true);
				}
			}
			return true;
		});
	}

	private static void askCompanion(OpenAIConversations conversations, ServerPlayer sender, String line) {
		if (!hasKey(sender) || tooSoon("chat:" + sender.getUUID(), 800)) {
			return;
		}
		MinecraftServer server = sender.level().getServer();
		if (server == null) {
			return;
		}
		UUID speakerId = sender.getUUID();
		String name = ConfigLoader.companionName();
		conversations.ask(server, ConversationStore.COMPANION_KEY, companionInstructions(name), line, reply -> {
			ServerPlayer speaker = server.getPlayerList().getPlayer(speakerId);
			if (speaker == null) {
				return;
			}
			if (reply == null) {
				speaker.sendSystemMessage(Component.literal(name + " is quiet for a moment.").withStyle(ChatFormatting.RED));
				return;
			}
			speaker.sendSystemMessage(Component.literal("<" + name + "> ").withStyle(ChatFormatting.AQUA)
					.append(Component.literal(reply)));
		});
	}

	private static void askVillager(OpenAIConversations conversations, ServerPlayer sender, Villager villager, String line, boolean paceChat) {
		if (!hasKey(sender) || (paceChat && tooSoon("chat:" + sender.getUUID(), 800))) {
			return;
		}
		MinecraftServer server = sender.level().getServer();
		if (server == null) {
			return;
		}
		UUID speakerId = sender.getUUID();
		UUID villagerId = villager.getUUID();
		var dimension = villager.level().dimension();
		String name = displayName(villager);
		String key = "villager:" + villagerId;
		conversations.ask(server, key, villagerInstructions(villager, name), line, reply -> {
			ServerPlayer speaker = server.getPlayerList().getPlayer(speakerId);
			ServerLevel level = server.getLevel(dimension);
			Entity entity = level == null ? null : level.getEntity(villagerId);
			if (reply == null) {
				if (speaker != null) {
					speaker.sendSystemMessage(Component.literal(name + " loses their train of thought.").withStyle(ChatFormatting.RED));
				}
				return;
			}
			if (entity instanceof Villager talking) {
				VillagerBubble.show(talking, reply);
				return;
			}
			if (speaker != null) {
				speaker.sendSystemMessage(Component.literal("<" + name + "> ").withStyle(ChatFormatting.GOLD)
						.append(Component.literal(reply)));
			}
		});
	}

	private static boolean hasKey(ServerPlayer player) {
		if (!ConfigLoader.apiKey().isEmpty()) {
			return true;
		}
		if (!tooSoon("nokey:" + player.getUUID(), 5000)) {
			player.sendSystemMessage(Component.literal("Add Open_AI_API_Key to config/aicompanion_config.json")
					.withStyle(ChatFormatting.RED));
		}
		return false;
	}

	private static String companionInstructions(String name) {
		return "You are " + name + ", a companion who lives with the players in Minecraft. "
				+ ConfigLoader.personality()
				+ " Every player on this world shares this one conversation with you. "
				+ "Each message starts with the speaker's name. Remember each person, what they told you, and what you told them. "
				+ "Reply in one or two short spoken sentences. Never mention being an AI, a model, or a conversation.";
	}

	private static String villagerInstructions(Villager villager, String name) {
		String job = professionPath(villager);
		return "You are " + name + ", a " + job.replace('_', ' ')
				+ " villager living in a Minecraft world. " + flavor(job) + " "
				+ "Many different players may speak to you, and this is one continuous life. "
				+ "Each message starts with the speaker's name. Remember who they are and what you have already said to each of them. "
				+ "Stay in the world. Reply in one or two short spoken sentences. Never mention being an AI, a model, or a conversation.";
	}

	private static String flavor(String job) {
		return switch (job) {
			case "farmer" -> "You care about crops, weather, and whether people have eaten.";
			case "librarian" -> "You love books and perk up when someone mentions something worth writing down.";
			case "cleric" -> "You speak gently and notice when someone is hurt or frightened.";
			case "armorer", "weaponsmith", "toolsmith" -> "You talk like a smith, proud of your work and wary of lava.";
			case "butcher" -> "You are hearty and food is never far from your mind.";
			case "cartographer" -> "You love maps, biomes, and news from far away.";
			case "fletcher" -> "You notice bows, arrows, and anything that flies.";
			case "fisherman" -> "You talk about water, rain, boats, and the catch.";
			case "leatherworker" -> "You notice hides, armor, and the state of the road.";
			case "mason" -> "You notice stone, buildings, and whether the ground feels solid.";
			case "shepherd" -> "You are warm and talk easily about animals.";
			case "nitwit" -> "You are cheerful, a little scattered, and still remember people.";
			default -> "You are a neighbor in the village, and you remember faces.";
		};
	}

	private static Villager lookingAt(ServerPlayer player) {
		Vec3 start = player.getEyePosition();
		Vec3 end = start.add(player.getLookAngle().scale(LOOK_RANGE));
		AABB box = player.getBoundingBox().expandTowards(player.getLookAngle().scale(LOOK_RANGE)).inflate(1.0);
		EntityHitResult hit = ProjectileUtil.getEntityHitResult(
				player, start, end, box, entity -> entity instanceof Villager && entity.isAlive(), LOOK_RANGE * LOOK_RANGE);
		if (hit != null && hit.getEntity() instanceof Villager villager) {
			return villager;
		}
		return null;
	}

	private static Villager findNamed(ServerPlayer player, String token) {
		if (token.isBlank()) {
			return null;
		}
		List<Villager> found = player.level().getEntitiesOfClass(
				Villager.class,
				player.getBoundingBox().inflate(NAME_RANGE),
				villager -> token.equalsIgnoreCase(displayName(villager)));
		found.sort(Comparator.comparingDouble(player::distanceToSqr));
		return found.isEmpty() ? null : found.getFirst();
	}

	static String displayName(Villager villager) {
		if (villager.hasCustomName() && villager.getCustomName() != null) {
			return villager.getCustomName().getString();
		}
		String job = professionPath(villager).replace('_', ' ');
		if (job.isEmpty()) {
			return "Villager";
		}
		return Character.toUpperCase(job.charAt(0)) + job.substring(1);
	}

	private static String professionPath(Villager villager) {
		return villager.getVillagerData().profession().unwrapKey()
				.map(key -> key.identifier().getPath())
				.orElse("villager");
	}

	private static String addressedTo(String text, String name) {
		String prefix = "@" + name;
		if (text.length() < prefix.length() || !text.regionMatches(true, 0, prefix, 0, prefix.length())) {
			return null;
		}
		if (text.length() == prefix.length()) {
			return "";
		}
		if (!Character.isWhitespace(text.charAt(prefix.length()))) {
			return null;
		}
		return text.substring(prefix.length()).trim();
	}

	private static int indexOfWhitespace(String text) {
		for (int i = 0; i < text.length(); i++) {
			if (Character.isWhitespace(text.charAt(i))) {
				return i;
			}
		}
		return -1;
	}

	private static boolean tooSoon(String key, long gapMillis) {
		long now = System.currentTimeMillis();
		if (RECENT.size() > 512) {
			RECENT.clear();
		}
		Long last = RECENT.get(key);
		if (last != null && now - last < gapMillis) {
			return true;
		}
		RECENT.put(key, now);
		return false;
	}
}
