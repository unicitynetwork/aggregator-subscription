package org.unicitylabs.proxy.repository;

import org.unicitylabs.proxy.model.PaymentSessionStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for managing payment sessions in the database.
 */
public class PaymentRepository {
    private static final Logger logger = LoggerFactory.getLogger(PaymentRepository.class);

    private static final String CREATE_SESSION_SQL = """
        INSERT INTO payment_sessions (
            id, api_key, payment_address, receiver_nonce,
            target_plan_id, amount_required, expires_at, should_create_key, refund_amount
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        """;

    private static final String FIND_BY_ID_SQL = """
        SELECT id, api_key, payment_address, receiver_nonce,
               status, target_plan_id, amount_required,
               token_received, created_at, completed_at, expires_at, should_create_key, refund_amount,
               request_id, completion_request_json, completion_request_timestamp
        FROM payment_sessions
        WHERE id = ?
        """;

    private static final String FIND_BY_ID_AND_LOCK_SQL = """
        SELECT id, api_key, payment_address, receiver_nonce,
               status, target_plan_id, amount_required,
               token_received, created_at, completed_at, expires_at, should_create_key, refund_amount,
               request_id, completion_request_json, completion_request_timestamp
        FROM payment_sessions
        WHERE id = ?
        FOR UPDATE NOWAIT
        """;

    private static final String UPDATE_STATUS_SQL = """
        UPDATE payment_sessions
        SET status = ?::varchar, completed_at = ?, token_received = ?
        WHERE id = ? AND status = 'pending'
        """;

    private static final String DELETE_BY_API_KEY_SQL = """
        DELETE FROM payment_sessions
        WHERE api_key = ?
        """;

    private static final String DELETE_BY_PRICING_PLAN = """
        DELETE FROM payment_sessions
        WHERE target_plan_id = ?
        """;

    private static final String UPDATE_SESSION_API_KEY_SQL = """
        UPDATE payment_sessions
        SET api_key = ?
        WHERE id = ?
        """;

    private static final String CANCEL_PENDING_SESSION_SQL = """
        UPDATE payment_sessions
        SET status = 'cancelled'::varchar, cancelled_at = CURRENT_TIMESTAMP
        WHERE api_key = ? AND status = 'pending'
        """;

    public PaymentSession createSessionWithOptionalKey(Connection conn, String apiKey, String paymentAddress,
                                       byte[] receiverNonce, long targetPlanId,
                                       BigInteger amountRequired, Instant expiresAt,
                                       boolean shouldCreateKey, BigInteger refundAmount) throws SQLException {
        UUID sessionId = UUID.randomUUID();

        try (PreparedStatement stmt = conn.prepareStatement(CREATE_SESSION_SQL)) {
            stmt.setObject(1, sessionId);
            stmt.setString(2, apiKey); // Can be null if shouldCreateKey is true
            stmt.setString(3, paymentAddress);
            stmt.setBytes(4, receiverNonce);
            stmt.setLong(5, targetPlanId);
            stmt.setBigDecimal(6, new BigDecimal(amountRequired));
            stmt.setTimestamp(7, Timestamp.from(expiresAt));
            stmt.setBoolean(8, shouldCreateKey);
            stmt.setBigDecimal(9, new BigDecimal(refundAmount));

            int rows = stmt.executeUpdate();
            if (rows > 0) {
                logger.info("Created payment session {} for {} API key", sessionId,
                    shouldCreateKey ? "new" : "existing");
                return new PaymentSession(sessionId, apiKey, paymentAddress,
                    receiverNonce, PaymentSessionStatus.PENDING, targetPlanId, amountRequired,
                    null, Instant.now(), null, expiresAt, shouldCreateKey, refundAmount,
                    null, null, null);
            }
        } catch (SQLException e) {
            if (e.getMessage() != null && e.getMessage().contains("idx_one_pending_payment_per_key")) {
                logger.info("API key {} already has a pending payment session", apiKey);
                throw new IllegalStateException("A pending payment session already exists for this API key");
            }
            throw e;
        }
        throw new RuntimeException("Unable to insert into database");
    }

