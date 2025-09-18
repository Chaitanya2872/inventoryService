package com.bmsedge.inventory.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.bmsedge.inventory.util.BigDecimalDeserializer;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@JsonIgnoreProperties(ignoreUnknown = true)
public class ItemRequest {
    @NotBlank(message = "Item name is required")
    @Size(max = 100, message = "Item name must not exceed 100 characters")
    private String itemName;

    @Size(max = 500, message = "Item description must not exceed 500 characters")
    private String itemDescription;

    @NotNull(message = "Current quantity is required")
    @Min(value = 0, message = "Current quantity cannot be negative")
    private Integer currentQuantity;

    @Min(value = 0, message = "Old stock quantity cannot be negative")
    private Integer oldStockQuantity;

    @NotNull(message = "Max stock level is required")
    @Min(value = 1, message = "Max stock level must be at least 1")
    private Integer maxStockLevel;

    @NotNull(message = "Min stock level is required")
    @Min(value = 0, message = "Min stock level cannot be negative")
    private Integer minStockLevel;

    private LocalDate expiryDate;

    @NotNull(message = "Category ID is required")
    private Long categoryId;

    @NotBlank(message = "Unit of measurement is required")
    @Size(max = 50, message = "Unit of measurement must not exceed 50 characters")
    private String unitOfMeasurement;

    // Use custom deserializer for BigDecimal to handle empty objects
    @JsonDeserialize(using = BigDecimalDeserializer.class)
    private BigDecimal unitPrice;

    @JsonDeserialize(using = BigDecimalDeserializer.class)
    private BigDecimal reorderLevel;

    @JsonDeserialize(using = BigDecimalDeserializer.class)
    private BigDecimal reorderQuantity;

    private Long primaryBinId;
    private Long secondaryBinId;

    // Default constructor (required for Jackson deserialization)
    public ItemRequest() {}

    // Legacy constructor for backward compatibility with FileUploadService
    public ItemRequest(String itemName, String itemDescription, Integer currentQuantity,
                       Integer oldStockQuantity, LocalDateTime expiryDate, Long categoryId) {
        this.itemName = itemName;
        this.itemDescription = itemDescription;
        this.currentQuantity = currentQuantity;
        this.oldStockQuantity = oldStockQuantity;
        this.expiryDate = expiryDate != null ? expiryDate.toLocalDate() : null;
        this.categoryId = categoryId;
        // Set default stock levels - these should be updated by business logic
        this.minStockLevel = 5; // Default minimum
        this.maxStockLevel = Math.max(currentQuantity + 50, 100); // Default maximum
        this.unitOfMeasurement = "pcs"; // Default unit
    }

    public ItemRequest(String itemName, String itemDescription, Integer currentQuantity,
                       Integer oldStockQuantity, Integer maxStockLevel, Integer minStockLevel,
                       LocalDateTime expiryDate, Long categoryId) {
        this.itemName = itemName;
        this.itemDescription = itemDescription;
        this.currentQuantity = currentQuantity;
        this.oldStockQuantity = oldStockQuantity;
        this.maxStockLevel = maxStockLevel;
        this.minStockLevel = minStockLevel;
        this.expiryDate = expiryDate != null ? expiryDate.toLocalDate() : null;
        this.categoryId = categoryId;
        this.unitOfMeasurement = "pcs"; // Default unit
    }

    public ItemRequest(String itemName, String itemDescription, Integer currentQuantity,
                       Integer oldStockQuantity, Integer maxStockLevel, Integer minStockLevel,
                       String unitOfMeasurement, LocalDateTime expiryDate, Long categoryId) {
        this.itemName = itemName;
        this.itemDescription = itemDescription;
        this.currentQuantity = currentQuantity;
        this.oldStockQuantity = oldStockQuantity;
        this.maxStockLevel = maxStockLevel;
        this.minStockLevel = minStockLevel;
        this.unitOfMeasurement = unitOfMeasurement;
        this.expiryDate = expiryDate != null ? expiryDate.toLocalDate() : null;
        this.categoryId = categoryId;
    }

    // Helper methods for validation
    public void validate() {
        // Set defaults for empty/zero values
        if (maxStockLevel == null || maxStockLevel == 0) {
            maxStockLevel = currentQuantity != null ? Math.max(currentQuantity + 50, 100) : 100;
        }

        if (minStockLevel == null || minStockLevel == 0) {
            minStockLevel = 5;
        }

        if (categoryId == null || categoryId == 0) {
            throw new IllegalArgumentException("Category ID is required and must be valid");
        }

        if (unitOfMeasurement == null || unitOfMeasurement.trim().isEmpty()) {
            unitOfMeasurement = "pcs";
        }

        // Ensure min < max
        if (minStockLevel >= maxStockLevel) {
            // Auto-adjust if invalid
            maxStockLevel = minStockLevel + 50;
        }
    }

    // Getters and Setters
    public String getItemName() { return itemName; }
    public void setItemName(String itemName) { this.itemName = itemName; }

    public String getItemDescription() { return itemDescription; }
    public void setItemDescription(String itemDescription) { this.itemDescription = itemDescription; }

    public Integer getCurrentQuantity() { return currentQuantity; }
    public void setCurrentQuantity(Integer currentQuantity) { this.currentQuantity = currentQuantity; }

    public Integer getOldStockQuantity() { return oldStockQuantity; }
    public void setOldStockQuantity(Integer oldStockQuantity) { this.oldStockQuantity = oldStockQuantity; }

    public Integer getMaxStockLevel() { return maxStockLevel; }
    public void setMaxStockLevel(Integer maxStockLevel) { this.maxStockLevel = maxStockLevel; }

    public Integer getMinStockLevel() { return minStockLevel; }
    public void setMinStockLevel(Integer minStockLevel) { this.minStockLevel = minStockLevel; }

    public String getUnitOfMeasurement() { return unitOfMeasurement; }
    public void setUnitOfMeasurement(String unitOfMeasurement) { this.unitOfMeasurement = unitOfMeasurement; }

    public LocalDate getExpiryDate() { return expiryDate; }
    public void setExpiryDate(LocalDate expiryDate) { this.expiryDate = expiryDate; }

    public Long getCategoryId() { return categoryId; }
    public void setCategoryId(Long categoryId) { this.categoryId = categoryId; }

    public BigDecimal getUnitPrice() { return unitPrice; }
    public void setUnitPrice(BigDecimal unitPrice) { this.unitPrice = unitPrice; }

    public BigDecimal getReorderLevel() { return reorderLevel; }
    public void setReorderLevel(BigDecimal reorderLevel) { this.reorderLevel = reorderLevel; }

    public BigDecimal getReorderQuantity() { return reorderQuantity; }
    public void setReorderQuantity(BigDecimal reorderQuantity) { this.reorderQuantity = reorderQuantity; }

    public Long getPrimaryBinId() { return primaryBinId; }
    public void setPrimaryBinId(Long primaryBinId) { this.primaryBinId = primaryBinId; }

    public Long getSecondaryBinId() { return secondaryBinId; }
    public void setSecondaryBinId(Long secondaryBinId) { this.secondaryBinId = secondaryBinId; }
}