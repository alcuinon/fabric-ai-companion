package net.nian.aicompanion;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Compact facts about a villager and whoever is standing nearby.
 * Built on the server thread, then sent with the next reply.
 */
final class VillagerContext {
	private static final String[] MANNERS = {
			"speaks softly",
			"speaks in a bright, quick way",
			"is blunt and a little grumpy",
			"is warm and nosy",
			"is proud and formal",
			"is shy until the topic is their work",
			"teases people gently",
			"worries easily and shows it"
	};
	private static final String[] HABITS = {
			"notices what people are holding",
			"comments on the weather",
			"remembers favors and slights",
			"brags when a trade is a good one",
			"gets nervous when monsters are close",
			"asks where someone has been",
			"haggles even during small talk",
			"treats regulars like neighbors"
	};

	private VillagerContext() {
	}

	static String instructions(Villager villager, ServerPlayer focus) {
		String job = VillagerTalk.professionPath(villager);
		StringBuilder text = new StringBuilder();
		text.append("You are ").append(VillagerTalk.displayName(villager))
				.append(", a level ").append(villager.getVillagerData().level()).append(' ')
				.append(job.replace('_', ' ')).append(" villager. ");
		text.append(flavor(job)).append(' ');
		text.append("Your manner: ").append(traits(villager)).append(". ");
		text.append("Many players share this one life with you. Each line starts with the speaker's name. Remember them. ");
		text.append(scene(villager, focus));
		text.append(" Reply in one or two short spoken sentences. Use these facts when they matter. Never mention being an AI.");
		if (text.length() > 1400) {
			return text.substring(0, 1400);
		}
		return text.toString();
	}

	static String impulse(Villager villager, ServerPlayer player) {
		String name = player.getName().getString();
		if (hostileNearby(villager)) {
			return "a hostile mob is close, and you want to warn " + name;
		}
		String trade = tradeTouching(villager, player.getMainHandItem());
		if (trade != null) {
			return name + " is carrying something from your stall (" + trade + "), and you want to mention it";
		}
		if (villager.level() instanceof ServerLevel level && level.isRaining()) {
			return "the weather has turned, and you want to say so to " + name;
		}
		if (isNight(villager)) {
			return "night is here, and you want to mention it to " + name;
		}
		String held = stackName(player.getMainHandItem());
		if (!held.isEmpty()) {
			return name + " is holding " + held + ", and you want to remark on it";
		}
		return "you feel like greeting " + name;
	}

	private static String scene(Villager villager, ServerPlayer focus) {
		StringBuilder text = new StringBuilder();
		text.append("Right now it is ").append(timeOfDay(villager)).append(" and ").append(weather(villager)).append(". ");
		text.append("Your goods: ").append(trades(villager)).append(". ");
		text.append(focus.getName().getString()).append(" is here, ").append(gear(focus)).append(". ");
		String others = otherPlayers(villager, focus);
		if (!others.isEmpty()) {
			text.append("Also nearby: ").append(others).append(". ");
		}
		String creatures = creatures(villager);
		if (!creatures.isEmpty()) {
			text.append("Creatures around you: ").append(creatures).append(". ");
		}
		return text.toString();
	}

	private static String trades(Villager villager) {
		MerchantOffers offers = villager.getOffers();
		if (offers == null || offers.isEmpty()) {
			return "nothing stocked";
		}
		StringBuilder text = new StringBuilder();
		int shown = 0;
		for (MerchantOffer offer : offers) {
			if (shown == 5) {
				break;
			}
			if (shown > 0) {
				text.append("; ");
			}
			text.append(price(offer)).append(" -> ").append(stackName(offer.getResult()));
			if (offer.isOutOfStock()) {
				text.append(" (sold out)");
			} else {
				text.append(" (").append(Math.max(0, offer.getMaxUses() - offer.getUses())).append(" left)");
			}
			shown++;
		}
		return text.toString();
	}

	private static String price(MerchantOffer offer) {
		String cost = stackName(offer.getCostA());
		if (!offer.getCostB().isEmpty()) {
			cost = cost + " + " + stackName(offer.getCostB());
		}
		return cost;
	}

