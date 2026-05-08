package com.example.iml.orchestrator.integration;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.util.Map;

final class FanOutCoordinator implements AutoCloseable {
    private static final Logger log = LogManager.getLogger(FanOutCoordinator.class);

    private final RobotTcpPublisher robotPublisher;
    private final ClientHttpStubServer clientServer;

    private FanOutCoordinator(RobotTcpPublisher robotPublisher, ClientHttpStubServer clientServer) {
        this.robotPublisher = robotPublisher;
        this.clientServer = clientServer;
    }

    static FanOutCoordinator fromConfig(Map<String, Object> root) {
        @SuppressWarnings("unchecked")
        Map<String, Object> fanout = (Map<String, Object>) root.get("fanout");
        if (fanout == null) {
            throw new IllegalStateException("fanout config section is missing");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> robotCfg = (Map<String, Object>) fanout.get("robot_tcp");
        @SuppressWarnings("unchecked")
        Map<String, Object> clientCfg = (Map<String, Object>) fanout.get("client_http");
        if (robotCfg == null || clientCfg == null) {
            throw new IllegalStateException("fanout.robot_tcp or fanout.client_http is missing");
        }

        String robotHost = String.valueOf(robotCfg.getOrDefault("host", "127.0.0.1"));
        int robotPort = toInt(robotCfg.get("port"), 9999);
        int robotQueue = toInt(robotCfg.get("queue_size"), 256);
        int robotConnectTimeout = toInt(robotCfg.get("connect_timeout_ms"), 300);
        int robotWriteTimeout = toInt(robotCfg.get("write_timeout_ms"), 300);

        String clientHost = String.valueOf(clientCfg.getOrDefault("host", "127.0.0.1"));
        int clientPort = toInt(clientCfg.get("port"), 8088);
        int clientQueue = toInt(clientCfg.get("queue_size"), 128);
        int clientDelay = toInt(clientCfg.get("artificial_delay_ms"), 0);

        RobotTcpPublisher robotPublisher =
                new RobotTcpPublisher(robotHost, robotPort, robotConnectTimeout, robotWriteTimeout, robotQueue);
        try {
            ClientHttpStubServer clientServer = new ClientHttpStubServer(clientHost, clientPort, clientQueue, clientDelay);
            log.info("fanout started robot={}:{} client_http={}:{} delayMs={}",
                    robotHost, robotPort, clientHost, clientPort, clientDelay);
            return new FanOutCoordinator(robotPublisher, clientServer);
        } catch (IOException e) {
            robotPublisher.close();
            throw new IllegalStateException("failed to start client http stub", e);
        }
    }

    void publish(FanOutEvent event) {
        robotPublisher.publish(event);
        clientServer.publish(event);
    }

    String metricsSummary() {
        return "robot.queueDepth=" + robotPublisher.queueDepth()
                + " robot.dropped=" + robotPublisher.droppedTotal()
                + " client.queueDepth=" + clientServer.queueDepth()
                + " client.dropped=" + clientServer.droppedTotal();
    }

    @Override
    public void close() {
        clientServer.close();
        robotPublisher.close();
    }

    private static int toInt(Object value, int fallback) {
        if (value instanceof Number n) return n.intValue();
        if (value == null) return fallback;
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }
}
