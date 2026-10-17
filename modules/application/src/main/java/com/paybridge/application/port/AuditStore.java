package com.paybridge.application.port;

import java.util.Map;

/**
 * Append only business audit trail (DMR audit vocabulary). Writes join the caller's transaction so a mutation and its
 * audit row commit or roll back together. Details must never carry credentials; implementations reject suspicious keys.
 */
public interface AuditStore {

    void record(Entry entry);

    enum ActorType { USER, SERVICE, SYSTEM }

    enum Result { SUCCESS, REJECTED, FAILURE }

    record Entry(ActorType actorType, String actorUserId, String merchantId, String action, String targetType,
                 String targetId, Result result, String correlationId, Map<String, Object> details) {
        public static Entry of(ActorType type, String actorUserId, String merchantId, String action, String targetType,
                               String targetId, Result result, String correlationId) {
            return new Entry(type, actorUserId, merchantId, action, targetType, targetId, result, correlationId, Map.of());
        }

        public Entry withDetails(Map<String, Object> details) {
            return new Entry(actorType, actorUserId, merchantId, action, targetType, targetId, result, correlationId, details);
        }
    }
}
