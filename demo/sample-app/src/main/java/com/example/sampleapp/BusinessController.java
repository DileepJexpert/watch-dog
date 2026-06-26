package com.example.sampleapp;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

/**
 * Business-domain POST endpoints. Hit them with curl — when the payload is
 * obviously bad (missing field, negative amount, wrong card length, frozen
 * account flag, etc.) the controller logs a realistic banking-style ERROR
 * and returns a 500. That makes the dashboard light up with
 * "Loan disbursement failed" instead of "Synthetic failure".
 *
 * Every error includes:
 *   - A specific BusinessException subclass (PaymentDeclined, AccountFrozen, ...)
 *   - A correlation-style ID embedded in the message so the agent can
 *     reference it in the chat
 *   - A counter increment so Prometheus tracks per-failure-mode metrics
 *
 * The exceptions deliberately surface through Spring's default mapper as
 * 500 Internal Server Error so the HTTP error-rate metric also moves.
 */
@RestController
@RequestMapping("/api")
public class BusinessController {

    private static final Logger log = LoggerFactory.getLogger(BusinessController.class);

    private final Counter accountsFailures;
    private final Counter loansFailures;
    private final Counter paymentsFailures;
    private final Counter transfersFailures;

    public BusinessController(MeterRegistry registry) {
        this.accountsFailures = Counter.builder("app.business.failures")
                .description("Business operation failures by domain")
                .tag("domain", "accounts").register(registry);
        this.loansFailures = Counter.builder("app.business.failures")
                .tag("domain", "loans").register(registry);
        this.paymentsFailures = Counter.builder("app.business.failures")
                .tag("domain", "payments").register(registry);
        this.transfersFailures = Counter.builder("app.business.failures")
                .tag("domain", "transfers").register(registry);
    }

    // ───────────────────────────────────────────────────────────────────────
    // Accounts
    // ───────────────────────────────────────────────────────────────────────

    /**
     * POST /api/accounts — create a customer account.
     * Required JSON: { "customerId": "C-12345", "pan": "ABCDE1234F", "kycDocId": "doc-..." }
     *
     * Triggers on bad payload:
     *   missing customerId  → KYC document upload timeout
     *   missing pan         → PAN verification failure
     *   missing kycDocId    → KYC document upload timeout
     */
    @PostMapping("/accounts")
    public Map<String, Object> createAccount(@RequestBody(required = false) Map<String, Object> body) {
        String customerId = asString(body, "customerId");
        String pan = asString(body, "pan");
        String kycDocId = asString(body, "kycDocId");
        String corr = "ACC-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();

        if (customerId == null || customerId.isBlank() || kycDocId == null || kycDocId.isBlank()) {
            accountsFailures.increment();
            String msg = String.format(
                    "Account creation failed: KYC document upload timeout for customer %s [%s]",
                    customerId == null ? "<unknown>" : customerId, corr);
            log.error(msg, new BusinessException("KYC_TIMEOUT", corr));
            throw new BusinessException(msg, "KYC_TIMEOUT", corr);
        }
        if (pan == null || pan.length() != 10) {
            accountsFailures.increment();
            String msg = String.format(
                    "Customer KYC update failed: PAN verification service returned HTTP 504 for customer %s [%s]",
                    customerId, corr);
            log.error(msg, new BusinessException("PAN_VERIFY_504", corr));
            throw new BusinessException(msg, "PAN_VERIFY_504", corr);
        }

        log.info("POST /api/accounts succeeded for customer {} [{}]", customerId, corr);
        return Map.of("status", "created", "customerId", customerId, "correlationId", corr);
    }

    // ───────────────────────────────────────────────────────────────────────
    // Loans
    // ───────────────────────────────────────────────────────────────────────

    /**
     * POST /api/loans/disburse — disburse an approved loan.
     * Required: { "applicationId": "LA-123A", "amount": 50000, "sourceAccount": "A-78901" }
     *
     * Triggers on bad payload:
     *   negative amount / amount > 10_000_000  → InsufficientFundsException
     *   missing sourceAccount                  → AccountFrozenException
     */
    @PostMapping("/loans/disburse")
    public Map<String, Object> disburseLoan(@RequestBody(required = false) Map<String, Object> body) {
        String applicationId = asString(body, "applicationId");
        Number amountRaw = asNumber(body, "amount");
        String sourceAccount = asString(body, "sourceAccount");
        String corr = "LD-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();

        BigDecimal amount = amountRaw == null ? null : new BigDecimal(amountRaw.toString());
        if (applicationId == null || amount == null) {
            loansFailures.increment();
            String msg = String.format(
                    "Loan disbursement failed: invalid request payload, applicationId=%s amount=%s [%s]",
                    applicationId, amount, corr);
            log.error(msg, new BusinessException("BAD_PAYLOAD", corr));
            throw new BusinessException(msg, "BAD_PAYLOAD", corr);
        }
        if (amount.signum() <= 0 || amount.compareTo(new BigDecimal("10000000")) > 0) {
            loansFailures.increment();
            String msg = String.format(
                    "Loan disbursement failed: insufficient funds in source account for application %s, requested INR %s [%s]",
                    applicationId, amount, corr);
            log.error(msg, new BusinessException("INSUFFICIENT_FUNDS", corr));
            throw new BusinessException(msg, "INSUFFICIENT_FUNDS", corr);
        }
        if (sourceAccount == null || sourceAccount.isBlank()) {
            loansFailures.increment();
            String msg = String.format(
                    "Money transfer failed: beneficiary account is frozen for loan %s [%s]",
                    applicationId, corr);
            log.error(msg, new BusinessException("ACCOUNT_FROZEN", corr));
            throw new BusinessException(msg, "ACCOUNT_FROZEN", corr);
        }

        log.info("Loan {} disbursed for INR {} from {} [{}]", applicationId, amount, sourceAccount, corr);
        return Map.of("status", "disbursed", "applicationId", applicationId, "correlationId", corr);
    }

