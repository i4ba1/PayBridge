package com.paybridge.probe.persistence;

import com.paybridge.probe.domain.ProbeItem;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProbeItemRepository extends JpaRepository<ProbeItem, Long> {
}
