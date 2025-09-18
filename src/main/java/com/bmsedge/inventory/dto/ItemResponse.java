package com.bmsedge.inventory.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

public class ItemResponse {
    private Long id;
    private String itemCode;
    private String itemName;
    private String itemDescription;

    // Stock quantities
    private BigDecimal currentQuantity;
    private BigDecimal openingStock;
    private BigDecimal closingStock;
    private BigDecimal oldStockQuantity;

    // Stock levels
    private BigDecimal maxStockLevel;
    private BigDecimal minStockLevel;
    private BigDecimal reorderLevel;
    private BigDecimal reorderQuantity;

    // Unit and pricing
    private String unitOfMeasurement;
    private BigDecimal unitPrice;
    private BigDecimal totalValue;

    // Coverage and alerts
    private BigDecimal avgDailyConsumption;
    private Integer coverageDays;
    private String stockAlertLevel;
    private LocalDate expectedStockoutDate;

    // Bins
    private Long primaryBinId;
    private Long secondaryBinId;
    private String primaryBinCode;
    private String secondaryBinCode;

    // Dates
    private LocalDateTime expiryDate;
    private LocalDateTime lastReceivedDate;
    private LocalDateTime lastConsumptionDate;

    // QR Code
    private String qrCodePath;
    private String qrCodeData;

    // Category
    private CategoryResponse category;

    // Metadata
    private Long createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // Additional computed fields
    private boolean needsReorder;
    private boolean isExpired;
    private boolean isExpiringSoon;
    private BigDecimal percentageOfStock; // Current stock as % of max

    // Constructors
    public ItemResponse() {}

    // Builder pattern for cleaner construction
    public static class Builder {
        private ItemResponse response = new ItemResponse();

        public Builder id(Long id) {
            response.id = id;
            return this;
        }

        public Builder itemCode(String itemCode) {
            response.itemCode = itemCode;
            return this;
        }

        public Builder itemName(String itemName) {
            response.itemName = itemName;
            return this;
        }

        public Builder itemDescription(String itemDescription) {
            response.itemDescription = itemDescription;
            return this;
        }

        public Builder currentQuantity(BigDecimal currentQuantity) {
            response.currentQuantity = currentQuantity;
            return this;
        }

        public Builder openingStock(BigDecimal openingStock) {
            response.openingStock = openingStock;
            return this;
        }

        public Builder closingStock(BigDecimal closingStock) {
            response.closingStock = closingStock;
            return this;
        }

        public Builder oldStockQuantity(BigDecimal oldStockQuantity) {
            response.oldStockQuantity = oldStockQuantity;
            return this;
        }

        public Builder maxStockLevel(BigDecimal maxStockLevel) {
            response.maxStockLevel = maxStockLevel;
            return this;
        }

        public Builder minStockLevel(BigDecimal minStockLevel) {
            response.minStockLevel = minStockLevel;
            return this;
        }

        public Builder reorderLevel(BigDecimal reorderLevel) {
            response.reorderLevel = reorderLevel;
            return this;
        }

        public Builder reorderQuantity(BigDecimal reorderQuantity) {
            response.reorderQuantity = reorderQuantity;
            return this;
        }

        public Builder unitOfMeasurement(String unitOfMeasurement) {
            response.unitOfMeasurement = unitOfMeasurement;
            return this;
        }

        public Builder unitPrice(BigDecimal unitPrice) {
            response.unitPrice = unitPrice;
            return this;
        }

        public Builder totalValue(BigDecimal totalValue) {
            response.totalValue = totalValue;
            return this;
        }

        public Builder avgDailyConsumption(BigDecimal avgDailyConsumption) {
            response.avgDailyConsumption = avgDailyConsumption;
            return this;
        }

        public Builder coverageDays(Integer coverageDays) {
            response.coverageDays = coverageDays;
            return this;
        }

        public Builder stockAlertLevel(String stockAlertLevel) {
            response.stockAlertLevel = stockAlertLevel;
            return this;
        }

        public Builder expectedStockoutDate(LocalDate expectedStockoutDate) {
            response.expectedStockoutDate = expectedStockoutDate;
            return this;
        }

        public Builder primaryBinId(Long primaryBinId) {
            response.primaryBinId = primaryBinId;
            return this;
        }

        public Builder secondaryBinId(Long secondaryBinId) {
            response.secondaryBinId = secondaryBinId;
            return this;
        }

        public Builder primaryBinCode(String primaryBinCode) {
            response.primaryBinCode = primaryBinCode;
            return this;
        }

        public Builder secondaryBinCode(String secondaryBinCode) {
            response.secondaryBinCode = secondaryBinCode;
            return this;
        }

        public Builder expiryDate(LocalDateTime expiryDate) {
            response.expiryDate = expiryDate;
            return this;
        }

        public Builder lastReceivedDate(LocalDateTime lastReceivedDate) {
            response.lastReceivedDate = lastReceivedDate;
            return this;
        }

        public Builder lastConsumptionDate(LocalDateTime lastConsumptionDate) {
            response.lastConsumptionDate = lastConsumptionDate;
            return this;
        }

