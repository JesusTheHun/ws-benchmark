package com.example.wsbenchmark;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

import java.util.HashMap;
import java.util.Map;

@Configuration
@EnableWebSocket
public class TestWebSocketConfig implements WebSocketConfigurer {

    private static Map<String, AbstractWebSocketHandler> toRegister = new HashMap<>();

    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        for (Map.Entry<String, AbstractWebSocketHandler> entry : toRegister.entrySet()) {
            registry.addHandler(entry.getValue(), entry.getKey()).setAllowedOrigins("*");
        }
    }

    public static void addWebSockethandler(AbstractWebSocketHandler webSocketHandler, String endpoint) {
        toRegister.put(endpoint, webSocketHandler);
    }
}
