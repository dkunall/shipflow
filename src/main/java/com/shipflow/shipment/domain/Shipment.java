package com.shipflow.shipment.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * Shipment aggregate root. Status only changes through {@link #moveTo} which enforces the explicit state machine;
 * {@code @Version} provides optimistic locking for concurrent scans/commands on the same shipment.
 */
@Entity
@Table(name = "shipment")
public class Shipment {

    @Id
    private UUID id;
    @Column(name = "merchant_id", nullable = false)
    private UUID merchantId;
    @Column(name = "merchant_order_ref", nullable = false, length = 100)
    private String merchantOrderRef;
    @Column(name = "tracking_number", nullable = false, length = 20)
    private String trackingNumber;
    @Column(nullable = false, length = 32)
    private String source;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private ShipmentStatus status;
    @Column(name = "status_rank", nullable = false)
    private int statusRank;
    @Column(name = "status_event_at")
    private Instant statusEventAt;
    @Version
    private Long version; // wrapper: null => new entity => plain INSERT (no merge SELECT)
    @Column(name = "origin_hub_id", nullable = false)
    private long originHubId;
    @Column(name = "delivery_hub_id", nullable = false)
    private long deliveryHubId;
    @Column(name = "destination_pincode", nullable = false, length = 16)
    private String destinationPincode;
    @Column(name = "zone_code", nullable = false, length = 32)
    private String zoneCode;
    @Column(name = "country_code", nullable = false, length = 2)
    private String countryCode;
    @Column(name = "consignee_name", nullable = false, length = 200)
    private String consigneeName;
    @Column(name = "consignee_phone", nullable = false, length = 20)
    private String consigneePhone;
    @Column(name = "consignee_phone_hash", nullable = false, length = 64)
    private String consigneePhoneHash;
    @Column(name = "address_line1", nullable = false, length = 300)
    private String addressLine1;
    @Column(name = "address_line2", length = 300)
    private String addressLine2;
    @Column(length = 200)
    private String landmark;
    @Column(nullable = false, length = 100)
    private String city;
    @Column(name = "dest_lat", nullable = false)
    private double destLat;
    @Column(name = "dest_lng", nullable = false)
    private double destLng;
    @Column(name = "weight_g", nullable = false)
    private int weightG;
    @Enumerated(EnumType.STRING)
    @Column(name = "payment_mode", nullable = false, length = 8)
    private PaymentMode paymentMode;
    @Column(name = "cod_amount", precision = 12, scale = 2)
    private BigDecimal codAmount;
    @Column(nullable = false, length = 3)
    private String currency;
    @Column(name = "declared_value", nullable = false, precision = 12, scale = 2)
    private BigDecimal declaredValue;
    @Column(name = "carrier_id")
    private Long carrierId;
    @Column(name = "carrier_awb", length = 64)
    private String carrierAwb;
    @Column(name = "shipping_cost", precision = 12, scale = 2)
    private BigDecimal shippingCost;
    @Column(name = "dispatch_date")
    private LocalDate dispatchDate;
    @Column(name = "promised_date")
    private LocalDate promisedDate;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "edd_explanation", columnDefinition = "jsonb")
    private String eddExplanation;
    @Column(name = "risk_score", nullable = false)
    private int riskScore;
    @Column(name = "risk_band", nullable = false, length = 8)
    private String riskBand;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "risk_assessment", columnDefinition = "jsonb")
    private String riskAssessment;
    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;
    @Column(name = "last_ndr_reason", length = 32)
    private String lastNdrReason;
    @Column(name = "delivered_at")
    private Instant deliveredAt;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Shipment() {
    }

    /** Factory for a freshly booked shipment (status CREATED). */
    public static Shipment book(NewShipment n, Instant now) {
        Shipment s = new Shipment();
        s.id = Objects.requireNonNull(n.id());
        s.merchantId = n.merchantId();
        s.merchantOrderRef = n.merchantOrderRef();
        s.trackingNumber = n.trackingNumber();
        s.source = n.source();
        s.status = ShipmentStatus.CREATED;
        s.statusRank = ShipmentStatus.CREATED.rank();
        s.originHubId = n.originHubId();
        s.deliveryHubId = n.deliveryHubId();
        s.destinationPincode = n.pincode();
        s.zoneCode = n.zoneCode();
        s.countryCode = n.countryCode();
        s.consigneeName = n.consigneeName();
        s.consigneePhone = n.consigneePhone();
        s.consigneePhoneHash = n.consigneePhoneHash();
        s.addressLine1 = n.addressLine1();
        s.addressLine2 = n.addressLine2();
        s.landmark = n.landmark();
        s.city = n.city();
        s.destLat = n.lat();
        s.destLng = n.lng();
        s.weightG = n.weightG();
        s.paymentMode = n.paymentMode();
        s.codAmount = n.codAmount();
        s.currency = n.currency();
        s.declaredValue = n.declaredValue();
        s.riskScore = n.riskScore();
        s.riskBand = n.riskBand();
        s.riskAssessment = n.riskAssessmentJson();
        s.createdAt = now;
        s.updatedAt = now;
        return s;
    }

    /** Input for {@link #book}; keeps the factory signature readable. */
    public record NewShipment(UUID id, UUID merchantId, String merchantOrderRef, String trackingNumber, String source,
                              long originHubId, long deliveryHubId, String pincode, String zoneCode,
                              String countryCode, String consigneeName, String consigneePhone,
                              String consigneePhoneHash, String addressLine1, String addressLine2, String landmark,
                              String city, double lat, double lng, int weightG, PaymentMode paymentMode,
                              BigDecimal codAmount, String currency, BigDecimal declaredValue, int riskScore,
                              String riskBand, String riskAssessmentJson) {
    }

    /**
     * The only way to change status. Enforces the explicit transition table and maintains derived fields.
     * @param scanOccurredAt event time of the scan that caused it (null for system/command transitions)
     */
    public ShipmentStatus moveTo(ShipmentStatus target, Instant scanOccurredAt, Instant now) {
        if (!ShipmentStateMachine.canTransition(status, target)) {
            throw new InvalidTransitionException(status, target);
        }
        ShipmentStatus previous = status;
        status = target;
        statusRank = target.rank();
        if (scanOccurredAt != null) {
            statusEventAt = scanOccurredAt;
        }
        if (target == ShipmentStatus.OUT_FOR_DELIVERY) {
            attemptCount++;
        }
        if (target == ShipmentStatus.DELIVERED) {
            deliveredAt = scanOccurredAt != null ? scanOccurredAt : now;
        }
        if (target == ShipmentStatus.CREATED) {
            clearCarrier();
        }
        updatedAt = now;
        return previous;
    }

    public void assignCarrier(long carrierId, BigDecimal shippingCost, LocalDate dispatchDate, LocalDate promisedDate,
                              String eddExplanationJson, Instant now) {
        this.carrierId = carrierId;
        this.carrierAwb = null;
        this.shippingCost = shippingCost;
        this.dispatchDate = dispatchDate;
        this.promisedDate = promisedDate;
        this.eddExplanation = eddExplanationJson;
        this.updatedAt = now;
    }

    private void clearCarrier() {
        carrierId = null;
        carrierAwb = null;
        shippingCost = null;
        promisedDate = null;
        eddExplanation = null;
    }

    public void confirmCarrierBooking(String awb, Instant now) {
        this.carrierAwb = awb;
        this.updatedAt = now;
    }

    public void recordNdr(String reason, Instant now) {
        this.lastNdrReason = reason;
        this.updatedAt = now;
    }

    public boolean isCod() {
        return paymentMode == PaymentMode.COD;
    }

    public UUID getId() { return id; }
    public UUID getMerchantId() { return merchantId; }
    public String getMerchantOrderRef() { return merchantOrderRef; }
    public String getTrackingNumber() { return trackingNumber; }
    public String getSource() { return source; }
    public ShipmentStatus getStatus() { return status; }
    public Instant getStatusEventAt() { return statusEventAt; }
    public long getVersion() { return version == null ? 0 : version; }
    public long getOriginHubId() { return originHubId; }
    public long getDeliveryHubId() { return deliveryHubId; }
    public String getDestinationPincode() { return destinationPincode; }
    public String getZoneCode() { return zoneCode; }
    public String getCountryCode() { return countryCode; }
    public String getConsigneeName() { return consigneeName; }
    public String getConsigneePhone() { return consigneePhone; }
    public String getConsigneePhoneHash() { return consigneePhoneHash; }
    public String getAddressLine1() { return addressLine1; }
    public String getAddressLine2() { return addressLine2; }
    public String getLandmark() { return landmark; }
    public String getCity() { return city; }
    public double getDestLat() { return destLat; }
    public double getDestLng() { return destLng; }
    public int getWeightG() { return weightG; }
    public PaymentMode getPaymentMode() { return paymentMode; }
    public BigDecimal getCodAmount() { return codAmount; }
    public String getCurrency() { return currency; }
    public BigDecimal getDeclaredValue() { return declaredValue; }
    public Long getCarrierId() { return carrierId; }
    public String getCarrierAwb() { return carrierAwb; }
    public BigDecimal getShippingCost() { return shippingCost; }
    public LocalDate getDispatchDate() { return dispatchDate; }
    public LocalDate getPromisedDate() { return promisedDate; }
    public String getEddExplanation() { return eddExplanation; }
    public int getRiskScore() { return riskScore; }
    public String getRiskBand() { return riskBand; }
    public String getRiskAssessment() { return riskAssessment; }
    public int getAttemptCount() { return attemptCount; }
    public String getLastNdrReason() { return lastNdrReason; }
    public Instant getDeliveredAt() { return deliveredAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