	private static String tradeTouching(Villager villager, ItemStack held) {
		if (held.isEmpty()) {
			return null;
		}
		Item item = held.getItem();
		MerchantOffers offers = villager.getOffers();
		if (offers == null) {
			return null;
		}
		for (MerchantOffer offer : offers) {
			if (offer.getCostA().getItem() == item || offer.getCostB().getItem() == item || offer.getResult().getItem() == item) {
				return price(offer) + " -> " + stackName(offer.getResult());
			}
		}
		return null;
	}

	private static String gear(ServerPlayer player) {
		StringBuilder text = new StringBuilder();
		String main = stackName(player.getMainHandItem());
		String off = stackName(player.getOffhandItem());
		if (!main.isEmpty()) {
			text.append("holding ").append(main);
		}
		if (!off.isEmpty()) {
			if (!text.isEmpty()) {
				text.append(", ");
			}
			text.append("offhand ").append(off);
		}
		String armor = armor(player);
		if (!armor.isEmpty()) {
			if (!text.isEmpty()) {
				text.append(", ");
			}
			text.append("wearing ").append(armor);
		}
		return text.isEmpty() ? "with empty hands" : text.toString();
	}

	private static String armor(ServerPlayer player) {
		StringBuilder text = new StringBuilder();
		EquipmentSlot[] slots = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
		for (EquipmentSlot slot : slots) {
			String name = stackName(player.getItemBySlot(slot));
			if (name.isEmpty()) {
				continue;
			}
			if (!text.isEmpty()) {
				text.append(", ");
			}
			text.append(name);
		}
		return text.toString();
	}

	private static String otherPlayers(Villager villager, ServerPlayer focus) {
		if (!(villager.level() instanceof ServerLevel level)) {
			return "";
		}
		StringBuilder text = new StringBuilder();
		int shown = 0;
		for (ServerPlayer other : level.players()) {
			if (other == focus || other.distanceToSqr(villager) > 100) {
				continue;
			}
			if (shown == 3) {
				break;
			}
			if (shown > 0) {
				text.append(", ");
			}
			text.append(other.getName().getString());
			shown++;
		}
		return text.toString();
	}

	private static String creatures(Villager villager) {
		Map<String, Integer> counts = new LinkedHashMap<>();
		for (Entity entity : villager.level().getEntities(villager, villager.getBoundingBox().inflate(10, 4, 10),
				nearby -> nearby != villager && nearby.isAlive() && !(nearby instanceof ServerPlayer))) {
			counts.merge(entity.getName().getString(), 1, Integer::sum);
			if (counts.size() >= 6) {
				break;
			}
		}
		if (counts.isEmpty()) {
			return "";
		}
		StringBuilder text = new StringBuilder();
		for (Map.Entry<String, Integer> entry : counts.entrySet()) {
			if (!text.isEmpty()) {
				text.append(", ");
			}
			text.append(entry.getValue()).append(' ').append(entry.getKey());
		}
		return text.toString();
	}

	private static boolean hostileNearby(Villager villager) {
		for (Entity entity : villager.level().getEntities(villager, villager.getBoundingBox().inflate(8, 3, 8), entity -> entity instanceof Enemy)) {
			if (entity.isAlive()) {
				return true;
			}
		}
		return false;
	}

	private static String weather(Villager villager) {
		if (villager.level() instanceof ServerLevel level && level.isThundering()) {
			return "a thunderstorm";
		}
		if (villager.level().isRaining()) {
			return "rain";
		}
		return "clear skies";
	}

	private static boolean isNight(Villager villager) {
		long day = villager.level().getDefaultClockTime() % 24000;
		return day >= 13000 && day < 23000;
	}

	private static String timeOfDay(Villager villager) {
		long day = villager.level().getDefaultClockTime() % 24000;
		if (day < 1000 || day >= 23000) {
			return "dawn";
		}
		if (day < 12000) {
			return "day";
		}
		if (day < 13000) {
			return "dusk";
		}
		return "night";
	}

	private static String traits(Villager villager) {
		long bits = villager.getUUID().getLeastSignificantBits() ^ villager.getUUID().getMostSignificantBits();
		String manner = MANNERS[Math.floorMod((int) bits, MANNERS.length)];
		String habit = HABITS[Math.floorMod((int) (bits >>> 8), HABITS.length)];
		return manner + ", and " + habit;
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

	private static String stackName(ItemStack stack) {
		if (stack == null || stack.isEmpty()) {
			return "";
		}
		String name = stack.getHoverName().getString();
		if (stack.getCount() > 1) {
			return stack.getCount() + " " + name;
		}
		return name;
	}
}
