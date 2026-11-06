package net.nian.aicompanion;

import com.mojang.math.Transformation;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A small text display above a villager. It faces the camera and follows them until it expires.
 */
public final class VillagerBubble {
	private static final String TAG = "aicompanion_bubble";
	private static final String UNTIL_PREFIX = "until:";
	private static final int MIN_TICKS = 20 * 5;
	private static final int MAX_TICKS = 20 * 12;
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
			return;
		}

		Display.TextDisplay display = new Display.TextDisplay(EntityType.TEXT_DISPLAY, level);
		write(display, text, expire);
		display.setLineWidth(108);
		display.setBackgroundColor(0xF2FFF6E8);
		display.setFlags((byte) 0);
		display.setBillboardConstraints(Display.BillboardConstraints.CENTER);
		display.setTransformation(new Transformation(
				new Vector3f(0f, 0.15f, 0f),
				new Quaternionf(),
				new Vector3f(0.45f, 0.45f, 0.45f),
				new Quaternionf()));
		display.setShadowRadius(0f);
		display.setViewRange(4f);
		display.setPosRotInterpolationDuration(2);
		display.setNoGravity(true);
		display.addTag(TAG);
		display.setPos(villager.getX(), headY(villager), villager.getZ());
		if (!level.addFreshEntity(display)) {
			return;
		}
		ACTIVE.put(villager.getUUID(), new LiveBubble(display.getUUID(), villager.getUUID(), level.dimension(), expire));
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
			double x = villager.getX();
			double y = headY(villager);
			double z = villager.getZ();
			if (display.distanceToSqr(x, y, z) > 0.0004) {
				display.setPos(x, y, z);
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
		display.setText(Component.literal(text).withStyle(ChatFormatting.DARK_GRAY));
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

	private static double headY(Villager villager) {
		return villager.getY() + villager.getBbHeight() + 0.25;
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
