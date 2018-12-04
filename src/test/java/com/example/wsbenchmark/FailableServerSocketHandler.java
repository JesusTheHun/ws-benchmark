package com.example.wsbenchmark;

import org.apache.commons.lang.exception.ExceptionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

public class FailableServerSocketHandler extends AbstractWebSocketHandler {

    protected int maxMessageSize = 26214400;

    protected Logger logger;

    private ConcurrentHashMap<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private AtomicInteger receivedMessageCount = new AtomicInteger(0);
    private CountDownLatch expectation = new CountDownLatch(0);

    private boolean failure = false;
    private Integer expectedMessageSize = null;

    public FailableServerSocketHandler(String name) {
        logger = LoggerFactory.getLogger(FailableServerSocketHandler.class.getSimpleName() + "-" + name);
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) throws Exception {
        receivedMessageCount.incrementAndGet();
        expectation.countDown();

        if (expectedMessageSize != null && expectedMessageSize != message.getPayloadLength()) {
            logger.error("Unexpected message size of {} bytes", message.getPayloadLength());
        }

        logger.trace("Message received, {} bytes", message.getPayloadLength());
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        if (failure) {
            try {
                session.close(CloseStatus.SERVER_ERROR);
            } catch (IOException e) {
                logger.warn(ExceptionUtils.getStackTrace(e));
            }

            return;
        }

        sessions.put(session.getId(), session);
        session.setBinaryMessageSizeLimit(maxMessageSize);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        sessions.remove(session.getId());

        if (status != CloseStatus.NORMAL && status != CloseStatus.GOING_AWAY) {
            logger.warn("Connection dropped with status {}", status);
        }
    }

    public void emulateFailure() throws IOException {
        failure = true;

        for (WebSocketSession s : sessions.values()) {
            s.close(CloseStatus.SERVER_ERROR);
        }
    }

    public void recoverFailure() {
        failure = false;
    }

    public int getSessionsCount() {
        return sessions.size();
    }

    public int getReceivedMessageCount() {
        return receivedMessageCount.get();
    }

    public void expectMessagesCount(int count) {
        expectation = new CountDownLatch(count);
    }

    public void awaitExpectation() throws InterruptedException {
        expectation.await();
    }

    public void expectMessageSize(int size) {
        expectedMessageSize = size;
    }
}