    public Optional<PaymentSession> findById(Connection conn, UUID sessionId) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(FIND_BY_ID_SQL)) {
            stmt.setObject(1, sessionId);

            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapResultSetToPaymentSession(rs));
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Find a payment session by ID and lock it for update (prevents concurrent processing).
     * Uses SELECT FOR UPDATE NOWAIT to fail fast if another transaction is already processing this session.
     */
    public Optional<PaymentSession> findByIdAndLock(Connection conn, UUID sessionId) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(FIND_BY_ID_AND_LOCK_SQL)) {
            stmt.setObject(1, sessionId);

            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    logger.debug("Acquired lock on payment session {}", sessionId);
                    return Optional.of(mapResultSetToPaymentSession(rs));
                }
            }
        } catch (SQLException e) {
            // PostgreSQL lock_not_available error code: 55P03
            if (e.getSQLState() != null && e.getSQLState().equals("55P03")) {
                logger.info("Payment session {} is already being processed by another request", sessionId);
                throw new SessionLockedException(
                    "This payment session is already being processed. Please wait.", e);
            }
            throw e;
        }
        return Optional.empty();
    }

    public boolean updateSessionStatus(Connection conn, UUID sessionId, PaymentSessionStatus newStatus,
                                      String tokenReceived) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(UPDATE_STATUS_SQL)) {
            stmt.setString(1, newStatus.getValue());
            stmt.setTimestamp(2, newStatus == PaymentSessionStatus.COMPLETED ? Timestamp.from(Instant.now()) : null);
            stmt.setString(3, tokenReceived);
            stmt.setObject(4, sessionId);

            int rows = stmt.executeUpdate();
            if (rows > 0) {
                logger.info("Updated payment session {} to status: {}", sessionId, newStatus);
                return true;
            }
        }
        return false;
    }

    /**
     * @return the number of deleted sessions
     */
    public int deletePaymentSessionsByApiKey(String apiKey) {
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(DELETE_BY_API_KEY_SQL)) {

            stmt.setString(1, apiKey);

            int deleted = stmt.executeUpdate();
            if (deleted > 0) {
                logger.info("Deleted {} payment sessions for API key: {}", deleted, apiKey);
            }
            return deleted;
        } catch (SQLException e) {
            throw new RuntimeException("Error deleting payment sessions for API key: " + apiKey, e);
        }
    }

    /**
     * @return the number of deleted sessions
     */
    public int deletePaymentSessionsByPricingPlan(long pricingPlanId) {
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(DELETE_BY_PRICING_PLAN)) {

            stmt.setLong(1, pricingPlanId);

            int deleted = stmt.executeUpdate();
            if (deleted > 0) {
                logger.info("Deleted {} payment sessions for pricing plan ID: {}", deleted, pricingPlanId);
            }
            return deleted;
        } catch (SQLException e) {
            throw new RuntimeException("Error deleting payment sessions for pricing plan ID: " + pricingPlanId, e);
        }
    }

    public boolean updateSessionApiKey(Connection conn, UUID sessionId, String apiKey) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(UPDATE_SESSION_API_KEY_SQL)) {
            stmt.setString(1, apiKey);
            stmt.setObject(2, sessionId);

            int rows = stmt.executeUpdate();
            if (rows > 0) {
                logger.info("Updated session {} with new API key: {}", sessionId, apiKey);
                return true;
            }
        }
        return false;
    }

    public int cancelPendingSessions(Connection conn, String apiKey) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(CANCEL_PENDING_SESSION_SQL)) {
            stmt.setString(1, apiKey);

            int cancelled = stmt.executeUpdate();
            if (cancelled > 0) {
                logger.info("Cancelled {} pending payment sessions for API key: {}", cancelled, apiKey);
            }
            return cancelled;
        }
    }

    private PaymentSession mapResultSetToPaymentSession(ResultSet rs) throws SQLException {
        Timestamp completionRequestTs = rs.getTimestamp("completion_request_timestamp");
        return new PaymentSession(
            (UUID) rs.getObject("id"),
            rs.getString("api_key"),
            rs.getString("payment_address"),
            rs.getBytes("receiver_nonce"),
            PaymentSessionStatus.fromValue(rs.getString("status")),
            rs.getInt("target_plan_id"),
            rs.getBigDecimal("amount_required").toBigInteger(),
            rs.getString("token_received"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("completed_at") != null ?
                rs.getTimestamp("completed_at").toInstant() : null,
            rs.getTimestamp("expires_at").toInstant(),
            rs.getBoolean("should_create_key"),
            rs.getBigDecimal("refund_amount").toBigInteger(),
            rs.getString("request_id"),
            rs.getString("completion_request_json"),
            completionRequestTs != null ? completionRequestTs.toInstant() : null
        );
    }

    public void storeCompletionRequest(Connection conn, UUID sessionId, String requestId, String completionRequestJson) throws SQLException {
        String sql = """
            UPDATE payment_sessions
            SET request_id = ?,
                completion_request_json = ?,
                completion_request_timestamp = CURRENT_TIMESTAMP
            WHERE id = ?
              AND (request_id IS NULL OR request_id = ?)
              AND (completion_request_json IS NULL OR completion_request_json = ?)
            """;

        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, requestId);
            stmt.setString(2, completionRequestJson);
            stmt.setObject(3, sessionId);
            stmt.setString(4, requestId); // Allow same request_id (idempotent)
            stmt.setString(5, completionRequestJson); // Allow same json (idempotent)

            int rows = stmt.executeUpdate();
            if (rows == 0) {
                // Check if session exists
                try (PreparedStatement checkStmt = conn.prepareStatement(
                    "SELECT request_id, completion_request_json FROM payment_sessions WHERE id = ?")) {
                    checkStmt.setObject(1, sessionId);
                    try (ResultSet rs = checkStmt.executeQuery()) {
                        if (!rs.next()) {
                            throw new SQLException("No payment session found with id: " + sessionId);
                        }
                        // Session exists but update failed - must be a conflict
                        String existingRequestId = rs.getString("request_id");
                        logger.error("Completion request conflict for session {}: existing request_id={}, new request_id={}",
                            sessionId, existingRequestId, requestId);
                        throw new CompletionRequestConflictException(
                            "A different completion request is already being processed for this session");
                    }
                }
            } else {
                logger.info("Stored completion request for session {} with request_id {}",
                    sessionId, requestId);
            }
        } catch (SQLException e) {
            // PostgreSQL unique violation error code: 23505
            if (e.getSQLState() != null && e.getSQLState().equals("23505") &&
                e.getMessage().contains("idx_payment_sessions_request_id")) {
                logger.error("Duplicate request_id detected: {}", requestId);
                throw new DuplicateRequestIdException(
                    "This token has already been used for payment. Request ID: " + requestId,
                    e
                );
            }
            throw e;
        }
    }

    /**
     * Exception thrown when a request_id is reused (same token used twice).
     */
    public static class DuplicateRequestIdException extends RuntimeException {
        public DuplicateRequestIdException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Exception thrown when a session is already locked by another transaction.
     */
    public static class SessionLockedException extends RuntimeException {
        public SessionLockedException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Exception thrown when trying to store a different completion request for a session
     * that already has one stored.
     */
    public static class CompletionRequestConflictException extends RuntimeException {
        public CompletionRequestConflictException(String message) {
            super(message);
        }
    }

    public record PaymentSession(UUID id, String apiKey, String paymentAddress, byte[] receiverNonce,
                                 PaymentSessionStatus status, long targetPlanId, BigInteger amountRequired,
                                 String tokenReceived, Instant createdAt, Instant completedAt, Instant expiresAt,
                                 boolean shouldCreateKey, BigInteger refundAmount, String requestId,
                                 String completionRequestJson, Instant completionRequestTimestamp) {
    }
}