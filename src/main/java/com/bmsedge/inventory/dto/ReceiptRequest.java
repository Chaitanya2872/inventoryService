package com.bmsedge.inventory.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.bmsedge.inventory.util.BigDecimalDeserializer;

import java.math.BigDecimal;

public class ReceiptRequest {

    @NotNull(message = "Quantity is required")
    @Positive(message = "Quantity must be positive")
    @JsonDeserialize(using = BigDecimalDeserializer.class)
    private BigDecimal quantity;

    @JsonDeserialize(using = BigDecimalDeserializer.class)
    private BigDecimal unitPrice;

    private String referenceNumber;

    private String supplier;

    private String notes;

    // Constructors
    public ReceiptRequest() {}

    public ReceiptRequest(BigDecimal quantity, BigDecimal unitPrice, String referenceNumber) {
        this.quantity = quantity;
        this.unitPrice = unitPrice;
        this.referenceNumber = referenceNumber;
    }

    // Getters and Setters
    public BigDecimal getQuantity() { return quantity; }
    public void setQuantity(BigDecimal quantity) { this.quantity = quantity; }

    public BigDecimal getUnitPrice() { return unitPrice; }
    public void setUnitPrice(BigDecimal unitPrice) { this.unitPrice = unitPrice; }

    public String getReferenceNumber() { return referenceNumber; }
    public void setReferenceNumber(String referenceNumber) { this.referenceNumber = referenceNumber; }

    public String getSupplier() { return supplier; }
    public void setSupplier(String supplier) { this.supplier = supplier; }

    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
}