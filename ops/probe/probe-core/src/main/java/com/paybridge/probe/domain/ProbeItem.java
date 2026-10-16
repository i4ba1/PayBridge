package com.paybridge.probe.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "probe_item")
public class ProbeItem {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String label;

    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    protected ProbeItem() {
    }

    public ProbeItem(String label, long amountMinor) {
        this.label = label;
        this.amountMinor = amountMinor;
    }

    public Long getId() {
        return id;
    }

    public String getLabel() {
        return label;
    }

    public long getAmountMinor() {
        return amountMinor;
    }
}
