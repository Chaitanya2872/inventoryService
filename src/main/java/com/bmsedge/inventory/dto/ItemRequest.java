package com.bmsedge.inventory.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Setter
@Getter
public class ItemRequest {

    private String itemCode;

    @NotBlank(message = "Item name is required")
    @Size(max = 100, message = "Item name must not exceed 100 characters")
    private String itemName;

    @Size(max = 100, message = "Item SKU must not exceed 100 characters")
    private String itemSku;  // NEW: SKU for item variants (e.g., "125ml", "500ml", "1L")

    @Size(max = 500, message = "Item description must not exceed 500 characters")
    private String itemDescription;

    @NotNull(message = "Current quantity is required")
    @Min(value = 0, message = "Current quantity must be non-negative")
    private Integer currentQuantity;

    // Replaced min/max with reorder fields
    @DecimalMin(value = "0.0", inclusive = true, message = "Reorder level must be non-negative")
    private BigDecimal reorderLevel;

    @DecimalMin(value = "0.0", inclusive = true, message = "Reorder quantity must be non-negative")
    private BigDecimal reorderQuantity;

    @NotBlank(message = "Unit of measurement is required")
    @Size(max = 50, message = "Unit of measurement must not exceed 50 characters")
    private String unitOfMeasurement;

    @DecimalMin(value = "0.0", inclusive = true, message = "Unit price must be non-negative")
    private BigDecimal unitPrice;

    @NotNull(message = "Category ID is required")
    private Long categoryId;

    private LocalDateTime expiryDate;

    // Stock status (IN_STOCK, LOW_STOCK, OUT_OF_STOCK, CRITICAL)
    private String stockStatus;

    // Bin location IDs
    private Long primaryBinId;
    private Long secondaryBinId;

    // Constructor
    public ItemRequest() {}

    /**
     * Validate that SKU is provided when needed for items with variants
     */
    public boolean isValid() {
        // Add custom validation logic here if needed
        return itemName != null && !itemName.trim().isEmpty();
    }

    /**
     * Get full display name with SKU
     */
    public String getFullDisplayName() {
        if (itemSku != null && !itemSku.isEmpty()) {
            return itemName + " (" + itemSku + ")";
        }
        return itemName;
    }

    /**
     * Check if this is a variant item (has SKU)
     */
    public boolean isVariantItem() {
        return itemSku != null && !itemSku.isEmpty();
    }

    @Override
    public String toString() {
        return "ItemRequest{" +
                "itemCode='" + itemCode + '\'' +
                ", itemName='" + itemName + '\'' +
                ", itemSku='" + itemSku + '\'' +
                ", currentQuantity=" + currentQuantity +
                ", reorderLevel=" + reorderLevel +
                ", unitOfMeasurement='" + unitOfMeasurement + '\'' +
                ", categoryId=" + categoryId +
                ", stockStatus='" + stockStatus + '\'' +
                '}';
    }
}