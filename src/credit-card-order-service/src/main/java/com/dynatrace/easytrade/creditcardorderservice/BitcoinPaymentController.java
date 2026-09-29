package com.dynatrace.easytrade.creditcardorderservice;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.dynatrace.easytrade.creditcardorderservice.models.BitcoinPayment;
import com.dynatrace.easytrade.creditcardorderservice.models.BitcoinPaymentRequest;
import com.dynatrace.easytrade.creditcardorderservice.models.StandardResponse;

import dev.openfeature.sdk.Client;
import dev.openfeature.sdk.OpenFeatureAPI;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;

/**
 * Bitcoin payment API.
 *
 * <p>The whole flow is gated behind the OpenFeature boolean flag
 * {@code bitcoin_payments_enabled} (default {@code false}), mirroring the existing
 * {@code credit_card_meltdown} pattern. This gives a redeploy-free kill-switch and a
 * progressive-rollout lever, which is how the feature is meant to be scaled up to 100x
 * traffic: enable for a small cohort, watch monitoring, widen. When the flag is off the
 * endpoints return HTTP 404 as if they did not exist.
 *
 * <p>Create is idempotent on {@link BitcoinPaymentRequest#idempotencyKey()}: a retry with
 * the same key returns the existing payment (HTTP 200) instead of creating a duplicate,
 * which is essential when the same request can arrive more than once under load.
 */
@RestController
@RequestMapping(value = "/v1/payments/bitcoin",
        produces = {"application/json", "application/xml"})
@CrossOrigin
@ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Existing payment returned (idempotent replay)", content =
                @Content(schema = @Schema(implementation = StandardResponse.class))),
        @ApiResponse(responseCode = "201", description = "Bitcoin payment created", content =
                @Content(schema = @Schema(implementation = StandardResponse.class))),
        @ApiResponse(responseCode = "400", description = "Bad request", content =
                @Content(schema = @Schema(implementation = StandardResponse.class))),
        @ApiResponse(responseCode = "404", description = "Feature disabled or payment not found", content =
                @Content(schema = @Schema(implementation = StandardResponse.class))),
        @ApiResponse(responseCode = "500", description = "Internal server error", content =
                @Content(schema = @Schema(implementation = StandardResponse.class))),
})
public class BitcoinPaymentController {
    private static final Logger logger = LoggerFactory.getLogger(BitcoinPaymentController.class);

    static final String FEATURE_FLAG = "bitcoin_payments_enabled";
    static final String FEATURE_DISABLED = "Bitcoin payments are not currently enabled.";
    static final String PAYMENT_CREATED = "Bitcoin payment created successfully.";
    static final String PAYMENT_REPLAYED = "Bitcoin payment already exists for this idempotency key.";
    static final String PAYMENT_NOT_FOUND = "No bitcoin payment exists for the given id.";
    static final String INVALID_AMOUNT = "fiatAmount must be greater than zero.";

    // Illustrative fixed conversion rate (fiat units per 1 BTC). A production build would
    // fetch a live quote from a rate provider / settlement service; that outbound integration
    // is intentionally kept off the request hot path (see docs/bitcoin-payment-feature.md).
    private static final BigDecimal FIAT_PER_BTC = new BigDecimal("60000");

    private final DatabaseHelper dbHelper;
    private final OpenFeatureAPI openFeatureAPI;

    public BitcoinPaymentController(DatabaseHelper dbHelper, OpenFeatureAPI openFeatureAPI) {
        this.dbHelper = dbHelper;
        this.openFeatureAPI = openFeatureAPI;
    }

    private boolean isEnabled() {
        Client client = openFeatureAPI.getClient();
        return client.getBooleanValue(FEATURE_FLAG, false);
    }

    @PostMapping(value = "", consumes = {"application/json", "application/xml"})
    @Operation(summary = "Create a bitcoin payment (idempotent on idempotencyKey)")
    public ResponseEntity<StandardResponse> createBitcoinPayment(@RequestBody BitcoinPaymentRequest request) {
        if (!isEnabled()) {
            return buildResponse(HttpStatus.NOT_FOUND, FEATURE_DISABLED, null, null, null);
        }

        logger.info("Creating bitcoin payment for accountId: {}", request.accountId());

        if (request.fiatAmount().compareTo(BigDecimal.ZERO) <= 0) {
            return buildResponse(HttpStatus.BAD_REQUEST, INVALID_AMOUNT, null, request, null);
        }

        try (Connection conn = dbHelper.getConnection()) {
            // Idempotency: if a payment with this key already exists, return it unchanged.
            Optional<BitcoinPayment> existing =
                    dbHelper.getBitcoinPaymentByIdempotencyKey(conn, request.idempotencyKey());
            if (existing.isPresent()) {
                return buildResponse(HttpStatus.OK, PAYMENT_REPLAYED, existing.get(), null, null);
            }

            BigDecimal amountBtc = request.fiatAmount()
                    .divide(FIAT_PER_BTC, 8, RoundingMode.HALF_UP);

            BitcoinPayment payment = dbHelper.insertBitcoinPayment(conn, request, amountBtc);
            return buildResponse(HttpStatus.CREATED, PAYMENT_CREATED, payment, null, null);
        } catch (SQLException e) {
            // A unique-constraint violation here means a concurrent request created the same
            // idempotency key between our lookup and insert -- treat it as an idempotent replay.
            if (isDuplicateKey(e)) {
                return handleConcurrentDuplicate(request.idempotencyKey(), e);
            }
            return handleSQLException(e);
        }
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a bitcoin payment by id")
    public ResponseEntity<StandardResponse> getBitcoinPayment(@PathVariable String id) {
        if (!isEnabled()) {
            return buildResponse(HttpStatus.NOT_FOUND, FEATURE_DISABLED, null, null, null);
        }
        try (Connection conn = dbHelper.getConnection()) {
            return dbHelper.getBitcoinPaymentById(conn, id)
                    .map(p -> buildResponse(HttpStatus.OK, "Bitcoin payment found.", p, null, null))
                    .orElse(buildResponse(HttpStatus.NOT_FOUND, PAYMENT_NOT_FOUND, null, null, null));
        } catch (SQLException e) {
            return handleSQLException(e);
        }
    }

    private ResponseEntity<StandardResponse> handleConcurrentDuplicate(String idempotencyKey, SQLException original) {
        try (Connection conn = dbHelper.getConnection()) {
            Optional<BitcoinPayment> existing = dbHelper.getBitcoinPaymentByIdempotencyKey(conn, idempotencyKey);
            if (existing.isPresent()) {
                return buildResponse(HttpStatus.OK, PAYMENT_REPLAYED, existing.get(), null, null);
            }
            return handleSQLException(original);
        } catch (SQLException e) {
            return handleSQLException(e);
        }
    }

    private boolean isDuplicateKey(SQLException e) {
        // MSSQL: 2627 (PK/unique constraint) and 2601 (unique index) indicate a duplicate.
        int code = e.getErrorCode();
        return code == 2627 || code == 2601;
    }

    private ResponseEntity<StandardResponse> buildResponse(HttpStatus status, String message,
            Object results, Object data, Object error) {
        if (status.is5xxServerError()) {
            logger.error(message);
        } else {
            logger.info(message);
        }
        return ResponseEntity
                .status(status)
                .body(new StandardResponse(status.value(), message, results, data, error));
    }

    private ResponseEntity<StandardResponse> handleSQLException(SQLException e) {
        return buildResponse(HttpStatus.INTERNAL_SERVER_ERROR, "An exception occurred!",
                null, null, e.getMessage());
    }
}
