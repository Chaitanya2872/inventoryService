package com.bmsedge.inventory.service;

import com.bmsedge.inventory.dto.AnalyticsResponse;
import com.bmsedge.inventory.model.*;
import com.bmsedge.inventory.repository.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class AnalyticsService {

    @Autowired
    private ItemRepository itemRepository;

    @Autowired
    private ConsumptionRecordRepository consumptionRecordRepository;

    @Autowired
    private FootfallDataRepository footfallDataRepository;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private StockMovementRepository stockMovementRepository;

    // ===== UTILITY METHODS =====
    private BigDecimal nullSafe(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }

    private BigDecimal safeDivide(BigDecimal numerator, BigDecimal denominator, int scale, RoundingMode mode) {
        numerator = nullSafe(numerator);
        denominator = nullSafe(denominator);
        if (denominator.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        return numerator.divide(denominator, scale, mode);
    }

    private LocalDate parseDate(String dateStr) {
        if (dateStr == null || dateStr.isEmpty()) return null;
        try {
            return LocalDate.parse(dateStr);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * FIXED: Get actual date range from consumption records
     */
    private Map<String, LocalDate> getActualDataRange() {
        Map<String, LocalDate> range = new HashMap<>();

        List<ConsumptionRecord> records = consumptionRecordRepository.findAll();

        if (!records.isEmpty()) {
            LocalDate minDate = records.stream()
                    .map(ConsumptionRecord::getConsumptionDate)
                    .filter(Objects::nonNull)
                    .min(LocalDate::compareTo)
                    .orElse(LocalDate.of(2025, 1, 1));

            LocalDate maxDate = records.stream()
                    .map(ConsumptionRecord::getConsumptionDate)
                    .filter(Objects::nonNull)
                    .max(LocalDate::compareTo)
                    .orElse(LocalDate.of(2025, 7, 31));

            range.put("minDate", minDate);
            range.put("maxDate", maxDate);
        } else {
            range.put("minDate", LocalDate.of(2025, 1, 1));
            range.put("maxDate", LocalDate.of(2025, 7, 31));
        }

        return range;
    }

    // ===== MAIN API METHODS =====

    /**
     * Dashboard statistics - working implementation
     */
    public Map<String, Object> getDashboardStats() {
        Map<String, Object> stats = new HashMap<>();

        try {
            List<Item> allItems = itemRepository.findAll();
            Long totalQuantity = itemRepository.getTotalQuantity();
            if (totalQuantity == null) totalQuantity = 0L;

            stats.put("totalItems", allItems.size());
            stats.put("totalQuantity", totalQuantity);

            // Count low stock items (below 10 units)
            long lowStockCount = allItems.stream()
                    .filter(item -> nullSafe(item.getCurrentQuantity()).compareTo(BigDecimal.valueOf(10)) < 0)
                    .count();
            stats.put("lowStockAlerts", (int) lowStockCount);

            // Count expired items
            LocalDateTime now = LocalDateTime.now();
            long expiredCount = allItems.stream()
                    .filter(item -> item.getExpiryDate() != null)
                    .filter(item -> {
                        try {
                            LocalDateTime expiry = LocalDateTime.parse(item.getExpiryDate() + "T00:00:00");
                            return expiry.isBefore(now);
                        } catch (Exception e) {
                            return false;
                        }
                    })
                    .count();
            stats.put("expiredItems", (int) expiredCount);

            // Count items expiring in 7 days
            LocalDateTime weekAhead = now.plusDays(7);
            long expiringSoonCount = allItems.stream()
                    .filter(item -> item.getExpiryDate() != null)
                    .filter(item -> {
                        try {
                            LocalDateTime expiry = LocalDateTime.parse(item.getExpiryDate() + "T00:00:00");
                            return expiry.isAfter(now) && expiry.isBefore(weekAhead);
                        } catch (Exception e) {
                            return false;
                        }
                    })
                    .count();
            stats.put("expiringSoon", (int) expiringSoonCount);

        } catch (Exception e) {
            System.err.println("Error in getDashboardStats: " + e.getMessage());
            stats.put("totalItems", 0);
            stats.put("totalQuantity", 0);
            stats.put("lowStockAlerts", 0);
            stats.put("expiredItems", 0);
            stats.put("expiringSoon", 0);
        }

        return stats;
    }

    /**
     * FIXED: Consumption Trends Analysis with proper data loading
     */
    public Map<String, Object> getConsumptionTrends(String period, String groupBy, Long categoryId) {
        return getConsumptionTrends(period, groupBy, categoryId, null, null);
    }

    /**
     * FIXED: Enhanced Consumption Trends with actual consumption data
     */
    public Map<String, Object> getConsumptionTrends(String period, String groupBy, Long categoryId, LocalDate startDate, LocalDate endDate) {
        Map<String, Object> result = new HashMap<>();

        try {
            // Get actual date range from consumption records if not provided
            if (endDate == null || startDate == null) {
                Map<String, LocalDate> dateRange = getActualDataRange();
                startDate = (startDate == null) ? dateRange.get("minDate") : startDate;
                endDate = (endDate == null) ? dateRange.get("maxDate") : endDate;
            }

            result.put("period", period);
            result.put("groupBy", groupBy);
            result.put("startDate", startDate.toString());
            result.put("endDate", endDate.toString());
            result.put("actualDataRange", startDate.toString() + " to " + endDate.toString());

            List<Map<String, Object>> trendsData = new ArrayList<>();

            if ("category".equals(groupBy)) {
                trendsData = getEnhancedCategoryTrends(period, startDate, endDate, categoryId);
            } else {
                trendsData = getEnhancedItemTrends(period, startDate, endDate, categoryId);
            }

            result.put("data", trendsData);
            result.put("totalRecords", trendsData.size());

        } catch (Exception e) {
            System.err.println("Error in getConsumptionTrends: " + e.getMessage());
            e.printStackTrace();
            result.put("error", e.getMessage());
            result.put("data", new ArrayList<>());
        }

        return result;
    }

    /**
     * FIXED: Get enhanced category trends with actual data
     */
    private List<Map<String, Object>> getEnhancedCategoryTrends(String period, LocalDate startDate, LocalDate endDate, Long categoryId) {
        List<Map<String, Object>> trends = new ArrayList<>();

        try {
            List<Category> categories;
            if (categoryId != null) {
                Optional<Category> cat = categoryRepository.findById(categoryId);
                categories = cat.isPresent() ? Arrays.asList(cat.get()) : new ArrayList<>();
            } else {
                categories = categoryRepository.findAll();
            }

            for (Category category : categories) {
                Map<String, Object> categoryTrend = new HashMap<>();
                categoryTrend.put("categoryId", category.getId());
                categoryTrend.put("categoryName", category.getCategoryName());

                // Get all consumption records for this category in the date range
                List<ConsumptionRecord> categoryRecords = consumptionRecordRepository
                        .findByCategoryAndDateBetween(category.getId(), startDate, endDate);

                List<Map<String, Object>> dataPoints = new ArrayList<>();

                // Generate data points based on period
                LocalDate current = startDate;
                while (!current.isAfter(endDate)) {
                    Map<String, Object> point = new HashMap<>();
                    LocalDate periodEnd = getPeriodEnd(current, period, endDate);

                    // Get consumption data for this period
                    BigDecimal periodConsumption = BigDecimal.ZERO;
                    BigDecimal periodCost = BigDecimal.ZERO;
                    List<Map<String, Object>> itemDetails = new ArrayList<>();

                    for (ConsumptionRecord record : categoryRecords) {
                        if (!record.getConsumptionDate().isBefore(current) &&
                                !record.getConsumptionDate().isAfter(periodEnd)) {

                            BigDecimal consumed = nullSafe(record.getConsumedQuantity());
                            BigDecimal unitPrice = nullSafe(record.getItem().getUnitPrice());

                            periodConsumption = periodConsumption.add(consumed);
                            periodCost = periodCost.add(consumed.multiply(unitPrice));

                            // Add item detail
                            Map<String, Object> itemDetail = new HashMap<>();
                            itemDetail.put("itemId", record.getItem().getId());
                            itemDetail.put("itemName", record.getItem().getItemName());
                            itemDetail.put("consumptionDate", record.getConsumptionDate().toString());
                            itemDetail.put("quantity", consumed);
                            itemDetail.put("unitPrice", unitPrice);
                            itemDetail.put("totalCost", consumed.multiply(unitPrice));
                            itemDetails.add(itemDetail);
                        }
                    }

                    // Format the period label
                    point.put("periodLabel", formatPeriodLabel(current, period));
                    point.put("startDate", current.toString());
                    point.put("endDate", periodEnd.toString());
                    point.put("consumption", periodConsumption);
                    point.put("cost", periodCost);
                    point.put("itemCount", itemDetails.size());
                    point.put("items", itemDetails);

                    dataPoints.add(point);

                    // Move to next period
                    current = getNextPeriodStart(current, period);
                }

                // Calculate summary metrics
                BigDecimal totalConsumption = dataPoints.stream()
                        .map(p -> (BigDecimal) p.get("consumption"))
                        .reduce(BigDecimal.ZERO, BigDecimal::add);

                BigDecimal totalCost = dataPoints.stream()
                        .map(p -> (BigDecimal) p.get("cost"))
                        .reduce(BigDecimal.ZERO, BigDecimal::add);

                categoryTrend.put("dataPoints", dataPoints);
                categoryTrend.put("totalConsumption", totalConsumption);
                categoryTrend.put("totalCost", totalCost);
                categoryTrend.put("averageConsumption", dataPoints.isEmpty() ? BigDecimal.ZERO :
                        safeDivide(totalConsumption, BigDecimal.valueOf(dataPoints.size()), 2, RoundingMode.HALF_UP));
                categoryTrend.put("periodCount", dataPoints.size());

                trends.add(categoryTrend);
            }
        } catch (Exception e) {
            System.err.println("Error in getEnhancedCategoryTrends: " + e.getMessage());
            e.printStackTrace();
        }

        return trends;
    }

    /**
     * FIXED: Get enhanced item trends with actual consumption data
     */
    private List<Map<String, Object>> getEnhancedItemTrends(String period, LocalDate startDate, LocalDate endDate, Long categoryId) {
        List<Map<String, Object>> trends = new ArrayList<>();

        try {
            List<Item> items;
            if (categoryId != null) {
                // FIX: Remove pageable parameter - it doesn't exist in this scope
                items = itemRepository.findAll().stream()
                        .filter(item -> item.getCategory() != null && item.getCategory().getId().equals(categoryId))
                        .collect(Collectors.toList());
            } else {
                items = itemRepository.findAll();
            }

            // Process top 20 items for performance
            items = items.stream().limit(20).collect(Collectors.toList());

            for (Item item : items) {
                Map<String, Object> itemTrend = new HashMap<>();
                itemTrend.put("itemId", item.getId());
                itemTrend.put("itemName", item.getItemName());
                itemTrend.put("categoryId", item.getCategory().getId());
                itemTrend.put("categoryName", item.getCategory().getCategoryName());
                itemTrend.put("unitPrice", item.getUnitPrice());

                // Get all consumption records for this item
                List<ConsumptionRecord> itemRecords = consumptionRecordRepository
                        .findByItemAndConsumptionDateBetween(item, startDate, endDate);

                List<Map<String, Object>> dataPoints = new ArrayList<>();

                // Generate data points based on period
                LocalDate current = startDate;
                while (!current.isAfter(endDate)) {
                    Map<String, Object> point = new HashMap<>();
                    LocalDate periodEnd = getPeriodEnd(current, period, endDate);

                    // Get consumption for this period
                    BigDecimal periodConsumption = BigDecimal.ZERO;
                    BigDecimal periodCost = BigDecimal.ZERO;
                    int recordCount = 0;

                    for (ConsumptionRecord record : itemRecords) {
                        if (!record.getConsumptionDate().isBefore(current) &&
                                !record.getConsumptionDate().isAfter(periodEnd)) {

                            BigDecimal consumed = nullSafe(record.getConsumedQuantity());
                            periodConsumption = periodConsumption.add(consumed);
                            periodCost = periodCost.add(consumed.multiply(nullSafe(item.getUnitPrice())));
                            recordCount++;
                        }
                    }

                    point.put("periodLabel", formatPeriodLabel(current, period));
                    point.put("startDate", current.toString());
                    point.put("endDate", periodEnd.toString());
                    point.put("consumption", periodConsumption);
                    point.put("cost", periodCost);
                    point.put("recordCount", recordCount);

                    dataPoints.add(point);

                    // Move to next period
                    current = getNextPeriodStart(current, period);
                }

                // Calculate summary metrics
                BigDecimal totalConsumption = dataPoints.stream()
                        .map(p -> (BigDecimal) p.get("consumption"))
                        .reduce(BigDecimal.ZERO, BigDecimal::add);

                BigDecimal totalCost = dataPoints.stream()
                        .map(p -> (BigDecimal) p.get("cost"))
                        .reduce(BigDecimal.ZERO, BigDecimal::add);

                itemTrend.put("dataPoints", dataPoints);
                itemTrend.put("totalConsumption", totalConsumption);
                itemTrend.put("totalCost", totalCost);
                itemTrend.put("averageConsumption", dataPoints.isEmpty() ? BigDecimal.ZERO :
                        safeDivide(totalConsumption, BigDecimal.valueOf(dataPoints.size()), 2, RoundingMode.HALF_UP));

                if (totalConsumption.compareTo(BigDecimal.ZERO) > 0) {
                    trends.add(itemTrend);
                }
            }

            // Sort by total consumption descending
            trends.sort((a, b) -> {
                BigDecimal consumptionA = (BigDecimal) a.get("totalConsumption");
                BigDecimal consumptionB = (BigDecimal) b.get("totalConsumption");
                return consumptionB.compareTo(consumptionA);
            });

        } catch (Exception e) {
            System.err.println("Error in getEnhancedItemTrends: " + e.getMessage());
            e.printStackTrace();
        }

        return trends;
    }

    /**
     * Helper method to get period end date
     */
    private LocalDate getPeriodEnd(LocalDate periodStart, String period, LocalDate maxDate) {
        LocalDate periodEnd;
        switch (period.toLowerCase()) {
            case "daily" -> periodEnd = periodStart;
            case "weekly" -> periodEnd = periodStart.plusDays(6);
            case "monthly" -> periodEnd = periodStart.with(TemporalAdjusters.lastDayOfMonth());
            default -> periodEnd = periodStart;
        }
        return periodEnd.isAfter(maxDate) ? maxDate : periodEnd;
    }

    /**
     * Helper method to get next period start date
     */
    private LocalDate getNextPeriodStart(LocalDate current, String period) {
        switch (period.toLowerCase()) {
            case "daily" -> { return current.plusDays(1); }
            case "weekly" -> { return current.plusWeeks(1); }
            case "monthly" -> { return current.plusMonths(1).withDayOfMonth(1); }
            default -> { return current.plusDays(1); }
        }
    }

    /**
     * Helper method to format period label
     */
    private String formatPeriodLabel(LocalDate date, String period) {
        switch (period.toLowerCase()) {
            case "daily" -> { return date.format(DateTimeFormatter.ofPattern("yyyy-MM-dd")); }
            case "weekly" -> { return "Week of " + date.format(DateTimeFormatter.ofPattern("yyyy-MM-dd")); }
            case "monthly" -> { return date.format(DateTimeFormatter.ofPattern("MMM yyyy")); }
            default -> { return date.toString(); }
        }
    }

    /**
     * FIXED: Get consumption for a specific item using actual consumption records
     */
    private BigDecimal getConsumptionForItem(Long itemId, LocalDate startDate, LocalDate endDate) {
        try {
            Optional<Item> itemOpt = itemRepository.findById(itemId);
            if (itemOpt.isEmpty()) return BigDecimal.ZERO;

            Item item = itemOpt.get();
            List<ConsumptionRecord> records = consumptionRecordRepository
                    .findByItemAndConsumptionDateBetween(item, startDate, endDate);

            return records.stream()
                    .filter(Objects::nonNull)
                    .map(ConsumptionRecord::getConsumedQuantity)
                    .filter(Objects::nonNull)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
        } catch (Exception e) {
            return BigDecimal.ZERO;
        }
    }

    /**
     * FIXED: Get consumption for a category using actual consumption records
     */
    private BigDecimal getConsumptionForCategory(Long categoryId, LocalDate startDate, LocalDate endDate) {
        try {
            List<ConsumptionRecord> records = consumptionRecordRepository
                    .findByCategoryAndDateBetween(categoryId, startDate, endDate);

            return records.stream()
                    .filter(Objects::nonNull)
                    .map(ConsumptionRecord::getConsumedQuantity)
                    .filter(Objects::nonNull)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
        } catch (Exception e) {
            return BigDecimal.ZERO;
        }
    }

    /**
     * FIXED: Top Consuming Items with actual consumption data
     */
    public Map<String, Object> getTopConsumingItems(int days, int limit) {
        Map<String, Object> result = new HashMap<>();

        try {
            // Get actual date range
            Map<String, LocalDate> dateRange = getActualDataRange();
            LocalDate endDate = dateRange.get("maxDate");
            LocalDate startDate = endDate.minusDays(days);

            // Ensure start date is not before actual data start
            if (startDate.isBefore(dateRange.get("minDate"))) {
                startDate = dateRange.get("minDate");
            }

            result.put("period", days + " days");
            result.put("startDate", startDate.toString());
            result.put("endDate", endDate.toString());

            // Get all consumption records in the period
            List<ConsumptionRecord> records = consumptionRecordRepository
                    .findByConsumptionDateBetween(startDate, endDate);

            // Group by item and calculate totals
            Map<Item, BigDecimal> itemConsumption = new HashMap<>();
            Map<Item, BigDecimal> itemCosts = new HashMap<>();

            for (ConsumptionRecord record : records) {
                Item item = record.getItem();
                BigDecimal consumed = nullSafe(record.getConsumedQuantity());
                BigDecimal cost = consumed.multiply(nullSafe(item.getUnitPrice()));

                itemConsumption.merge(item, consumed, BigDecimal::add);
                itemCosts.merge(item, cost, BigDecimal::add);
            }

            // Sort and limit
            List<Map<String, Object>> topItems = itemConsumption.entrySet().stream()
                    .sorted(Map.Entry.<Item, BigDecimal>comparingByValue().reversed())
                    .limit(limit)
                    .map(entry -> {
                        Item item = entry.getKey();
                        BigDecimal consumption = entry.getValue();
                        BigDecimal cost = itemCosts.get(item);

                        Map<String, Object> itemData = new HashMap<>();
                        itemData.put("itemId", item.getId());
                        itemData.put("itemName", item.getItemName());
                        itemData.put("categoryName", item.getCategory().getCategoryName());

                        itemData.put("consumedQuantity", consumption.doubleValue());
                        itemData.put("totalCost", cost.doubleValue());
                        itemData.put("unitPrice", item.getUnitPrice());

                        // Generate daily pattern for last 7 days
                        List<Double> dailyPattern = new ArrayList<>();
                        LocalDate patternStart = endDate.minusDays(6);
                        for (int i = 0; i < 7; i++) {
                            LocalDate day = patternStart.plusDays(i);
                            BigDecimal dayConsumption = getConsumptionForItem(item.getId(), day, day);
                            dailyPattern.add(dayConsumption.doubleValue());
                        }
                        itemData.put("dailyPattern", dailyPattern);

                        return itemData;
                    })
                    .collect(Collectors.toList());

            BigDecimal grandTotalConsumption = itemConsumption.values().stream()
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            // Add percentage of total
            topItems.forEach(item -> {
                BigDecimal consumed = BigDecimal.valueOf((Double) item.get("consumedQuantity"));
                BigDecimal percentage = grandTotalConsumption.compareTo(BigDecimal.ZERO) > 0 ?
                        consumed.multiply(BigDecimal.valueOf(100)).divide(grandTotalConsumption, 2, RoundingMode.HALF_UP) :
                        BigDecimal.ZERO;
                item.put("percentageOfTotal", percentage.doubleValue());
            });

            result.put("topConsumers", topItems);
            result.put("totalConsumption", grandTotalConsumption.doubleValue());

        } catch (Exception e) {
            System.err.println("Error in getTopConsumingItems: " + e.getMessage());
            e.printStackTrace();
            result.put("topConsumers", new ArrayList<>());
            result.put("totalConsumption", 0.0);
        }

        return result;
    }

    /**
     * FIXED: Cost Distribution by Category with actual consumption data
     */
    public Map<String, Object> getCostDistributionByCategory(String period, LocalDate startDate, LocalDate endDate) {
        Map<String, Object> result = new HashMap<>();

        try {
            // Get actual date range if not provided
            if (endDate == null || startDate == null) {
                Map<String, LocalDate> dateRange = getActualDataRange();
                startDate = (startDate == null) ? dateRange.get("minDate") : startDate;
                endDate = (endDate == null) ? dateRange.get("maxDate") : endDate;
            }

            result.put("period", period);
            result.put("startDate", startDate.toString());
            result.put("endDate", endDate.toString());

            // Get all consumption records in the period
            List<ConsumptionRecord> records = consumptionRecordRepository
                    .findByConsumptionDateBetween(startDate, endDate);

            // Build nested structure: Month -> Bin (1-15, 16-31) -> Category -> Items
            List<Map<String, Object>> monthlyBreakdown = new ArrayList<>();
            BigDecimal grandTotal = BigDecimal.ZERO;

            // Group records by year-month
            Map<String, List<ConsumptionRecord>> recordsByMonth = records.stream()
                    .collect(Collectors.groupingBy(r ->
                            r.getConsumptionDate().format(DateTimeFormatter.ofPattern("yyyy-MM"))
                    ));

            // Process each month
            for (Map.Entry<String, List<ConsumptionRecord>> monthEntry :
                    new TreeMap<>(recordsByMonth).entrySet()) {

                String monthKey = monthEntry.getKey();
                List<ConsumptionRecord> monthRecords = monthEntry.getValue();

                Map<String, Object> monthData = new HashMap<>();
                monthData.put("month", monthKey);

                LocalDate monthDate = LocalDate.parse(monthKey + "-01");
                monthData.put("monthName", monthDate.format(DateTimeFormatter.ofPattern("MMMM yyyy")));

                // Split records into bins (1-15 and 16-31)
                List<Map<String, Object>> bins = new ArrayList<>();
                BigDecimal monthTotal = BigDecimal.ZERO;

                // Bin 1: Days 1-15
                Map<String, Object> bin1 = createBinBreakdown(
                        "1-15",
                        monthDate,
                        monthDate.withDayOfMonth(15),
                        monthRecords.stream()
                                .filter(r -> r.getConsumptionDate().getDayOfMonth() <= 15)
                                .collect(Collectors.toList())
                );
                bins.add(bin1);
                monthTotal = monthTotal.add((BigDecimal) bin1.get("totalCost"));

                // Bin 2: Days 16-31
                int lastDay = monthDate.lengthOfMonth();
                Map<String, Object> bin2 = createBinBreakdown(
                        "16-" + lastDay,
                        monthDate.withDayOfMonth(16),
                        monthDate.withDayOfMonth(lastDay),
                        monthRecords.stream()
                                .filter(r -> r.getConsumptionDate().getDayOfMonth() > 15)
                                .collect(Collectors.toList())
                );
                bins.add(bin2);
                monthTotal = monthTotal.add((BigDecimal) bin2.get("totalCost"));

                monthData.put("bins", bins);
                monthData.put("totalCost", monthTotal);
                monthData.put("percentage", BigDecimal.ZERO); // Will calculate later

                monthlyBreakdown.add(monthData);
                grandTotal = grandTotal.add(monthTotal);
            }

            // Calculate month percentages
            BigDecimal finalGrandTotal = grandTotal;
            monthlyBreakdown.forEach(month -> {
                BigDecimal monthCost = (BigDecimal) month.get("totalCost");
                BigDecimal percentage = safeDivide(
                        monthCost.multiply(BigDecimal.valueOf(100)),
                        finalGrandTotal,
                        2,
                        RoundingMode.HALF_UP
                );
                month.put("percentage", percentage);
            });

            // Also create legacy categoryDistribution for backward compatibility
            Map<Category, BigDecimal> categoryQuantity = new HashMap<>();
            Map<Category, BigDecimal> categoryCost = new HashMap<>();

            for (ConsumptionRecord record : records) {
                Category category = record.getItem().getCategory();
                BigDecimal consumed = nullSafe(record.getConsumedQuantity());
                BigDecimal unitPrice = nullSafe(record.getItem().getUnitPrice());
                BigDecimal cost = consumed.multiply(unitPrice);

                categoryQuantity.merge(category, consumed, BigDecimal::add);
                categoryCost.merge(category, cost, BigDecimal::add);
            }

            List<Map<String, Object>> distributionData = new ArrayList<>();
            for (Map.Entry<Category, BigDecimal> entry : categoryCost.entrySet()) {
                Category category = entry.getKey();
                BigDecimal cost = entry.getValue();
                BigDecimal quantity = categoryQuantity.get(category);

                if (cost.compareTo(BigDecimal.ZERO) > 0) {
                    Map<String, Object> categoryData = new HashMap<>();
                    categoryData.put("category", category.getCategoryName());
                    categoryData.put("categoryId", category.getId());
                    categoryData.put("totalQuantity", quantity);
                    categoryData.put("totalCost", cost);
                    categoryData.put("avgUnitPrice", safeDivide(cost, quantity, 2, RoundingMode.HALF_UP));
                    categoryData.put("percentage", safeDivide(
                            cost.multiply(BigDecimal.valueOf(100)),
                            grandTotal,
                            2,
                            RoundingMode.HALF_UP
                    ));
                    distributionData.add(categoryData);
                }
            }

            // Sort by total cost descending
            distributionData.sort((a, b) -> {
                BigDecimal costA = (BigDecimal) a.get("totalCost");
                BigDecimal costB = (BigDecimal) b.get("totalCost");
                return costB.compareTo(costA);
            });

            result.put("totalCost", grandTotal);
            result.put("monthlyBreakdown", monthlyBreakdown);
            result.put("categoryDistribution", distributionData); // Legacy format
            result.put("totalMonths", monthlyBreakdown.size());

        } catch (Exception e) {
            System.err.println("Error in getCostDistributionByCategory: " + e.getMessage());
            e.printStackTrace();
            result.put("totalCost", BigDecimal.ZERO);
            result.put("monthlyBreakdown", new ArrayList<>());
            result.put("categoryDistribution", new ArrayList<>());
        }

        return result;
    }


    private Map<String, Object> createBinBreakdown(String binPeriod, LocalDate binStart,
                                                   LocalDate binEnd, List<ConsumptionRecord> binRecords) {
        Map<String, Object> binData = new HashMap<>();
        binData.put("binPeriod", binPeriod);
        binData.put("startDate", binStart.toString());
        binData.put("endDate", binEnd.toString());

        // Group by category
        Map<Category, List<ConsumptionRecord>> recordsByCategory = binRecords.stream()
                .collect(Collectors.groupingBy(r -> r.getItem().getCategory()));

        List<Map<String, Object>> categories = new ArrayList<>();
        BigDecimal binTotal = BigDecimal.ZERO;

        for (Map.Entry<Category, List<ConsumptionRecord>> categoryEntry : recordsByCategory.entrySet()) {
            Category category = categoryEntry.getKey();
            List<ConsumptionRecord> categoryRecords = categoryEntry.getValue();

            Map<String, Object> categoryData = new HashMap<>();
            categoryData.put("categoryId", category.getId());
            categoryData.put("categoryName", category.getCategoryName());

            // Group by item within category
            Map<Item, List<ConsumptionRecord>> recordsByItem = categoryRecords.stream()
                    .collect(Collectors.groupingBy(ConsumptionRecord::getItem));

            List<Map<String, Object>> items = new ArrayList<>();
            BigDecimal categoryTotal = BigDecimal.ZERO;
            BigDecimal categoryQuantity = BigDecimal.ZERO;

            for (Map.Entry<Item, List<ConsumptionRecord>> itemEntry : recordsByItem.entrySet()) {
                Item item = itemEntry.getKey();
                List<ConsumptionRecord> itemRecords = itemEntry.getValue();

                BigDecimal itemQuantity = itemRecords.stream()
                        .map(ConsumptionRecord::getConsumedQuantity)
                        .filter(Objects::nonNull)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);

                BigDecimal unitPrice = nullSafe(item.getUnitPrice());
                BigDecimal itemCost = itemQuantity.multiply(unitPrice);

                if (itemCost.compareTo(BigDecimal.ZERO) > 0) {
                    Map<String, Object> itemData = new HashMap<>();
                    itemData.put("itemId", item.getId());
                    itemData.put("itemName", item.getItemName());

                    itemData.put("quantity", itemQuantity);
                    itemData.put("unitPrice", unitPrice);
                    itemData.put("totalCost", itemCost);
                    itemData.put("unitOfMeasurement", item.getUnitOfMeasurement());

                    // Add detailed consumption dates for this bin
                    List<Map<String, Object>> consumptionDetails = new ArrayList<>();
                    for (ConsumptionRecord record : itemRecords) {
                        Map<String, Object> detail = new HashMap<>();
                        detail.put("date", record.getConsumptionDate().toString());
                        detail.put("quantity", record.getConsumedQuantity());
                        detail.put("cost", record.getConsumedQuantity().multiply(unitPrice));
                        consumptionDetails.add(detail);
                    }
                    itemData.put("consumptionDetails", consumptionDetails);

                    items.add(itemData);
                    categoryTotal = categoryTotal.add(itemCost);
                    categoryQuantity = categoryQuantity.add(itemQuantity);
                }
            }

            // Sort items by cost descending
            items.sort((a, b) -> {
                BigDecimal costA = (BigDecimal) a.get("totalCost");
                BigDecimal costB = (BigDecimal) b.get("totalCost");
                return costB.compareTo(costA);
            });

            categoryData.put("items", items);
            categoryData.put("totalCost", categoryTotal);
            categoryData.put("totalQuantity", categoryQuantity);
            categoryData.put("itemCount", items.size());
            categoryData.put("avgUnitPrice", safeDivide(categoryTotal, categoryQuantity, 2, RoundingMode.HALF_UP));

            categories.add(categoryData);
            binTotal = binTotal.add(categoryTotal);
        }

        // Sort categories by cost descending
        categories.sort((a, b) -> {
            BigDecimal costA = (BigDecimal) a.get("totalCost");
            BigDecimal costB = (BigDecimal) b.get("totalCost");
            return costB.compareTo(costA);
        });

        // Calculate category percentages within bin
        BigDecimal finalBinTotal = binTotal;
        categories.forEach(category -> {
            BigDecimal categoryCost = (BigDecimal) category.get("totalCost");
            BigDecimal percentage = safeDivide(
                    categoryCost.multiply(BigDecimal.valueOf(100)),
                    finalBinTotal,
                    2,
                    RoundingMode.HALF_UP
            );
            category.put("percentage", percentage);
        });

        binData.put("categories", categories);
        binData.put("totalCost", binTotal);
        binData.put("categoryCount", categories.size());

        return binData;
    }

    /**
     * FIXED: Seasonal Cost Analysis with actual consumption data
     */
    public Map<String, Object> getSeasonalCostAnalysis(int years, boolean includeForecasts,
                                                       Long categoryId, String granularity) {
        Map<String, Object> result = new HashMap<>();

        try {
            // Get actual date range
            Map<String, LocalDate> dateRange = getActualDataRange();
            LocalDate endDate = dateRange.get("maxDate");
            LocalDate startDate = dateRange.get("minDate");

            result.put("years", years);
            result.put("granularity", granularity);
            result.put("startDate", startDate.toString());
            result.put("endDate", endDate.toString());

            // Get all consumption records
            List<ConsumptionRecord> records;
            if (categoryId != null) {
                records = consumptionRecordRepository.findByCategoryAndDateBetween(categoryId, startDate, endDate);
            } else {
                records = consumptionRecordRepository.findByConsumptionDateBetween(startDate, endDate);
            }

            // Group by month for seasonal analysis
            Map<String, List<Map<String, Object>>> monthlyData = new TreeMap<>();

            for (ConsumptionRecord record : records) {
                String monthKey = record.getConsumptionDate().format(DateTimeFormatter.ofPattern("MM-MMM"));
                String yearMonth = record.getConsumptionDate().format(DateTimeFormatter.ofPattern("yyyy-MM"));

                Map<String, Object> recordData = new HashMap<>();
                recordData.put("date", record.getConsumptionDate().toString());
                recordData.put("itemId", record.getItem().getId());
                recordData.put("itemName", record.getItem().getItemName());
                recordData.put("categoryName", record.getItem().getCategory().getCategoryName());
                recordData.put("consumption", record.getConsumedQuantity());
                recordData.put("cost", record.getConsumedQuantity().multiply(nullSafe(record.getItem().getUnitPrice())));
                recordData.put("yearMonth", yearMonth);

                monthlyData.computeIfAbsent(monthKey, k -> new ArrayList<>()).add(recordData);
            }

            // Calculate seasonal patterns
            List<Map<String, Object>> seasonalPatterns = new ArrayList<>();

            for (Map.Entry<String, List<Map<String, Object>>> entry : monthlyData.entrySet()) {
                Map<String, Object> pattern = new HashMap<>();
                pattern.put("month", entry.getKey());

                BigDecimal totalConsumption = entry.getValue().stream()
                        .map(r -> (BigDecimal) r.get("consumption"))
                        .reduce(BigDecimal.ZERO, BigDecimal::add);

                BigDecimal totalCost = entry.getValue().stream()
                        .map(r -> (BigDecimal) r.get("cost"))
                        .reduce(BigDecimal.ZERO, BigDecimal::add);

                pattern.put("totalConsumption", totalConsumption);
                pattern.put("totalCost", totalCost);
                pattern.put("averageConsumption", safeDivide(totalConsumption, BigDecimal.valueOf(entry.getValue().size()), 2, RoundingMode.HALF_UP));
                pattern.put("dataPoints", entry.getValue().size());

                seasonalPatterns.add(pattern);
            }

            result.put("seasonalPatterns", seasonalPatterns);
            result.put("includeForecasts", includeForecasts);

            if (includeForecasts) {
                result.put("forecasts", generateSimpleForecasts(seasonalPatterns));
            }

        } catch (Exception e) {
            System.err.println("Error in getSeasonalCostAnalysis: " + e.getMessage());
            e.printStackTrace();
            result.put("error", e.getMessage());
        }

        return result;
    }

    /**
     * Helper method to generate simple forecasts
     */
    private Map<String, Object> generateSimpleForecasts(List<Map<String, Object>> patterns) {
        Map<String, Object> forecasts = new HashMap<>();

        if (!patterns.isEmpty()) {
            BigDecimal avgMonthly = patterns.stream()
                    .map(p -> (BigDecimal) p.get("totalCost"))
                    .reduce(BigDecimal.ZERO, BigDecimal::add)
                    .divide(BigDecimal.valueOf(patterns.size()), 2, RoundingMode.HALF_UP);

            forecasts.put("projectedMonthlyAverage", avgMonthly);
            forecasts.put("projectedAnnual", avgMonthly.multiply(BigDecimal.valueOf(12)));
        }

        return forecasts;
    }

    // Keep other existing methods unchanged...

    /**
     * Legacy method for existing endpoints
     */
    public AnalyticsResponse getAnalytics() {
        try {
            AnalyticsResponse.UsageStock usageStock = generateUsageStock();
            List<AnalyticsResponse.ConsumptionTrend> consumptionTrends = generateConsumptionTrends();
            return new AnalyticsResponse(usageStock, consumptionTrends);
        } catch (Exception e) {
            System.err.println("Error in getAnalytics: " + e.getMessage());
            // Return empty response
            AnalyticsResponse.UsageStock emptyStock = new AnalyticsResponse.UsageStock(0, 0, 0, 0, 0, new HashMap<>());
            return new AnalyticsResponse(emptyStock, new ArrayList<>());
        }
    }

    private AnalyticsResponse.UsageStock generateUsageStock() {
        List<Item> allItems = itemRepository.findAll();

        int totalItems = allItems.size();
        int totalQuantity = allItems.stream()
                .mapToInt(item -> nullSafe(item.getCurrentQuantity()).intValue())
                .sum();

        int lowStockItems = (int) allItems.stream()
                .filter(item -> nullSafe(item.getCurrentQuantity()).compareTo(BigDecimal.valueOf(10)) < 0)
                .count();

        int expiredItems = 0; // Simplified
        int expiringItems = 0; // Simplified

        Map<String, Integer> categoryWiseStock = allItems.stream()
                .collect(Collectors.groupingBy(
                        item -> item.getCategory().getCategoryName(),
                        Collectors.summingInt(item -> nullSafe(item.getCurrentQuantity()).intValue())
                ));

        return new AnalyticsResponse.UsageStock(
                totalItems,
                totalQuantity,
                lowStockItems,
                expiredItems,
                expiringItems,
                categoryWiseStock
        );
    }

    private List<AnalyticsResponse.ConsumptionTrend> generateConsumptionTrends() {
        List<AnalyticsResponse.ConsumptionTrend> trends = new ArrayList<>();

        // Get actual consumption data for last 6 months
        Map<String, LocalDate> dateRange = getActualDataRange();
        LocalDate endDate = dateRange.get("maxDate");

        for (int i = 5; i >= 0; i--) {
            LocalDate monthStart = endDate.minusMonths(i).withDayOfMonth(1);
            LocalDate monthEnd = monthStart.with(TemporalAdjusters.lastDayOfMonth());

            if (monthEnd.isAfter(endDate)) {
                monthEnd = endDate;
            }

            String monthName = monthStart.format(DateTimeFormatter.ofPattern("MMM yyyy"));

            // Get actual consumption for the month
            List<ConsumptionRecord> monthRecords = consumptionRecordRepository
                    .findByConsumptionDateBetween(monthStart, monthEnd);

            int consumed = monthRecords.stream()
                    .mapToInt(r -> nullSafe(r.getConsumedQuantity()).intValue())
                    .sum();

            int added = 30 + (int)(Math.random() * 70); // Simplified
            int netChange = added - consumed;

            trends.add(new AnalyticsResponse.ConsumptionTrend(monthName, consumed, added, netChange));
        }

        return trends;
    }

    // Include all other existing placeholder methods unchanged...
    // (getEnhancedCostDistribution, getBudgetVsActualComparison, etc.)
    // These remain the same as in the original file

    // Add these methods to your AnalyticsService.java class

    /**
     * Stock Usage Analysis with Risk Assessment
     */
    public Map<String, Object> getStockUsageAnalysis(Long categoryId) {
        Map<String, Object> result = new HashMap<>();

        try {
            List<Item> items;
            if (categoryId != null) {
                // FIX: Filter by category without pageable
                items = itemRepository.findAll().stream()
                        .filter(item -> item.getCategory() != null && item.getCategory().getId().equals(categoryId))
                        .collect(Collectors.toList());
            } else {
                items = itemRepository.findAll();
            }

            List<Map<String, Object>> usageAnalysis = new ArrayList<>();
            Map<String, LocalDate> dateRange = getActualDataRange();
            LocalDate startDate = dateRange.get("minDate");
            LocalDate endDate = dateRange.get("maxDate");

            for (Item item : items) {
                Map<String, Object> itemUsage = new HashMap<>();
                itemUsage.put("itemId", item.getId());
                itemUsage.put("itemName", item.getItemName());
                itemUsage.put("categoryName", item.getCategory().getCategoryName());
                itemUsage.put("currentStock", item.getCurrentQuantity());


                // Calculate consumption rate
                BigDecimal totalConsumption = getConsumptionForItem(item.getId(), startDate, endDate);
                long daysDiff = ChronoUnit.DAYS.between(startDate, endDate) + 1;
                BigDecimal avgDailyConsumption = safeDivide(totalConsumption, BigDecimal.valueOf(daysDiff), 2, RoundingMode.HALF_UP);

                itemUsage.put("totalConsumption", totalConsumption);
                itemUsage.put("avgDailyConsumption", avgDailyConsumption);

                // Calculate days of stock remaining
                BigDecimal daysRemaining = avgDailyConsumption.compareTo(BigDecimal.ZERO) > 0 ?
                        safeDivide(nullSafe(item.getCurrentQuantity()), avgDailyConsumption, 0, RoundingMode.UP) :
                        BigDecimal.valueOf(999);

                itemUsage.put("daysOfStockRemaining", daysRemaining.intValue());

                // Risk level assessment
                String riskLevel;
                if (daysRemaining.compareTo(BigDecimal.valueOf(7)) < 0) {
                    riskLevel = "CRITICAL";
                } else if (daysRemaining.compareTo(BigDecimal.valueOf(14)) < 0) {
                    riskLevel = "HIGH";
                } else if (daysRemaining.compareTo(BigDecimal.valueOf(30)) < 0) {
                    riskLevel = "MEDIUM";
                } else {
                    riskLevel = "LOW";
                }
                itemUsage.put("riskLevel", riskLevel);

                usageAnalysis.add(itemUsage);
            }

            // Sort by risk level and days remaining
            usageAnalysis.sort((a, b) -> {
                int daysA = (Integer) a.get("daysOfStockRemaining");
                int daysB = (Integer) b.get("daysOfStockRemaining");
                return Integer.compare(daysA, daysB);
            });

            result.put("stockUsageAnalysis", usageAnalysis);
            result.put("totalItems", usageAnalysis.size());
            result.put("criticalItems", usageAnalysis.stream()
                    .filter(i -> "CRITICAL".equals(i.get("riskLevel"))).count());
            result.put("highRiskItems", usageAnalysis.stream()
                    .filter(i -> "HIGH".equals(i.get("riskLevel"))).count());

        } catch (Exception e) {
            System.err.println("Error in getStockUsageAnalysis: " + e.getMessage());
            e.printStackTrace();
            result.put("error", e.getMessage());
            result.put("stockUsageAnalysis", new ArrayList<>());
        }

        return result;
    }

    /**
     * Item Consumption Heatmap
     */
    public Map<String, Object> getItemConsumptionHeatmap(Long itemId, String period,
                                                         LocalDate startDate, LocalDate endDate) {
        Map<String, Object> result = new HashMap<>();

        try {
            Optional<Item> itemOpt = itemRepository.findById(itemId);
            if (itemOpt.isEmpty()) {
                result.put("error", "Item not found");
                return result;
            }

            Item item = itemOpt.get();

            // Get date range
            if (endDate == null || startDate == null) {
                Map<String, LocalDate> dateRange = getActualDataRange();
                startDate = (startDate == null) ? dateRange.get("minDate") : startDate;
                endDate = (endDate == null) ? dateRange.get("maxDate") : endDate;
            }

            result.put("itemId", itemId);
            result.put("itemName", item.getItemName());
            result.put("period", period);
            result.put("startDate", startDate.toString());
            result.put("endDate", endDate.toString());

            List<ConsumptionRecord> records = consumptionRecordRepository
                    .findByItemAndConsumptionDateBetween(item, startDate, endDate);

            Map<String, BigDecimal> heatmapData = new TreeMap<>();

            // Generate heatmap based on period
            if ("daily".equalsIgnoreCase(period)) {
                for (ConsumptionRecord record : records) {
                    String key = record.getConsumptionDate().toString();
                    heatmapData.put(key, record.getConsumedQuantity());
                }

                // Fill missing dates with zero
                LocalDate current = startDate;
                while (!current.isAfter(endDate)) {
                    String key = current.toString();
                    heatmapData.putIfAbsent(key, BigDecimal.ZERO);
                    current = current.plusDays(1);
                }
            } else if ("weekly".equalsIgnoreCase(period)) {
                // Group by week
                for (ConsumptionRecord record : records) {
                    LocalDate weekStart = record.getConsumptionDate()
                            .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
                    String key = "Week " + weekStart.toString();
                    heatmapData.merge(key, record.getConsumedQuantity(), BigDecimal::add);
                }
            } else if ("monthly".equalsIgnoreCase(period)) {
                // Group by month
                for (ConsumptionRecord record : records) {
                    String key = record.getConsumptionDate()
                            .format(DateTimeFormatter.ofPattern("yyyy-MM"));
                    heatmapData.merge(key, record.getConsumedQuantity(), BigDecimal::add);
                }
            }

            result.put("heatmapData", heatmapData);
            result.put("maxConsumption", heatmapData.values().stream()
                    .max(BigDecimal::compareTo).orElse(BigDecimal.ZERO));
            result.put("totalConsumption", heatmapData.values().stream()
                    .reduce(BigDecimal.ZERO, BigDecimal::add));
            result.put("dataPoints", heatmapData.size());

        } catch (Exception e) {
            System.err.println("Error in getItemConsumptionHeatmap: " + e.getMessage());
            e.printStackTrace();
            result.put("error", e.getMessage());
        }

        return result;
    }

    /**
     * Stock Movement Analysis
     */
    public Map<String, Object> getStockMovementAnalysis(String period, LocalDate startDate, LocalDate endDate,
                                                        Long categoryId, Long itemId) {
        Map<String, Object> result = new HashMap<>();

        try {
            // Get date range
            if (endDate == null || startDate == null) {
                Map<String, LocalDate> dateRange = getActualDataRange();
                startDate = (startDate == null) ? dateRange.get("minDate") : startDate;
                endDate = (endDate == null) ? dateRange.get("maxDate") : endDate;
            }

            result.put("period", period);
            result.put("startDate", startDate.toString());
            result.put("endDate", endDate.toString());

            List<Object[]> movementData;

            if (itemId != null) {
                movementData = stockMovementRepository.getItemMovementAnalysis(itemId, startDate, endDate);
                result.put("filterType", "item");
                result.put("filterId", itemId);
            } else if (categoryId != null) {
                movementData = stockMovementRepository.getCategoryMovementAnalysis(categoryId, startDate, endDate);
                result.put("filterType", "category");
                result.put("filterId", categoryId);
            } else {
                movementData = stockMovementRepository.getAllMovementAnalysis(startDate, endDate);
                result.put("filterType", "all");
            }

            List<Map<String, Object>> movements = new ArrayList<>();
            BigDecimal totalInward = BigDecimal.ZERO;
            BigDecimal totalOutward = BigDecimal.ZERO;

            for (Object[] row : movementData) {
                Map<String, Object> movement = new HashMap<>();
                String movementType = (String) row[0];
                BigDecimal quantity = (BigDecimal) row[1];
                Long count = ((Number) row[2]).longValue();

                movement.put("movementType", movementType);
                movement.put("totalQuantity", quantity);
                movement.put("transactionCount", count);

                if ("RECEIPT".equals(movementType) || "ADJUSTMENT_IN".equals(movementType)) {
                    totalInward = totalInward.add(quantity);
                } else {
                    totalOutward = totalOutward.add(quantity);
                }

                movements.add(movement);
            }

            result.put("movements", movements);
            result.put("totalInward", totalInward);
            result.put("totalOutward", totalOutward);
            result.put("netChange", totalInward.subtract(totalOutward));

        } catch (Exception e) {
            System.err.println("Error in getStockMovementAnalysis: " + e.getMessage());
            e.printStackTrace();
            result.put("error", e.getMessage());
        }

        return result;
    }

    /**
     * Core Inventory Stock Levels
     */
    public Map<String, Object> getCoreInventoryStockLevels(Long categoryId, String alertLevel,
                                                           String sortBy, String sortOrder) {
        Map<String, Object> result = new HashMap<>();

        try {
            List<Item> items;
            if (categoryId != null) {
                // FIX: Filter by category without pageable
                items = itemRepository.findAll().stream()
                        .filter(item -> item.getCategory() != null && item.getCategory().getId().equals(categoryId))
                        .collect(Collectors.toList());
            } else {
                items = itemRepository.findAll();
            }

            List<Map<String, Object>> stockLevels = new ArrayList<>();
            Map<String, LocalDate> dateRange = getActualDataRange();
            LocalDate startDate = dateRange.get("minDate");
            LocalDate endDate = dateRange.get("maxDate");

            for (Item item : items) {
                Map<String, Object> stockInfo = new HashMap<>();
                stockInfo.put("itemId", item.getId());
                stockInfo.put("itemName", item.getItemName());

                stockInfo.put("categoryName", item.getCategory().getCategoryName());
                stockInfo.put("currentQuantity", item.getCurrentQuantity());
                stockInfo.put("unitPrice", item.getUnitPrice());
                stockInfo.put("totalValue", item.getTotalValue());

                // Calculate average daily consumption
                BigDecimal avgConsumption = safeDivide(
                        getConsumptionForItem(item.getId(), startDate, endDate),
                        BigDecimal.valueOf(ChronoUnit.DAYS.between(startDate, endDate) + 1),
                        2, RoundingMode.HALF_UP
                );

                // Calculate coverage days
                BigDecimal coverageDays = avgConsumption.compareTo(BigDecimal.ZERO) > 0
                        ? safeDivide(nullSafe(item.getCurrentQuantity()), avgConsumption, 0, RoundingMode.UP)
                        : BigDecimal.valueOf(999);

                stockInfo.put("coverageDays", coverageDays.intValue());
                stockInfo.put("avgDailyConsumption", avgConsumption);

                // Determine stock alert level based on coverageDays
                String stockAlertLevel;
                if (coverageDays.intValue() <= 3) {
                    stockAlertLevel = "CRITICAL";
                } else if (coverageDays.intValue() <= 7) {
                    stockAlertLevel = "WARNING";
                } else {
                    stockAlertLevel = "NORMAL";
                }
                stockInfo.put("stockAlertLevel", stockAlertLevel);

                // Filter by alert level if specified
                if (alertLevel == null || alertLevel.equalsIgnoreCase(stockAlertLevel)) {
                    stockLevels.add(stockInfo);
                }
            }

            // Sorting
            Comparator<Map<String, Object>> comparator;
            switch (sortBy.toLowerCase()) {
                case "coveragedays":
                    comparator = Comparator.comparing(m -> (Integer) m.get("coverageDays"));
                    break;
                case "currentquantity":
                    comparator = Comparator.comparing(m -> (BigDecimal) m.get("currentQuantity"));
                    break;
                case "totalvalue":
                    comparator = Comparator.comparing(m -> (BigDecimal) m.get("totalValue"));
                    break;
                default:
                    comparator = Comparator.comparing(m -> (String) m.get("stockAlertLevel"));
            }

            if ("desc".equalsIgnoreCase(sortOrder)) {
                comparator = comparator.reversed();
            }

            stockLevels.sort(comparator);

            result.put("stockLevels", stockLevels);
            result.put("totalItems", stockLevels.size());
            result.put("criticalCount", stockLevels.stream()
                    .filter(i -> "CRITICAL".equals(i.get("stockAlertLevel"))).count());
            result.put("warningCount", stockLevels.stream()
                    .filter(i -> "WARNING".equals(i.get("stockAlertLevel"))).count());

        } catch (Exception e) {
            System.err.println("Error in getCoreInventoryStockLevels: " + e.getMessage());
            e.printStackTrace();
            result.put("error", e.getMessage());
            result.put("stockLevels", new ArrayList<>());
        }

        return result;
    }


    /**
     * Budget Consumption Analysis
     */
    public Map<String, Object> getBudgetConsumptionAnalysis(String period, LocalDate startDate, LocalDate endDate,
                                                            String budgetType, Long categoryId, String department,
                                                            boolean includeProjections) {
        Map<String, Object> result = new HashMap<>();

        try {
            // Get date range
            if (endDate == null || startDate == null) {
                Map<String, LocalDate> dateRange = getActualDataRange();
                startDate = (startDate == null) ? dateRange.get("minDate") : startDate;
                endDate = (endDate == null) ? dateRange.get("maxDate") : endDate;
            }

            result.put("period", period);
            result.put("startDate", startDate.toString());
            result.put("endDate", endDate.toString());
            result.put("budgetType", budgetType);

            // Get actual consumption data
            List<ConsumptionRecord> records;
            if (categoryId != null) {
                records = consumptionRecordRepository.findByCategoryAndDateBetween(categoryId, startDate, endDate);
            } else {
                records = consumptionRecordRepository.findByConsumptionDateBetween(startDate, endDate);
            }

            // Calculate monthly data for budget chart
            Map<String, Map<String, BigDecimal>> monthlyData = new TreeMap<>();

            for (ConsumptionRecord record : records) {
                String monthKey = record.getConsumptionDate().format(DateTimeFormatter.ofPattern("yyyy-MM"));

                monthlyData.computeIfAbsent(monthKey, k -> {
                    Map<String, BigDecimal> monthMap = new HashMap<>();
                    monthMap.put("actualSpending", BigDecimal.ZERO);
                    monthMap.put("quantity", BigDecimal.ZERO);
                    return monthMap;
                });

                Map<String, BigDecimal> monthMap = monthlyData.get(monthKey);
                BigDecimal cost = nullSafe(record.getConsumedQuantity())
                        .multiply(nullSafe(record.getItem().getUnitPrice()));

                monthMap.put("actualSpending", monthMap.get("actualSpending").add(cost));
                monthMap.put("quantity", monthMap.get("quantity").add(record.getConsumedQuantity()));
            }

            // Create budget data array for chart
            List<Map<String, Object>> budgetData = new ArrayList<>();
            BigDecimal totalActualSpending = BigDecimal.ZERO;

            for (Map.Entry<String, Map<String, BigDecimal>> entry : monthlyData.entrySet()) {
                String monthKey = entry.getKey();
                BigDecimal actualSpending = entry.getValue().get("actualSpending");

                // Generate mock budget (120% of average spending)
                BigDecimal plannedBudget = actualSpending.multiply(BigDecimal.valueOf(1.2));

                Map<String, Object> monthData = new HashMap<>();
                monthData.put("month", monthKey);
                monthData.put("plannedBudget", plannedBudget.doubleValue());
                monthData.put("actualSpending", actualSpending.doubleValue());

                BigDecimal variance = actualSpending.subtract(plannedBudget);
                BigDecimal variancePercentage = safeDivide(
                        variance.multiply(BigDecimal.valueOf(100)),
                        plannedBudget, 2, RoundingMode.HALF_UP
                );

                monthData.put("variance", variance.doubleValue());
                monthData.put("variancePercentage", variancePercentage.doubleValue());

                budgetData.add(monthData);
                totalActualSpending = totalActualSpending.add(actualSpending);
            }

            // Calculate totals
            BigDecimal totalPlannedBudget = totalActualSpending.multiply(BigDecimal.valueOf(1.2));
            BigDecimal totalVariance = totalActualSpending.subtract(totalPlannedBudget);

            result.put("budgetData", budgetData);
            result.put("totalPlannedBudget", totalPlannedBudget.doubleValue());
            result.put("totalActualSpending", totalActualSpending.doubleValue());
            result.put("totalVariance", totalVariance.doubleValue());
            result.put("totalVariancePercentage",
                    safeDivide(totalVariance.multiply(BigDecimal.valueOf(100)),
                            totalPlannedBudget, 2, RoundingMode.HALF_UP).doubleValue());

        } catch (Exception e) {
            System.err.println("Error in getBudgetConsumptionAnalysis: " + e.getMessage());
            e.printStackTrace();
            result.put("error", e.getMessage());

            // Return empty structure to prevent frontend errors
            result.put("budgetData", new ArrayList<>());
            result.put("totalPlannedBudget", 0);
            result.put("totalActualSpending", 0);
            result.put("totalVariance", 0);
        }

        return result;
    }

    /**
     * Enhanced Cost Distribution
     */
    public Map<String, Object> getEnhancedCostDistribution(String period, LocalDate startDate, LocalDate endDate,
                                                           String breakdown, boolean includeProjections,
                                                           Long categoryId, String department, String costCenter) {
        Map<String, Object> result = new HashMap<>();

        try {
            // Get date range
            if (endDate == null || startDate == null) {
                Map<String, LocalDate> dateRange = getActualDataRange();
                startDate = (startDate == null) ? dateRange.get("minDate") : startDate;
                endDate = (endDate == null) ? dateRange.get("maxDate") : endDate;
            }

            result.put("period", period);
            result.put("startDate", startDate.toString());
            result.put("endDate", endDate.toString());
            result.put("breakdown", breakdown);

            // Get consumption records
            List<ConsumptionRecord> records;
            if (categoryId != null) {
                records = consumptionRecordRepository.findByCategoryAndDateBetween(categoryId, startDate, endDate);
            } else {
                records = consumptionRecordRepository.findByConsumptionDateBetween(startDate, endDate);
            }

            if ("detailed".equalsIgnoreCase(breakdown)) {
                // Monthly breakdown with categories and items
                Map<String, Map<String, List<Map<String, Object>>>> monthlyBreakdown = new TreeMap<>();

                for (ConsumptionRecord record : records) {
                    String monthKey = record.getConsumptionDate().format(DateTimeFormatter.ofPattern("yyyy-MM"));
                    String categoryName = record.getItem().getCategory().getCategoryName();

                    monthlyBreakdown.computeIfAbsent(monthKey, k -> new HashMap<>())
                            .computeIfAbsent(categoryName, k -> new ArrayList<>());

                    Map<String, Object> itemDetail = new HashMap<>();
                    itemDetail.put("itemId", record.getItem().getId());
                    itemDetail.put("itemName", record.getItem().getItemName());
                    itemDetail.put("consumptionDate", record.getConsumptionDate().toString());
                    itemDetail.put("quantity", record.getConsumedQuantity());
                    itemDetail.put("unitPrice", record.getItem().getUnitPrice());
                    itemDetail.put("totalCost", record.getConsumedQuantity().multiply(record.getItem().getUnitPrice()));

                    monthlyBreakdown.get(monthKey).get(categoryName).add(itemDetail);
                }

                // Convert to structured format
                List<Map<String, Object>> monthlyData = new ArrayList<>();
                for (Map.Entry<String, Map<String, List<Map<String, Object>>>> monthEntry : monthlyBreakdown.entrySet()) {
                    Map<String, Object> monthData = new HashMap<>();
                    monthData.put("month", monthEntry.getKey());

                    List<Map<String, Object>> categories = new ArrayList<>();
                    BigDecimal monthTotal = BigDecimal.ZERO;

                    for (Map.Entry<String, List<Map<String, Object>>> categoryEntry : monthEntry.getValue().entrySet()) {
                        Map<String, Object> categoryData = new HashMap<>();
                        categoryData.put("categoryName", categoryEntry.getKey());

                        BigDecimal categoryTotal = categoryEntry.getValue().stream()
                                .map(item -> (BigDecimal) item.get("totalCost"))
                                .reduce(BigDecimal.ZERO, BigDecimal::add);

                        categoryData.put("items", categoryEntry.getValue());
                        categoryData.put("totalCost", categoryTotal);
                        categoryData.put("itemCount", categoryEntry.getValue().size());

                        categories.add(categoryData);
                        monthTotal = monthTotal.add(categoryTotal);
                    }

                    monthData.put("categories", categories);
                    monthData.put("monthlyTotal", monthTotal);
                    monthlyData.add(monthData);
                }

                result.put("monthlyBreakdown", monthlyData);
            } else {
                // Summary breakdown
                Map<String, BigDecimal> categoryCosts = new HashMap<>();

                for (ConsumptionRecord record : records) {
                    String categoryName = record.getItem().getCategory().getCategoryName();
                    BigDecimal cost = record.getConsumedQuantity().multiply(record.getItem().getUnitPrice());
                    categoryCosts.merge(categoryName, cost, BigDecimal::add);
                }

                result.put("categoryCosts", categoryCosts);
            }

            if (includeProjections) {
                result.put("projections", generateSimpleForecasts(new ArrayList<>()));
            }

        } catch (Exception e) {
            System.err.println("Error in getEnhancedCostDistribution: " + e.getMessage());
            e.printStackTrace();
            result.put("error", e.getMessage());
        }

        return result;
    }

    /**
     * Budget vs Actual Comparison
     */
    public Map<String, Object> getBudgetVsActualComparison(int year, String granularity, Long categoryId,
                                                           String department, boolean includeForecasts,
                                                           boolean includeVariance, Integer quarter) {
        Map<String, Object> result = new HashMap<>();

        try {
            result.put("year", year);
            result.put("granularity", granularity);

            LocalDate yearStart = LocalDate.of(year, 1, 1);
            LocalDate yearEnd = LocalDate.of(year, 12, 31);
            LocalDate currentDate = LocalDate.now();

            if (currentDate.isBefore(yearEnd)) {
                yearEnd = currentDate;
            }

            // Get actual consumption data
            List<ConsumptionRecord> records;
            if (categoryId != null) {
                records = consumptionRecordRepository.findByCategoryAndDateBetween(categoryId, yearStart, yearEnd);
            } else {
                records = consumptionRecordRepository.findByConsumptionDateBetween(yearStart, yearEnd);
            }

            // Calculate actual costs by month
            Map<String, BigDecimal> monthlyActual = new TreeMap<>();
            for (ConsumptionRecord record : records) {
                String monthKey = record.getConsumptionDate().format(DateTimeFormatter.ofPattern("yyyy-MM"));
                BigDecimal cost = record.getConsumedQuantity().multiply(record.getItem().getUnitPrice());
                monthlyActual.merge(monthKey, cost, BigDecimal::add);
            }

            List<Map<String, Object>> comparison = new ArrayList<>();

            // Generate comparison data
            for (int month = 1; month <= 12; month++) {
                if (quarter != null && (month - 1) / 3 + 1 != quarter) {
                    continue;
                }

                String monthKey = String.format("%d-%02d", year, month);
                LocalDate monthStart = LocalDate.of(year, month, 1);

                if (monthStart.isAfter(currentDate)) {
                    break;
                }

                Map<String, Object> monthData = new HashMap<>();
                monthData.put("period", monthKey);
                monthData.put("monthName", monthStart.getMonth().toString());

                // Mock budget (in production, from budget repository)
                BigDecimal budgeted = BigDecimal.valueOf(50000 + (month * 1000)); // Example budget
                BigDecimal actual = monthlyActual.getOrDefault(monthKey, BigDecimal.ZERO);

                monthData.put("budgeted", budgeted);
                monthData.put("actual", actual);

                if (includeVariance) {
                    BigDecimal variance = actual.subtract(budgeted);
                    BigDecimal variancePercent = safeDivide(variance.multiply(BigDecimal.valueOf(100)), budgeted, 2, RoundingMode.HALF_UP);
                    monthData.put("variance", variance);
                    monthData.put("variancePercent", variancePercent);
                }

                comparison.add(monthData);
            }

            result.put("comparison", comparison);

            // Calculate totals
            BigDecimal totalBudget = comparison.stream()
                    .map(m -> (BigDecimal) m.get("budgeted"))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal totalActual = comparison.stream()
                    .map(m -> (BigDecimal) m.get("actual"))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            result.put("totalBudget", totalBudget);
            result.put("totalActual", totalActual);
            result.put("totalVariance", totalActual.subtract(totalBudget));

            if (includeForecasts) {
                // Simple forecast for remaining months
                int monthsComplete = comparison.size();
                if (monthsComplete > 0) {
                    BigDecimal avgMonthly = safeDivide(totalActual, BigDecimal.valueOf(monthsComplete), 2, RoundingMode.HALF_UP);
                    BigDecimal forecastedTotal = avgMonthly.multiply(BigDecimal.valueOf(12));
                    result.put("forecastedAnnualTotal", forecastedTotal);
                }
            }

        } catch (Exception e) {
            System.err.println("Error in getBudgetVsActualComparison: " + e.getMessage());
            e.printStackTrace();
            result.put("error", e.getMessage());
        }

        return result;
    }

    /**
     * Cost Optimization Recommendations
     */
    public Map<String, Object> getCostOptimizationRecommendations(String analysisType, double threshold, String period,
                                                                  Long categoryId, double minSavings,
                                                                  boolean includeAlternatives) {
        Map<String, Object> result = new HashMap<>();

        try {
            result.put("analysisType", analysisType);
            result.put("threshold", threshold);
            result.put("minSavings", minSavings);

            List<Map<String, Object>> recommendations = new ArrayList<>();

            Map<String, LocalDate> dateRange = getActualDataRange();
            LocalDate startDate = dateRange.get("minDate");
            LocalDate endDate = dateRange.get("maxDate");

            // Get items for analysis
            List<Item> items;
            if (categoryId != null) {
                items = itemRepository.findAll().stream()
                        .filter(item -> item.getCategory() != null && item.getCategory().getId().equals(categoryId))
                        .collect(Collectors.toList());
            } else {
                items = itemRepository.findAll();
            }

            for (Item item : items) {
                BigDecimal totalConsumption = getConsumptionForItem(item.getId(), startDate, endDate);
                BigDecimal totalCost = totalConsumption.multiply(nullSafe(item.getUnitPrice()));

                if (totalCost.compareTo(BigDecimal.valueOf(minSavings)) > 0) {
                    Map<String, Object> recommendation = new HashMap<>();
                    recommendation.put("itemId", item.getId());
                    recommendation.put("itemName", item.getItemName());
                    recommendation.put("currentUnitPrice", item.getUnitPrice());
                    recommendation.put("totalConsumption", totalConsumption);
                    recommendation.put("currentTotalCost", totalCost);

                    // Generate optimization suggestions
                    List<String> suggestions = new ArrayList<>();
                    BigDecimal potentialSavings = BigDecimal.ZERO;

                    // Price variance analysis
                    if ("variance".equalsIgnoreCase(analysisType)) {
                        BigDecimal targetPrice = item.getUnitPrice().multiply(BigDecimal.valueOf(1 - threshold));
                        BigDecimal savingsIfOptimized = totalConsumption.multiply(item.getUnitPrice().subtract(targetPrice));

                        if (savingsIfOptimized.compareTo(BigDecimal.valueOf(minSavings)) > 0) {
                            suggestions.add("Negotiate price reduction to " + targetPrice + " per unit");
                            potentialSavings = potentialSavings.add(savingsIfOptimized);
                        }
                    }

                    // Volume optimization
                    BigDecimal avgDailyConsumption = safeDivide(totalConsumption,
                            BigDecimal.valueOf(ChronoUnit.DAYS.between(startDate, endDate) + 1), 2, RoundingMode.HALF_UP);

                    if (avgDailyConsumption.compareTo(BigDecimal.valueOf(10)) > 0) {
                        suggestions.add("Consider bulk purchasing for volume discounts");
                        potentialSavings = potentialSavings.add(totalCost.multiply(BigDecimal.valueOf(0.05))); // 5% bulk discount
                    }

                    if (includeAlternatives) {
                        suggestions.add("Explore alternative suppliers or substitute products");
                    }

                    if (!suggestions.isEmpty()) {
                        recommendation.put("suggestions", suggestions);
                        recommendation.put("potentialSavings", potentialSavings);
                        recommendation.put("savingsPercentage", safeDivide(
                                potentialSavings.multiply(BigDecimal.valueOf(100)), totalCost, 2, RoundingMode.HALF_UP));
                        recommendations.add(recommendation);
                    }
                }
            }

            // Sort by potential savings
            recommendations.sort((a, b) -> {
                BigDecimal savingsA = (BigDecimal) a.get("potentialSavings");
                BigDecimal savingsB = (BigDecimal) b.get("potentialSavings");
                return savingsB.compareTo(savingsA);
            });

            result.put("recommendations", recommendations);
            result.put("totalPotentialSavings", recommendations.stream()
                    .map(r -> (BigDecimal) r.get("potentialSavings"))
                    .reduce(BigDecimal.ZERO, BigDecimal::add));

        } catch (Exception e) {
            System.err.println("Error in getCostOptimizationRecommendations: " + e.getMessage());
            e.printStackTrace();
            result.put("error", e.getMessage());
            result.put("recommendations", new ArrayList<>());
        }

        return result;
    }

    /**
     * Burn Rate Analysis
     */
    public Map<String, Object> getBurnRateAnalysis(LocalDate targetDate, boolean includeTrends,
                                                   Long categoryId, String department, double alertThreshold) {
        Map<String, Object> result = new HashMap<>();

        try {
            if (targetDate == null) {
                targetDate = LocalDate.now().plusMonths(6);
            }

            result.put("targetDate", targetDate.toString());
            result.put("alertThreshold", alertThreshold);

            Map<String, LocalDate> dateRange = getActualDataRange();
            LocalDate startDate = dateRange.get("minDate");
            LocalDate endDate = dateRange.get("maxDate");

            // Get consumption records
            List<ConsumptionRecord> records;
            if (categoryId != null) {
                records = consumptionRecordRepository.findByCategoryAndDateBetween(categoryId, startDate, endDate);
            } else {
                records = consumptionRecordRepository.findByConsumptionDateBetween(startDate, endDate);
            }

            // Calculate burn rate
            BigDecimal totalCost = BigDecimal.ZERO;
            for (ConsumptionRecord record : records) {
                totalCost = totalCost.add(record.getConsumedQuantity().multiply(record.getItem().getUnitPrice()));
            }

            long daysElapsed = ChronoUnit.DAYS.between(startDate, endDate) + 1;
            BigDecimal dailyBurnRate = safeDivide(totalCost, BigDecimal.valueOf(daysElapsed), 2, RoundingMode.HALF_UP);
            BigDecimal monthlyBurnRate = dailyBurnRate.multiply(BigDecimal.valueOf(30));

            // Project to target date
            long daysToTarget = ChronoUnit.DAYS.between(endDate, targetDate);
            BigDecimal projectedSpend = dailyBurnRate.multiply(BigDecimal.valueOf(daysToTarget));

            result.put("currentBurnRate", Map.of(
                    "daily", dailyBurnRate,
                    "weekly", dailyBurnRate.multiply(BigDecimal.valueOf(7)),
                    "monthly", monthlyBurnRate
            ));
            result.put("totalSpentToDate", totalCost);
            result.put("projectedSpendToTarget", projectedSpend);
            result.put("daysToTarget", daysToTarget);

            if (includeTrends) {
                // Calculate trend over last 3 months
                List<Map<String, Object>> monthlyTrends = new ArrayList<>();

                for (int i = 2; i >= 0; i--) {
                    LocalDate monthStart = endDate.minusMonths(i).withDayOfMonth(1);
                    LocalDate monthEnd = monthStart.with(TemporalAdjusters.lastDayOfMonth());

                    if (monthEnd.isAfter(endDate)) {
                        monthEnd = endDate;
                    }

                    BigDecimal monthCost = BigDecimal.ZERO;
                    for (ConsumptionRecord record : records) {
                        if (!record.getConsumptionDate().isBefore(monthStart) &&
                                !record.getConsumptionDate().isAfter(monthEnd)) {
                            monthCost = monthCost.add(record.getConsumedQuantity().multiply(record.getItem().getUnitPrice()));
                        }
                    }

                    Map<String, Object> monthTrend = new HashMap<>();
                    monthTrend.put("month", monthStart.format(DateTimeFormatter.ofPattern("MMM yyyy")));
                    monthTrend.put("cost", monthCost);
                    monthlyTrends.add(monthTrend);
                }

                result.put("trends", monthlyTrends);
            }

            // Alert status
            String alertStatus = "NORMAL";
            if (monthlyBurnRate.compareTo(BigDecimal.valueOf(100000 * alertThreshold)) > 0) {
                alertStatus = "HIGH";
            }
            result.put("alertStatus", alertStatus);

        } catch (Exception e) {
            System.err.println("Error in getBurnRateAnalysis: " + e.getMessage());
            e.printStackTrace();
            result.put("error", e.getMessage());
        }

        return result;
    }

    /**
     * Cost Per Employee Analysis
     */
    public Map<String, Object> getCostPerEmployeeAnalysis(String period, LocalDate startDate, LocalDate endDate,
                                                          Long categoryId, String department,
                                                          boolean includeComparisons) {
        Map<String, Object> result = new HashMap<>();

        try {
            // Get date range
            if (endDate == null || startDate == null) {
                Map<String, LocalDate> dateRange = getActualDataRange();
                startDate = (startDate == null) ? dateRange.get("minDate") : startDate;
                endDate = (endDate == null) ? dateRange.get("maxDate") : endDate;
            }

            result.put("period", period);
            result.put("startDate", startDate.toString());
            result.put("endDate", endDate.toString());

            // Get consumption records
            List<ConsumptionRecord> records;
            if (categoryId != null) {
                records = consumptionRecordRepository.findByCategoryAndDateBetween(categoryId, startDate, endDate);
            } else {
                records = consumptionRecordRepository.findByConsumptionDateBetween(startDate, endDate);
            }

            // Calculate total costs
            BigDecimal totalCost = BigDecimal.ZERO;
            Map<String, BigDecimal> categoryCosts = new HashMap<>();

            for (ConsumptionRecord record : records) {
                BigDecimal cost = record.getConsumedQuantity().multiply(record.getItem().getUnitPrice());
                totalCost = totalCost.add(cost);

                String categoryName = record.getItem().getCategory().getCategoryName();
                categoryCosts.merge(categoryName, cost, BigDecimal::add);
            }

            // Get average employee count (mock data - in production from footfall)
            BigDecimal avgEmployeeCount = BigDecimal.valueOf(250); // Example average

            BigDecimal costPerEmployee = safeDivide(totalCost, avgEmployeeCount, 2, RoundingMode.HALF_UP);

            result.put("totalCost", totalCost);
            result.put("averageEmployeeCount", avgEmployeeCount);
            result.put("costPerEmployee", costPerEmployee);

            // Category breakdown per employee
            List<Map<String, Object>> categoryBreakdown = new ArrayList<>();
            for (Map.Entry<String, BigDecimal> entry : categoryCosts.entrySet()) {
                Map<String, Object> categoryData = new HashMap<>();
                categoryData.put("category", entry.getKey());
                categoryData.put("totalCost", entry.getValue());
                categoryData.put("costPerEmployee", safeDivide(entry.getValue(), avgEmployeeCount, 2, RoundingMode.HALF_UP));
                categoryData.put("percentageOfTotal", safeDivide(
                        entry.getValue().multiply(BigDecimal.valueOf(100)), totalCost, 2, RoundingMode.HALF_UP));
                categoryBreakdown.add(categoryData);
            }

            result.put("categoryBreakdown", categoryBreakdown);

            if (includeComparisons) {
                // Industry benchmark comparison (mock data)
                BigDecimal industryAverage = BigDecimal.valueOf(85); // Example industry average
                BigDecimal variance = costPerEmployee.subtract(industryAverage);

                Map<String, Object> comparison = new HashMap<>();
                comparison.put("industryAverage", industryAverage);
                comparison.put("variance", variance);
                comparison.put("performance", variance.compareTo(BigDecimal.ZERO) < 0 ? "BELOW_AVERAGE" : "ABOVE_AVERAGE");
                result.put("comparison", comparison);
            }

        } catch (Exception e) {
            System.err.println("Error in getCostPerEmployeeAnalysis: " + e.getMessage());
            e.printStackTrace();
            result.put("error", e.getMessage());
        }

        return result;
    }

    /**
     * Cost Variance Alerts
     */
    public Map<String, Object> getCostVarianceAlerts(String severity, boolean activeOnly,
                                                     Long categoryId, String department, double threshold) {
        Map<String, Object> result = new HashMap<>();

        try {
            result.put("severity", severity);
            result.put("threshold", threshold);

            List<Map<String, Object>> alerts = new ArrayList<>();

            Map<String, LocalDate> dateRange = getActualDataRange();
            LocalDate endDate = dateRange.get("maxDate");
            LocalDate currentMonthStart = endDate.withDayOfMonth(1);
            LocalDate previousMonthStart = currentMonthStart.minusMonths(1);
            LocalDate previousMonthEnd = currentMonthStart.minusDays(1);

            // Get items for analysis
            List<Item> items;
            if (categoryId != null) {
                items = itemRepository.findAll().stream()
                        .filter(item -> item.getCategory() != null && item.getCategory().getId().equals(categoryId))
                        .collect(Collectors.toList());
            } else {
                items = itemRepository.findAll();
            }

            for (Item item : items) {
                // Compare current month vs previous month
                BigDecimal currentMonthConsumption = getConsumptionForItem(item.getId(), currentMonthStart, endDate);
                BigDecimal previousMonthConsumption = getConsumptionForItem(item.getId(), previousMonthStart, previousMonthEnd);

                if (previousMonthConsumption.compareTo(BigDecimal.ZERO) > 0) {
                    BigDecimal variance = currentMonthConsumption.subtract(previousMonthConsumption);
                    BigDecimal variancePercent = safeDivide(
                            variance.multiply(BigDecimal.valueOf(100)), previousMonthConsumption, 2, RoundingMode.HALF_UP);

                    boolean createAlert = false;
                    String alertSeverity = "LOW";

                    if (variancePercent.abs().compareTo(BigDecimal.valueOf(threshold * 100)) > 0) {
                        if (variancePercent.abs().compareTo(BigDecimal.valueOf(50)) > 0) {
                            alertSeverity = "HIGH";
                            createAlert = true;
                        } else if (variancePercent.abs().compareTo(BigDecimal.valueOf(25)) > 0) {
                            alertSeverity = "MEDIUM";
                            createAlert = true;
                        } else {
                            alertSeverity = "LOW";
                            createAlert = true;
                        }

                        if (createAlert && (severity.equalsIgnoreCase("all") || severity.equalsIgnoreCase(alertSeverity))) {
                            Map<String, Object> alert = new HashMap<>();
                            alert.put("itemId", item.getId());
                            alert.put("itemName", item.getItemName());
                            alert.put("category", item.getCategory().getCategoryName());
                            alert.put("severity", alertSeverity);
                            alert.put("variancePercent", variancePercent);
                            alert.put("currentMonthConsumption", currentMonthConsumption);
                            alert.put("previousMonthConsumption", previousMonthConsumption);
                            alert.put("active", true);
                            alert.put("message", String.format("%s consumption variance: %.2f%%",
                                    variancePercent.compareTo(BigDecimal.ZERO) > 0 ? "Increased" : "Decreased",
                                    variancePercent.abs()));

                            alerts.add(alert);
                        }
                    }
                }
            }

            // Sort by severity and variance
            alerts.sort((a, b) -> {
                String sevA = (String) a.get("severity");
                String sevB = (String) b.get("severity");
                if (!sevA.equals(sevB)) {
                    return sevB.compareTo(sevA); // HIGH > MEDIUM > LOW
                }
                BigDecimal varA = (BigDecimal) a.get("variancePercent");
                BigDecimal varB = (BigDecimal) b.get("variancePercent");
                return varB.abs().compareTo(varA.abs());
            });

            result.put("alerts", alerts);
            result.put("totalAlerts", alerts.size());
            result.put("highSeverityCount", alerts.stream().filter(a -> "HIGH".equals(a.get("severity"))).count());
            result.put("mediumSeverityCount", alerts.stream().filter(a -> "MEDIUM".equals(a.get("severity"))).count());
            result.put("lowSeverityCount", alerts.stream().filter(a -> "LOW".equals(a.get("severity"))).count());

        } catch (Exception e) {
            System.err.println("Error in getCostVarianceAlerts: " + e.getMessage());
            e.printStackTrace();
            result.put("error", e.getMessage());
            result.put("alerts", new ArrayList<>());
        }

        return result;
    }

    // Keep helper class
    private static class ItemConsumptionSummary {
        Item item;
        BigDecimal totalConsumption;
        BigDecimal totalCost;
    }
}