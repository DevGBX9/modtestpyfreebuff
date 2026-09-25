package com.example;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/**
 * Small helper to run a task after a delay of whole server ticks.
 *
 * <p>MinecraftServer no longer exposes a per-tick task queue, so we implement
 * tick-accurate delays by re-submitting the task once per tick through
 * {@link MinecraftServer#execute(Runnable)} (which runs on the server thread
 * during {@code runServer} iteration) and decrementing a counter each time.
 */
public final class ServerTickScheduler {
	private ServerTickScheduler() {
	}

	public static void schedule(ServerLevel level, int delayTicks, Runnable task) {
		schedule(level.getServer(), delayTicks, task);
	}

	public static void schedule(MinecraftServer server, int delayTicks, Runnable task) {
		server.execute(new Runnable() {
			private int remaining = Math.max(1, delayTicks);

			@Override
			public void run() {
				if (--remaining > 0) {
					server.execute(this);
				} else {
					task.run();
					remaining = 0;
				}
			}
		});
	}
}