        public Builder qrCodePath(String qrCodePath) {
            response.qrCodePath = qrCodePath;
            return this;
        }

        public Builder qrCodeData(String qrCodeData) {
            response.qrCodeData = qrCodeData;
            return this;
        }

        public Builder category(CategoryResponse category) {
            response.category = category;
            return this;
        }

        public Builder createdBy(Long createdBy) {
            response.createdBy = createdBy;
            return this;
        }

        public Builder createdAt(LocalDateTime createdAt) {
            response.createdAt = createdAt;
            return this;
        }

        public Builder updatedAt(LocalDateTime updatedAt) {
            response.updatedAt = updatedAt;
            return this;
        }

        public Builder needsReorder(boolean needsReorder) {
            response.needsReorder = needsReorder;
            return this;
        }

        public Builder isExpired(boolean isExpired) {
            response.isExpired = isExpired;
            return this;
        }

        public Builder isExpiringSoon(boolean isExpiringSoon) {
            response.isExpiringSoon = isExpiringSoon;
            return this;
        }

        public Builder percentageOfStock(BigDecimal percentageOfStock) {
            response.percentageOfStock = percentageOfStock;
            return this;
        }

        public ItemResponse build() {
            return response;
        }
    }

    // All getters and setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getItemCode() { return itemCode; }
    public void setItemCode(String itemCode) { this.itemCode = itemCode; }

    public String getItemName() { return itemName; }
    public void setItemName(String itemName) { this.itemName = itemName; }

    public String getItemDescription() { return itemDescription; }
    public void setItemDescription(String itemDescription) { this.itemDescription = itemDescription; }

    public BigDecimal getCurrentQuantity() { return currentQuantity; }
    public void setCurrentQuantity(BigDecimal currentQuantity) { this.currentQuantity = currentQuantity; }

    public BigDecimal getOpeningStock() { return openingStock; }
    public void setOpeningStock(BigDecimal openingStock) { this.openingStock = openingStock; }

    public BigDecimal getClosingStock() { return closingStock; }
    public void setClosingStock(BigDecimal closingStock) { this.closingStock = closingStock; }

    public BigDecimal getOldStockQuantity() { return oldStockQuantity; }
    public void setOldStockQuantity(BigDecimal oldStockQuantity) { this.oldStockQuantity = oldStockQuantity; }

    public BigDecimal getMaxStockLevel() { return maxStockLevel; }
    public void setMaxStockLevel(BigDecimal maxStockLevel) { this.maxStockLevel = maxStockLevel; }

    public BigDecimal getMinStockLevel() { return minStockLevel; }
    public void setMinStockLevel(BigDecimal minStockLevel) { this.minStockLevel = minStockLevel; }

    public BigDecimal getReorderLevel() { return reorderLevel; }
    public void setReorderLevel(BigDecimal reorderLevel) { this.reorderLevel = reorderLevel; }

    public BigDecimal getReorderQuantity() { return reorderQuantity; }
    public void setReorderQuantity(BigDecimal reorderQuantity) { this.reorderQuantity = reorderQuantity; }

    public String getUnitOfMeasurement() { return unitOfMeasurement; }
    public void setUnitOfMeasurement(String unitOfMeasurement) { this.unitOfMeasurement = unitOfMeasurement; }

    public BigDecimal getUnitPrice() { return unitPrice; }
    public void setUnitPrice(BigDecimal unitPrice) { this.unitPrice = unitPrice; }

    public BigDecimal getTotalValue() { return totalValue; }
    public void setTotalValue(BigDecimal totalValue) { this.totalValue = totalValue; }

    public BigDecimal getAvgDailyConsumption() { return avgDailyConsumption; }
    public void setAvgDailyConsumption(BigDecimal avgDailyConsumption) { this.avgDailyConsumption = avgDailyConsumption; }

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

    public String getPrimaryBinCode() { return primaryBinCode; }
    public void setPrimaryBinCode(String primaryBinCode) { this.primaryBinCode = primaryBinCode; }

    public String getSecondaryBinCode() { return secondaryBinCode; }
    public void setSecondaryBinCode(String secondaryBinCode) { this.secondaryBinCode = secondaryBinCode; }

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

    public CategoryResponse getCategory() { return category; }
    public void setCategory(CategoryResponse category) { this.category = category; }

    public Long getCreatedBy() { return createdBy; }
    public void setCreatedBy(Long createdBy) { this.createdBy = createdBy; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    public boolean isNeedsReorder() { return needsReorder; }
    public void setNeedsReorder(boolean needsReorder) { this.needsReorder = needsReorder; }

    public boolean isExpired() { return isExpired; }
    public void setExpired(boolean expired) { isExpired = expired; }

    public boolean isExpiringSoon() { return isExpiringSoon; }
    public void setExpiringSoon(boolean expiringSoon) { isExpiringSoon = expiringSoon; }

    public BigDecimal getPercentageOfStock() { return percentageOfStock; }
    public void setPercentageOfStock(BigDecimal percentageOfStock) { this.percentageOfStock = percentageOfStock; }
}