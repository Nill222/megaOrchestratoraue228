package com.example.iml.orchestrator.integration;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

final class BoundedEventQueue {
    private final int capacity;
    private final Deque<FanOutEvent> queue = new ArrayDeque<>();
    private long droppedTotal;
    private long pushedTotal;

    BoundedEventQueue(int capacity) {
        this.capacity = Math.max(1, capacity);
    }

    synchronized void offer(FanOutEvent event) {
        if (queue.size() >= capacity) {
            queue.pollFirst();
            droppedTotal++;
        }
        queue.offerLast(event);
        pushedTotal++;
        notifyAll();
    }

    synchronized FanOutEvent take() throws InterruptedException {
        while (queue.isEmpty()) {
            wait();
        }
        return queue.pollFirst();
    }

    synchronized List<FanOutEvent> snapshot() {
        return new ArrayList<>(queue);
    }

    synchronized int size() {
        return queue.size();
    }

    synchronized long droppedTotal() {
        return droppedTotal;
    }

    synchronized long pushedTotal() {
        return pushedTotal;
    }
}
