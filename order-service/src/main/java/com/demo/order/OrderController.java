package com.demo.order;

import com.demo.order.client.UserClient;
import com.demo.order.model.User;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class OrderController {

    @Autowired
    private UserClient userClient;

    @CircuitBreaker(name = "orderService", fallbackMethod = "fallback")
    @GetMapping("/order/{orderId}")
    public Map<String, Object> getOrder(@PathVariable Long orderId) {
        User user = userClient.getUser(orderId);
        return Map.of("orderId", orderId, "user", user);
    }

    public Map<String, Object> fallback(Long orderId, Throwable t) {
        return Map.of("orderId", orderId,
                "user", "fallback user",
                "reason", t.getClass().getSimpleName());
    }
}
