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
public class ConsumptionRequest {

    // Getters and Setters
    @NotNull(message = "Quantity is required")
    @Positive(message = "Quantity must be positive")
    @JsonDeserialize(using = BigDecimalDeserializer.class)
    private BigDecimal quantity;

    private String department;

    private String notes;

    // Constructors
    public ConsumptionRequest() {}

    public ConsumptionRequest(BigDecimal quantity, String department) {
        this.quantity = quantity;
        this.department = department;
    }

}
