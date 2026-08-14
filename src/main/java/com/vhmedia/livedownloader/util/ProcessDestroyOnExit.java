package com.vhmedia.livedownloader.util;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Registers child processes for best-effort cleanup when the JVM exits.
 * {@code Process.destroyOnExit()} is not available on Java 21.
 */
public final class ProcessDestroyOnExit {

	private static final Set<Process> LIVE = ConcurrentHashMap.newKeySet();
	private static final AtomicBoolean HOOK_REGISTERED = new AtomicBoolean(false);

	private ProcessDestroyOnExit() {
	}

	public static void register(Process process) {
		if (process == null) {
			return;
		}
		ensureHook();
		LIVE.add(process);
		process.onExit().whenComplete((ignored, error) -> LIVE.remove(process));
	}

	private static void ensureHook() {
		if (!HOOK_REGISTERED.compareAndSet(false, true)) {
			return;
		}
		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			for (Process process : LIVE) {
				destroyQuietly(process);
			}
		}, "process-destroy-on-exit"));
	}

	private static void destroyQuietly(Process process) {
		if (!process.isAlive()) {
			return;
		}
		process.destroy();
		try {
			if (!process.waitFor(2, TimeUnit.SECONDS) && process.isAlive()) {
				process.destroyForcibly();
			}
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			if (process.isAlive()) {
				process.destroyForcibly();
			}
		}
	}
}
