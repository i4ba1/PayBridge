package com.paybridge.simulator.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paybridge.simulator.config.ConnectionAbortValve;
import com.paybridge.simulator.service.ApiException;
import com.paybridge.simulator.service.ChargeBody;
import com.paybridge.simulator.service.ChargeService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ChargeController {
    private final ChargeService charges;
    private final ObjectMapper json;

    public ChargeController(ChargeService charges, ObjectMapper json) {
        this.charges = charges;
        this.json = json;
    }

    /**
     * Writes the response by hand so that a scenario can end the request with no response bytes at all: the handler
     * returns without touching the response and the connection valve closes the socket.
     */
    @PostMapping("/v1/charges")
    public void create(@RequestBody ChargeBody body,
                       @RequestHeader(value = "Idempotency-Key", required = false) String key,
                       @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
                       HttpServletRequest request, HttpServletResponse response) throws IOException {
        ChargeBody valid = body.withValidatedDefaults(key);
        echo(response, correlationId);
        switch (charges.charge(valid)) {
            case ChargeService.Respond r -> write(response, r.status(), r.charge());
            case ChargeService.Abort a -> {
                sleep(a.sleepMillis());
                request.setAttribute(ConnectionAbortValve.ATTRIBUTE, Boolean.TRUE);
            }
        }
    }

    @GetMapping("/v1/charges/by-key/{key}")
    public void lookup(@PathVariable String key, @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
                       HttpServletResponse response) throws IOException {
        if (key.length() > 64) {
            throw ApiException.invalid("key is too long");
        }
        echo(response, correlationId);
        var found = charges.lookup(key);
        if (found.isEmpty()) {
            throw new ApiException(404, "charge_not_found", "No charge exists for this key");
        }
        write(response, 200, found.get());
    }

    private void write(HttpServletResponse response, int status, Object body) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=utf-8");
        response.getOutputStream().write(json.writeValueAsString(body).getBytes(StandardCharsets.UTF_8));
    }

    private static void echo(HttpServletResponse response, String correlationId) {
        if (correlationId != null && correlationId.matches("^[A-Za-z0-9._:-]{1,64}$")) {
            response.setHeader("X-Correlation-Id", correlationId);
        }
    }

    private static void sleep(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
