package com.bmsedge.inventory.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "items")
public class Item {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Size(max = 50)
    @Column(name = "item_code", unique = true)
    private String itemCode;

    @NotBlank
    @Size(max = 100)
    @Column(name = "item_name")
    private String itemName;

    @Size(max = 500)
    @Column(name = "item_description")
    private String itemDescription;

    // Stock quantities - Using BigDecimal for precision
    @NotNull
    @Min(0)
    @Column(name = "current_quantity", precision = 10, scale = 2)
    private BigDecimal currentQuantity = BigDecimal.ZERO;

    @Column(name = "opening_stock", precision = 10, scale = 2)
    private BigDecimal openingStock = BigDecimal.ZERO;

    @Column(name = "closing_stock", precision = 10, scale = 2)
    private BigDecimal closingStock = BigDecimal.ZERO;

    @Min(0)
    @Column(name = "old_stock_quantity", precision = 10, scale = 2)
    private BigDecimal oldStockQuantity;

    // Stock thresholds
    @NotNull
    @Min(1)
    @Column(name = "max_stock_level", precision = 10, scale = 2)
    private BigDecimal maxStockLevel;

    @NotNull
    @Min(0)
    @Column(name = "min_stock_level", precision = 10, scale = 2)
    private BigDecimal minStockLevel;

    @Column(name = "reorder_level", precision = 10, scale = 2)
    private BigDecimal reorderLevel;

    @Column(name = "reorder_quantity", precision = 10, scale = 2)
    private BigDecimal reorderQuantity;

    // Unit and pricing
    @NotBlank
    @Size(max = 50)
    @Column(name = "unit_of_measurement")
    private String unitOfMeasurement = "pcs";

    @Column(name = "unit_price", precision = 10, scale = 2)
    private BigDecimal unitPrice;

    @Column(name = "total_value", precision = 12, scale = 2)
    private BigDecimal totalValue;

    // Coverage and alerts
    @Column(name = "avg_daily_consumption", precision = 10, scale = 2)
    private BigDecimal avgDailyConsumption;

    @Column(name = "coverage_days")
    private Integer coverageDays;

    @Column(name = "stock_alert_level")
    private String stockAlertLevel = "SAFE"; // HIGH, MEDIUM, LOW, SAFE

    @Column(name = "expected_stockout_date")
    private LocalDate expectedStockoutDate;

    // Bin allocation
    @Column(name = "primary_bin_id")
    private Long primaryBinId;

    @Column(name = "secondary_bin_id")
    private Long secondaryBinId;

    // Dates
    @Column(name = "expiry_date")
    private LocalDateTime expiryDate;

    @Column(name = "last_received_date")
    private LocalDateTime lastReceivedDate;

    @Column(name = "last_consumption_date")
    private LocalDateTime lastConsumptionDate;

    @Column(name = "qr_code_path")
    private String qrCodePath;

    @Column(name = "qr_code_data")
    private String qrCodeData;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id")
    private Category category;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    // Constructors
    public Item() {}

