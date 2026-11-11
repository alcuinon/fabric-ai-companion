package net.nian.aicompanion;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
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
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class VillagerTalk {
	private static final double LOOK_RANGE = 32.0;
	private static final double NEAR_RANGE = 8.0;
	private static final double NAME_RANGE = 16.0;
	private static final ConcurrentHashMap<String, Long> RECENT = new ConcurrentHashMap<>();
	private static final Map<UUID, Long> NEXT_VILLAGER_TALK = new HashMap<>();
	private static final Map<UUID, Long> NEXT_PLAYER_TALK = new HashMap<>();
	private static final Map<UUID, Long> NEXT_PAIR_CHAT = new HashMap<>();
	private static final long VILLAGER_QUIET_TICKS = 20L * 60 * 12;
	private static final long PLAYER_QUIET_TICKS = 20L * 60 * 4;
	private static final long PAIR_AREA_QUIET = 20L * 60 * 4;
	private static final double PAIR_RANGE_SQR = 25.0;
	private static int initiativeTick;

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
			if (!villager.isSleeping() && !tooSoon(serverPlayer.getUUID() + ":" + villager.getUUID(), 2500)) {
				String speaker = serverPlayer.getName().getString();
				askVillager(conversations, serverPlayer, villager, VillagerContext.greeting(villager, speaker), false, true);
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
				if (named != null && !named.isSleeping()) {
					String speaker = sender.getName().getString();
					String line = rest.isEmpty()
							? speaker + " says hello. Greet them, and show that you remember them if you have talked before."
							: speaker + ": " + rest;
					askVillager(conversations, sender, named, line, true, true);
				}
				return true;
			}

			if (VillagerLocal.isCrowd(text)) {
				if (ConfigLoader.villagersHearChat()) {
					VillagerLocal.addressCrowd(sender, text);
				}
				return true;
			}

			Villager target = villagerForChat(sender);
			if (target != null && !VillagerLocal.tryCanned(sender, target, text)) {
				askVillager(conversations, sender, target, sender.getName().getString() + ": " + text, true, true);
			}
			return true;
		});

		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (ConfigLoader.apiKey().isEmpty() || (++initiativeTick % 200) != 0) {
				return;
			}
			for (ServerPlayer player : server.getPlayerList().getPlayers()) {
				if (!considerSpeaking(conversations, player)) {
					considerVillagerChat(conversations, player);
				}
			}
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

	static void askVillager(OpenAIConversations conversations, ServerPlayer sender, Villager villager, String line, boolean paceChat, boolean warnMissingKey) {
		if (ConfigLoader.apiKey().isEmpty()) {
			if (warnMissingKey) {
				hasKey(sender);
			}
			return;
		}
		if (paceChat && tooSoon("chat:" + sender.getUUID(), 800)) {
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
		String instructions = VillagerContext.instructions(villager, sender);
		conversations.ask(server, key, instructions, line, reply -> {
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

	static boolean hasKey(ServerPlayer player) {
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

	private static boolean considerSpeaking(OpenAIConversations conversations, ServerPlayer player) {
		if (player.isSpectator() || player.isDeadOrDying()) {
			return false;
		}
		long now = player.level().getGameTime();
		if (now < NEXT_PLAYER_TALK.getOrDefault(player.getUUID(), 0L)) {
			return false;
		}
		Villager chosen = null;
		double best = 36;
		for (Villager villager : player.level().getEntitiesOfClass(Villager.class, player.getBoundingBox().inflate(6), Villager::isAlive)) {
			if (villager.isSleeping() || now < NEXT_VILLAGER_TALK.getOrDefault(villager.getUUID(), 0L) || VillagerBubble.isActive(villager.getUUID())) {
				continue;
			}
			double distance = villager.distanceToSqr(player);
			if (distance < best) {
				best = distance;
				chosen = villager;
			}
		}
		if (chosen == null) {
			return false;
		}
		String impulse = VillagerContext.impulse(chosen, player);
		if (impulse == null) {
			return false;
		}
		long mix = now ^ player.getUUID().getLeastSignificantBits() ^ chosen.getUUID().getLeastSignificantBits();
		if (Math.floorMod(mix, 12) != 0) {
			return false;
		}
		NEXT_VILLAGER_TALK.put(chosen.getUUID(), now + VILLAGER_QUIET_TICKS);
		NEXT_PLAYER_TALK.put(player.getUUID(), now + PLAYER_QUIET_TICKS);
		String name = player.getName().getString();
		askVillager(conversations, player, chosen,
				name + " is nearby. " + impulse + ". Say one short sentence to " + name + ".",
				false, false);
		return true;
	}

	private static void considerVillagerChat(OpenAIConversations conversations, ServerPlayer player) {
		if (player.isSpectator() || player.isDeadOrDying()) {
			return;
		}
		long now = player.level().getGameTime();
		if (now < NEXT_PAIR_CHAT.getOrDefault(player.getUUID(), 0L)) {
			return;
		}
		List<Villager> group = player.level().getEntitiesOfClass(
				Villager.class, player.getBoundingBox().inflate(12), villager -> villager.isAlive() && !villager.isSleeping());
		Villager speaker = null;
		Villager listener = null;
		double best = PAIR_RANGE_SQR + 1;
		for (int i = 0; i < group.size(); i++) {
			Villager first = group.get(i);
			if (busy(first, now)) {
				continue;
			}
			for (int j = i + 1; j < group.size(); j++) {
				Villager second = group.get(j);
				if (busy(second, now)) {
					continue;
				}
				double distance = first.distanceToSqr(second);
				if (distance <= PAIR_RANGE_SQR && distance < best) {
					best = distance;
					speaker = first;
					listener = second;
				}
			}
		}
		if (speaker == null || listener == null) {
			return;
		}
		if (speaker.distanceToSqr(player) > listener.distanceToSqr(player)) {
			Villager swap = speaker;
			speaker = listener;
			listener = swap;
		}
		long mix = now ^ speaker.getUUID().getLeastSignificantBits() ^ listener.getUUID().getLeastSignificantBits();
		if (Math.floorMod(mix, 8) != 0) {
			return;
		}
		NEXT_PAIR_CHAT.put(player.getUUID(), now + PAIR_AREA_QUIET);
		quiet(speaker, VILLAGER_QUIET_TICKS);
		quiet(listener, VILLAGER_QUIET_TICKS);
		askBetween(conversations, player, speaker, listener, null, true);
	}

	private static boolean busy(Villager villager, long now) {
		return now < NEXT_VILLAGER_TALK.getOrDefault(villager.getUUID(), 0L) || VillagerBubble.isActive(villager.getUUID());
	}

	private static void askBetween(OpenAIConversations conversations, ServerPlayer witness, Villager speaker, Villager listener, String heardLine, boolean mayAnswer) {
		MinecraftServer server = witness.level().getServer();
		if (server == null || ConfigLoader.apiKey().isEmpty()) {
			return;
		}
		UUID witnessId = witness.getUUID();
		UUID speakerId = speaker.getUUID();
		UUID listenerId = listener.getUUID();
		var dimension = speaker.level().dimension();
		String listenerName = displayName(listener);
		String instructions = VillagerContext.instructions(speaker, witness, listenerName, !mayAnswer);
		String line = mayAnswer
				? listenerName + " is beside you. Say one short sentence to " + listenerName + "."
				: listenerName + " says to you: " + heardLine;
		conversations.ask(server, "villager:" + speakerId, instructions, line, reply -> {
			if (reply == null || reply.isBlank()) {
				return;
			}
			ServerLevel level = server.getLevel(dimension);
			Entity entity = level == null ? null : level.getEntity(speakerId);
			if (!(entity instanceof Villager talking) || !talking.isAlive() || talking.isSleeping()) {
				return;
			}
			VillagerBubble.show(talking, reply);
			if (!mayAnswer) {
				return;
			}
			Entity other = level.getEntity(listenerId);
			if (!(other instanceof Villager heard) || !heard.isAlive() || heard.isSleeping() || talking.distanceToSqr(heard) > 64) {
				return;
			}
			talking.lookAt(heard, 40.0f, 40.0f);
			ServerPlayer still = server.getPlayerList().getPlayer(witnessId);
			if (still == null) {
				return;
			}
			askBetween(conversations, still, heard, talking, reply, false);
		});
	}

	static void quiet(Villager villager, long ticks) {
		long now = villager.level().getGameTime();
		long until = now + ticks;
		if (until > NEXT_VILLAGER_TALK.getOrDefault(villager.getUUID(), 0L)) {
			NEXT_VILLAGER_TALK.put(villager.getUUID(), until);
		}
	}

	private static Villager villagerForChat(ServerPlayer player) {
		if (!ConfigLoader.villagersHearChat()) {
			return null;
		}
		Villager lookedAt = lookingAt(player);
		if (lookedAt != null) {
			return lookedAt.isSleeping() ? null : lookedAt;
		}
		if (ConfigLoader.chatLooksOnly()) {
			return null;
		}
		return closest(player);
	}

	private static Villager lookingAt(ServerPlayer player) {
		Vec3 start = player.getEyePosition();
		Vec3 look = player.getLookAngle();
		Vec3 end = start.add(look.scale(LOOK_RANGE));
		HitResult block = player.level().clip(new ClipContext(start, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
		if (block.getType() != HitResult.Type.MISS) {
			end = block.getLocation();
		}
		double reachSqr = start.distanceToSqr(end);
		AABB box = player.getBoundingBox().expandTowards(look.scale(LOOK_RANGE)).inflate(1.0);
		EntityHitResult hit = ProjectileUtil.getEntityHitResult(
				player, start, end, box, entity -> entity instanceof Villager && entity.isAlive(), reachSqr);
		if (hit != null && hit.getEntity() instanceof Villager villager) {
			return villager;
		}
		Villager aimed = null;
		double bestDot = 0.99;
		for (Villager villager : player.level().getEntitiesOfClass(Villager.class, player.getBoundingBox().inflate(LOOK_RANGE), Villager::isAlive)) {
			Vec3 to = villager.getEyePosition().subtract(start);
			double distanceSqr = to.lengthSqr();
			if (distanceSqr < 0.25 || distanceSqr > LOOK_RANGE * LOOK_RANGE || distanceSqr > reachSqr + 1.0) {
				continue;
			}
			double dot = look.dot(to.scale(1.0 / Math.sqrt(distanceSqr)));
			if (dot > bestDot) {
				bestDot = dot;
				aimed = villager;
			}
		}
		return aimed;
	}

	private static Villager closest(ServerPlayer player) {
		Villager chosen = null;
		double best = NEAR_RANGE * NEAR_RANGE;
		for (Villager villager : player.level().getEntitiesOfClass(Villager.class, player.getBoundingBox().inflate(NEAR_RANGE), Villager::isAlive)) {
			if (villager.isSleeping()) {
				continue;
			}
			double distance = villager.distanceToSqr(player);
			if (distance < best) {
				best = distance;
				chosen = villager;
			}
		}
		return chosen;
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

	static String professionPath(Villager villager) {
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
