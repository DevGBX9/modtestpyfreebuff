package com.example;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.TickTask;
import net.minecraft.server.level.ServerLevel;

/**
 * Small helper to run a task after a delay on the server thread,
 * backed by the vanilla {@link MinecraftServer#tell(TickTask)} queue.
 */
public final class ServerTickScheduler {
	private ServerTickScheduler() {
	}

	public static void schedule(ServerLevel level, int delayTicks, Runnable task) {
		MinecraftServer server = level.getServer();
		server.tell(new TickTask(server.getTickCount() + Math.max(1, delayTicks), task));
	}
}
