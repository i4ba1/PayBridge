package com.paybridge.probe.mongo;

import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

@org.springframework.context.annotation.Profile("!standalone")
@Component
public class ProbeViewStore {
    private final MongoTemplate mongo;

    public ProbeViewStore(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    public void upsert(String id, String text, long revision) {
        mongo.upsert(Query.query(Criteria.where("_id").is(id)),
                new Update().set("text", text).max("revision", revision), ProbeView.class);
    }

    public ProbeView find(String id) {
        return mongo.findById(id, ProbeView.class);
    }
}
