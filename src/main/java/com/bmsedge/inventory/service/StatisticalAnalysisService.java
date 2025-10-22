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
import java.util.*;
import java.util.stream.Collectors;

@Service
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
     * OPTIMIZED: Only calculates 30-day statistics and uses write transaction only for the update
     */
    @Transactional
    public void updateItemStatistics(Long itemId, LocalDate consumptionDate) {
        Item item = itemRepository.findById(itemId)
                .orElseThrow(() -> new RuntimeException("Item not found: " + itemId));

        // OPTIMIZATION: Only calculate 30-day statistics since 60 and 90-day results were never used
        Map<String, Object> stats30 = calculateItemStatistics(itemId, 30);

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
     * Update statistics for ALL items in the system
     * OPTIMIZED: Batch processing to avoid N+1 queries
     *
     * @return Summary of the update operation
     */
    @Transactional
    public Map<String, Object> updateAllItemStatistics() {
        long days = 0;
        logger.info("Starting batch update of statistics for all items (last {} days)", days);
        long startTime = System.currentTimeMillis();

        LocalDate endDate = LocalDate.now();
        LocalDate startDate = endDate.minusDays(days);

        // STEP 1: Fetch all items in one query
        List<Item> allItems = itemRepository.findAll();
        logger.info("Found {} items to process", allItems.size());

        // STEP 2: Fetch ALL consumption records in ONE query
        List<ConsumptionRecord> allRecords = consumptionRepository
                .findByConsumptionDateBetween(startDate, endDate);
        logger.info("Fetched {} consumption records", allRecords.size());

        // STEP 3: Group records by itemId in memory
        Map<Long, List<ConsumptionRecord>> recordsByItem = allRecords.stream()
                .collect(Collectors.groupingBy(r -> r.getItem().getId()));

        // STEP 4: Process each item
        int successCount = 0;
        int failCount = 0;
        int noDataCount = 0;
        List<String> errors = new ArrayList<>();

        for (Item item : allItems) {
            try {
                List<ConsumptionRecord> itemRecords = recordsByItem.get(item.getId());

                if (itemRecords == null || itemRecords.isEmpty()) {
                    noDataCount++;
                    // Set default values for items with no consumption data
                    item.updateStatistics(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, "NO_DATA");
                    continue;
                }

                // Calculate statistics using in-memory data
                List<BigDecimal> consumptions = itemRecords.stream()
                        .map(r -> r.getConsumedQuantity() != null ? r.getConsumedQuantity() : BigDecimal.ZERO)
                        .collect(Collectors.toList());

                BigDecimal mean = calculateMean(consumptions);
                BigDecimal std = calculateStandardDeviation(consumptions, mean);
                BigDecimal cv = calculateCV(mean, std);
                String volatility = classifyVolatility(cv);

                // Update item statistics
                item.updateStatistics(mean, std, cv, volatility);
                successCount++;

            } catch (Exception e) {
                failCount++;
                errors.add("Item " + item.getId() + " (" + item.getItemName() + "): " + e.getMessage());
                logger.error("Error updating statistics for item {}: {}", item.getId(), e.getMessage());
            }
        }

        // STEP 5: Batch save all items (single transaction)
        itemRepository.saveAll(allItems);

        long duration = System.currentTimeMillis() - startTime;
        logger.info("Batch update completed in {}ms. Success: {}, No Data: {}, Failed: {}",
                duration, successCount, noDataCount, failCount);

        // Return summary
        Map<String, Object> summary = new HashMap<>();
        summary.put("totalItems", allItems.size());
        summary.put("successCount", successCount);
        summary.put("noDataCount", noDataCount);
        summary.put("failCount", failCount);
        summary.put("durationMs", duration);
        summary.put("errors", errors);
        summary.put("periodDays", days);

        return summary;
    }

    /**
     * Update statistics for a specific list of items
     * Useful for partial updates or specific category updates
     *
     * @param itemIds List of item IDs to update
     * @param days Number of days to calculate statistics for
     * @return Summary of the update operation
     */
    @Transactional
    public Map<String, Object> updateItemStatisticsBatch(List<Long> itemIds, int days) {
        logger.info("Starting batch update for {} specific items (last {} days)", itemIds.size(), days);
        long startTime = System.currentTimeMillis();

        LocalDate endDate = LocalDate.now();
        LocalDate startDate = endDate.minusDays(days);

        // STEP 1: Fetch specific items in one query
        List<Item> items = itemRepository.findAllById(itemIds);
        logger.info("Found {} items to process", items.size());

        // STEP 2: Fetch consumption records for these items in ONE query
        List<ConsumptionRecord> records = consumptionRepository
                .findByItemIdsAndDateRange(itemIds, startDate, endDate);
        logger.info("Fetched {} consumption records", records.size());

        // STEP 3: Group records by itemId in memory
        Map<Long, List<ConsumptionRecord>> recordsByItem = records.stream()
                .collect(Collectors.groupingBy(r -> r.getItem().getId()));

        // STEP 4: Process each item
        int successCount = 0;
        int failCount = 0;
        int noDataCount = 0;
        List<String> errors = new ArrayList<>();

        for (Item item : items) {
            try {
                List<ConsumptionRecord> itemRecords = recordsByItem.get(item.getId());

                if (itemRecords == null || itemRecords.isEmpty()) {
                    noDataCount++;
                    item.updateStatistics(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, "NO_DATA");
                    continue;
                }

                List<BigDecimal> consumptions = itemRecords.stream()
                        .map(r -> r.getConsumedQuantity() != null ? r.getConsumedQuantity() : BigDecimal.ZERO)
                        .collect(Collectors.toList());

                BigDecimal mean = calculateMean(consumptions);
                BigDecimal std = calculateStandardDeviation(consumptions, mean);
                BigDecimal cv = calculateCV(mean, std);
                String volatility = classifyVolatility(cv);

                item.updateStatistics(mean, std, cv, volatility);
                successCount++;

            } catch (Exception e) {
                failCount++;
                errors.add("Item " + item.getId() + " (" + item.getItemName() + "): " + e.getMessage());
                logger.error("Error updating statistics for item {}: {}", item.getId(), e.getMessage());
            }
        }

        // STEP 5: Batch save all items
        itemRepository.saveAll(items);

        long duration = System.currentTimeMillis() - startTime;
        logger.info("Batch update completed in {}ms. Success: {}, No Data: {}, Failed: {}",
                duration, successCount, noDataCount, failCount);

        Map<String, Object> summary = new HashMap<>();
        summary.put("totalItems", items.size());
        summary.put("successCount", successCount);
        summary.put("noDataCount", noDataCount);
        summary.put("failCount", failCount);
        summary.put("durationMs", duration);
        summary.put("errors", errors);
        summary.put("periodDays", days);

        return summary;
    }

    /**
     * Update statistics for all items in a specific category
     *
     * @param categoryId Category ID
     * @param days Number of days to calculate statistics for
     * @return Summary of the update operation
     */
    @Transactional
    public Map<String, Object> updateCategoryItemStatistics(Long categoryId, int days) {
        logger.info("Starting update for all items in category {} (last {} days)", categoryId, days);

        // Get all items in this category
        List<Item> categoryItems = itemRepository.findByCategoryId(categoryId);
        List<Long> itemIds = categoryItems.stream()
                .map(Item::getId)
                .collect(Collectors.toList());

        return updateItemStatisticsBatch(itemIds, days);
    }

    /**
     * Calculate comprehensive statistics for an item
     * OPTIMIZED: Uses read-only transaction and regular streams instead of parallelStream
     */
    @Transactional(readOnly = true)
    public Map<String, Object> calculateItemStatistics(Long itemId, int days) {
        Map<String, Object> stats = new HashMap<>();

        LocalDate endDate = LocalDate.now();
        LocalDate startDate = endDate.minusDays(days);

        // Fetch all records for this item at once
        List<ConsumptionRecord> records = consumptionRepository
                .findByItemIdAndConsumptionDateBetween(itemId, startDate, endDate);

        if (records.isEmpty()) {
            stats.put("error", "No consumption data available");
            return stats;
        }

        // OPTIMIZATION: Use regular stream - parallelStream adds overhead for small datasets
        List<BigDecimal> consumptions = records.stream()
                .map(r -> r.getConsumedQuantity() != null ? r.getConsumedQuantity() : BigDecimal.ZERO)
                .collect(Collectors.toList());

        BigDecimal mean = calculateMean(consumptions);
        BigDecimal median = calculateMedian(consumptions);
        BigDecimal std = calculateStandardDeviation(consumptions, mean);
        BigDecimal cv = calculateCV(mean, std);
        BigDecimal max = consumptions.stream().max(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
        BigDecimal min = consumptions.stream().min(BigDecimal::compareTo).orElse(BigDecimal.ZERO);

        String volatilityClass = classifyVolatility(cv);
        String trend = analyzeTrend(consumptions);
        Map<String, Object> seasonality = detectSeasonality(records);
        String pattern = analyzeConsumptionPattern(records);

        long daysWithActivity = records.stream()
                .filter(r -> r.getConsumedQuantity() != null && r.getConsumedQuantity().compareTo(BigDecimal.ZERO) > 0)
                .count();

        BigDecimal activityRate = BigDecimal.valueOf(daysWithActivity)
                .divide(BigDecimal.valueOf(days), 2, RoundingMode.HALF_UP);

        BigDecimal totalConsumption = consumptions.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal forecast = forecastNextPeriod(consumptions, trend);

        // OPTIMIZATION: Get item name only when needed, in one query at the start
        Item item = records.get(0).getItem(); // Already loaded with the records

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
        stats.put("activityRate", activityRate);
        stats.put("seasonality", seasonality);
        stats.put("totalConsumption", totalConsumption);
        stats.put("percentile25", calculatePercentile(consumptions, 25));
        stats.put("percentile75", calculatePercentile(consumptions, 75));
        stats.put("percentile90", calculatePercentile(consumptions, 90));
        stats.put("forecastNextPeriod", forecast);

        return stats;
    }


    /**
     * Calculate statistics for a category
     * OPTIMIZED: Fixed N+1 query problem by batch fetching items
     */
    @Transactional(readOnly = true)
    public Map<String, Object> calculateCategoryStatistics(Long categoryId, int days) {
        Map<String, Object> stats = new HashMap<>();

        Category category = categoryRepository.findById(categoryId)
                .orElseThrow(() -> new RuntimeException("Category not found: " + categoryId));

        LocalDate endDate = LocalDate.now();
        LocalDate startDate = endDate.minusDays(days);

        // Fetch all consumption records for the category in one query
        List<ConsumptionRecord> records = consumptionRepository
                .findByCategoryIdAndDateRange(categoryId, startDate, endDate);

        if (records.isEmpty()) {
            stats.put("error", "No consumption data available for category");
            return stats;
        }

        // OPTIMIZATION: Use regular stream instead of parallelStream for grouping
        Map<Long, List<ConsumptionRecord>> recordsByItem = records.stream()
                .collect(Collectors.groupingBy(r -> r.getItem().getId()));

        // OPTIMIZATION: Batch fetch all items instead of individual queries in loop
        Set<Long> itemIds = recordsByItem.keySet();
        Map<Long, Item> itemsMap = itemRepository.findAllById(itemIds).stream()
                .collect(Collectors.toMap(Item::getId, item -> item));

        List<Map<String, Object>> itemStats = new ArrayList<>();
        BigDecimal totalCategoryConsumption = BigDecimal.ZERO;

        // Process each item's records
        for (Map.Entry<Long, List<ConsumptionRecord>> entry : recordsByItem.entrySet()) {
            List<BigDecimal> consumptions = entry.getValue().stream()
                    .map(r -> r.getConsumedQuantity() != null ? r.getConsumedQuantity() : BigDecimal.ZERO)
                    .collect(Collectors.toList());

            BigDecimal itemTotal = consumptions.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
            totalCategoryConsumption = totalCategoryConsumption.add(itemTotal);

            // OPTIMIZATION: Get item from pre-loaded map instead of database call
            Item item = itemsMap.get(entry.getKey());
            if (item != null) {
                Map<String, Object> itemStat = new HashMap<>();
                BigDecimal mean = calculateMean(consumptions);
                BigDecimal std = calculateStandardDeviation(consumptions, mean);

                itemStat.put("itemId", item.getId());
                itemStat.put("itemName", item.getItemName());
                itemStat.put("totalConsumption", itemTotal);
                itemStat.put("avgConsumption", mean);
                itemStat.put("cv", calculateCV(mean, std));
                itemStats.add(itemStat);
            }
        }

        // Sort items by total consumption descending
        itemStats.sort((a, b) -> ((BigDecimal) b.get("totalConsumption"))
                .compareTo((BigDecimal) a.get("totalConsumption")));

        // Calculate category-level metrics
        BigDecimal avgConsumption = itemStats.isEmpty() ? BigDecimal.ZERO :
                totalCategoryConsumption.divide(BigDecimal.valueOf(itemStats.size()), 4, RoundingMode.HALF_UP);

        BigDecimal categoryCV = calculateCategoryCoefficientOfVariation(recordsByItem);

        // Identify top performers
        int topN = Math.min(5, itemStats.size());
        List<Map<String, Object>> topItems = itemStats.subList(0, topN);

        stats.put("categoryId", categoryId);
        stats.put("categoryName", category.getCategoryName());
        stats.put("periodDays", days);
        stats.put("totalItems", itemStats.size());
        stats.put("totalCategoryConsumption", totalCategoryConsumption);
        stats.put("avgConsumptionPerItem", avgConsumption);
        stats.put("categoryCV", categoryCV);
        stats.put("topItems", topItems);
        stats.put("allItemStats", itemStats);

        return stats;
    }

    /**
     * Get comprehensive dashboard statistics
     * OPTIMIZED: Uses read-only transaction
     */
    @Transactional(readOnly = true)
    public Map<String, Object> getDashboardStatistics(int days) {
        Map<String, Object> dashboard = new HashMap<>();

        LocalDate endDate = LocalDate.now();
        LocalDate startDate = endDate.minusDays(days);

        // Fetch all records once
        List<ConsumptionRecord> allRecords = consumptionRepository
                .findByConsumptionDateBetween(startDate, endDate);

        // Calculate various metrics
        BigDecimal totalConsumption = allRecords.stream()
                .map(r -> r.getConsumedQuantity() != null ? r.getConsumedQuantity() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        long activeItems = allRecords.stream()
                .map(r -> r.getItem().getId())
                .distinct()
                .count();

        long activeCategories = allRecords.stream()
                .map(r -> r.getItem().getCategory().getId())
                .distinct()
                .count();

        // Group by date for trend analysis
        Map<LocalDate, BigDecimal> dailyConsumption = allRecords.stream()
                .collect(Collectors.groupingBy(
                        ConsumptionRecord::getConsumptionDate,
                        Collectors.mapping(
                                r -> r.getConsumedQuantity() != null ? r.getConsumedQuantity() : BigDecimal.ZERO,
                                Collectors.reducing(BigDecimal.ZERO, BigDecimal::add)
                        )
                ));

        dashboard.put("periodDays", days);
        dashboard.put("totalConsumption", totalConsumption);
        dashboard.put("activeItems", activeItems);
        dashboard.put("activeCategories", activeCategories);
        dashboard.put("dailyConsumption", dailyConsumption);

        return dashboard;
    }

    // ==================== HELPER METHODS ====================

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