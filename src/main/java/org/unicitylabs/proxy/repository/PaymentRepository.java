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
               token_received, created_at, completed_at, expires_at, should_create_key, refund_amount
        FROM payment_sessions
        WHERE id = ?
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

    /**
     * Create a new payment session with optional API key creation
     */
    public PaymentSession createSessionWithOptionalKey(String apiKey, String paymentAddress,
                                       byte[] receiverNonce, long targetPlanId,
                                       BigInteger amountRequired, Instant expiresAt,
                                       boolean shouldCreateKey, BigInteger refundAmount) {
        UUID sessionId = UUID.randomUUID();

        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(CREATE_SESSION_SQL)) {

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
                    null, Instant.now(), null, expiresAt, shouldCreateKey, refundAmount);
            }
        } catch (SQLException e) {
            if (e.getMessage() != null && e.getMessage().contains("idx_one_pending_payment_per_key")) {
                logger.warn("API key {} already has a pending payment session", apiKey);
                throw new IllegalStateException("A pending payment session already exists for this API key");
            }
            logger.error("Error creating payment session", e);
        }
        return null;
    }

    /**
     * Find a payment session by ID
     */
    public Optional<PaymentSession> findById(UUID sessionId) {
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(FIND_BY_ID_SQL)) {

            stmt.setObject(1, sessionId);

            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapResultSetToPaymentSession(rs));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Error finding payment session: " + sessionId, e);
        }
        return Optional.empty();
    }

    /**
     * Update a payment session's status
     */
    public boolean updateSessionStatus(UUID sessionId, PaymentSessionStatus newStatus, String tokenReceived) {
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(UPDATE_STATUS_SQL)) {

            stmt.setString(1, newStatus.getValue());
            stmt.setTimestamp(2, newStatus == PaymentSessionStatus.COMPLETED ? Timestamp.from(Instant.now()) : null);
            stmt.setString(3, tokenReceived);
            stmt.setObject(4, sessionId);

            int rows = stmt.executeUpdate();
            if (rows > 0) {
                logger.info("Updated payment session {} to status: {}", sessionId, newStatus);
                return true;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Error updating payment session status", e);
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
     * Update the API key for a session (used when creating a new key on payment completion)
     */
    public boolean updateSessionApiKey(UUID sessionId, String apiKey) {
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(UPDATE_SESSION_API_KEY_SQL)) {

            stmt.setString(1, apiKey);
            stmt.setObject(2, sessionId);

            int rows = stmt.executeUpdate();
            if (rows > 0) {
                logger.info("Updated session {} with new API key: {}", sessionId, apiKey);
                return true;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Error updating session API key", e);
        }
        return false;
    }

    /**
     * Cancel any pending payment sessions for an API key
     */
    public int cancelPendingSessions(String apiKey) {
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(CANCEL_PENDING_SESSION_SQL)) {

            stmt.setString(1, apiKey);

            int cancelled = stmt.executeUpdate();
            if (cancelled > 0) {
                logger.info("Cancelled {} pending payment sessions for API key: {}", cancelled, apiKey);
            }
            return cancelled;
        } catch (SQLException e) {
            throw new RuntimeException("Error cancelling pending sessions for API key: " + apiKey, e);
        }
    }

    private PaymentSession mapResultSetToPaymentSession(ResultSet rs) throws SQLException {
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
            rs.getBigDecimal("refund_amount").toBigInteger()
        );
    }

    public record PaymentSession(UUID id, String apiKey, String paymentAddress, byte[] receiverNonce,
                                 PaymentSessionStatus status, long targetPlanId, BigInteger amountRequired,
                                 String tokenReceived, Instant createdAt, Instant completedAt, Instant expiresAt,
                                 boolean shouldCreateKey, BigInteger refundAmount) {
    }
}