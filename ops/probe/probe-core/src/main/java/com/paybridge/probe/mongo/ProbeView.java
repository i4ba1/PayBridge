package com.paybridge.probe.mongo;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("probe_view")
public record ProbeView(@Id String id, String text, long revision) {
}
