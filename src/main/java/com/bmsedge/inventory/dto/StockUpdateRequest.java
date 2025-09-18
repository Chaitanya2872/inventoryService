package com.bmsedge.inventory.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.bmsedge.inventory.util.BigDecimalDeserializer;

import java.math.BigDecimal;

public class StockUpdateRequest {

    @NotNull(message = "Item ID is required")
    private Long itemId;

    @NotNull(message = "New quantity is required")
    @Positive(message = "New quantity must be positive")
    @JsonDeserialize(using = BigDecimalDeserializer.class)
    private BigDecimal newQuantity;

    private String notes;

    private String reason;

    // Constructors
    public StockUpdateRequest() {}

    public StockUpdateRequest(Long itemId, BigDecimal newQuantity, String notes) {
        this.itemId = itemId;
        this.newQuantity = newQuantity;
        this.notes = notes;
    }

    // Getters and Setters
    public Long getItemId() { return itemId; }
    public void setItemId(Long itemId) { this.itemId = itemId; }

    public BigDecimal getNewQuantity() { return newQuantity; }
    public void setNewQuantity(BigDecimal newQuantity) { this.newQuantity = newQuantity; }

    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
}