    // Constructor for basic item creation
    public Item(String itemName, String itemDescription, BigDecimal currentQuantity,
                BigDecimal maxStockLevel, BigDecimal minStockLevel, String unitOfMeasurement,
                LocalDateTime expiryDate, Category category, Long createdBy) {
        this.itemName = itemName;
        this.itemDescription = itemDescription;
        this.currentQuantity = currentQuantity;
        this.openingStock = currentQuantity;
        this.closingStock = currentQuantity;
        this.maxStockLevel = maxStockLevel;
        this.minStockLevel = minStockLevel;
        this.unitOfMeasurement = unitOfMeasurement;
        this.expiryDate = expiryDate;
        this.category = category;
        this.createdBy = createdBy;
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();

        // Set reorder level as 2x minimum stock level by default
        this.reorderLevel = minStockLevel.multiply(BigDecimal.valueOf(2));
        this.reorderQuantity = maxStockLevel.subtract(minStockLevel);

        // Calculate initial alert level
        updateStockAlertLevel();
    }

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        updateStockAlertLevel();
        calculateTotalValue();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
        updateStockAlertLevel();
        calculateTotalValue();
        calculateCoverageDays();
    }

    // Business methods
    public void updateStockAlertLevel() {
        if (currentQuantity == null || minStockLevel == null) return;

        if (currentQuantity.compareTo(minStockLevel) <= 0) {
            this.stockAlertLevel = "HIGH";
        } else if (reorderLevel != null && currentQuantity.compareTo(reorderLevel) <= 0) {
            this.stockAlertLevel = "MEDIUM";
        } else if (currentQuantity.compareTo(minStockLevel.multiply(BigDecimal.valueOf(2))) <= 0) {
            this.stockAlertLevel = "LOW";
        } else {
            this.stockAlertLevel = "SAFE";
        }
    }

    public void calculateTotalValue() {
        if (currentQuantity != null && unitPrice != null) {
            this.totalValue = currentQuantity.multiply(unitPrice);
        }
    }

    public void calculateCoverageDays() {
        if (currentQuantity != null && avgDailyConsumption != null &&
                avgDailyConsumption.compareTo(BigDecimal.ZERO) > 0) {
            this.coverageDays = currentQuantity.divide(avgDailyConsumption, 0, BigDecimal.ROUND_DOWN).intValue();

            // Calculate expected stockout date
            if (coverageDays != null && coverageDays > 0) {
                this.expectedStockoutDate = LocalDate.now().plusDays(coverageDays);
            }
        }
    }

    // Helper method to check if item needs reorder
    public boolean needsReorder() {
        if (currentQuantity == null || reorderLevel == null) return false;
        return currentQuantity.compareTo(reorderLevel) <= 0;
    }

    // Helper method to check if item is expired
    public boolean isExpired() {
        if (expiryDate == null) return false;
        return expiryDate.isBefore(LocalDateTime.now());
    }

    // Helper method to check if item is expiring soon (within days)
    public boolean isExpiringSoon(int days) {
        if (expiryDate == null) return false;
        return expiryDate.isBefore(LocalDateTime.now().plusDays(days));
    }

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getItemCode() { return itemCode; }
    public void setItemCode(String itemCode) { this.itemCode = itemCode; }

    public String getItemName() { return itemName; }
    public void setItemName(String itemName) { this.itemName = itemName; }

    public String getItemDescription() { return itemDescription; }
    public void setItemDescription(String itemDescription) { this.itemDescription = itemDescription; }

    public BigDecimal getCurrentQuantity() { return currentQuantity; }
    public void setCurrentQuantity(BigDecimal currentQuantity) {
        this.currentQuantity = currentQuantity;
        updateStockAlertLevel();
        calculateTotalValue();
    }

    public BigDecimal getOpeningStock() { return openingStock; }
    public void setOpeningStock(BigDecimal openingStock) { this.openingStock = openingStock; }

    public BigDecimal getClosingStock() { return closingStock; }
    public void setClosingStock(BigDecimal closingStock) { this.closingStock = closingStock; }

    public BigDecimal getOldStockQuantity() { return oldStockQuantity; }
    public void setOldStockQuantity(BigDecimal oldStockQuantity) { this.oldStockQuantity = oldStockQuantity; }

    public BigDecimal getMaxStockLevel() { return maxStockLevel; }
    public void setMaxStockLevel(BigDecimal maxStockLevel) { this.maxStockLevel = maxStockLevel; }

    public BigDecimal getMinStockLevel() { return minStockLevel; }
    public void setMinStockLevel(BigDecimal minStockLevel) {
        this.minStockLevel = minStockLevel;
        updateStockAlertLevel();
    }

    public BigDecimal getReorderLevel() { return reorderLevel; }
    public void setReorderLevel(BigDecimal reorderLevel) { this.reorderLevel = reorderLevel; }

    public BigDecimal getReorderQuantity() { return reorderQuantity; }
    public void setReorderQuantity(BigDecimal reorderQuantity) { this.reorderQuantity = reorderQuantity; }

    public String getUnitOfMeasurement() { return unitOfMeasurement; }
    public void setUnitOfMeasurement(String unitOfMeasurement) { this.unitOfMeasurement = unitOfMeasurement; }

    public BigDecimal getUnitPrice() { return unitPrice; }
    public void setUnitPrice(BigDecimal unitPrice) {
        this.unitPrice = unitPrice;
        calculateTotalValue();
    }

    public BigDecimal getTotalValue() { return totalValue; }
    public void setTotalValue(BigDecimal totalValue) { this.totalValue = totalValue; }

    public BigDecimal getAvgDailyConsumption() { return avgDailyConsumption; }
    public void setAvgDailyConsumption(BigDecimal avgDailyConsumption) {
        this.avgDailyConsumption = avgDailyConsumption;
        calculateCoverageDays();
    }

    public Integer getCoverageDays() { return coverageDays; }
    public void setCoverageDays(Integer coverageDays) { this.coverageDays = coverageDays; }

    public String getStockAlertLevel() { return stockAlertLevel; }
    public void setStockAlertLevel(String stockAlertLevel) { this.stockAlertLevel = stockAlertLevel; }

    public LocalDate getExpectedStockoutDate() { return expectedStockoutDate; }
    public void setExpectedStockoutDate(LocalDate expectedStockoutDate) { this.expectedStockoutDate = expectedStockoutDate; }

    public Long getPrimaryBinId() { return primaryBinId; }
    public void setPrimaryBinId(Long primaryBinId) { this.primaryBinId = primaryBinId; }

    public Long getSecondaryBinId() { return secondaryBinId; }
    public void setSecondaryBinId(Long secondaryBinId) { this.secondaryBinId = secondaryBinId; }

    public LocalDateTime getExpiryDate() { return expiryDate; }
    public void setExpiryDate(LocalDateTime expiryDate) { this.expiryDate = expiryDate; }

    public LocalDateTime getLastReceivedDate() { return lastReceivedDate; }
    public void setLastReceivedDate(LocalDateTime lastReceivedDate) { this.lastReceivedDate = lastReceivedDate; }

    public LocalDateTime getLastConsumptionDate() { return lastConsumptionDate; }
    public void setLastConsumptionDate(LocalDateTime lastConsumptionDate) { this.lastConsumptionDate = lastConsumptionDate; }

    public String getQrCodePath() { return qrCodePath; }
    public void setQrCodePath(String qrCodePath) { this.qrCodePath = qrCodePath; }

    public String getQrCodeData() { return qrCodeData; }
    public void setQrCodeData(String qrCodeData) { this.qrCodeData = qrCodeData; }

    public Category getCategory() { return category; }
    public void setCategory(Category category) { this.category = category; }

    public Long getCreatedBy() { return createdBy; }
    public void setCreatedBy(Long createdBy) { this.createdBy = createdBy; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}