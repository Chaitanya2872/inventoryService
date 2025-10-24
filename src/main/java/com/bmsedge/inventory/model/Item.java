package com.bmsedge.inventory.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Getter
@Entity
@Table(name = "items",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_item_name_sku",
                        columnNames = {"item_name", "item_sku"}
                )
        },
        indexes = {
                @Index(name = "idx_items_sku", columnList = "item_sku"),
                @Index(name = "idx_items_name", columnList = "item_name"),
                @Index(name = "idx_items_status", columnList = "stock_status")
        })
public class Item {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;


    @NotBlank
    @Size(max = 100)
    @Column(name = "item_name", nullable = false)
    private String itemName;

    @Size(max = 100)
    @Column(name = "item_sku")
    private String itemSku;

    @Size(max = 500)
    @Column(name = "item_description")
    private String itemDescription;

    // Stock quantities
    @NotNull
    @Column(name = "current_quantity", precision = 10, scale = 2)
    private BigDecimal currentQuantity = BigDecimal.ZERO;

    @Column(name = "opening_stock", precision = 10, scale = 2)
    private BigDecimal openingStock = BigDecimal.ZERO;

    @Column(name = "closing_stock", precision = 10, scale = 2)
    private BigDecimal closingStock = BigDecimal.ZERO;

    // Cached totals (auto-calculated by trigger)
    @Column(name = "total_consumed_stock", precision = 10, scale = 2)
    private BigDecimal totalConsumedStock = BigDecimal.ZERO;

    @Column(name = "total_received_stock", precision = 10, scale = 2)
    private BigDecimal totalReceivedStock = BigDecimal.ZERO;

    @Column(name = "month_consumed_stock", precision = 10, scale = 2)
    private BigDecimal monthConsumedStock = BigDecimal.ZERO;

    @Column(name = "month_received_stock", precision = 10, scale = 2)
    private BigDecimal monthReceivedStock = BigDecimal.ZERO;

    // Reorder fields
    @Column(name = "reorder_level", precision = 10, scale = 2)
    private BigDecimal reorderLevel;

    @Column(name = "reorder_quantity", precision = 10, scale = 2)
    private BigDecimal reorderQuantity;

    // Statistical fields
    @Column(name = "avg_daily_consumption", precision = 10, scale = 4)
    private BigDecimal avgDailyConsumption;

    @Column(name = "std_daily_consumption", precision = 10, scale = 4)
    private BigDecimal stdDailyConsumption;

    @Column(name = "consumption_cv", precision = 5, scale = 4)
    private BigDecimal consumptionCV;

    @Column(name = "volatility_classification", length = 20)
    private String volatilityClassification = "MEDIUM";

    @Column(name = "is_highly_volatile")
    private Boolean isHighlyVolatile = false;

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
    @Column(name = "coverage_days")
    private Integer coverageDays;

    @Column(name = "stock_alert_level")
    private String stockAlertLevel = "SAFE";

    // NEW: Stock status (simpler than alert level)
    @Column(name = "stock_status", length = 20)
    private String stockStatus = "IN_STOCK";  // IN_STOCK, LOW_STOCK, OUT_OF_STOCK, CRITICAL

    @Column(name = "expected_stockout_date")
    private LocalDate expectedStockoutDate;

    // Bin locations
    @Column(name = "primary_bin_id")
    private Long primaryBinId;

    @Column(name = "secondary_bin_id")
    private Long secondaryBinId;

    // Category relationship
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id")
    private Category category;

    // Dates
    @Column(name = "expiry_date")
    private LocalDateTime expiryDate;

    @Column(name = "last_received_date")
    private LocalDateTime lastReceivedDate;

    @Column(name = "last_consumption_date")
    private LocalDateTime lastConsumptionDate;

    @Column(name = "last_statistics_update")
    private LocalDateTime lastStatisticsUpdate;

    // Analytics fields
    @Column(name = "consumption_pattern", length = 20)
    private String consumptionPattern;

    @Column(name = "trend", length = 20)
    private String trend;

    @Column(name = "forecast_next_period", precision = 10, scale = 2)
    private BigDecimal forecastNextPeriod;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Setter
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    // Constructors
    public Item() {}

