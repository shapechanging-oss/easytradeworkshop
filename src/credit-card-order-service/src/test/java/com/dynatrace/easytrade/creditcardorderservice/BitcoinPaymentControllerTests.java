package com.dynatrace.easytrade.creditcardorderservice;

import com.dynatrace.easytrade.creditcardorderservice.models.BitcoinPayment;
import com.dynatrace.easytrade.creditcardorderservice.models.BitcoinPaymentRequest;
import com.dynatrace.easytrade.creditcardorderservice.models.StandardResponse;

import dev.openfeature.sdk.Client;
import dev.openfeature.sdk.OpenFeatureAPI;
import lombok.SneakyThrows;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;

@ExtendWith(MockitoExtension.class)
public class BitcoinPaymentControllerTests {
    @Mock
    DatabaseHelper helper;
    @Mock
    OpenFeatureAPI openFeatureAPI;
    @Mock
    Client client;

    private final BitcoinPaymentRequest REQUEST = new BitcoinPaymentRequest(
            13, "idem-key-1", new BigDecimal("120000"), "USD", "bc1qexampleaddress");

    private void enableFeature(boolean enabled) {
        Mockito.when(openFeatureAPI.getClient()).thenReturn(client);
        Mockito.when(client.getBooleanValue(eq(BitcoinPaymentController.FEATURE_FLAG), eq(false)))
                .thenReturn(enabled);
    }

    @Test
    void featureDisabledReturns404() {
        enableFeature(false);
        BitcoinPaymentController controller = new BitcoinPaymentController(helper, openFeatureAPI);
        ResponseEntity<StandardResponse> response = controller.createBitcoinPayment(REQUEST);
        var body = response.getBody();
        assertNotNull(body, "Expected response body to not be null");
        assertEquals(HttpStatus.NOT_FOUND.value(), body.statusCode());
        assertEquals(BitcoinPaymentController.FEATURE_DISABLED, body.message());
    }

    @Test
    void invalidAmountReturns400() {
        enableFeature(true);
        BitcoinPaymentRequest zero = new BitcoinPaymentRequest(
                13, "idem-key-zero", BigDecimal.ZERO, "USD", "bc1qexampleaddress");
        BitcoinPaymentController controller = new BitcoinPaymentController(helper, openFeatureAPI);
        ResponseEntity<StandardResponse> response = controller.createBitcoinPayment(zero);
        var body = response.getBody();
        assertNotNull(body, "Expected response body to not be null");
        assertEquals(HttpStatus.BAD_REQUEST.value(), body.statusCode());
        assertEquals(BitcoinPaymentController.INVALID_AMOUNT, body.message());
    }

    @Test
    @SneakyThrows
    void createsPaymentWhenNoneExists() {
        enableFeature(true);
        Mockito.when(helper.getConnection()).thenReturn(null);
        Mockito.when(helper.getBitcoinPaymentByIdempotencyKey(eq(null), eq("idem-key-1")))
                .thenReturn(Optional.empty());
        BitcoinPayment created = new BitcoinPayment("id-1", 13, "idem-key-1",
                new BigDecimal("120000"), "USD", new BigDecimal("2.00000000"),
                "bc1qexampleaddress", "payment_created", OffsetDateTime.now());
        Mockito.when(helper.insertBitcoinPayment(eq(null), eq(REQUEST), any(BigDecimal.class)))
                .thenReturn(created);

        BitcoinPaymentController controller = new BitcoinPaymentController(helper, openFeatureAPI);
        ResponseEntity<StandardResponse> response = controller.createBitcoinPayment(REQUEST);
        var body = response.getBody();
        assertNotNull(body, "Expected response body to not be null");
        assertEquals(HttpStatus.CREATED.value(), body.statusCode());
        assertEquals(BitcoinPaymentController.PAYMENT_CREATED, body.message());
    }

    @Test
    @SneakyThrows
    void replaysExistingPaymentForSameIdempotencyKey() {
        enableFeature(true);
        Mockito.when(helper.getConnection()).thenReturn(null);
        BitcoinPayment existing = new BitcoinPayment("id-1", 13, "idem-key-1",
                new BigDecimal("120000"), "USD", new BigDecimal("2.00000000"),
                "bc1qexampleaddress", "payment_created", OffsetDateTime.now());
        Mockito.when(helper.getBitcoinPaymentByIdempotencyKey(eq(null), eq("idem-key-1")))
                .thenReturn(Optional.of(existing));

        BitcoinPaymentController controller = new BitcoinPaymentController(helper, openFeatureAPI);
        ResponseEntity<StandardResponse> response = controller.createBitcoinPayment(REQUEST);
        var body = response.getBody();
        assertNotNull(body, "Expected response body to not be null");
        assertEquals(HttpStatus.OK.value(), body.statusCode());
        assertEquals(BitcoinPaymentController.PAYMENT_REPLAYED, body.message());
    }

    @Test
    @SneakyThrows
    void getReturns404WhenNotFound() {
        enableFeature(true);
        Mockito.when(helper.getConnection()).thenReturn(null);
        Mockito.when(helper.getBitcoinPaymentById(eq(null), anyString())).thenReturn(Optional.empty());

        BitcoinPaymentController controller = new BitcoinPaymentController(helper, openFeatureAPI);
        ResponseEntity<StandardResponse> response = controller.getBitcoinPayment("missing-id");
        var body = response.getBody();
        assertNotNull(body, "Expected response body to not be null");
        assertEquals(HttpStatus.NOT_FOUND.value(), body.statusCode());
        assertEquals(BitcoinPaymentController.PAYMENT_NOT_FOUND, body.message());
    }
}
