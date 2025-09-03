package com.unicity.proxy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import com.beust.jcommander.JCommander;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Main {
    private static final Logger logger = LoggerFactory.getLogger(Main.class);
    
    public static void main(String[] args) {
        ProxyConfig config = new ProxyConfig();
        JCommander commander = JCommander.newBuilder()
            .addObject(config)
            .programName("aggregator-subscription")
            .build();
        
        try {
            commander.parse(args);
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            commander.usage();
            System.exit(1);
        }
        
        if (config.isHelp()) {
            commander.usage();
            return;
        }
        
        configureLogging(config.getLogLevel());
        
        logger.info("Starting Aggregator Subscription Proxy");
        logger.info("Configuration: {}", config);
        
        try {
            ProxyServer server = new ProxyServer(config);
            server.start();
            
            server.awaitTermination();
        } catch (Exception e) {
            logger.error("Fatal error", e);
            System.exit(1);
        }
    }
    
    private static void configureLogging(String level) {
        LoggerContext loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();
        ch.qos.logback.classic.Logger rootLogger = loggerContext.getLogger("ROOT");
        
        try {
            Level logLevel = Level.toLevel(level, Level.INFO);
            rootLogger.setLevel(logLevel);
            logger.info("Log level set to: {}", logLevel);
        } catch (Exception e) {
            logger.warn("Invalid log level '{}', using INFO", level);
            rootLogger.setLevel(Level.INFO);
        }
    }
}