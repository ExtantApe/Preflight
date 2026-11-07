package com.extantape.preflight.plugin;

/** The one piece of the Bukkit scheduler the runner needs, split out so it can run in unit tests. */
interface TickScheduler {
    Task runLater(long ticks, Runnable action);

    interface Task {
        void cancel();
    }
}
