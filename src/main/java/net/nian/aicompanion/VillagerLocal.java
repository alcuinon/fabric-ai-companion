package net.nian.aicompanion;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Lines a villager can say with no model call: greetings, a hit, weather, and a crowd hello.
 */
final class VillagerLocal {
	private static final double GREET_RANGE = 5.0;
	private static final double CROWD_RANGE = 12.0;
	private static final int CROWD_CAP = 8;
	private static final long GREET_QUIET = 20L * 90;
	private static final long WEATHER_QUIET = 20L * 90;
	private static final Pattern CROWD = Pattern.compile(
			"\\b(everyone|everybody|you all|all of you)\\b|^(?:hi|hello|hey|howdy|yo|greetings|good morning|good evening|good night)\\s+(?:all|folks)\\b");
	private static final Pattern FILLER = Pattern.compile("\\b(everyone|everybody|you all|all of you|all|folks|guys|there)\\b");
	private static final Pattern ONLY_GREETING = Pattern.compile(
			"(?:hi|hello|hey|howdy|yo|greetings|good morning|good evening|good night)?");
	private static final Set<String> GREETS = Set.of(
			"hi", "hello", "hey", "howdy", "yo", "greetings", "good morning", "good evening", "good night",
			"hi there", "hello there", "hey there");
	private static final Set<String> BYES = Set.of(
			"bye", "goodbye", "bye bye", "see you", "see ya", "cya", "farewell", "later");
	private static final Set<String> THANKS = Set.of(
			"thanks", "thank you", "ty", "thx", "thanks a lot", "thank you so much");

	private static final List<Pending> PENDING = new ArrayList<>();
	private static final Map<UUID, Integer> LAST_HURT = new HashMap<>();
	private static final Map<String, Long> NEXT_LINE = new HashMap<>();
	private static final Map<UUID, Boolean> WAS_RAINING = new HashMap<>();
	private static final Map<UUID, Boolean> WAS_NIGHT = new HashMap<>();
	private static final Set<String> WAS_NEAR = new HashSet<>();
	private static OpenAIConversations conversations;
	private static int tick;

	private VillagerLocal() {
	}

	static void register(OpenAIConversations talks) {
		conversations = talks;
		ServerTickEvents.END_SERVER_TICK.register(VillagerLocal::tick);
	}

	static boolean isCrowd(String text) {
		return CROWD.matcher(normalize(text)).find();
	}

	static void addressCrowd(ServerPlayer sender, String text) {
		List<Villager> villagers = new ArrayList<>();
		for (Villager villager : nearby(sender, CROWD_RANGE)) {
			if (!villager.isSleeping()) {
				villagers.add(villager);
			}
		}
		if (villagers.isEmpty()) {
			return;
		}
		if (villagers.size() > CROWD_CAP) {
			villagers = villagers.subList(0, CROWD_CAP);
		}
		boolean greeting = isOnlyGreeting(text);
		if (!greeting && !VillagerTalk.hasKey(sender)) {
			return;
		}
		long now = sender.level().getGameTime();
		String speaker = sender.getName().getString();
		int index = 0;
		for (Villager villager : villagers) {
			long when = now + index * (greeting ? 8L : 12L);
			if (greeting) {
				queue(when, sender, villager, VillagerPersona.greet(villager), false);
			} else {
				queue(when, sender, villager, speaker + ": " + text, true);
			}
			index++;
		}
	}

	static boolean tryCanned(ServerPlayer sender, Villager villager, String text) {
		String said = normalize(text);
		String line;
		if (GREETS.contains(said)) {
			line = VillagerPersona.greet(villager);
		} else if (BYES.contains(said)) {
			line = VillagerPersona.farewell(villager);
		} else if (THANKS.contains(said)) {
			line = VillagerPersona.thanks(villager);
		} else {
			return false;
		}
		VillagerBubble.show(villager, line);
		remember(sender, villager);
		return true;
	}

	private static void tick(MinecraftServer server) {
		long now = 0L;
		ServerLevel clock = null;
		for (ServerLevel level : server.getAllLevels()) {
			clock = level;
			now = level.getGameTime();
			break;
		}
		if (clock == null) {
			return;
		}
		flush(server, now);
		tick++;
		if (tick % 5 == 0) {
			for (ServerPlayer player : server.getPlayerList().getPlayers()) {
				noticeHurt(player);
			}
		}
		if (tick % 20 == 0) {
			for (ServerPlayer player : server.getPlayerList().getPlayers()) {
				noticeLife(player);
			}
		}
	}

	private static void flush(MinecraftServer server, long now) {
		if (PENDING.isEmpty()) {
			return;
		}
		for (int i = 0; i < PENDING.size();) {
			Pending pending = PENDING.get(i);
			if (pending.when > now) {
				i++;
				continue;
			}
			PENDING.remove(i);
			ServerLevel level = server.getLevel(pending.dimension);
			Entity entity = level == null ? null : level.getEntity(pending.villagerId);
			if (!(entity instanceof Villager villager) || !villager.isAlive() || villager.isSleeping()) {
				continue;
			}
			ServerPlayer player = server.getPlayerList().getPlayer(pending.playerId);
			if (pending.ai) {
				if (player != null && conversations != null) {
					VillagerTalk.askVillager(conversations, player, villager, pending.line, false, false);
				}
			} else {
				VillagerBubble.show(villager, pending.line);
				if (player != null) {
					remember(player, villager);
				}
			}
		}
	}

