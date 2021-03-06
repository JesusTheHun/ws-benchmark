package com.example.wsbenchmark;

import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.server.LocalServerPort;
import org.springframework.test.context.junit4.SpringRunner;

import java.math.BigInteger;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@RunWith(SpringRunner.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public class WsBenchmarkApplicationTests {

	protected final Logger logger = LoggerFactory.getLogger(this.getClass());

	@LocalServerPort
	private int serverPort;

	public int getServerPort() {
		return serverPort;
	}

	private static String endpoint = "/ws-target-1";
	private static FailableServerSocketHandler webSocketHandler = new FailableServerSocketHandler("target1");

	@BeforeClass
	public static void initSuite() {
		TestWebSocketConfig.addWebSockethandler(webSocketHandler, endpoint);
	}

	public ScheduledFuture monitorWebSocketHandler(FailableServerSocketHandler webSocketHandler, long period, TimeUnit unit) {
		ScheduledExecutorService monitor = Executors.newSingleThreadScheduledExecutor();
		return monitor.scheduleAtFixedRate(() -> {
			logger.info("{} messages received", webSocketHandler.getReceivedMessageCount());
		}, period, period, unit);
	}

	@Test
	public void sendMessagesToOneServer() throws URISyntaxException, InterruptedException {

		////////////
		// CONFIG //
		////////////

		int messagesCountPerClient = 500;
		int clientsCount = 20;
		int sendPoolSize = 40;
		int totalMessagesCount = messagesCountPerClient * clientsCount;

		int messageSize = 307200;

		webSocketHandler.expectMessagesCount(totalMessagesCount);
		webSocketHandler.expectMessageSize(messageSize);

		String serverEndpoint = String.format("ws://localhost:%d/%s", getServerPort(), endpoint);
		byte[] message = new byte[messageSize];
		Arrays.fill(message, (byte) 8);

		///////////
		// SETUP //
		///////////

		Map<Integer, DemoClient> clients = new HashMap<>();

		for (int i = 0; i < clientsCount; i++) {
			URI uri = new URI(serverEndpoint);
			DemoClient client = new DemoClient(uri);
			client.connectBlocking();

			clients.put(i, client);
		}

		ThreadPoolExecutor tpe = new ThreadPoolExecutor(sendPoolSize, sendPoolSize, 1, TimeUnit.MINUTES, new LinkedBlockingQueue<>());
		tpe.prestartAllCoreThreads();

		logger.info("Ready");

		// Get some time to start visualvm
//        Thread.sleep(5);

		monitorWebSocketHandler(webSocketHandler, 1, TimeUnit.SECONDS);

		/////////
		// RUN //
		/////////

		LocalDateTime startTime = LocalDateTime.now();

		for (int i = 0; i < messagesCountPerClient; i++) {
			for (int c = 0; c < clientsCount; c++) {
				DemoClient client = clients.get(c);

				tpe.submit(() -> {
					client.send(message);
				});
			}
		}

		tpe.shutdown();
		tpe.awaitTermination(1, TimeUnit.MINUTES);

		webSocketHandler.awaitExpectation();

		LocalDateTime endTime = LocalDateTime.now();

		long duration = ChronoUnit.MILLIS.between(startTime, endTime);

		int messagesPerSecond = (int) (totalMessagesCount * 1000 / duration);

		BigInteger bandwidth = BigInteger.valueOf(messageSize)
				.multiply(BigInteger.valueOf(totalMessagesCount))
				.multiply(BigInteger.valueOf(1000))
				.divide(BigInteger.valueOf(duration))
				.divide(BigInteger.valueOf(1024*1024))
				;

		logger.info("Test duration : {} ms", duration);
		logger.info("{} messages per second", messagesPerSecond);
		logger.info("{} MB/s total", bandwidth);
		logger.info("{} MB/s per connection", bandwidth.divide(BigInteger.valueOf(clientsCount)));
	}

	@Test
	public void sendMessagesToOneServerAtFixedRate() throws URISyntaxException, InterruptedException, ExecutionException {

		////////////
		// CONFIG //
		////////////

		int testDurationInSeconds = 30;
		int sendMessageEveryXms = 1000 / 15;
		int clientsCount = 90;
		int sendPoolSize = 8;

		int messageSize = 307200;

		webSocketHandler.expectMessageSize(messageSize);

		String serverEndpoint = String.format("ws://localhost:%d/%s", getServerPort(), endpoint);
		byte[] message = new byte[messageSize];
		Arrays.fill(message, (byte) 8);

		///////////
		// SETUP //
		///////////

		Map<Integer, DemoClient> clients = new HashMap<>();

		for (int i = 0; i < clientsCount; i++) {
			URI uri = new URI(serverEndpoint);
			DemoClient client = new DemoClient(uri);
			client.connectBlocking();

			clients.put(i, client);
		}

		ThreadPoolExecutor tpe = new ThreadPoolExecutor(sendPoolSize, sendPoolSize, 1, TimeUnit.MINUTES, new LinkedBlockingQueue<>());
		tpe.prestartAllCoreThreads();

		logger.info("Ready");

		// Get some time to start visualvm
//        Thread.sleep(5);

		monitorWebSocketHandler(webSocketHandler, 1, TimeUnit.SECONDS);

		AtomicInteger sentMessageCount = new AtomicInteger(0);

		/////////
		// RUN //
		/////////

		LocalDateTime startTime = LocalDateTime.now();

		ScheduledExecutorService messageEmitter = Executors.newSingleThreadScheduledExecutor();
		ScheduledFuture messageEmitterScheduler = messageEmitter.scheduleAtFixedRate(() -> {

			for (int c = 0; c < clientsCount; c++) {
				DemoClient client = clients.get(c);

				tpe.submit(() -> {
					client.send(message);
				});

                sentMessageCount.incrementAndGet();
			}

		}, 0, sendMessageEveryXms, TimeUnit.MILLISECONDS);


		////////////////////////
		// TERMINATE THE TEST //
		////////////////////////

		ScheduledExecutorService executionTimeout = Executors.newSingleThreadScheduledExecutor();
		executionTimeout.scheduleAtFixedRate(() -> {
			messageEmitterScheduler.cancel(false);
		}, testDurationInSeconds, Integer.MAX_VALUE, TimeUnit.SECONDS);

		LocalDateTime emittionEnd = null;

		try {
			messageEmitterScheduler.get();
		} catch (CancellationException e) {
			// intented behavior
			emittionEnd = LocalDateTime.now();
			logger.info("{} messages have been emitted, termination", sentMessageCount.get());
		}

		tpe.shutdown();
		tpe.awaitTermination(1, TimeUnit.MINUTES);

		int totalMessagesCount = sentMessageCount.get();

		while(totalMessagesCount != webSocketHandler.getReceivedMessageCount()) {
			Thread.sleep(100);
		}

		LocalDateTime endTime = LocalDateTime.now();

		long duration = ChronoUnit.MILLIS.between(startTime, endTime);
		long delay = ChronoUnit.MILLIS.between(emittionEnd, endTime);

		/////////////
		// RESULTS //
		/////////////

		int messagePerSecond = (int) (totalMessagesCount * 1000 / duration);
		long heapAllocated = Runtime.getRuntime().totalMemory() / 1024 / 1024;

		BigInteger bandwidth = BigInteger.valueOf(messageSize)
			.multiply(BigInteger.valueOf(totalMessagesCount))
			.multiply(BigInteger.valueOf(1000))
			.divide(BigInteger.valueOf(duration))
			.divide(BigInteger.valueOf(1024*1024))
		;

		logger.info("Test duration : {} ms", duration);
		logger.info("Reception delay : {} ms, or {} messages", delay, (delay / sendMessageEveryXms) + 1);
		logger.info("{} messages per second", messagePerSecond);
		logger.info("{} MB/s total", bandwidth);
		logger.info("{} MB/s per client", bandwidth.divide(BigInteger.valueOf(clientsCount)));
		logger.info("Allocated heap : {} MB, or {} MB per client", heapAllocated, heapAllocated / clientsCount);
	}
}
