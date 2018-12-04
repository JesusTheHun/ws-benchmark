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
	public void sendSamplesToOneServer() throws URISyntaxException, InterruptedException {

		////////////
		// CONFIG //
		////////////

		int samplesCountPerClient = 500;
		int clientsCount = 20;
		int sendPoolSize = 40;
		int totalSamplesCount = samplesCountPerClient * clientsCount;

		int sampleSize = 307200;

		webSocketHandler.expectMessagesCount(totalSamplesCount);
		webSocketHandler.expectMessageSize(sampleSize);

		String serverEndpoint = String.format("ws://localhost:%d/%s", getServerPort(), endpoint);
		byte[] message = new byte[sampleSize];
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

		for (int i = 0; i < samplesCountPerClient; i++) {
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

		int samplesPerSecond = (int) (totalSamplesCount * 1000 / duration);

		BigInteger bandwidth = BigInteger.valueOf(sampleSize)
				.multiply(BigInteger.valueOf(totalSamplesCount))
				.multiply(BigInteger.valueOf(1000))
				.divide(BigInteger.valueOf(duration))
				.divide(BigInteger.valueOf(1024*1024))
				;

		logger.info("Test duration : {} ms", duration);
		logger.info("{} samples per second", samplesPerSecond);
		logger.info("{} MB/s total", bandwidth);
		logger.info("{} MB/s per connection", bandwidth.divide(BigInteger.valueOf(clientsCount)));
	}

	@Test
	public void sendSamplesToOneServerAtFixedRate() throws URISyntaxException, InterruptedException, ExecutionException {

		////////////
		// CONFIG //
		////////////

		int testDurationInSeconds = 180;
		int sendSampleEveryXms = 1000 / 15;
		int clientsCount = 500;
		int sendPoolSize = 4;

		int sampleSize = 307200;

		webSocketHandler.expectMessageSize(sampleSize);

		String serverEndpoint = String.format("ws://localhost:%d/%s", getServerPort(), endpoint);
		byte[] message = new byte[sampleSize];
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

		ScheduledExecutorService sampleEmitter = Executors.newSingleThreadScheduledExecutor();
		ScheduledFuture sampleEmitterScheduler = sampleEmitter.scheduleAtFixedRate(() -> {

			for (int c = 0; c < clientsCount; c++) {
				DemoClient client = clients.get(c);

				tpe.submit(() -> {
					sentMessageCount.incrementAndGet();
					client.send(message);
				});
			}

		}, 0, sendSampleEveryXms, TimeUnit.MILLISECONDS);


		////////////////////////
		// TERMINATE THE TEST //
		////////////////////////

		ScheduledExecutorService executionTimeout = Executors.newSingleThreadScheduledExecutor();
		executionTimeout.scheduleAtFixedRate(() -> {
			sampleEmitterScheduler.cancel(false);
		}, testDurationInSeconds, Integer.MAX_VALUE, TimeUnit.SECONDS);

		LocalDateTime emittionEnd = null;

		try {
			sampleEmitterScheduler.get();
		} catch (CancellationException e) {
			// intented behavior
			emittionEnd = LocalDateTime.now();
			logger.info("{} samples have been emitted, termination", sentMessageCount.get());
		}

		tpe.shutdown();
		tpe.awaitTermination(1, TimeUnit.MINUTES);

		int totalSamplesCount = sentMessageCount.get();

		while(sentMessageCount.get() != webSocketHandler.getReceivedMessageCount()) {
			Thread.sleep(100);
		}

		LocalDateTime endTime = LocalDateTime.now();

		long duration = ChronoUnit.MILLIS.between(startTime, endTime);
		long delay = ChronoUnit.MILLIS.between(emittionEnd, endTime);

		/////////////
		// RESULTS //
		/////////////

		int samplesPerSecond = (int) (totalSamplesCount * 1000 / duration);

		BigInteger bandwidth = BigInteger.valueOf(sampleSize)
				.multiply(BigInteger.valueOf(totalSamplesCount))
				.multiply(BigInteger.valueOf(1000))
				.divide(BigInteger.valueOf(duration))
				.divide(BigInteger.valueOf(1024*1024))
				;

		logger.info("Test duration : {} ms", duration);
		logger.info("Reception delay : {} ms, or {} samples", delay, (delay / sendSampleEveryXms) + 1);
		logger.info("{} samples per second", samplesPerSecond);
		logger.info("{} MB/s total", bandwidth);
		logger.info("{} MB/s per client", bandwidth.divide(BigInteger.valueOf(clientsCount)));
	}
}
