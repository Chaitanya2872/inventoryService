package com.bmsedge.inventory.service;

import com.bmsedge.inventory.dto.AnalyticsResponse;
import com.bmsedge.inventory.model.*;
import com.bmsedge.inventory.repository.*;
import com.bmsedge.inventory.exception.ResourceNotFoundException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
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
    private StockMovementRepository stockMovementRepository;

    @Autowired
    private CategoryRepository categoryRepository;

    /**
     * Legacy method - returns AnalyticsResponse for existing frontend
     */
    public AnalyticsResponse getAnalytics() {
        AnalyticsResponse.UsageStock usageStock = generateUsageStock();
        List<AnalyticsResponse.ConsumptionTrend> consumptionTrends = generateConsumptionTrends();

        return new AnalyticsResponse(usageStock, consumptionTrends);
    }

    /**
     * Dashboard statistics - enhanced version
     */
    public Map<String, Object> getDashboardStats() {
        Map<String, Object> stats = new HashMap<>();

        List<Item> allItems = itemRepository.findAll();
        Long totalQuantity = itemRepository.getTotalQuantity();
        if (totalQuantity == null) totalQuantity = 0L;

        stats.put("totalItems", allItems.size());
        stats.put("totalQuantity", totalQuantity);
        stats.put("lowStockAlerts", itemRepository.findLowStockItems(10).size());
        stats.put("expiredItems", itemRepository.findExpiredItems(LocalDateTime.now()).size());

        LocalDateTime weekAhead = LocalDateTime.now().plusDays(7);
        stats.put("expiringSoon", itemRepository.findExpiringItems(LocalDateTime.now(), weekAhead).size());

        return stats;
    }

    /**
     * Get consumption data date range (used by multiple methods)
     */
    private Map<String, LocalDate> getConsumptionDataDateRange() {
        List<ConsumptionRecord> allRecords = consumptionRecordRepository.findAll();

        if (allRecords.isEmpty()) {
            LocalDate today = LocalDate.now();
            Map<String, LocalDate> dateRange = new HashMap<>();
            dateRange.put("minDate", today.minusDays(30));
            dateRange.put("maxDate", today);
            return dateRange;
        }

        LocalDate minDate = allRecords.stream()
                .map(ConsumptionRecord::getConsumptionDate)
                .min(LocalDate::compareTo)
                .orElse(LocalDate.now().minusDays(30));

        LocalDate maxDate = allRecords.stream()
                .map(ConsumptionRecord::getConsumptionDate)
                .max(LocalDate::compareTo)
                .orElse(LocalDate.now());

        Map<String, LocalDate> dateRange = new HashMap<>();
        dateRange.put("minDate", minDate);
        dateRange.put("maxDate", maxDate);
        return dateRange;
    }

    /**
     * GRAPH 1: Consumption Trends - Daily/Weekly/Monthly by Category and Items
     */
    public Map<String, Object> getConsumptionTrends(String period, String groupBy, Long categoryId) {
        Map<String, Object> result = new HashMap<>();

        // Get the actual date range where we have consumption data
        Map<String, LocalDate> dateRange = getConsumptionDataDateRange();
        LocalDate dataMinDate = dateRange.get("minDate");
        LocalDate dataMaxDate = dateRange.get("maxDate");

        LocalDate endDate = dataMaxDate;
        LocalDate startDate = switch (period.toLowerCase()) {
            case "daily" -> dataMinDate.isAfter(dataMaxDate.minusDays(30)) ?
                    dataMaxDate.minusDays(30) : dataMinDate;
            case "weekly" -> dataMinDate.isAfter(dataMaxDate.minusWeeks(12)) ?
                    dataMaxDate.minusWeeks(12) : dataMinDate;
            case "monthly" -> dataMinDate.isAfter(dataMaxDate.minusMonths(6)) ?
                    dataMaxDate.minusMonths(6) : dataMinDate;
            default -> dataMinDate.isAfter(dataMaxDate.minusDays(30)) ?
                    dataMaxDate.minusDays(30) : dataMinDate;
        };

        // Determine date range based on period, but constrain to actual data

        result.put("period", period);
        result.put("groupBy", groupBy);
        result.put("startDate", startDate.toString());
        result.put("endDate", endDate.toString());
        result.put("actualDataRange", dataMinDate.toString() + " to " + dataMaxDate.toString());

        if ("category".equals(groupBy)) {
            result.put("data", getCategoryConsumptionTrends(period, startDate, endDate, categoryId));
        } else {
            result.put("data", getItemConsumptionTrends(period, startDate, endDate, categoryId));
        }

        return result;
    }

    private List<Map<String, Object>> getCategoryConsumptionTrends(String period, LocalDate startDate, LocalDate endDate, Long categoryId) {
        List<Map<String, Object>> trends = new ArrayList<>();
        List<Category> categories = categoryId != null ?
                Arrays.asList(categoryRepository.findById(categoryId).orElse(null)) :
                categoryRepository.findAll();

        categories = categories.stream().filter(Objects::nonNull).collect(Collectors.toList());

        for (Category category : categories) {
            Map<String, Object> categoryTrend = new HashMap<>();
            categoryTrend.put("category", category.getCategoryName());
            categoryTrend.put("categoryId", category.getId());

            List<Map<String, Object>> dataPoints = new ArrayList<>();

            if ("daily".equals(period)) {
                LocalDate currentDate = startDate;
                while (!currentDate.isAfter(endDate)) {
                    BigDecimal consumption = consumptionRecordRepository
                            .getTotalConsumptionByCategoryAndDate(category.getCategoryName(), currentDate);

                    Map<String, Object> point = new HashMap<>();
                    point.put("date", currentDate.toString());
                    point.put("consumption", consumption != null ? consumption.doubleValue() : 0.0);
                    dataPoints.add(point);
                    currentDate = currentDate.plusDays(1);
                }
            } else if ("weekly".equals(period)) {
                LocalDate weekStart = startDate.with(DayOfWeek.MONDAY);
                while (!weekStart.isAfter(endDate)) {
                    LocalDate weekEnd = weekStart.plusDays(6);
                    if (weekEnd.isAfter(endDate)) weekEnd = endDate;

                    BigDecimal consumption = consumptionRecordRepository
                            .getTotalConsumptionByCategoryInPeriod(category.getCategoryName(), weekStart, weekEnd);

                    Map<String, Object> point = new HashMap<>();
                    point.put("week", weekStart.format(DateTimeFormatter.ofPattern("'Week' w, yyyy")));
                    point.put("weekStart", weekStart.toString());
                    point.put("consumption", consumption != null ? consumption.doubleValue() : 0.0);
                    dataPoints.add(point);

                    weekStart = weekStart.plusWeeks(1);
                }
            } else { // monthly
                LocalDate monthStart = startDate.withDayOfMonth(1);
                while (!monthStart.isAfter(endDate)) {
                    LocalDate monthEnd = monthStart.with(TemporalAdjusters.lastDayOfMonth());
                    if (monthEnd.isAfter(endDate)) monthEnd = endDate;

                    BigDecimal consumption = consumptionRecordRepository
                            .getTotalConsumptionByCategoryInPeriod(category.getCategoryName(), monthStart, monthEnd);

                    Map<String, Object> point = new HashMap<>();
                    point.put("month", monthStart.format(DateTimeFormatter.ofPattern("MMM yyyy")));
                    point.put("consumption", consumption != null ? consumption.doubleValue() : 0.0);
                    dataPoints.add(point);

                    monthStart = monthStart.plusMonths(1);
                }
            }

            categoryTrend.put("dataPoints", dataPoints);
            trends.add(categoryTrend);
        }

        return trends;
    }

    private List<Map<String, Object>> getItemConsumptionTrends(String period, LocalDate startDate, LocalDate endDate, Long categoryId) {
        List<Map<String, Object>> trends = new ArrayList<>();
        List<Item> items = categoryId != null ?
                itemRepository.findByCategoryId(categoryId) :
                itemRepository.findAll();

        // Limit to top 10 items for performance
        Pageable limit = PageRequest.of(0, 10);
        List<Object[]> topItems = consumptionRecordRepository.getTopConsumersInPeriod(startDate, endDate, limit);

        for (Object[] itemData : topItems) {
            Long itemId = (Long) itemData[4];
            Optional<Item> itemOpt = itemRepository.findById(itemId);
            if (itemOpt.isEmpty()) continue;
            Item item = itemOpt.get();

            Map<String, Object> itemTrend = new HashMap<>();
            itemTrend.put("itemName", item.getItemName());
            itemTrend.put("itemId", item.getId());
            itemTrend.put("category", item.getCategory().getCategoryName());

            List<Map<String, Object>> dataPoints = new ArrayList<>();

            if ("daily".equals(period)) {
                LocalDate currentDate = startDate;
                while (!currentDate.isAfter(endDate)) {
                    BigDecimal consumption = consumptionRecordRepository
                            .getTotalConsumptionByItemAndDate(item.getId(), currentDate);

                    Map<String, Object> point = new HashMap<>();
                    point.put("date", currentDate.toString());
                    point.put("consumption", consumption != null ? consumption.doubleValue() : 0.0);
                    dataPoints.add(point);
                    currentDate = currentDate.plusDays(1);
                }
            } else if ("weekly".equals(period)) {
                LocalDate weekStart = startDate.with(DayOfWeek.MONDAY);
                while (!weekStart.isAfter(endDate)) {
                    LocalDate weekEnd = weekStart.plusDays(6);
                    if (weekEnd.isAfter(endDate)) weekEnd = weekStart.plusDays((int) ChronoUnit.DAYS.between(weekStart, endDate));

                    BigDecimal consumption = consumptionRecordRepository
                            .getTotalConsumptionByItemInPeriod(item.getId(), weekStart, weekEnd);

                    Map<String, Object> point = new HashMap<>();
                    point.put("week", weekStart.format(DateTimeFormatter.ofPattern("'Week' w, yyyy")));
                    point.put("weekStart", weekStart.toString());
                    point.put("consumption", consumption != null ? consumption.doubleValue() : 0.0);
                    dataPoints.add(point);

                    weekStart = weekStart.plusWeeks(1);
                }
            } else { // monthly
                LocalDate monthStart = startDate.withDayOfMonth(1);
                while (!monthStart.isAfter(endDate)) {
                    LocalDate monthEnd = monthStart.with(TemporalAdjusters.lastDayOfMonth());
                    if (monthEnd.isAfter(endDate)) monthEnd = endDate;

                    BigDecimal consumption = consumptionRecordRepository
                            .getTotalConsumptionByItemInPeriod(item.getId(), monthStart, monthEnd);

                    Map<String, Object> point = new HashMap<>();
                    point.put("month", monthStart.format(DateTimeFormatter.ofPattern("MMM yyyy")));
                    point.put("consumption", consumption != null ? consumption.doubleValue() : 0.0);
                    dataPoints.add(point);

                    monthStart = monthStart.plusMonths(1);
                }
            }

            itemTrend.put("dataPoints", dataPoints);
            trends.add(itemTrend);
        }

        return trends;
    }

    /**
     * GRAPH 2: Stock Usage Analysis with Risk Assessment
     */
    public Map<String, Object> getStockUsageAnalysis(Long categoryId) {
        Map<String, Object> result = new HashMap<>();

        List<Item> items = categoryId != null ?
                itemRepository.findByCategoryId(categoryId) :
                itemRepository.findAll();

        Map<String, Long> riskSummary = new HashMap<>();
        riskSummary.put("CRITICAL", itemRepository.countByStockAlertLevel("CRITICAL"));
        riskSummary.put("HIGH", itemRepository.countByStockAlertLevel("HIGH"));
        riskSummary.put("MEDIUM", itemRepository.countByStockAlertLevel("MEDIUM"));
        riskSummary.put("LOW", itemRepository.countByStockAlertLevel("LOW"));
        riskSummary.put("SAFE", itemRepository.countByStockAlertLevel("SAFE"));
        result.put("riskLevelSummary", riskSummary);

        return result;
    }

    /**
     * GRAPH 3: Top Consuming Items with Trend Analysis
     */
    public Map<String, Object> getTopConsumingItems(int days, int limit) {
        Map<String, Object> result = new HashMap<>();

        // Get actual data range
        Map<String, LocalDate> dateRange = getConsumptionDataDateRange();
        LocalDate dataMaxDate = dateRange.get("maxDate");
        LocalDate dataMinDate = dateRange.get("minDate");

        // Use the more recent date as end date, but constrain by available data
        LocalDate endDate = dataMaxDate;
        LocalDate startDate = dataMaxDate.minusDays(days);

        // If requested period goes before our data, adjust start date
        if (startDate.isBefore(dataMinDate)) {
            startDate = dataMinDate;
            days = (int) ChronoUnit.DAYS.between(startDate, endDate);
        }

        result.put("period", days + " days");
        result.put("startDate", startDate.toString());
        result.put("endDate", endDate.toString());
        result.put("actualDataRange", dataMinDate.toString() + " to " + dataMaxDate.toString());

        // Create Pageable for limit
        Pageable pageable = PageRequest.of(0, limit);
        List<Object[]> topConsumers = consumptionRecordRepository.getTopConsumersInPeriod(startDate, endDate, pageable);

        List<Map<String, Object>> topItems = new ArrayList<>();
        BigDecimal totalConsumption = BigDecimal.ZERO;

        for (Object[] row : topConsumers) {
            String itemName = (String) row[0];
            String categoryName = (String) row[1];
            BigDecimal consumedQty = (BigDecimal) row[2];
            BigDecimal totalCost = (BigDecimal) row[3];
            Long itemId = (Long) row[4];

            totalConsumption = totalConsumption.add(consumedQty);

            Map<String, Object> item = new HashMap<>();
            item.put("itemId", itemId);
            item.put("itemName", itemName);
            item.put("categoryName", categoryName);
            item.put("consumedQuantity", consumedQty.doubleValue());
            item.put("totalCost", totalCost.doubleValue());

            // Get daily consumption pattern for sparkline
            List<Map<String, Object>> dailyPattern = new ArrayList<>();
            LocalDate currentDate = startDate;
            while (!currentDate.isAfter(endDate)) {
                BigDecimal dailyConsumption = consumptionRecordRepository
                        .getTotalConsumptionByItemAndDate(itemId, currentDate);

                Map<String, Object> dayPoint = new HashMap<>();
                dayPoint.put("date", currentDate.toString());
                dayPoint.put("consumption", dailyConsumption != null ? dailyConsumption.doubleValue() : 0.0);
                dailyPattern.add(dayPoint);
                currentDate = currentDate.plusDays(1);
            }
            item.put("dailyPattern", dailyPattern);

            topItems.add(item);
        }

        result.put("topConsumers", topItems);
        result.put("totalConsumption", totalConsumption.doubleValue());

        return result;
    }

    /**
     * NEW ENDPOINT: Item-specific consumption heatmap
     * Returns daily consumption data for a specific item with employee correlation
     */
    public Map<String, Object> getItemConsumptionHeatmap(Long itemId, String period, LocalDate startDate, LocalDate endDate) {
        Map<String, Object> result = new HashMap<>();

        // Validate item exists
        Optional<Item> itemOpt = itemRepository.findById(itemId);
        if (itemOpt.isEmpty()) {
            throw new ResourceNotFoundException("Item not found with ID: " + itemId);
        }
        Item item = itemOpt.get();

        // If no dates provided, use reasonable defaults based on actual data
        if (endDate == null || startDate == null) {
            Map<String, LocalDate> dataRange = getConsumptionDataDateRange();
            endDate = endDate != null ? endDate : dataRange.get("maxDate");
            startDate = startDate != null ? startDate :
                    ("daily".equals(period) ? endDate.minusDays(90) :
                            "weekly".equals(period) ? endDate.minusWeeks(26) :
                                    endDate.minusMonths(12));
        }

        // Get consumption data for the item
        List<ConsumptionRecord> consumptionRecords = consumptionRecordRepository
                .findByItemAndConsumptionDateBetween(item, startDate, endDate);

        // Get footfall data for the same period
        List<FootfallData> footfallRecords = footfallDataRepository
                .findByDateBetweenOrderByDateAsc(startDate, endDate);

        // Create footfall map for quick lookup
        Map<LocalDate, FootfallData> footfallMap = footfallRecords.stream()
                .collect(Collectors.toMap(FootfallData::getDate, fd -> fd));

        // Process heatmap data based on period
        List<Map<String, Object>> heatmapData = new ArrayList<>();

        if ("daily".equals(period)) {
            // Daily heatmap
            LocalDate currentDate = startDate;
            while (!currentDate.isAfter(endDate)) {
                Map<String, Object> dayData = new HashMap<>();
                dayData.put("date", currentDate.toString());
                dayData.put("dayOfWeek", currentDate.getDayOfWeek().toString());
                dayData.put("month", currentDate.getMonth().toString());

                // Find consumption for this date
                final LocalDate finalCurrentDate = currentDate;
                Optional<ConsumptionRecord> consumptionOpt = consumptionRecords.stream()
                        .filter(cr -> cr.getConsumptionDate().equals(finalCurrentDate))
                        .findFirst();

                BigDecimal consumedQty = consumptionOpt.isPresent() ? consumptionOpt.get().getConsumedQuantity() : BigDecimal.ZERO;
                BigDecimal consumptionCost = consumedQty.multiply(item.getUnitPrice() != null ? item.getUnitPrice() : BigDecimal.ZERO);

                dayData.put("consumption", consumedQty);
                dayData.put("cost", consumptionCost);

                // Add employee data if available
                FootfallData footfall = footfallMap.get(currentDate);
                if (footfall != null) {
                    int employeeCount = footfall.getEmployeeCount();
                    dayData.put("employeeCount", employeeCount);

                    // Calculate per-capita consumption
                    if (employeeCount > 0) {
                        BigDecimal perCapita = consumedQty.divide(BigDecimal.valueOf(employeeCount), 4, RoundingMode.HALF_UP);
                        dayData.put("consumptionPerCapita", perCapita);
                    } else {
                        dayData.put("consumptionPerCapita", BigDecimal.ZERO);
                    }
                } else {
                    dayData.put("employeeCount", 0);
                    dayData.put("consumptionPerCapita", BigDecimal.ZERO);
                }

                heatmapData.add(dayData);
                currentDate = currentDate.plusDays(1);
            }
        } else if ("weekly".equals(period)) {
            // Weekly aggregation
            LocalDate weekStart = startDate.with(DayOfWeek.MONDAY);
            while (!weekStart.isAfter(endDate)) {
                LocalDate weekEnd = weekStart.plusDays(6);
                if (weekEnd.isAfter(endDate)) weekEnd = endDate;

                Map<String, Object> weekData = new HashMap<>();
                weekData.put("weekStart", weekStart.toString());
                weekData.put("weekEnd", weekEnd.toString());
                weekData.put("week", weekStart.format(DateTimeFormatter.ofPattern("'Week' w, yyyy")));

                // Sum consumption for the week
                final LocalDate finalWeekStart = weekStart;
                final LocalDate finalWeekEnd = weekEnd;
                BigDecimal weekConsumption = consumptionRecords.stream()
                        .filter(cr -> !cr.getConsumptionDate().isBefore(finalWeekStart) && !cr.getConsumptionDate().isAfter(finalWeekEnd))
                        .map(ConsumptionRecord::getConsumedQuantity)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);

                BigDecimal weekCost = weekConsumption.multiply(item.getUnitPrice() != null ? item.getUnitPrice() : BigDecimal.ZERO);

                weekData.put("consumption", weekConsumption);
                weekData.put("cost", weekCost);

                // Average employee count for the week
                final LocalDate finalWeekStart2 = weekStart;
                final LocalDate finalWeekEnd2 = weekEnd;
                double avgEmployees = footfallRecords.stream()
                        .filter(fd -> !fd.getDate().isBefore(finalWeekStart2) && !fd.getDate().isAfter(finalWeekEnd2))
                        .mapToInt(FootfallData::getEmployeeCount)
                        .average().orElse(0.0);

                weekData.put("avgEmployeeCount", Math.round(avgEmployees));

                if (avgEmployees > 0) {
                    BigDecimal perCapita = weekConsumption.divide(BigDecimal.valueOf(avgEmployees), 4, RoundingMode.HALF_UP);
                    weekData.put("consumptionPerCapita", perCapita);
                } else {
                    weekData.put("consumptionPerCapita", BigDecimal.ZERO);
                }

                heatmapData.add(weekData);
                weekStart = weekStart.plusWeeks(1);
            }
        } else {
            // Monthly aggregation
            LocalDate monthStart = startDate.withDayOfMonth(1);
            while (!monthStart.isAfter(endDate)) {
                LocalDate monthEnd = monthStart.with(TemporalAdjusters.lastDayOfMonth());
                if (monthEnd.isAfter(endDate)) monthEnd = endDate;

                Map<String, Object> monthData = new HashMap<>();
                monthData.put("month", monthStart.format(DateTimeFormatter.ofPattern("MMM yyyy")));
                monthData.put("monthStart", monthStart.toString());
                monthData.put("monthEnd", monthEnd.toString());

                // Sum consumption for the month
                final LocalDate finalMonthStart = monthStart;
                final LocalDate finalMonthEnd = monthEnd;
                BigDecimal monthConsumption = consumptionRecords.stream()
                        .filter(cr -> !cr.getConsumptionDate().isBefore(finalMonthStart) && !cr.getConsumptionDate().isAfter(finalMonthEnd))
                        .map(ConsumptionRecord::getConsumedQuantity)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);

                BigDecimal monthCost = monthConsumption.multiply(item.getUnitPrice() != null ? item.getUnitPrice() : BigDecimal.ZERO);

                monthData.put("consumption", monthConsumption);
                monthData.put("cost", monthCost);

                // Average employee count for the month
                final LocalDate finalMonthStart2 = monthStart;
                final LocalDate finalMonthEnd2 = monthEnd;
                double avgEmployees = footfallRecords.stream()
                        .filter(fd -> !fd.getDate().isBefore(finalMonthStart2) && !fd.getDate().isAfter(finalMonthEnd2))
                        .mapToInt(FootfallData::getEmployeeCount)
                        .average().orElse(0.0);

                monthData.put("avgEmployeeCount", Math.round(avgEmployees));

                if (avgEmployees > 0) {
                    BigDecimal perCapita = monthConsumption.divide(BigDecimal.valueOf(avgEmployees), 4, RoundingMode.HALF_UP);
                    monthData.put("consumptionPerCapita", perCapita);
                } else {
                    monthData.put("consumptionPerCapita", BigDecimal.ZERO);
                }

                heatmapData.add(monthData);
                monthStart = monthStart.plusMonths(1);
            }
        }

        // Summary statistics
        BigDecimal totalConsumption = heatmapData.stream()
                .map(day -> (BigDecimal) day.get("consumption"))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalCost = heatmapData.stream()
                .map(day -> (BigDecimal) day.get("cost"))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal avgDailyConsumption = !heatmapData.isEmpty() ?
                totalConsumption.divide(BigDecimal.valueOf(heatmapData.size()), 4, RoundingMode.HALF_UP) :
                BigDecimal.ZERO;

        result.put("itemId", itemId);
        result.put("itemName", item.getItemName());
        result.put("itemCode", item.getItemCode());
        result.put("category", item.getCategory().getCategoryName());
        result.put("period", period);
        result.put("startDate", startDate.toString());
        result.put("endDate", endDate.toString());
        result.put("heatmapData", heatmapData);
        result.put("totalConsumption", totalConsumption);
        result.put("totalCost", totalCost);
        result.put("avgDailyConsumption", avgDailyConsumption);
        result.put("dataPoints", heatmapData.size());

        return result;
    }

    /**
     * NEW ENDPOINT: Cost Distribution by Category (for Donut Chart)
     */
    public Map<String, Object> getCostDistributionByCategory(String period, LocalDate startDate, LocalDate endDate) {
        Map<String, Object> result = new HashMap<>();

        // Use reasonable defaults if dates not provided
        if (endDate == null || startDate == null) {
            Map<String, LocalDate> dataRange = getConsumptionDataDateRange();
            endDate = endDate != null ? endDate : dataRange.get("maxDate");
            startDate = startDate != null ? startDate :
                    ("monthly".equals(period) ? endDate.minusMonths(6) : endDate.minusDays(90));
        }

        // Get all consumption records in the period
        List<Object[]> categoryConsumption = consumptionRecordRepository.getCategoryConsumptionWithCost(startDate, endDate);

        List<Map<String, Object>> distributionData = new ArrayList<>();
        BigDecimal totalCost = BigDecimal.ZERO;

        for (Object[] row : categoryConsumption) {
            String categoryName = (String) row[0];
            BigDecimal totalQuantity = (BigDecimal) row[1];
            BigDecimal avgUnitPrice = (BigDecimal) row[2];
            BigDecimal categoryCost = totalQuantity.multiply(avgUnitPrice);

            Map<String, Object> categoryData = new HashMap<>();
            categoryData.put("category", categoryName);
            categoryData.put("totalQuantity", totalQuantity);
            categoryData.put("avgUnitPrice", avgUnitPrice);
            categoryData.put("totalCost", categoryCost);

            distributionData.add(categoryData);
            totalCost = totalCost.add(categoryCost);
        }

        // Calculate percentages
        final BigDecimal finalTotalCost = totalCost;
        distributionData.forEach(category -> {
            BigDecimal categoryCost = (BigDecimal) category.get("totalCost");
            BigDecimal percentage = finalTotalCost.compareTo(BigDecimal.ZERO) > 0 ?
                    categoryCost.divide(finalTotalCost, 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100)) :
                    BigDecimal.ZERO;
            category.put("percentage", percentage);
        });

        // Sort by cost descending
        distributionData.sort((a, b) -> {
            BigDecimal costA = (BigDecimal) a.get("totalCost");
            BigDecimal costB = (BigDecimal) b.get("totalCost");
            return costB.compareTo(costA);
        });

        result.put("period", period);
        result.put("startDate", startDate.toString());
        result.put("endDate", endDate.toString());
        result.put("totalCost", totalCost);
        result.put("categoryDistribution", distributionData);
        result.put("categories", distributionData.size());

        return result;
    }

    /**
     * NEW ENDPOINT: Stock Movement Analysis
     */
    public Map<String, Object> getStockMovementAnalysis(String period, LocalDate startDate, LocalDate endDate, Long categoryId, Long itemId) {
        Map<String, Object> result = new HashMap<>();

        // Use reasonable defaults if dates not provided
        if (endDate == null || startDate == null) {
            Map<String, LocalDate> dataRange = getConsumptionDataDateRange();
            endDate = endDate != null ? endDate : dataRange.get("maxDate");
            startDate = startDate != null ? startDate : endDate.minusDays(30);
        }

        // Get stock movements based on filters
        List<Object[]> movementData;
        if (itemId != null) {
            movementData = stockMovementRepository.getItemMovementAnalysis(itemId, startDate, endDate);
        } else if (categoryId != null) {
            movementData = stockMovementRepository.getCategoryMovementAnalysis(categoryId, startDate, endDate);
        } else {
            movementData = stockMovementRepository.getAllMovementAnalysis(startDate, endDate);
        }

        // Process movement data
        List<Map<String, Object>> movements = new ArrayList<>();
        Map<String, BigDecimal> movementTypeTotals = new HashMap<>();

        for (Object[] row : movementData) {
            String movementType = (String) row[0];
            String itemName = (String) row[1];
            String categoryName = (String) row[2];
            LocalDate movementDate = (LocalDate) row[3];
            BigDecimal quantity = (BigDecimal) row[4];
            BigDecimal unitPrice = (BigDecimal) row[5];
            BigDecimal totalValue = quantity.multiply(unitPrice != null ? unitPrice : BigDecimal.ZERO);

            Map<String, Object> movement = new HashMap<>();
            movement.put("movementType", movementType);
            movement.put("itemName", itemName);
            movement.put("categoryName", categoryName);
            movement.put("movementDate", movementDate.toString());
            movement.put("quantity", quantity);
            movement.put("unitPrice", unitPrice);
            movement.put("totalValue", totalValue);

            movements.add(movement);

            // Aggregate by movement type
            movementTypeTotals.merge(movementType, totalValue, BigDecimal::add);
        }

        // Create movement type summary
        List<Map<String, Object>> movementSummary = new ArrayList<>();
        BigDecimal totalMovementValue = BigDecimal.ZERO;

        for (Map.Entry<String, BigDecimal> entry : movementTypeTotals.entrySet()) {
            Map<String, Object> summary = new HashMap<>();
            summary.put("movementType", entry.getKey());
            summary.put("totalValue", entry.getValue());

            final String movementType = entry.getKey();
            long count = movements.stream()
                    .mapToLong(m -> movementType.equals(m.get("movementType")) ? 1 : 0)
                    .sum();
            summary.put("count", count);

            movementSummary.add(summary);
            totalMovementValue = totalMovementValue.add(entry.getValue());
        }

        // Calculate percentages
        final BigDecimal finalTotal = totalMovementValue;
        movementSummary.forEach(summary -> {
            BigDecimal value = (BigDecimal) summary.get("totalValue");
            BigDecimal percentage = finalTotal.compareTo(BigDecimal.ZERO) > 0 ?
                    value.divide(finalTotal, 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100)) :
                    BigDecimal.ZERO;
            summary.put("percentage", percentage);
        });

        // Sort movements by date descending
        movements.sort((a, b) -> {
            String dateA = (String) a.get("movementDate");
            String dateB = (String) b.get("movementDate");
            return dateB.compareTo(dateA);
        });

        result.put("period", period);
        result.put("startDate", startDate.toString());
        result.put("endDate", endDate.toString());
        result.put("totalMovements", movements.size());
        result.put("totalMovementValue", totalMovementValue);
        result.put("movementSummary", movementSummary);
        result.put("movements", movements);

        return result;
    }

    /**
     * ENHANCED: Core Inventory Stock Level Table with detailed metrics
     */
    public Map<String, Object> getCoreInventoryStockLevels(Long categoryId, String alertLevel, String sortBy, String sortOrder) {
        Map<String, Object> result = new HashMap<>();

        // Get all items with filters
        List<Item> items;
        if (categoryId != null) {
            items = itemRepository.findByCategoryId(categoryId);
        } else {
            items = itemRepository.findAll();
        }

        // Apply alert level filter
        if (alertLevel != null && !alertLevel.isEmpty() && !"all".equalsIgnoreCase(alertLevel)) {
            final String finalAlertLevel = alertLevel;
            items = items.stream()
                    .filter(item -> finalAlertLevel.equalsIgnoreCase(item.getStockAlertLevel()))
                    .collect(Collectors.toList());
        }

        List<Map<String, Object>> stockLevels = new ArrayList<>();

        for (Item item : items) {
            Map<String, Object> stockData = new HashMap<>();

            // Basic item info
            stockData.put("itemId", item.getId());
            stockData.put("itemCode", item.getItemCode());
            stockData.put("itemName", item.getItemName());
            stockData.put("categoryName", item.getCategory().getCategoryName());
            stockData.put("uom", item.getUnitOfMeasurement());

            // Stock levels
            stockData.put("currentQuantity", item.getCurrentQuantity());
            stockData.put("openingStock", item.getOpeningStock());
            stockData.put("maxStockLevel", item.getMaxStockLevel());
            stockData.put("minStockLevel", item.getMinStockLevel());
            stockData.put("reorderLevel", item.getReorderLevel());

            // Pricing
            stockData.put("unitPrice", item.getUnitPrice());
            BigDecimal inventoryValue = item.getCurrentQuantity().multiply(
                    item.getUnitPrice() != null ? item.getUnitPrice() : BigDecimal.ZERO);
            stockData.put("inventoryValue", inventoryValue);

            // Calculate metrics
            BigDecimal avgDailyConsumption = consumptionRecordRepository
                    .getAverageDailyConsumption(item.getId(), LocalDate.now().minusDays(30));

            if (avgDailyConsumption != null && avgDailyConsumption.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal coverageDays = item.getCurrentQuantity().divide(avgDailyConsumption, 2, RoundingMode.HALF_UP);
                stockData.put("coverageDays", coverageDays);
                stockData.put("avgDailyConsumption", avgDailyConsumption);

                // Expected stockout date
                LocalDate expectedStockout = LocalDate.now().plusDays(coverageDays.longValue());
                stockData.put("expectedStockoutDate", expectedStockout.toString());
            } else {
                stockData.put("coverageDays", 999); // Very high number for items with no consumption
                stockData.put("avgDailyConsumption", BigDecimal.ZERO);
                stockData.put("expectedStockoutDate", "N/A");
            }

            // Stock alert level
            stockData.put("stockAlertLevel", item.getStockAlertLevel());

            // Bin locations
            stockData.put("primaryBinId", item.getPrimaryBinId());
            stockData.put("secondaryBinId", item.getSecondaryBinId());

            // Dates
            stockData.put("lastReceivedDate", item.getLastReceivedDate() != null ?
                    item.getLastReceivedDate().toLocalDate().toString() : null);
            stockData.put("lastConsumptionDate", item.getLastConsumptionDate() != null ?
                    item.getLastConsumptionDate().toLocalDate().toString() : null);

            stockLevels.add(stockData);
        }

        // Apply sorting
        if (sortBy != null && !sortBy.isEmpty()) {
            boolean ascending = "asc".equalsIgnoreCase(sortOrder);

            switch (sortBy.toLowerCase()) {
                case "itemname":
                    stockLevels.sort((a, b) -> {
                        String nameA = (String) a.get("itemName");
                        String nameB = (String) b.get("itemName");
                        return ascending ? nameA.compareTo(nameB) : nameB.compareTo(nameA);
                    });
                    break;
                case "currentquantity":
                    stockLevels.sort((a, b) -> {
                        BigDecimal qtyA = (BigDecimal) a.get("currentQuantity");
                        BigDecimal qtyB = (BigDecimal) b.get("currentQuantity");
                        return ascending ? qtyA.compareTo(qtyB) : qtyB.compareTo(qtyA);
                    });
                    break;
                case "coveragedays":
                    stockLevels.sort((a, b) -> {
                        Number coverageA = (Number) a.get("coverageDays");
                        Number coverageB = (Number) b.get("coverageDays");
                        return ascending ?
                                Double.compare(coverageA.doubleValue(), coverageB.doubleValue()) :
                                Double.compare(coverageB.doubleValue(), coverageA.doubleValue());
                    });
                    break;
                case "inventoryvalue":
                    stockLevels.sort((a, b) -> {
                        BigDecimal valueA = (BigDecimal) a.get("inventoryValue");
                        BigDecimal valueB = (BigDecimal) b.get("inventoryValue");
                        return ascending ? valueA.compareTo(valueB) : valueB.compareTo(valueA);
                    });
                    break;
                default:
                    // Default sort by stock alert level (Critical first)
                    Map<String, Integer> alertPriority = new HashMap<>();
                    alertPriority.put("CRITICAL", 1);
                    alertPriority.put("LOW", 2);
                    alertPriority.put("MEDIUM", 3);
                    alertPriority.put("SAFE", 4);

                    stockLevels.sort((a, b) -> {
                        String alertA = (String) a.get("stockAlertLevel");
                        String alertB = (String) b.get("stockAlertLevel");
                        int priorityA = alertPriority.getOrDefault(alertA, 5);
                        int priorityB = alertPriority.getOrDefault(alertB, 5);
                        return Integer.compare(priorityA, priorityB);
                    });
                    break;
            }
        }

        // Summary statistics
        Map<String, Long> alertSummary = stockLevels.stream()
                .collect(Collectors.groupingBy(
                        item -> (String) item.get("stockAlertLevel"),
                        Collectors.counting()));

        BigDecimal totalInventoryValue = stockLevels.stream()
                .map(item -> (BigDecimal) item.get("inventoryValue"))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        result.put("stockLevels", stockLevels);
        result.put("totalItems", stockLevels.size());
        result.put("alertSummary", alertSummary);
        result.put("totalInventoryValue", totalInventoryValue);
        Map<String, Object> filters = new HashMap<>();
        filters.put("categoryId", categoryId);
        filters.put("alertLevel", alertLevel);
        filters.put("sortBy", sortBy);
        filters.put("sortOrder", sortOrder);
        result.put("filters", filters);

        return result;
    }

    // Legacy helper methods
    private AnalyticsResponse.UsageStock generateUsageStock() {
        List<Item> allItems = itemRepository.findAll();

        int totalItems = allItems.size();
        Long totalQuantityLong = itemRepository.getTotalQuantity();
        int totalQuantity = totalQuantityLong != null ? totalQuantityLong.intValue() : 0;
        int lowStockItems = itemRepository.findLowStockItems(10).size();
        int expiredItems = itemRepository.findExpiredItems(LocalDateTime.now()).size();

        LocalDateTime futureDate = LocalDateTime.now().plusDays(30);
        int expiringItems = itemRepository.findExpiringItems(LocalDateTime.now(), futureDate).size();

        List<Object[]> categoryStockData = itemRepository.getCategoryWiseStock();
        Map<String, Integer> categoryWiseStock = categoryStockData.stream()
                .collect(Collectors.toMap(
                        row -> (String) row[0],
                        row -> ((Number) row[1]).intValue(),
                        (existing, replacement) -> existing
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

        // Use actual data range for trends
        Map<String, LocalDate> dateRange = getConsumptionDataDateRange();
        LocalDate maxDate = dateRange.get("maxDate");

        for (int i = 5; i >= 0; i--) {
            LocalDate startOfMonth = maxDate.minusMonths(i).withDayOfMonth(1);
            LocalDate endOfMonth = startOfMonth.plusMonths(1).minusDays(1);

            // Get actual consumption data for this month
            BigDecimal monthlyConsumption = consumptionRecordRepository
                    .getTotalConsumptionInPeriod(startOfMonth, endOfMonth);

            int consumed = monthlyConsumption != null ? monthlyConsumption.intValue() : 0;

            // Get items added this month (if any)
            List<Item> itemsCreatedThisMonth = itemRepository.findItemsCreatedBetween(
                    startOfMonth.atStartOfDay(), endOfMonth.atTime(23, 59, 59)
            );

            int added = itemsCreatedThisMonth.stream()
                    .mapToInt(item -> item.getCurrentQuantity() != null ?
                            item.getCurrentQuantity().intValue() : 0)
                    .sum();

            int netChange = added - consumed;

            String monthName = startOfMonth.format(DateTimeFormatter.ofPattern("MMM yyyy"));
            trends.add(new AnalyticsResponse.ConsumptionTrend(monthName, consumed, added, netChange));
        }

        return trends;
    }
}