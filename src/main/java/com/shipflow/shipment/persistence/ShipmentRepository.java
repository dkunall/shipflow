package com.shipflow.shipment.persistence;

import com.shipflow.shipment.domain.Shipment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ShipmentRepository extends JpaRepository<Shipment, UUID> {
    Optional<Shipment> findByTrackingNumber(String trackingNumber);

    Optional<Shipment> findByCarrierIdAndCarrierAwb(Long carrierId, String awb);
}
