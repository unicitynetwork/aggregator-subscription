package org.unicitylabs.proxy.repository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.function.Function;

/**
 * Transaction manager for handling database transactions with proper isolation and rollback.
 * <p>
 * Features:
 * - Single transaction per operation (all repository calls share the connection)
 * - Automatic rollback on exceptions
 * - Connection cleanup via try-with-resources
 * - Configurable transaction timeout
 */
public class TransactionManager {
    private static final Logger logger = LoggerFactory.getLogger(TransactionManager.class);

    private final DatabaseConfig databaseConfig;

    public TransactionManager(DatabaseConfig databaseConfig) {
        this.databaseConfig = databaseConfig;
    }

    private static final int DEFAULT_TIMEOUT_SECONDS = 120;

    /**
     * Execute a function within a database transaction.
     * <p>
     * The connection is:
     * - Auto-committed disabled
     * - Read committed isolation level
     * - Rolled back on any exception
     * - Committed on success
     * - Automatically closed
     *
     * @param operation Function that receives a Connection and returns a result
     * @param <T> Return type
     * @return Result from the operation
     * @throws RuntimeException if operation fails or transaction cannot be established
     */
    public <T> T executeInTransaction(Function<Connection, T> operation) {
        return executeInTransaction(operation, DEFAULT_TIMEOUT_SECONDS);
    }

    /**
     * Execute a function within a database transaction with custom timeout.
     *
     * @param operation Function that receives a Connection and returns a result
     * @param timeoutSeconds Transaction timeout in seconds
     * @param <T> Return type
     * @return Result from the operation
     * @throws RuntimeException if operation fails or transaction cannot be established
     */
    public <T> T executeInTransaction(Function<Connection, T> operation, int timeoutSeconds) {
        long startTime = System.currentTimeMillis();

        try (Connection conn = databaseConfig.getConnection()) {
            // Configure connection for transaction
            conn.setAutoCommit(false);
            conn.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);

            // Set network timeout if supported (in milliseconds)
            try {
                conn.setNetworkTimeout(null, timeoutSeconds * 1000);
            } catch (SQLException | AbstractMethodError e) {
                // Some JDBC drivers don't support setNetworkTimeout, that's okay
                logger.debug("Network timeout not supported by JDBC driver");
            }

            try {
                // Execute the operation
                T result = operation.apply(conn);

                // Commit if successful
                conn.commit();

                long duration = System.currentTimeMillis() - startTime;
                logger.debug("Transaction committed successfully in {}ms", duration);

                return result;

            } catch (Exception e) {
                // Rollback on any exception
                try {
                    conn.rollback();
                    long duration = System.currentTimeMillis() - startTime;
                    logger.warn("Transaction rolled back after {}ms due to: {}",
                        duration, e.getMessage());
                } catch (SQLException rollbackEx) {
                    logger.error("Failed to rollback transaction", rollbackEx);
                    // Add rollback exception as suppressed
                    e.addSuppressed(rollbackEx);
                }

                // Re-throw the original exception
                if (e instanceof RuntimeException) {
                    throw (RuntimeException) e;
                } else {
                    throw new RuntimeException("Transaction failed", e);
                }
            }

        } catch (SQLException e) {
            throw new RuntimeException("Failed to obtain database connection", e);
        }
    }

    /**
     * Execute a runnable within a database transaction (no return value).
     *
     * @param operation Runnable that receives a Connection
     * @throws RuntimeException if operation fails or transaction cannot be established
     */
    public void executeInTransaction(ConnectionConsumer operation) {
        executeInTransaction(conn -> {
            try {
                operation.accept(conn);
                return null;
            } catch (Exception e) {
                if (e instanceof RuntimeException) {
                    throw (RuntimeException) e;
                } else {
                    throw new RuntimeException("Transaction operation failed", e);
                }
            }
        });
    }

    /**
     * Functional interface for operations that don't return a value.
     */
    @FunctionalInterface
    public interface ConnectionConsumer {
        void accept(Connection conn) throws Exception;
    }
}
