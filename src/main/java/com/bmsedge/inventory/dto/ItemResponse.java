package com.bmsedge.inventory.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public class ItemResponse {
    private Long id;
    private String itemCode;
    private String itemName;
    private String itemDescription;
    private Integer currentQuantity;
    private Integer openingStock;
    private Integer closingStock;

    // New fields for consumed and received stock
    private BigDecimal totalConsumedStock;
    private BigDecimal totalReceivedStock;
    private BigDecimal monthConsumedStock;
    private BigDecimal monthReceivedStock;

    // Reorder fields (replacing min/max)
    private Integer reorderLevel;
    private Integer reorderQuantity;

    // Statistical fields
    private BigDecimal avgDailyConsumption;
    private BigDecimal stdDailyConsumption;
    private BigDecimal consumptionCV;
    private String volatilityClassification;
    private Boolean isHighlyVolatile;

    private String unitOfMeasurement;
    private BigDecimal unitPrice;
    private BigDecimal totalValue;

    private String stockAlertLevel;
    private Integer coverageDays;
    private LocalDate expectedStockoutDate;

    private Long categoryId;
    private String categoryName;

    private LocalDateTime expiryDate;
    private LocalDateTime lastReceivedDate;
    private LocalDateTime lastConsumptionDate;
    private LocalDateTime lastStatisticsUpdate;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // Additional analytics fields
    private String consumptionPattern;
    private String trend;
    private BigDecimal forecastNextPeriod;
    private Boolean needsReorder;
    private Boolean isCritical;

    // Getters and Setters
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getItemCode() {
        return itemCode;
    }

    public void setItemCode(String itemCode) {
        this.itemCode = itemCode;
    }

    public String getItemName() {
        return itemName;
    }

    public void setItemName(String itemName) {
        this.itemName = itemName;
    }

    public String getItemDescription() {
        return itemDescription;
    }

    public void setItemDescription(String itemDescription) {
        this.itemDescription = itemDescription;
    }

    public Integer getCurrentQuantity() {
        return currentQuantity;
    }

    public void setCurrentQuantity(Integer currentQuantity) {
        this.currentQuantity = currentQuantity;
    }

    public Integer getOpeningStock() {
        return openingStock;
    }

    public void setOpeningStock(Integer openingStock) {
        this.openingStock = openingStock;
    }

    public Integer getClosingStock() {
        return closingStock;
    }

    public void setClosingStock(Integer closingStock) {
        this.closingStock = closingStock;
    }

    public BigDecimal getTotalConsumedStock() {
        return totalConsumedStock;
    }

    public void setTotalConsumedStock(BigDecimal totalConsumedStock) {
        this.totalConsumedStock = totalConsumedStock;
    }

    public BigDecimal getTotalReceivedStock() {
        return totalReceivedStock;
    }

    public void setTotalReceivedStock(BigDecimal totalReceivedStock) {
        this.totalReceivedStock = totalReceivedStock;
    }

    public BigDecimal getMonthConsumedStock() {
        return monthConsumedStock;
    }

    public void setMonthConsumedStock(BigDecimal monthConsumedStock) {
        this.monthConsumedStock = monthConsumedStock;
    }

    public BigDecimal getMonthReceivedStock() {
        return monthReceivedStock;
    }

    public void setMonthReceivedStock(BigDecimal monthReceivedStock) {
        this.monthReceivedStock = monthReceivedStock;
    }

    public Integer getReorderLevel() {
        return reorderLevel;
    }

    public void setReorderLevel(Integer reorderLevel) {
        this.reorderLevel = reorderLevel;
    }

    public Integer getReorderQuantity() {
        return reorderQuantity;
    }

    public void setReorderQuantity(Integer reorderQuantity) {
        this.reorderQuantity = reorderQuantity;
    }

    public BigDecimal getAvgDailyConsumption() {
        return avgDailyConsumption;
    }

    public void setAvgDailyConsumption(BigDecimal avgDailyConsumption) {
        this.avgDailyConsumption = avgDailyConsumption;
    }

    public BigDecimal getStdDailyConsumption() {
        return stdDailyConsumption;
    }

    public void setStdDailyConsumption(BigDecimal stdDailyConsumption) {
        this.stdDailyConsumption = stdDailyConsumption;
    }

    public BigDecimal getConsumptionCV() {
        return consumptionCV;
    }

    public void setConsumptionCV(BigDecimal consumptionCV) {
        this.consumptionCV = consumptionCV;
    }

    public String getVolatilityClassification() {
        return volatilityClassification;
    }

    public void setVolatilityClassification(String volatilityClassification) {
        this.volatilityClassification = volatilityClassification;
    }


    // Add this field to ItemResponse.java
    private List<Map<String, Object>> consumptionRecords;

    // Add this getter and setter
    public List<Map<String, Object>> getConsumptionRecords() {
        return consumptionRecords;
    }

    public void setConsumptionRecords(List<Map<String, Object>> consumptionRecords) {
        this.consumptionRecords = consumptionRecords;
    }

    public Boolean getIsHighlyVolatile() {
        return isHighlyVolatile;
    }

    public void setIsHighlyVolatile(Boolean isHighlyVolatile) {
        this.isHighlyVolatile = isHighlyVolatile;
    }

    public String getUnitOfMeasurement() {
        return unitOfMeasurement;
    }

    public void setUnitOfMeasurement(String unitOfMeasurement) {
        this.unitOfMeasurement = unitOfMeasurement;
    }

    public BigDecimal getUnitPrice() {
        return unitPrice;
    }

    public void setUnitPrice(BigDecimal unitPrice) {
        this.unitPrice = unitPrice;
    }

    public BigDecimal getTotalValue() {
        return totalValue;
    }

    public void setTotalValue(BigDecimal totalValue) {
        this.totalValue = totalValue;
    }

    public String getStockAlertLevel() {
        return stockAlertLevel;
    }

    public void setStockAlertLevel(String stockAlertLevel) {
        this.stockAlertLevel = stockAlertLevel;
    }

    public Integer getCoverageDays() {
        return coverageDays;
    }

    public void setCoverageDays(Integer coverageDays) {
        this.coverageDays = coverageDays;
    }

    public LocalDate getExpectedStockoutDate() {
        return expectedStockoutDate;
    }

    public void setExpectedStockoutDate(LocalDate expectedStockoutDate) {
        this.expectedStockoutDate = expectedStockoutDate;
    }

    public Long getCategoryId() {
        return categoryId;
    }

    public void setCategoryId(Long categoryId) {
        this.categoryId = categoryId;
    }

    public String getCategoryName() {
        return categoryName;
    }

    public void setCategoryName(String categoryName) {
        this.categoryName = categoryName;
    }

    public LocalDateTime getExpiryDate() {
        return expiryDate;
    }

    public void setExpiryDate(LocalDateTime expiryDate) {
        this.expiryDate = expiryDate;
    }

    public LocalDateTime getLastReceivedDate() {
        return lastReceivedDate;
    }

    public void setLastReceivedDate(LocalDateTime lastReceivedDate) {
        this.lastReceivedDate = lastReceivedDate;
    }

    public LocalDateTime getLastConsumptionDate() {
        return lastConsumptionDate;
    }

    public void setLastConsumptionDate(LocalDateTime lastConsumptionDate) {
        this.lastConsumptionDate = lastConsumptionDate;
    }

    public LocalDateTime getLastStatisticsUpdate() {
        return lastStatisticsUpdate;
    }

    public void setLastStatisticsUpdate(LocalDateTime lastStatisticsUpdate) {
        this.lastStatisticsUpdate = lastStatisticsUpdate;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    public String getConsumptionPattern() {
        return consumptionPattern;
    }

    public void setConsumptionPattern(String consumptionPattern) {
        this.consumptionPattern = consumptionPattern;
    }

    public String getTrend() {
        return trend;
    }

    public void setTrend(String trend) {
        this.trend = trend;
    }

    public BigDecimal getForecastNextPeriod() {
        return forecastNextPeriod;
    }

    public void setForecastNextPeriod(BigDecimal forecastNextPeriod) {
        this.forecastNextPeriod = forecastNextPeriod;
    }

    public Boolean getNeedsReorder() {
        return needsReorder;
    }

    public void setNeedsReorder(Boolean needsReorder) {
        this.needsReorder = needsReorder;
    }

    public Boolean getIsCritical() {
        return isCritical;
    }

    public void setIsCritical(Boolean isCritical) {
        this.isCritical = isCritical;
    }
}