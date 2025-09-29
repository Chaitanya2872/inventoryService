package com.bmsedge.inventory.service;

import com.bmsedge.inventory.model.Item;
import com.bmsedge.inventory.model.ConsumptionRecord;
import com.bmsedge.inventory.model.Category;
import com.bmsedge.inventory.repository.ItemRepository;
import com.bmsedge.inventory.repository.ConsumptionRecordRepository;
import com.bmsedge.inventory.repository.CategoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Transactional
public class StatisticalAnalysisService {

    private static final Logger logger = LoggerFactory.getLogger(StatisticalAnalysisService.class);

    @Autowired
    private ItemRepository itemRepository;

    @Autowired
    private ConsumptionRecordRepository consumptionRepository;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private ItemCorrelationService correlationService;

    /**
     * Update statistics for an item after consumption update
     */
    @Transactional
    public void updateItemStatistics(Long itemId, LocalDate consumptionDate) {
        Item item = itemRepository.findById(itemId)
                .orElseThrow(() -> new RuntimeException("Item not found: " + itemId));

        // Calculate statistics for last 30, 60, and 90 days
        Map<String, Object> stats30 = calculateItemStatistics(itemId, 30);
        Map<String, Object> stats60 = calculateItemStatistics(itemId, 60);
        Map<String, Object> stats90 = calculateItemStatistics(itemId, 90);

        // Use 30-day statistics for primary metrics
        BigDecimal mean = (BigDecimal) stats30.get("mean");
        BigDecimal std = (BigDecimal) stats30.get("standardDeviation");
        BigDecimal cv = (BigDecimal) stats30.get("coefficientOfVariation");
        String volatility = (String) stats30.get("volatilityClassification");

        // Update item with statistics
        item.updateStatistics(mean, std, cv, volatility);
        itemRepository.save(item);

        // Also update correlations for this item
        correlationService.updateCorrelationsForItem(itemId);

        logger.info("Updated statistics for item {}: mean={}, std={}, cv={}, volatility={}",
                item.getItemName(), mean, std, cv, volatility);
    }