    public Item(String itemName, String itemSku, Category category,
                BigDecimal currentQuantity, String unitOfMeasurement) {
        this.itemName = itemName;
        this.itemSku = itemSku;
        this.category = category;
        this.currentQuantity = currentQuantity;
        this.openingStock = currentQuantity;
        this.closingStock = currentQuantity;
        this.unitOfMeasurement = unitOfMeasurement;
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
        updateStockStatus();
    }

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        updateStockAlertLevel();
        updateStockStatus();
        calculateTotalValue();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
        updateStockAlertLevel();
        updateStockStatus();
        calculateTotalValue();
    }

    // Business methods
    public void updateStockAlertLevel() {
        if (currentQuantity == null || reorderLevel == null) return;

        if (currentQuantity.compareTo(reorderLevel.multiply(BigDecimal.valueOf(0.5))) <= 0) {
            this.stockAlertLevel = "CRITICAL";
        } else if (currentQuantity.compareTo(reorderLevel) <= 0) {
            this.stockAlertLevel = "HIGH";
        } else if (currentQuantity.compareTo(reorderLevel.multiply(BigDecimal.valueOf(1.5))) <= 0) {
            this.stockAlertLevel = "MEDIUM";
        } else {
            this.stockAlertLevel = "SAFE";
        }
    }

    /**
     * Update stock status (simpler categorization)
     */
    public void updateStockStatus() {
        if (currentQuantity == null) {
            this.stockStatus = "UNKNOWN";
            return;
        }

        if (currentQuantity.compareTo(BigDecimal.ZERO) <= 0) {
            this.stockStatus = "OUT_OF_STOCK";
        } else if (reorderLevel != null) {
            if (currentQuantity.compareTo(reorderLevel.multiply(BigDecimal.valueOf(0.5))) <= 0) {
                this.stockStatus = "CRITICAL";
            } else if (currentQuantity.compareTo(reorderLevel) <= 0) {
                this.stockStatus = "LOW_STOCK";
            } else {
                this.stockStatus = "IN_STOCK";
            }
        } else {
            this.stockStatus = "IN_STOCK";
        }
    }

    public void calculateTotalValue() {
        if (currentQuantity != null && unitPrice != null) {
            this.totalValue = currentQuantity.multiply(unitPrice);
        }
    }

    public void recordConsumption(BigDecimal quantity) {
        if (quantity != null && quantity.compareTo(BigDecimal.ZERO) > 0) {
            this.currentQuantity = this.currentQuantity.subtract(quantity);
            this.lastConsumptionDate = LocalDateTime.now();
            updateStockAlertLevel();
            updateStockStatus();
        }
    }

    public void recordReceipt(BigDecimal quantity) {
        if (quantity != null && quantity.compareTo(BigDecimal.ZERO) > 0) {
            this.currentQuantity = this.currentQuantity.add(quantity);
            this.lastReceivedDate = LocalDateTime.now();
            updateStockAlertLevel();
            updateStockStatus();
        }
    }

    public boolean needsReorder() {
        if (currentQuantity == null || reorderLevel == null) return false;
        return currentQuantity.compareTo(reorderLevel) <= 0;
    }

    /**
     * Check if item is out of stock
     */
    public boolean isOutOfStock() {
        return currentQuantity == null || currentQuantity.compareTo(BigDecimal.ZERO) <= 0;
    }

    /**
     * Check if item is critically low
     */
    public boolean isCriticallyLow() {
        if (currentQuantity == null || reorderLevel == null) return false;
        return currentQuantity.compareTo(reorderLevel.multiply(BigDecimal.valueOf(0.5))) <= 0;
    }

    /**
     * Get display name with SKU (e.g., "Pril-Dishwash (125ml)")
     */
    public String getFullDisplayName() {
        if (itemSku != null && !itemSku.isEmpty()) {
            return itemName + " (" + itemSku + ")";
        }
        return itemName;
    }

    // ============= GETTERS AND SETTERS =============

    public void setId(Long id) { this.id = id; }



    public void setItemName(String itemName) { this.itemName = itemName; }

    public void setItemSku(String itemSku) { this.itemSku = itemSku; }

    public void setItemDescription(String itemDescription) {
        this.itemDescription = itemDescription;
    }

    public void setCurrentQuantity(BigDecimal currentQuantity) {
        this.currentQuantity = currentQuantity;
        updateStockAlertLevel();
        updateStockStatus();
    }

    public void setOpeningStock(BigDecimal openingStock) {
        this.openingStock = openingStock;
    }

    public void setClosingStock(BigDecimal closingStock) {
        this.closingStock = closingStock;
    }

    public void setTotalConsumedStock(BigDecimal totalConsumedStock) {
        this.totalConsumedStock = totalConsumedStock;
    }

    public void setTotalReceivedStock(BigDecimal totalReceivedStock) {
        this.totalReceivedStock = totalReceivedStock;
    }

    public void setMonthConsumedStock(BigDecimal monthConsumedStock) {
        this.monthConsumedStock = monthConsumedStock;
    }

    public void setMonthReceivedStock(BigDecimal monthReceivedStock) {
        this.monthReceivedStock = monthReceivedStock;
    }

    public void setReorderLevel(BigDecimal reorderLevel) {
        this.reorderLevel = reorderLevel;
        updateStockAlertLevel();
        updateStockStatus();
    }

    public void setReorderQuantity(BigDecimal reorderQuantity) {
        this.reorderQuantity = reorderQuantity;
    }

    public void setAvgDailyConsumption(BigDecimal avgDailyConsumption) {
        this.avgDailyConsumption = avgDailyConsumption;
    }

    public void setStdDailyConsumption(BigDecimal stdDailyConsumption) {
        this.stdDailyConsumption = stdDailyConsumption;
    }

    public void setConsumptionCV(BigDecimal consumptionCV) {
        this.consumptionCV = consumptionCV;
    }

    public void setVolatilityClassification(String volatilityClassification) {
        this.volatilityClassification = volatilityClassification;
    }

    public void setIsHighlyVolatile(Boolean isHighlyVolatile) {
        this.isHighlyVolatile = isHighlyVolatile;
    }

    public void setUnitOfMeasurement(String unitOfMeasurement) {
        this.unitOfMeasurement = unitOfMeasurement;
    }

    public void setUnitPrice(BigDecimal unitPrice) {
        this.unitPrice = unitPrice;
        calculateTotalValue();
    }

    public void setTotalValue(BigDecimal totalValue) {
        this.totalValue = totalValue;
    }

    public void setCoverageDays(Integer coverageDays) {
        this.coverageDays = coverageDays;
    }

    public void setStockAlertLevel(String stockAlertLevel) {
        this.stockAlertLevel = stockAlertLevel;
    }

    public void setStockStatus(String stockStatus) {
        this.stockStatus = stockStatus;
    }

    public void setExpectedStockoutDate(LocalDate expectedStockoutDate) {
        this.expectedStockoutDate = expectedStockoutDate;
    }

    public void setPrimaryBinId(Long primaryBinId) {
        this.primaryBinId = primaryBinId;
    }

    public void setSecondaryBinId(Long secondaryBinId) {
        this.secondaryBinId = secondaryBinId;
    }

    public void setCategory(Category category) { this.category = category; }

    public void setExpiryDate(LocalDateTime expiryDate) {
        this.expiryDate = expiryDate;
    }

    public void setLastReceivedDate(LocalDateTime lastReceivedDate) {
        this.lastReceivedDate = lastReceivedDate;
    }

    public void setLastConsumptionDate(LocalDateTime lastConsumptionDate) {
        this.lastConsumptionDate = lastConsumptionDate;
    }

    public void setLastStatisticsUpdate(LocalDateTime lastStatisticsUpdate) {
        this.lastStatisticsUpdate = lastStatisticsUpdate;
    }

    public void setConsumptionPattern(String consumptionPattern) {
        this.consumptionPattern = consumptionPattern;
    }

    public void setTrend(String trend) {
        this.trend = trend;
    }

    public void setForecastNextPeriod(BigDecimal forecastNextPeriod) {
        this.forecastNextPeriod = forecastNextPeriod;
    }

    public void setCreatedBy(Long createdBy) { this.createdBy = createdBy; }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }


    public void updateStatistics(BigDecimal mean, BigDecimal std, BigDecimal cv, String volatility) {
        this.avgDailyConsumption = mean;
        this.stdDailyConsumption = std;
        this.consumptionCV = cv;
        this.volatilityClassification = volatility;

        // Update highly volatile flag based on classification
        this.isHighlyVolatile = "HIGH".equals(volatility) || "VERY_HIGH".equals(volatility);

        // Update timestamp
        this.lastStatisticsUpdate = LocalDateTime.now();

        // Trigger other dependent updates
        updateStockAlertLevel();
        updateStockStatus();
    }


}