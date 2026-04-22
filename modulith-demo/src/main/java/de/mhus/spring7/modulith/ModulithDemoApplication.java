package de.mhus.spring7.modulith;

import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.modulith.Modulithic;

import de.mhus.spring7.modulith.order.OrderService;

@Modulithic
@SpringBootApplication
public class ModulithDemoApplication {

    static void main(String[] args) {
        SpringApplication.run(ModulithDemoApplication.class, args);
    }

    @Bean
    CommandLineRunner demo(OrderService orders) {
        return args -> orders.placeOrder("coffee-beans", 3);
    }
}
