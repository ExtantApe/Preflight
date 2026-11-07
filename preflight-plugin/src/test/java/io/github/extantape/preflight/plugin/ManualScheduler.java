package io.github.extantape.preflight.plugin;

import java.util.ArrayList;
import java.util.List;

/** A scheduler the test advances by hand, one tick at a time. */
final class ManualScheduler implements TickScheduler {
    private final List<Entry> entries = new ArrayList<>();
    private long now;

    @Override
    public Task runLater(long ticks, Runnable action) {
        Entry entry = new Entry(now + ticks, action);
        entries.add(entry);
        return () -> entries.remove(entry);
    }

    void tick() {
        now++;
        List<Entry> due = entries.stream().filter(entry -> entry.at <= now).toList();
        entries.removeAll(due);
        due.forEach(entry -> entry.action.run());
    }

    void runUntilIdle(int maxTicks) {
        for (int i = 0; i < maxTicks && !entries.isEmpty(); i++) {
            tick();
        }
    }

    private record Entry(long at, Runnable action) {}
}