    // ───────────────────────────────────────────────────────────────────────
    // Payments
    // ───────────────────────────────────────────────────────────────────────

    /**
     * POST /api/payments — authorize a card payment.
     * Required: { "cardNumber": "4111111111111111", "amount": 1500, "currency": "INR" }
     *
     * Triggers on bad payload:
     *   bad cardNumber                → PaymentDeclinedException
     *   amount > 1_000_000             → FraudCheckException
     */
    @PostMapping("/payments")
    public Map<String, Object> authorizePayment(@RequestBody(required = false) Map<String, Object> body) {
        String cardNumber = asString(body, "cardNumber");
        Number amountRaw = asNumber(body, "amount");
        String currency = asString(body, "currency");
        String corr = "PAY-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();

        if (cardNumber == null || cardNumber.length() < 13 || cardNumber.length() > 19) {
            paymentsFailures.increment();
            String last4 = cardNumber == null ? "????"
                    : cardNumber.substring(Math.max(0, cardNumber.length() - 4));
            String msg = String.format(
                    "Payment authorization rejected by card issuer: card ending %s, invalid card length [%s]",
                    last4, corr);
            log.error(msg, new BusinessException("CARD_DECLINED", corr));
            throw new BusinessException(msg, "CARD_DECLINED", corr);
        }
        BigDecimal amount = amountRaw == null ? null : new BigDecimal(amountRaw.toString());
        if (amount != null && amount.compareTo(new BigDecimal("1000000")) > 0) {
            paymentsFailures.increment();
            String msg = String.format(
                    "Fraud check rejected transaction TXN-%s: velocity rule FR-VLT-04 triggered, amount %s %s [%s]",
                    Math.abs(corr.hashCode()) % 1_000_000_000, amount,
                    currency == null ? "INR" : currency, corr);
            log.error(msg, new BusinessException("FRAUD_VELOCITY", corr));
            throw new BusinessException(msg, "FRAUD_VELOCITY", corr);
        }

        log.info("Payment {} authorized for {} {}", corr, amount, currency);
        return Map.of("status", "authorized", "correlationId", corr);
    }

    // ───────────────────────────────────────────────────────────────────────
    // Transfers
    // ───────────────────────────────────────────────────────────────────────

    /**
     * POST /api/transfers — move money between accounts.
     * Required: { "fromAccount": "A-12345", "toAccount": "A-67890", "amount": 5000 }
     *
     * Triggers on bad payload:
     *   missing toAccount        → AccountFrozenException
     *   toAccount starts "FRZ-"  → AccountFrozenException
     *   fromAccount = toAccount  → BusinessException SELF_TRANSFER
     */
    @PostMapping("/transfers")
    public Map<String, Object> transfer(@RequestBody(required = false) Map<String, Object> body) {
        String fromAccount = asString(body, "fromAccount");
        String toAccount = asString(body, "toAccount");
        Number amountRaw = asNumber(body, "amount");
        String corr = "TXN-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();

        if (toAccount == null || toAccount.startsWith("FRZ-")) {
            transfersFailures.increment();
            String msg = String.format(
                    "Money transfer failed: beneficiary account %s is frozen, transaction %s",
                    toAccount, corr);
            log.error(msg, new BusinessException("ACCOUNT_FROZEN", corr));
            throw new BusinessException(msg, "ACCOUNT_FROZEN", corr);
        }
        if (fromAccount != null && fromAccount.equals(toAccount)) {
            transfersFailures.increment();
            String msg = String.format(
                    "Money transfer failed: source and beneficiary accounts are identical (%s), transaction %s",
                    fromAccount, corr);
            log.error(msg, new BusinessException("SELF_TRANSFER", corr));
            throw new BusinessException(msg, "SELF_TRANSFER", corr);
        }

        log.info("Transfer {} from {} to {} amount {}", corr, fromAccount, toAccount, amountRaw);
        return Map.of("status", "transferred", "correlationId", corr);
    }

    // ───────────────────────────────────────────────────────────────────────
    // Helpers
    // ───────────────────────────────────────────────────────────────────────

    private static String asString(Map<String, Object> body, String key) {
        if (body == null) return null;
        Object v = body.get(key);
        return v == null ? null : String.valueOf(v);
    }

    private static Number asNumber(Map<String, Object> body, String key) {
        if (body == null) return null;
        Object v = body.get(key);
        if (v instanceof Number n) return n;
        if (v instanceof String s) {
            try { return new BigDecimal(s); } catch (NumberFormatException ignored) { return null; }
        }
        return null;
    }

    /** Untyped business exception. The error code travels with the log so the agent
     *  can group failures by failure-mode when calling search_logs. */
    public static class BusinessException extends RuntimeException {
        public final String code;
        public final String correlationId;

        public BusinessException(String message, String code, String correlationId) {
            super(message);
            this.code = code;
            this.correlationId = correlationId;
        }
        public BusinessException(String code, String correlationId) {
            super(code + " [" + correlationId + "]");
            this.code = code;
            this.correlationId = correlationId;
        }
    }
}
