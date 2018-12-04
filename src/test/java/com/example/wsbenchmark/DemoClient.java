package com.example.wsbenchmark;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;

public class DemoClient extends WebSocketClient {

    private final Logger logger = LoggerFactory.getLogger(DemoClient.class);

    public DemoClient(URI serverUri) {
        super(serverUri);
    }

    @Override
    public void onOpen(ServerHandshake serverHandshake) {
        logger.debug("Connection open");
    }

    @Override
    public void onMessage(String s) {
        logger.debug("Message received : {}", s);
    }

    @Override
    public void onClose(int i, String s, boolean b) {
        logger.debug("Connection closed");
    }

    @Override
    public void onError(Exception e) {
        logger.error("Something went wrong");
    }
}