    /**
     * Calculate comprehensive statistics for an item
     */
    public Map<String, Object> calculateItemStatistics(Long itemId, int days) {
        Map<String, Object> stats = new HashMap<>();

        Item item = itemRepository.findById(itemId)
                .orElseThrow(() -> new RuntimeException("Item not found: " + itemId));

        LocalDate endDate = LocalDate.now();
        LocalDate startDate = endDate.minusDays(days);

        List<ConsumptionRecord> records = consumptionRepository
                .findByItemAndConsumptionDateBetween(item, startDate, endDate);

        if (records.isEmpty()) {
            stats.put("error", "No consumption data available");
            return stats;
        }

        // Extract consumption values
        List<BigDecimal> consumptions = records.stream()
                .map(r -> r.getConsumedQuantity() != null ? r.getConsumedQuantity() : BigDecimal.ZERO)
                .collect(Collectors.toList());

        // Basic statistics
        BigDecimal mean = calculateMean(consumptions);
        BigDecimal median = calculateMedian(consumptions);
        BigDecimal std = calculateStandardDeviation(consumptions, mean);
        BigDecimal cv = calculateCV(mean, std);
        BigDecimal max = consumptions.stream().max(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
        BigDecimal min = consumptions.stream().min(BigDecimal::compareTo).orElse(BigDecimal.ZERO);

        // Volatility classification
        String volatilityClass = classifyVolatility(cv);

        // Trend analysis
        String trend = analyzeTrend(consumptions);

        // Seasonality detection
        Map<String, Object> seasonality = detectSeasonality(records);

        // Consumption pattern
        String pattern = analyzeConsumptionPattern(records);

        // Days with activity
        long daysWithActivity = records.stream()
                .filter(r -> r.getConsumedQuantity() != null && r.getConsumedQuantity().compareTo(BigDecimal.ZERO) > 0)
                .count();

        // Build statistics map
        stats.put("itemId", itemId);
        stats.put("itemName", item.getItemName());
        stats.put("periodDays", days);
        stats.put("totalRecords", records.size());
        stats.put("mean", mean);
        stats.put("median", median);
        stats.put("standardDeviation", std);
        stats.put("coefficientOfVariation", cv);
        stats.put("max", max);
        stats.put("min", min);
        stats.put("range", max.subtract(min));
        stats.put("volatilityClassification", volatilityClass);
        stats.put("isHighlyVolatile", "HIGH".equals(volatilityClass) || "VERY_HIGH".equals(volatilityClass));
        stats.put("trend", trend);
        stats.put("consumptionPattern", pattern);
        stats.put("daysWithActivity", daysWithActivity);
        stats.put("activityRate", BigDecimal.valueOf(daysWithActivity)
                .divide(BigDecimal.valueOf(days), 2, RoundingMode.HALF_UP));
        stats.put("seasonality", seasonality);
        stats.put("totalConsumption", consumptions.stream().reduce(BigDecimal.ZERO, BigDecimal::add));

        // Percentiles
        stats.put("percentile25", calculatePercentile(consumptions, 25));
        stats.put("percentile75", calculatePercentile(consumptions, 75));
        stats.put("percentile90", calculatePercentile(consumptions, 90));

        // Forecast next period consumption
        BigDecimal forecast = forecastNextPeriod(consumptions, trend);
        stats.put("forecastNextPeriod", forecast);

        return stats;
    }

    /**
     * Calculate statistics for a category
     */
    public Map<String, Object> calculateCategoryStatistics(Long categoryId, int days) {
        Map<String, Object> stats = new HashMap<>();

        Category category = categoryRepository.findById(categoryId)
                .orElseThrow(() -> new RuntimeException("Category not found: " + categoryId));

        LocalDate endDate = LocalDate.now();
        LocalDate startDate = endDate.minusDays(days);

        List<ConsumptionRecord> records = consumptionRepository
                .findByCategoryIdAndDateRange(categoryId, startDate, endDate);

        if (records.isEmpty()) {
            stats.put("error", "No consumption data available for category");
            return stats;
        }

        // Group by item
        Map<Long, List<ConsumptionRecord>> recordsByItem = records.stream()
                .collect(Collectors.groupingBy(r -> r.getItem().getId()));

        // Calculate item-level statistics
        List<Map<String, Object>> itemStats = new ArrayList<>();
        BigDecimal totalCategoryConsumption = BigDecimal.ZERO;

        for (Map.Entry<Long, List<ConsumptionRecord>> entry : recordsByItem.entrySet()) {
            List<BigDecimal> consumptions = entry.getValue().stream()
                    .map(r -> r.getConsumedQuantity() != null ? r.getConsumedQuantity() : BigDecimal.ZERO)
                    .collect(Collectors.toList());

            BigDecimal itemTotal = consumptions.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
            totalCategoryConsumption = totalCategoryConsumption.add(itemTotal);

            Map<String, Object> itemStat = new HashMap<>();
            Item item = itemRepository.findById(entry.getKey()).orElse(null);
            if (item != null) {
                itemStat.put("itemId", item.getId());
                itemStat.put("itemName", item.getItemName());
                itemStat.put("totalConsumption", itemTotal);
                itemStat.put("avgConsumption", calculateMean(consumptions));
                itemStat.put("cv", calculateCV(calculateMean(consumptions),
                        calculateStandardDeviation(consumptions, calculateMean(consumptions))));
                itemStats.add(itemStat);
            }
        }

        // Sort items by total consumption
        itemStats.sort((a, b) -> {
            BigDecimal cons1 = (BigDecimal) a.get("totalConsumption");
            BigDecimal cons2 = (BigDecimal) b.get("totalConsumption");
            return cons2.compareTo(cons1);
        });

        // Calculate category-wide CV
        BigDecimal categoryCv = calculateCategoryCoefficientOfVariation(recordsByItem);

        stats.put("categoryId", categoryId);
        stats.put("categoryName", category.getCategoryName());
        stats.put("periodDays", days);
        stats.put("totalItems", recordsByItem.size());
        stats.put("totalRecords", records.size());
        stats.put("totalConsumption", totalCategoryConsumption);
        stats.put("categoryCV", categoryCv);
        stats.put("categoryVolatility", classifyVolatility(categoryCv));
        stats.put("itemStatistics", itemStats);
        stats.put("topConsumingItems", itemStats.stream().limit(5).collect(Collectors.toList()));

        return stats;
    }

    /**
     * Batch update statistics for all items
     */
    @Transactional
    public Map<String, Object> updateAllItemStatistics() {
        Map<String, Object> result = new HashMap<>();
        List<Item> allItems = itemRepository.findAll();
        int updated = 0;
        int failed = 0;
        List<String> errors = new ArrayList<>();

        for (Item item : allItems) {
            try {
                updateItemStatistics(item.getId(), LocalDate.now());
                updated++;
            } catch (Exception e) {
                failed++;
                errors.add(item.getItemName() + ": " + e.getMessage());
                logger.error("Failed to update statistics for item {}: {}", item.getItemName(), e.getMessage());
            }
        }

        result.put("totalItems", allItems.size());
        result.put("updated", updated);
        result.put("failed", failed);
        if (!errors.isEmpty()) {
            result.put("errors", errors);
        }
        result.put("timestamp", LocalDate.now());

        return result;
    }

    // Helper methods for statistical calculations

    private BigDecimal calculateMean(List<BigDecimal> values) {
        if (values.isEmpty()) return BigDecimal.ZERO;

        BigDecimal sum = values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        return sum.divide(BigDecimal.valueOf(values.size()), 4, RoundingMode.HALF_UP);
    }

    private BigDecimal calculateMedian(List<BigDecimal> values) {
        if (values.isEmpty()) return BigDecimal.ZERO;

        List<BigDecimal> sorted = new ArrayList<>(values);
        sorted.sort(BigDecimal::compareTo);

        int size = sorted.size();
        if (size % 2 == 0) {
            return sorted.get(size / 2 - 1).add(sorted.get(size / 2))
                    .divide(BigDecimal.valueOf(2), 4, RoundingMode.HALF_UP);
        } else {
            return sorted.get(size / 2);
        }
    }

    private BigDecimal calculateStandardDeviation(List<BigDecimal> values, BigDecimal mean) {
        if (values.size() <= 1) return BigDecimal.ZERO;

        BigDecimal sumSquares = values.stream()
                .map(v -> v.subtract(mean).pow(2))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal variance = sumSquares.divide(BigDecimal.valueOf(values.size() - 1), 10, RoundingMode.HALF_UP);

        // Calculate square root
        return sqrt(variance);
    }

    private BigDecimal calculateCV(BigDecimal mean, BigDecimal std) {
        if (mean.compareTo(BigDecimal.ZERO) == 0) return BigDecimal.ZERO;

        return std.divide(mean, 4, RoundingMode.HALF_UP);
    }

    private BigDecimal calculatePercentile(List<BigDecimal> values, int percentile) {
        if (values.isEmpty()) return BigDecimal.ZERO;

        List<BigDecimal> sorted = new ArrayList<>(values);
        sorted.sort(BigDecimal::compareTo);

        int index = (int) Math.ceil(percentile / 100.0 * sorted.size()) - 1;
        index = Math.max(0, Math.min(index, sorted.size() - 1));

        return sorted.get(index);
    }

    private String classifyVolatility(BigDecimal cv) {
        if (cv == null) return "UNKNOWN";

        BigDecimal absCV = cv.abs();

        if (absCV.compareTo(BigDecimal.valueOf(0.75)) > 0) {
            return "VERY_HIGH";
        } else if (absCV.compareTo(BigDecimal.valueOf(0.50)) > 0) {
            return "HIGH";
        } else if (absCV.compareTo(BigDecimal.valueOf(0.25)) > 0) {
            return "MEDIUM";
        } else if (absCV.compareTo(BigDecimal.valueOf(0.10)) > 0) {
            return "LOW";
        } else {
            return "VERY_LOW";
        }
    }

    private String analyzeTrend(List<BigDecimal> values) {
        if (values.size() < 3) return "INSUFFICIENT_DATA";

        // Simple linear regression
        int n = values.size();
        BigDecimal sumX = BigDecimal.ZERO;
        BigDecimal sumY = BigDecimal.ZERO;
        BigDecimal sumXY = BigDecimal.ZERO;
        BigDecimal sumX2 = BigDecimal.ZERO;

        for (int i = 0; i < n; i++) {
            BigDecimal x = BigDecimal.valueOf(i);
            BigDecimal y = values.get(i);

            sumX = sumX.add(x);
            sumY = sumY.add(y);
            sumXY = sumXY.add(x.multiply(y));
            sumX2 = sumX2.add(x.multiply(x));
        }

        BigDecimal slope = BigDecimal.valueOf(n).multiply(sumXY).subtract(sumX.multiply(sumY))
                .divide(BigDecimal.valueOf(n).multiply(sumX2).subtract(sumX.multiply(sumX)),
                        10, RoundingMode.HALF_UP);

        if (slope.compareTo(BigDecimal.valueOf(0.1)) > 0) {
            return "INCREASING";
        } else if (slope.compareTo(BigDecimal.valueOf(-0.1)) < 0) {
            return "DECREASING";
        } else {
            return "STABLE";
        }
    }

    private Map<String, Object> detectSeasonality(List<ConsumptionRecord> records) {
        Map<String, Object> seasonality = new HashMap<>();

        // Group by day of week
        Map<Integer, List<BigDecimal>> byDayOfWeek = new HashMap<>();
        for (ConsumptionRecord record : records) {
            int dayOfWeek = record.getConsumptionDate().getDayOfWeek().getValue();
            byDayOfWeek.computeIfAbsent(dayOfWeek, k -> new ArrayList<>())
                    .add(record.getConsumedQuantity() != null ? record.getConsumedQuantity() : BigDecimal.ZERO);
        }

        // Calculate average for each day
        Map<String, BigDecimal> dayAverages = new HashMap<>();
        String[] days = {"Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"};

        for (int i = 1; i <= 7; i++) {
            if (byDayOfWeek.containsKey(i)) {
                dayAverages.put(days[i - 1], calculateMean(byDayOfWeek.get(i)));
            }
        }

        seasonality.put("dayOfWeekPattern", dayAverages);

        // Detect weekly pattern
        BigDecimal weekdayAvg = BigDecimal.ZERO;
        BigDecimal weekendAvg = BigDecimal.ZERO;
        int weekdayCount = 0;
        int weekendCount = 0;

        for (Map.Entry<Integer, List<BigDecimal>> entry : byDayOfWeek.entrySet()) {
            BigDecimal avg = calculateMean(entry.getValue());
            if (entry.getKey() <= 5) {
                weekdayAvg = weekdayAvg.add(avg);
                weekdayCount++;
            } else {
                weekendAvg = weekendAvg.add(avg);
                weekendCount++;
            }
        }

        if (weekdayCount > 0) {
            weekdayAvg = weekdayAvg.divide(BigDecimal.valueOf(weekdayCount), 4, RoundingMode.HALF_UP);
        }
        if (weekendCount > 0) {
            weekendAvg = weekendAvg.divide(BigDecimal.valueOf(weekendCount), 4, RoundingMode.HALF_UP);
        }

        seasonality.put("weekdayAverage", weekdayAvg);
        seasonality.put("weekendAverage", weekendAvg);

        return seasonality;
    }

    private String analyzeConsumptionPattern(List<ConsumptionRecord> records) {
        if (records.isEmpty()) return "NO_DATA";

        // Count zero consumption days
        long zeroDays = records.stream()
                .filter(r -> r.getConsumedQuantity() == null || r.getConsumedQuantity().compareTo(BigDecimal.ZERO) == 0)
                .count();

        double zeroRatio = (double) zeroDays / records.size();

        if (zeroRatio > 0.7) {
            return "SPORADIC";
        } else if (zeroRatio > 0.3) {
            return "IRREGULAR";
        } else {
            return "REGULAR";
        }
    }

    private BigDecimal forecastNextPeriod(List<BigDecimal> values, String trend) {
        if (values.isEmpty()) return BigDecimal.ZERO;

        // Simple forecast based on trend
        BigDecimal lastValue = values.get(values.size() - 1);
        BigDecimal mean = calculateMean(values);

        switch (trend) {
            case "INCREASING":
                return lastValue.multiply(BigDecimal.valueOf(1.1));
            case "DECREASING":
                return lastValue.multiply(BigDecimal.valueOf(0.9));
            default:
                return mean;
        }
    }

    private BigDecimal calculateCategoryCoefficientOfVariation(Map<Long, List<ConsumptionRecord>> recordsByItem) {
        List<BigDecimal> categoryTotals = new ArrayList<>();

        for (List<ConsumptionRecord> itemRecords : recordsByItem.values()) {
            BigDecimal itemTotal = itemRecords.stream()
                    .map(r -> r.getConsumedQuantity() != null ? r.getConsumedQuantity() : BigDecimal.ZERO)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            categoryTotals.add(itemTotal);
        }

        BigDecimal mean = calculateMean(categoryTotals);
        BigDecimal std = calculateStandardDeviation(categoryTotals, mean);

        return calculateCV(mean, std);
    }

    private BigDecimal sqrt(BigDecimal value) {
        if (value.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }

        BigDecimal x = new BigDecimal(Math.sqrt(value.doubleValue()));
        return x.add(value.divide(x, 10, RoundingMode.HALF_UP))
                .divide(BigDecimal.valueOf(2), 10, RoundingMode.HALF_UP);
    }
}