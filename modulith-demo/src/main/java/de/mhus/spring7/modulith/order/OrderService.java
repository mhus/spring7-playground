package de.mhus.spring7.modulith.order;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

@Service
public class OrderService {

    private final ApplicationEventPublisher events;

    public OrderService(ApplicationEventPublisher events) {
        this.events = events;
    }

    public void placeOrder(String sku, int quantity) {
        IO.println("[order] placing order for " + quantity + "x " + sku);
        events.publishEvent(new OrderPlaced(sku, quantity));
    }
}
