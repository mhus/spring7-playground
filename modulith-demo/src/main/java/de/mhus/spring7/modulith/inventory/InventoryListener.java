package de.mhus.spring7.modulith.inventory;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import de.mhus.spring7.modulith.order.OrderPlaced;

@Component
class InventoryListener {

    @EventListener
    void on(OrderPlaced event) {
        IO.println("[inventory] reducing stock of " + event.sku() + " by " + event.quantity());
    }
}
