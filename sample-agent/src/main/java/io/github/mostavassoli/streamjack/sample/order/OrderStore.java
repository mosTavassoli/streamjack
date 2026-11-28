package io.github.mostavassoli.streamjack.sample.order;

import io.github.mostavassoli.streamjack.sample.domain.OrderRecord;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** The simulated order system: the latest record per order id, fed from the order topic. */
@ApplicationScoped
public class OrderStore {

    private final Map<String, OrderRecord> orders = new ConcurrentHashMap<>();

    public void put(OrderRecord order) {
        synchronized (orders) {
            orders.put(order.orderId(), order);
            orders.notifyAll();
        }
    }

    /**
     * The order, waiting up to {@code timeout} for it to arrive. Orders and tickets come from different topics, so a
     * ticket can be read before the order record that was written earlier.
     *
     * @return the order, or {@code null} if it did not arrive in time
     */
    public OrderRecord await(String orderId, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        synchronized (orders) {
            OrderRecord order = orders.get(orderId);
            while (order == null) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    return null;
                }
                orders.wait(Math.max(1, remaining / 1_000_000));
                order = orders.get(orderId);
            }
            return order;
        }
    }
}
