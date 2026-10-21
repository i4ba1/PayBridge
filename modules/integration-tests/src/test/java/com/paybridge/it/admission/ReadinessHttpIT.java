package com.paybridge.it.admission;

import static org.assertj.core.api.Assertions.assertThat;

import com.paybridge.core.admission.InvoicePointersHealthIndicator;
import com.paybridge.it.support.CoreHttpIT;
import com.paybridge.it.support.Fixtures;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.ConnectionCallback;

class ReadinessHttpIT extends CoreHttpIT {
    @Autowired InvoicePointersHealthIndicator indicator;

    private HttpResponse<String> readiness() throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/actuator/health/readiness")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void readinessIsUpWhileConsistentAndDownOnceAPointerIsBroken() throws Exception {
        indicator.runCheck();
        var up = readiness();
        assertThat(up.statusCode()).as(up.body()).isEqualTo(200);
        assertThat(up.body()).contains("\"status\":\"UP\"");

        String merchant = merchant("acme", "IDR");
        String broken = Fixtures.invoice(rootJdbc, merchant, 1_000, "IDR");
        rootJdbc.execute((ConnectionCallback<Void>) c -> {
            try (var st = c.createStatement()) {
                st.execute("SET FOREIGN_KEY_CHECKS = 0");
                st.executeUpdate("UPDATE invoice SET active_payment_id = '" + Fixtures.uuid() + "' WHERE id = '" + broken + "'");
                st.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
            return null;
        });
        try {
            indicator.runCheck();
            var down = readiness();
            assertThat(down.statusCode()).as(down.body()).isEqualTo(503);
        } finally {
            rootJdbc.update("UPDATE invoice SET active_payment_id = NULL WHERE id = ?", broken);
            indicator.runCheck();
        }
    }
}
