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
            target_plan_id, amount_required, expires_at, should_create_key
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        """;

    private static final String FIND_BY_ID_SQL = """
        SELECT id, api_key, payment_address, receiver_nonce,
               status, target_plan_id, amount_required,
               token_received, created_at, completed_at, expires_at, should_create_key
        FROM payment_sessions
        WHERE id = ?
        """;

    private static final String UPDATE_STATUS_SQL = """
        UPDATE payment_sessions
        SET status = ?::varchar, completed_at = ?, token_received = ?
        WHERE id = ? AND status = 'pending'
        """;

    private static final String FIND_PENDING_BY_API_KEY_SQL = """
        SELECT id, api_key, payment_address, receiver_nonce,
               status, target_plan_id, amount_required,
               token_received, created_at, completed_at, expires_at, should_create_key
        FROM payment_sessions
        WHERE api_key = ? AND status = 'pending' AND expires_at > CURRENT_TIMESTAMP
        """;

    private static final String EXPIRE_OLD_SESSIONS_SQL = """
        UPDATE payment_sessions
        SET status = 'expired'
        WHERE status = 'pending' AND expires_at <= CURRENT_TIMESTAMP
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
                                       boolean shouldCreateKey) {
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

            int rows = stmt.executeUpdate();
            if (rows > 0) {
                logger.info("Created payment session {} for {} API key", sessionId,
                    shouldCreateKey ? "new" : "existing");
                return new PaymentSession(sessionId, apiKey, paymentAddress,
                    receiverNonce, PaymentSessionStatus.PENDING, targetPlanId, amountRequired,
                    null, Instant.now(), null, expiresAt, shouldCreateKey);
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
     * Find a pending payment session for an API key
     */
    public Optional<PaymentSession> findPendingByApiKey(String apiKey) {
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(FIND_PENDING_BY_API_KEY_SQL)) {

            stmt.setString(1, apiKey);

            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapResultSetToPaymentSession(rs));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Error finding pending payment for API key: " + apiKey, e);
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
     * Expire old pending sessions
     */
    public int expireOldSessions() {
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement stmt = conn.prepareStatement(EXPIRE_OLD_SESSIONS_SQL)) {

            int expired = stmt.executeUpdate();
            if (expired > 0) {
                logger.info("Expired {} old payment sessions", expired);
            }
            return expired;
        } catch (SQLException e) {
            throw new RuntimeException("Error expiring old sessions", e);
        }
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
            rs.getBoolean("should_create_key")
        );
    }

    /**
     * Payment session data model
     */
    public static class PaymentSession {
        private final UUID id;
        private final String apiKey;
        private final String paymentAddress;
        private final byte[] receiverNonce;
        private final PaymentSessionStatus status;
        private final long targetPlanId;
        private final BigInteger amountRequired;
        private final String tokenReceived;
        private final Instant createdAt;
        private final Instant completedAt;
        private final Instant expiresAt;
        private final boolean shouldCreateKey;

        public PaymentSession(UUID id, String apiKey, String paymentAddress,
                             byte[] receiverNonce, PaymentSessionStatus status, long targetPlanId,
                             BigInteger amountRequired, String tokenReceived,
                             Instant createdAt, Instant completedAt, Instant expiresAt,
                             boolean shouldCreateKey) {
            this.id = id;
            this.apiKey = apiKey;
            this.paymentAddress = paymentAddress;
            this.receiverNonce = receiverNonce;
            this.status = status;
            this.targetPlanId = targetPlanId;
            this.amountRequired = amountRequired;
            this.tokenReceived = tokenReceived;
            this.createdAt = createdAt;
            this.completedAt = completedAt;
            this.expiresAt = expiresAt;
            this.shouldCreateKey = shouldCreateKey;
        }

        // Getters
        public UUID getId() { return id; }
        public String getApiKey() { return apiKey; }
        public String getPaymentAddress() { return paymentAddress; }
        public byte[] getReceiverNonce() { return receiverNonce; }
        public PaymentSessionStatus getStatus() { return status; }
        public long getTargetPlanId() { return targetPlanId; }
        public BigInteger getAmountRequired() { return amountRequired; }
        public String getTokenReceived() { return tokenReceived; }
        public Instant getCreatedAt() { return createdAt; }
        public Instant getCompletedAt() { return completedAt; }
        public Instant getExpiresAt() { return expiresAt; }
        public boolean isShouldCreateKey() { return shouldCreateKey; }
    }
}