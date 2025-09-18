package com.bmsedge.inventory.dto;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Setter
@Getter
public class AnalyticsResponse {
    // Main class Getters and Setters
    private UsageStock usageStock;
    private List<ConsumptionTrend> consumptionTrends;
    private LocalDateTime generatedAt;

    public AnalyticsResponse() {
        this.generatedAt = LocalDateTime.now();
    }

    public AnalyticsResponse(UsageStock usageStock, List<ConsumptionTrend> consumptionTrends) {
        this.usageStock = usageStock;
        this.consumptionTrends = consumptionTrends;
        this.generatedAt = LocalDateTime.now();
    }

    // Inner class for Usage Stock
    @Setter
    @Getter
    public static class UsageStock {
        // Getters and Setters
        private Integer totalItems;
        private Integer totalQuantity;
        private Integer lowStockItems;
        private Integer expiredItems;
        private Integer expiringItems;
        private Map<String, Integer> categoryWiseStock;

        public UsageStock() {}

        public UsageStock(Integer totalItems, Integer totalQuantity, Integer lowStockItems,
                          Integer expiredItems, Integer expiringItems, Map<String, Integer> categoryWiseStock) {
            this.totalItems = totalItems;
            this.totalQuantity = totalQuantity;
            this.lowStockItems = lowStockItems;
            this.expiredItems = expiredItems;
            this.expiringItems = expiringItems;
            this.categoryWiseStock = categoryWiseStock;
        }

    }

    // Inner class for Consumption Trend
    public static class ConsumptionTrend {
        private String month;
        private Integer consumed;
        private Integer added;
        private Integer netChange;

        public ConsumptionTrend() {}

        public ConsumptionTrend(String month, Integer consumed, Integer added, Integer netChange) {
            this.month = month;
            this.consumed = consumed;
            this.added = added;
            this.netChange = netChange;
        }

        // Getters and Setters
        public String getMonth() { return month; }
        public void setMonth(String month) { this.month = month; }

        public Integer getConsumed() { return consumed; }
        public void setConsumed(Integer consumed) { this.consumed = consumed; }

        public Integer getAdded() { return added; }
        public void setAdded(Integer added) { this.added = added; }

        public Integer getNetChange() { return netChange; }
        public void setNetChange(Integer netChange) { this.netChange = netChange; }
    }

}