	private static void noticeHurt(ServerPlayer player) {
		if (player.isSpectator() || player.isDeadOrDying()) {
			return;
		}
		for (Villager villager : player.level().getEntitiesOfClass(Villager.class, player.getBoundingBox().inflate(12), Villager::isAlive)) {
			int stamp = villager.getLastHurtByMobTimestamp();
			int seen = LAST_HURT.getOrDefault(villager.getUUID(), Integer.MIN_VALUE);
			if (stamp == seen) {
				continue;
			}
			LAST_HURT.put(villager.getUUID(), stamp);
			if (stamp != 0 && villager.hurtTime > 0) {
				VillagerBubble.show(villager, VillagerPersona.ouch(villager));
			}
		}
	}

	private static void noticeLife(ServerPlayer player) {
		if (player.isSpectator() || player.isDeadOrDying()) {
			return;
		}
		List<Villager> around = nearby(player, 8);
		greetArrivals(player, around);
		warnDanger(player, around);
		remarkSky(player, around);
	}

	private static void greetArrivals(ServerPlayer player, List<Villager> around) {
		Set<String> nowNear = new HashSet<>();
		Villager arrived = null;
		double best = GREET_RANGE * GREET_RANGE;
		long time = player.level().getGameTime();
		for (Villager villager : around) {
			if (villager.isSleeping()) {
				continue;
			}
			double distance = villager.distanceToSqr(player);
			if (distance > best) {
				continue;
			}
			String key = pair(player, villager);
			nowNear.add(key);
			if (WAS_NEAR.contains(key) || time < NEXT_LINE.getOrDefault("greet:" + key, 0L) || VillagerBubble.isActive(villager.getUUID())) {
				continue;
			}
			if (arrived == null || distance < arrived.distanceToSqr(player)) {
				arrived = villager;
			}
		}
		WAS_NEAR.removeIf(key -> key.startsWith(player.getUUID() + ":") && !nowNear.contains(key));
		WAS_NEAR.addAll(nowNear);
		if (arrived == null) {
			return;
		}
		VillagerBubble.show(arrived, VillagerPersona.greet(arrived));
		remember(player, arrived);
	}

	private static void warnDanger(ServerPlayer player, List<Villager> around) {
		Villager villager = firstAwake(around);
		if (villager == null || VillagerBubble.isActive(villager.getUUID())) {
			return;
		}
		long time = player.level().getGameTime();
		if (time < NEXT_LINE.getOrDefault("danger:" + villager.getUUID(), 0L)) {
			return;
		}
		boolean hostile = !villager.level().getEntities(villager, villager.getBoundingBox().inflate(8, 3, 8), entity -> entity instanceof Enemy && entity.isAlive()).isEmpty();
		if (!hostile) {
			return;
		}
		NEXT_LINE.put("danger:" + villager.getUUID(), time + 20L * 45);
		VillagerBubble.show(villager, VillagerPersona.danger(villager));
		VillagerTalk.quiet(villager, 20L * 20);
	}

	private static void remarkSky(ServerPlayer player, List<Villager> around) {
		if (around.isEmpty()) {
			return;
		}
		boolean raining = player.level().isRaining();
		boolean night = isNight(player.level());
		UUID id = player.getUUID();
		boolean knownRain = WAS_RAINING.containsKey(id);
		boolean knownNight = WAS_NIGHT.containsKey(id);
		boolean rainStarted = knownRain && raining && !WAS_RAINING.get(id);
		boolean nightStarted = knownNight && night && !WAS_NIGHT.get(id);
		WAS_RAINING.put(id, raining);
		WAS_NIGHT.put(id, night);
		if (!rainStarted && !nightStarted) {
			return;
		}
		Villager villager = firstAwake(around);
		if (villager == null || VillagerBubble.isActive(villager.getUUID())) {
			return;
		}
		long time = player.level().getGameTime();
		if (time < NEXT_LINE.getOrDefault("sky:" + id, 0L)) {
			return;
		}
		NEXT_LINE.put("sky:" + id, time + WEATHER_QUIET);
		VillagerBubble.show(villager, rainStarted ? VillagerPersona.rain(villager) : VillagerPersona.night(villager));
	}

	private static void queue(long when, ServerPlayer player, Villager villager, String line, boolean ai) {
		if (PENDING.size() > 48) {
			return;
		}
		PENDING.add(new Pending(when, player.getUUID(), villager.getUUID(), villager.level().dimension(), line, ai));
	}

	private static void remember(ServerPlayer player, Villager villager) {
		NEXT_LINE.put("greet:" + pair(player, villager), player.level().getGameTime() + GREET_QUIET);
		VillagerTalk.quiet(villager, 20L * 30);
	}

	private static Villager firstAwake(List<Villager> around) {
		for (Villager villager : around) {
			if (!villager.isSleeping()) {
				return villager;
			}
		}
		return null;
	}

	private static List<Villager> nearby(ServerPlayer player, double range) {
		List<Villager> villagers = player.level().getEntitiesOfClass(
				Villager.class, player.getBoundingBox().inflate(range), Villager::isAlive);
		villagers.sort(Comparator.comparingDouble(player::distanceToSqr));
		return villagers;
	}

	private static boolean isOnlyGreeting(String text) {
		String stripped = FILLER.matcher(normalize(text)).replaceAll(" ");
		stripped = stripped.replaceAll("\\s+", " ").trim();
		return ONLY_GREETING.matcher(stripped).matches();
	}

	private static boolean isNight(Level level) {
		long day = level.getDefaultClockTime() % 24000;
		return day >= 13000 && day < 23000;
	}

	private static String normalize(String text) {
		return text.toLowerCase(Locale.ROOT).replaceAll("[^a-z']+", " ").replaceAll("\\s+", " ").trim();
	}

	private static String pair(ServerPlayer player, Villager villager) {
		return player.getUUID() + ":" + villager.getUUID();
	}

	private record Pending(long when, UUID playerId, UUID villagerId, net.minecraft.resources.ResourceKey<Level> dimension, String line, boolean ai) {
	}
}
