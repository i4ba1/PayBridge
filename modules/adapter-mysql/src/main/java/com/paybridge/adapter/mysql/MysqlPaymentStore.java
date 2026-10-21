package com.paybridge.adapter.mysql;

import com.paybridge.adapter.mysql.entity.PaymentEntity;
import com.paybridge.application.port.PaymentStore;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Initial version: reads only. Admission, transitions and the outbox arrive in T07 to T09. */
@Repository
public class MysqlPaymentStore implements PaymentStore {

    interface PaymentRepository extends JpaRepository<PaymentEntity, String> {
        @Query("select p from PaymentEntity p where p.invoiceId = :invoiceId and p.status <> "
                + "com.paybridge.adapter.mysql.entity.PaymentEntity.Status.FAILED")
        Optional<PaymentEntity> findBlocking(@Param("invoiceId") String invoiceId);

        long countByMerchantId(String merchantId);
    }

    private final PaymentRepository payments;

    MysqlPaymentStore(PaymentRepository payments) {
        this.payments = payments;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PaymentRecord> findById(String id) {
        return payments.findById(id).map(MysqlPaymentStore::toRecord);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PaymentRecord> findBlockingPayment(String invoiceId) {
        return payments.findBlocking(invoiceId).map(MysqlPaymentStore::toRecord);
    }

    @Override
    @Transactional(readOnly = true)
    public long countByMerchant(String merchantId) {
        return payments.countByMerchantId(merchantId);
    }

    private static PaymentRecord toRecord(PaymentEntity e) {
        return new PaymentRecord(e.getId(), e.getMerchantId(), e.getInvoiceId(), e.getMethodCode(), e.getAmountMinor(),
                e.getCurrency(), e.getStatus().name(), e.getAggregateVersion(), e.getProviderKey(),
                e.getProviderReference(), e.getScenarioRunId(), e.getCorrelationId(), e.getRequestedBy(),
                e.getCreatedAt(), e.getUpdatedAt(), e.getCompletedAt());
    }
}
