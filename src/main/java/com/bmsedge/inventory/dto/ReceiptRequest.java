package com.bmsedge.inventory.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.bmsedge.inventory.util.BigDecimalDeserializer;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Setter
@Getter
public class ReceiptRequest {

    // Getters and Setters
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

}