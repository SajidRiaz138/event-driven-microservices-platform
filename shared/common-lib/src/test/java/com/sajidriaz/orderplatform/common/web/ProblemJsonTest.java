package com.sajidriaz.orderplatform.common.web;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProblemJsonTest {

    @Test
    void render_producesTheRfc9457MembersAndCorrelationIdExtension() {
        String json = ProblemJson.render(401, "unauthenticated", "Unauthorized",
                "A valid credential is required.", "/api/v1/orders", "corr-1");

        assertEquals("{\"type\":\"https://docs.platform.local/problems/unauthenticated\","
                + "\"title\":\"Unauthorized\","
                + "\"status\":401,"
                + "\"detail\":\"A valid credential is required.\","
                + "\"instance\":\"/api/v1/orders\","
                + "\"correlationId\":\"corr-1\"}", json);
    }

    @Test
    void render_omitsCorrelationIdWhenAbsent() {
        assertFalse(ProblemJson.render(403, "forbidden", "Forbidden", "Nope.", "/x", null)
                .contains("correlationId"));
        assertFalse(ProblemJson.render(403, "forbidden", "Forbidden", "Nope.", "/x", "  ")
                .contains("correlationId"));
    }

    @Test
    void render_escapesQuotesSoACraftedPathCannotBreakTheBody() {
        String json = ProblemJson.render(401, "unauthenticated", "Unauthorized", "d",
                "/api/v1/\"or\\ders", "c");

        assertTrue(json.contains("\"instance\":\"/api/v1/\\\"or\\\\ders\""), json);
    }

    @Test
    void render_escapesControlCharacters() {
        String json = ProblemJson.render(400, "malformed-request", "Bad request",
                "line\nbreak\ttab", "/x", "c");

        assertTrue(json.contains("line\\nbreak\\ttab"), json);
    }
}
