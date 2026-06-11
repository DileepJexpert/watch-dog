package com.watchdog.agent;

import com.watchdog.config.WatchdogProperties;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PiiRedactorTest {

    @Test
    void redactsEmailsAndCardNumbersAndTokens() {
        PiiRedactor r = new PiiRedactor(new WatchdogProperties());
        String input = "user dileepjexpert@example.com card 4111111111111111 token: abc123secret";
        String out = r.redactString(input);
        assertThat(out).doesNotContain("dileepjexpert@example.com");
        assertThat(out).doesNotContain("4111111111111111");
        assertThat(out).doesNotContain("abc123secret");
        assertThat(out).contains("[REDACTED]");
    }

    @Test
    void recursivelyRedactsMapsAndLists() {
        PiiRedactor r = new PiiRedactor(new WatchdogProperties());
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("password", "topsecret");
        nested.put("note", "contact ops@example.com");
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("user", nested);
        input.put("ssns", List.of("123-45-6789", "no-pii"));

        @SuppressWarnings("unchecked")
        Map<String, Object> redacted = (Map<String, Object>) r.redact(input);
        @SuppressWarnings("unchecked")
        Map<String, Object> user = (Map<String, Object>) redacted.get("user");
        assertThat(user.get("password")).isEqualTo("[REDACTED]");
        assertThat(String.valueOf(user.get("note"))).contains("[REDACTED]");
        assertThat(redacted.get("ssns").toString()).contains("[REDACTED]");
        assertThat(redacted.get("ssns").toString()).contains("no-pii");
    }

    @Test
    void disabledRedactionReturnsInputUnchanged() {
        WatchdogProperties props = new WatchdogProperties();
        props.getAgent().getRedaction().setEnabled(false);
        PiiRedactor r = new PiiRedactor(props);
        assertThat(r.redactString("email a@b.com")).isEqualTo("email a@b.com");
    }
}
