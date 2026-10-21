package com.paybridge.it.admission;

import static org.assertj.core.api.Assertions.assertThat;

import com.paybridge.application.payment.PaymentAdmission;
import com.paybridge.application.payment.PaymentAdmission.Accepted;
import com.paybridge.application.payment.PaymentAdmission.Code;
import com.paybridge.application.payment.PaymentAdmission.Rejected;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

class AdmissionConcurrencyIT extends AdmissionIT {

    @Test
    void fiftyThreadsOverFiveKeysProduceExactlyOnePayment() throws Exception {
        admit = useCase(settings(true, 10, 3));
        List<String> keys = List.of(key(), key(), key(), key(), key());
        int threads = 50;
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(threads);
        var futures = new ArrayList<Future<PaymentAdmission>>();
        try {
            for (int i = 0; i < threads; i++) {
                String key = keys.get(i % keys.size());
                futures.add(pool.submit(() -> {
                    start.await();
                    return request(key);
                }));
            }
            start.countDown();
            var results = new ArrayList<PaymentAdmission>();
            for (var f : futures) {
                results.add(f.get());
            }

            var created = results.stream().filter(r -> r instanceof Accepted a && !a.replayed()).map(r -> (Accepted) r).toList();
            assertThat(created).as("exactly one request created the payment").hasSize(1);
            String paymentId = created.get(0).paymentId();
            for (var r : results) {
                if (r instanceof Accepted a) {
                    assertThat(a.paymentId()).isEqualTo(paymentId);
                } else {
                    assertThat(((Rejected) r).code()).isEqualTo(Code.INVOICE_NOT_ELIGIBLE);
                }
            }
            assertThat(count("payment")).isOne();
            assertThat(count("payment_request")).as("only the winning key keeps its reservation").isOne();
            assertThat(count("payment_event")).isOne();
            assertThat(outboxCount()).isEqualTo(2);
            assertThat(admittedAuditCount()).isOne();
            assertThat(rootJdbc.queryForObject("SELECT active_payment_id FROM invoice WHERE id = ?", String.class, invoice))
                    .isEqualTo(paymentId);
        } finally {
            pool.shutdownNow();
        }
    }
}
