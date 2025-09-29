package com.bmsedge.inventory.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Setter
public class ItemRequest {

    // Getters and Setters
    @Getter
    private String itemCode;

    @Getter
    @NotBlank(message = "Item name is required")
    private String itemName;

    @Getter
    private String itemDescription;

    @Getter
    @NotNull(message = "Current quantity is required")
    @Min(value = 0, message = "Current quantity must be non-negative")
    private Integer currentQuantity;

    // Replaced min/max with reorder fields
    @Getter
    @DecimalMin(value = "0.0", inclusive = true, message = "Reorder level must be non-negative")
    private BigDecimal reorderLevel;

    @Getter
    @DecimalMin(value = "0.0", inclusive = true, message = "Reorder quantity must be non-negative")
    private BigDecimal reorderQuantity;

    @Getter
    @NotBlank(message = "Unit of measurement is required")
    private String unitOfMeasurement;

    @Getter
    private BigDecimal unitPrice;

    @Getter
    @NotNull(message = "Category ID is required")
    private Long categoryId;

    @Getter
    private LocalDateTime expiryDate;


    // Constructor
    public ItemRequest() {}

    // Setter for oldStockQuantity
    private int oldStockQuantity;
    // Setter for stockAlertLevel
    // Getter for stockAlertLevel
    @Getter
    private String stockAlertLevel;

}