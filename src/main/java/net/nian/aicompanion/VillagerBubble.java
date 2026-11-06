package net.nian.aicompanion;

import com.mojang.math.Transformation;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A text bubble on the villager. It stays upright, faces the player, and follows them.
 */
public final class VillagerBubble {
	private static final String TAG = "aicompanion_bubble";
	private static final String UNTIL_PREFIX = "until:";
	private static final int MIN_TICKS = 20 * 5;
	private static final int MAX_TICKS = 20 * 12;
	private static final FontDescription BUBBLE_FONT = new FontDescription.Resource(Identifier.fromNamespaceAndPath("minecraft", "uniform"));
	private static final Map<UUID, LiveBubble> ACTIVE = new ConcurrentHashMap<>();

	private static int tick;

	private VillagerBubble() {
	}

	public static void register() {
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			tick++;
			for (ServerLevel level : server.getAllLevels()) {
				follow(level);
				if (tick % 100 == 0) {
					sweep(level);
				}
			}
		});
	}

	public static void show(Villager villager, String text) {
		if (!(villager.level() instanceof ServerLevel level) || text == null || text.isBlank()) {
			return;
		}
		long expire = level.getGameTime() + duration(text);
		LiveBubble current = ACTIVE.get(villager.getUUID());
		if (current != null && level.getEntity(current.displayId) instanceof Display.TextDisplay existing) {
			write(existing, text, expire);
			current.expire = expire;
			speak(level, villager);
			return;
		}

		Display.TextDisplay display = new Display.TextDisplay(EntityType.TEXT_DISPLAY, level);
		write(display, text, expire);
		display.setLineWidth(240);
		display.setBackgroundColor(0xF4FFF8EE);
		display.setTextOpacity((byte) -1);
		display.setFlags((byte) 0);
		display.setBillboardConstraints(Display.BillboardConstraints.VERTICAL);
		display.setTransformation(new Transformation(
				new Vector3f(0f, 0f, 0f),
				new Quaternionf(),
				new Vector3f(0.62f, 0.62f, 0.62f),
				new Quaternionf()));
		display.setShadowRadius(0f);
		display.setViewRange(4f);
		display.setPosRotInterpolationDuration(2);
		display.setNoGravity(true);
		display.addTag(TAG);
		Vec3 anchor = anchor(villager);
		display.setPos(anchor.x, anchor.y, anchor.z);
		if (!level.addFreshEntity(display)) {
			return;
		}
		speak(level, villager);
		ACTIVE.put(villager.getUUID(), new LiveBubble(display.getUUID(), villager.getUUID(), level.dimension(), expire));
	}

	public static boolean isActive(UUID villagerId) {
		return ACTIVE.containsKey(villagerId);
	}

	private static void follow(ServerLevel level) {
		long now = level.getGameTime();
		Iterator<LiveBubble> bubbles = ACTIVE.values().iterator();
		while (bubbles.hasNext()) {
			LiveBubble bubble = bubbles.next();
			if (!bubble.dimension.equals(level.dimension())) {
				continue;
			}
			Entity displayEntity = level.getEntity(bubble.displayId);
			if (now >= bubble.expire) {
				if (displayEntity != null) {
					displayEntity.discard();
				}
				bubbles.remove();
				continue;
			}
			if (!(displayEntity instanceof Display.TextDisplay display)) {
				continue;
			}
			if (!(level.getEntity(bubble.villagerId) instanceof Villager villager) || !villager.isAlive()) {
				display.discard();
				bubbles.remove();
				continue;
			}
			Vec3 anchor = anchor(villager);
			if (display.distanceToSqr(anchor) > 0.0004) {
				display.setPos(anchor.x, anchor.y, anchor.z);
			}
		}
	}

	private static void sweep(ServerLevel level) {
		long now = level.getGameTime();
		for (ServerPlayer player : level.players()) {
			AABB box = player.getBoundingBox().inflate(48);
			for (Entity entity : level.getEntities(player, box, entity -> entity instanceof Display.TextDisplay && entity.entityTags().contains(TAG))) {
				if (expired(entity, now)) {
					entity.discard();
				}
			}
		}
	}

	private static void write(Display.TextDisplay display, String text, long expire) {
		display.setText(Component.literal(pad(text)).withStyle(style -> style
				.withFont(BUBBLE_FONT)
				.withColor(ChatFormatting.DARK_GRAY)));
		for (String tag : java.util.List.copyOf(display.entityTags())) {
			if (tag.startsWith(UNTIL_PREFIX)) {
				display.removeTag(tag);
			}
		}
		display.addTag(UNTIL_PREFIX + expire);
	}

	private static boolean expired(Entity entity, long now) {
		for (String tag : entity.entityTags()) {
			if (tag.startsWith(UNTIL_PREFIX)) {
				try {
					return now >= Long.parseLong(tag.substring(UNTIL_PREFIX.length()));
				} catch (NumberFormatException ignored) {
					return true;
				}
			}
		}
		return true;
	}

	private static Vec3 anchor(Villager villager) {
		double x = villager.getX();
		double y = villager.getY();
		double z = villager.getZ();
		double height = villager.getBbHeight();
		Vec3 look = villager.getLookAngle();
		double horizontal = Math.sqrt(look.x * look.x + look.z * look.z);
		double forwardX = horizontal < 1.0E-4 ? 0 : look.x / horizontal;
		double forwardZ = horizontal < 1.0E-4 ? 1 : look.z / horizontal;
		double sideX = -forwardZ;
		double sideZ = forwardX;
		return switch (ConfigLoader.bubblePosition()) {
			case "head", "upper", "upper_head" -> new Vec3(x, y + height + 0.38, z);
			case "left" -> new Vec3(x - sideX * 0.75, y + height * 0.62, z - sideZ * 0.75);
			case "right" -> new Vec3(x + sideX * 0.75, y + height * 0.62, z + sideZ * 0.75);
			default -> new Vec3(x + forwardX * 0.68, y + height * 0.58, z + forwardZ * 0.68);
		};
	}

	private static String pad(String text) {
		String[] words = text.trim().split("\\s+");
		StringBuilder line = new StringBuilder();
		StringBuilder block = new StringBuilder("\n");
		for (String word : words) {
			if (word.isEmpty()) {
				continue;
			}
			if (!line.isEmpty() && line.length() + 1 + word.length() > 22) {
				block.append("  ").append(line).append("  \n");
				line.setLength(0);
			}
			if (!line.isEmpty()) {
				line.append(' ');
			}
			line.append(word);
		}
		if (!line.isEmpty()) {
			block.append("  ").append(line).append("  \n");
		}
		return block.toString();
	}

	private static void speak(ServerLevel level, Villager villager) {
		int step = Math.floorMod(villager.getUUID().hashCode(), 5);
		float pitch = 0.9f + step * 0.035f;
		level.playSound(null, villager, SoundEvents.VILLAGER_AMBIENT, SoundSource.NEUTRAL, 0.9f, pitch);
	}

	private static int duration(String text) {
		return Math.min(MAX_TICKS, MIN_TICKS + text.length() * 2);
	}

	private static final class LiveBubble {
		private final UUID displayId;
		private final UUID villagerId;
		private final ResourceKey<Level> dimension;
		private long expire;

		private LiveBubble(UUID displayId, UUID villagerId, ResourceKey<Level> dimension, long expire) {
			this.displayId = displayId;
			this.villagerId = villagerId;
			this.dimension = dimension;
			this.expire = expire;
		}
	}
}
