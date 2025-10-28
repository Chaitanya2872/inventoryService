package com.bmsedge.inventory.service;

import com.bmsedge.inventory.dto.AnalyticsResponse;
import com.bmsedge.inventory.model.*;
import com.bmsedge.inventory.repository.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.Serializable;
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
    public Map<String, LocalDate> getActualDataRange() {
        Map<String, LocalDate> dateRange = new HashMap<>();
        try {
            LocalDate minDate = consumptionRecordRepository.findMinConsumptionDate();
            LocalDate maxDate = consumptionRecordRepository.findMaxConsumptionDate();

            // If there’s no data, default to current month
            if (minDate == null || maxDate == null) {
                YearMonth now = YearMonth.now();
                minDate = now.atDay(1);
                maxDate = now.atEndOfMonth();
            }

            dateRange.put("minDate", minDate);
            dateRange.put("maxDate", maxDate);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return dateRange;
    }

    public Map<String, Object> getMonthlyStockValueTrend(LocalDate startDate, LocalDate endDate, Long categoryId) {
        Map<String, Object> result = new HashMap<>();

        try {
            // Handle null date inputs
            if (endDate == null || startDate == null) {
                Map<String, LocalDate> dateRange = getActualDataRange();
                startDate = (startDate == null) ? dateRange.get("minDate") : startDate;
                endDate = (endDate == null) ? dateRange.get("maxDate") : endDate;
            }

            result.put("startDate", startDate.toString());
            result.put("endDate", endDate.toString());

            // 1️⃣ Fetch all items (filter by category if provided)
            List<Item> items = (categoryId != null)
                    ? itemRepository.findAll().stream()
                    .filter(i -> i.getCategory() != null && i.getCategory().getId().equals(categoryId))
                    .collect(Collectors.toList())
                    : itemRepository.findAll();

            if (items.isEmpty()) {
                result.put("trendData", new ArrayList<>());
                result.put("totalMonths", 0);
                result.put("formula",
                        "Total Monthly Consumption Value = Σ (Consumed Quantity × Unit Price)\n" +
                                "Average Daily Consumption Value = Total Monthly Consumption Value / Days in Month");
                return result;
            }

            // 2️⃣ Fetch all consumption records in ONE DB call
            List<ConsumptionRecord> allRecords =
                    consumptionRecordRepository.findByConsumptionDateBetween(startDate, endDate);

            // 3️⃣ Group records by itemId
            Map<Long, List<ConsumptionRecord>> recordsByItem = allRecords.stream()
                    .filter(r -> r.getItem() != null)
                    .collect(Collectors.groupingBy(r -> r.getItem().getId()));

            // 4️⃣ Iterate months
            Map<String, Map<String, BigDecimal>> monthlyValues = new TreeMap<>();
            LocalDate current = startDate.withDayOfMonth(1);

            while (!current.isAfter(endDate)) {
                String monthKey = current.format(DateTimeFormatter.ofPattern("yyyy-MM"));
                LocalDate monthEnd = current.with(TemporalAdjusters.lastDayOfMonth());
                if (monthEnd.isAfter(endDate)) {
                    monthEnd = endDate;
                }

                BigDecimal totalMonthlyConsumptionValue = BigDecimal.ZERO;
                int daysInMonth = current.lengthOfMonth();

                // 5️⃣ For each item, calculate monthly consumption value
                for (Item item : items) {
                    LocalDate finalCurrent = current;
                    LocalDate finalMonthEnd = monthEnd;

                    List<ConsumptionRecord> itemRecords = recordsByItem
                            .getOrDefault(item.getId(), List.of())
                            .stream()
                            .filter(r -> !r.getConsumptionDate().isBefore(finalCurrent)
                                    && !r.getConsumptionDate().isAfter(finalMonthEnd))
                            .collect(Collectors.toList());

                    BigDecimal itemTotalConsumptionValue = itemRecords.stream()
                            .map(r -> safe(r.getConsumedQuantity()).multiply(safe(item.getUnitPrice())))
                            .reduce(BigDecimal.ZERO, BigDecimal::add);

                    totalMonthlyConsumptionValue = totalMonthlyConsumptionValue.add(itemTotalConsumptionValue);
                }

                // Compute average daily consumption value
                BigDecimal avgDailyConsumptionValue = BigDecimal.ZERO;
                if (totalMonthlyConsumptionValue.compareTo(BigDecimal.ZERO) > 0) {
                    avgDailyConsumptionValue = totalMonthlyConsumptionValue
                            .divide(BigDecimal.valueOf(daysInMonth), 2, RoundingMode.HALF_UP);
                }

                if (totalMonthlyConsumptionValue.compareTo(BigDecimal.ZERO) > 0) {
                    monthlyValues.put(monthKey, Map.of(
                            "totalMonthlyConsumptionValue", totalMonthlyConsumptionValue,
                            "averageDailyConsumptionValue", avgDailyConsumptionValue
                    ));
                }

                current = current.plusMonths(1);
            }

            // 6️⃣ Prepare chart-friendly data
            List<Map<String, ? extends Serializable>> trendData = monthlyValues.entrySet().stream()
                    .map(e -> Map.of(
                            "month", e.getKey(),
                            "monthName", LocalDate.parse(e.getKey() + "-01")
                                    .format(DateTimeFormatter.ofPattern("MMM yyyy")),
                            "totalMonthlyConsumptionValue", e.getValue().get("totalMonthlyConsumptionValue"),
                            "averageDailyConsumptionValue", e.getValue().get("averageDailyConsumptionValue")
                    ))
                    .collect(Collectors.toList());

            // 7️⃣ Final response
            result.put("trendData", trendData);
            result.put("totalMonths", trendData.size());
            result.put("formula",
                    "Total Monthly Consumption Value = Σ (Consumed Quantity × Unit Price)\n" +
                            "Average Daily Consumption Value = Total Monthly Consumption Value / Days in Month");

        } catch (Exception e) {
            e.printStackTrace();
            result.put("error", e.getMessage());
            result.put("trendData", new ArrayList<>());
            result.put("formula",
                    "Total Monthly Consumption Value = Σ (Consumed Quantity × Unit Price)\n" +
                            "Average Daily Consumption Value = Total Monthly Consumption Value / Days in Month");
        }

        return result;
    }




    // 🧩 Null-safe BigDecimal helper
    private BigDecimal safe(BigDecimal value) {
        return (value != null) ? value : BigDecimal.ZERO;
    }



    /**monthlyStockValues.put(monthKey, monthStockValue);
     * NEW API 2: Forecast vs Actual with Bin Variance
     * Returns forecast vs actual data with Bin1 (Days 1-15) and Bin2 (Days 16-31) separation
     */
    public Map<String, Object> getForecastVsActualWithBins(int year, int month, Long categoryId) {
        Map<String, Object> result = new HashMap<>();

        try {
            YearMonth yearMonth = YearMonth.of(year, month);
            LocalDate monthStart = yearMonth.atDay(1);
            LocalDate monthEnd = yearMonth.atEndOfMonth();
            LocalDate bin1End = monthStart.plusDays(14); // Days 1-15
            LocalDate bin2Start = bin1End.plusDays(1);   // Days 16-31

            result.put("year", year);
            result.put("month", month);
            result.put("monthName", yearMonth.format(DateTimeFormatter.ofPattern("MMMM yyyy")));

            // Get items
            List<Item> items;
            if (categoryId != null) {
                items = itemRepository.findAll().stream()
                        .filter(item -> item.getCategory() != null && item.getCategory().getId().equals(categoryId))
                        .collect(Collectors.toList());
            } else {
                items = itemRepository.findAll();
            }

            // Calculate Bin 1 (Days 1-15)
            Map<String, Object> bin1Data = calculateBinData("Bin1 (Days 1-15)", items, monthStart, bin1End);

            // Calculate Bin 2 (Days 16-31)
            Map<String, Object> bin2Data = calculateBinData("Bin2 (Days 16-31)", items, bin2Start, monthEnd);

            // Overall month data
            Map<String, Object> monthData = calculateBinData("Full Month", items, monthStart, monthEnd);

            result.put("bin1", bin1Data);
            result.put("bin2", bin2Data);
            result.put("monthTotal", monthData);

            // Calculate bin variance
            BigDecimal bin1Actual = (BigDecimal) bin1Data.get("actualConsumption");
            BigDecimal bin2Actual = (BigDecimal) bin2Data.get("actualConsumption");
            BigDecimal binVariance = bin2Actual.subtract(bin1Actual);
            BigDecimal binVariancePercent = bin1Actual.compareTo(BigDecimal.ZERO) > 0 ?
                    safeDivide(binVariance.multiply(BigDecimal.valueOf(100)), bin1Actual, 2, RoundingMode.HALF_UP) :
                    BigDecimal.ZERO;

            result.put("binVariance", binVariance);
            result.put("binVariancePercent", binVariancePercent);

        } catch (Exception e) {
            System.err.println("Error in getForecastVsActualWithBins: " + e.getMessage());
            e.printStackTrace();
            result.put("error", e.getMessage());
        }

        return result;
    }

    /**
     * Helper method to calculate bin data (forecast vs actual)
     */
    private Map<String, Object> calculateBinData(String binName, List<Item> items,
                                                 LocalDate startDate, LocalDate endDate) {
        Map<String, Object> binData = new HashMap<>();
        binData.put("binName", binName);
        binData.put("startDate", startDate.toString());
        binData.put("endDate", endDate.toString());

        BigDecimal totalForecast = BigDecimal.ZERO;
        BigDecimal totalActual = BigDecimal.ZERO;

        for (Item item : items) {
            // Get actual consumption
            List<ConsumptionRecord> records = consumptionRecordRepository
                    .findByItemAndConsumptionDateBetween(item, startDate, endDate);

            BigDecimal actualConsumption = records.stream()
                    .map(ConsumptionRecord::getConsumedQuantity)
                    .filter(Objects::nonNull)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            // Calculate forecast (using avg daily consumption * days in period)
            long daysInPeriod = ChronoUnit.DAYS.between(startDate, endDate) + 1;
            BigDecimal avgDaily = nullSafe(item.getAvgDailyConsumption());
            BigDecimal forecastConsumption = avgDaily.multiply(BigDecimal.valueOf(daysInPeriod));

            totalForecast = totalForecast.add(forecastConsumption);
            totalActual = totalActual.add(actualConsumption);
        }

        // Calculate variance
        BigDecimal variance = totalActual.subtract(totalForecast);
        BigDecimal variancePercent = totalForecast.compareTo(BigDecimal.ZERO) > 0 ?
                safeDivide(variance.multiply(BigDecimal.valueOf(100)), totalForecast, 2, RoundingMode.HALF_UP) :
                BigDecimal.ZERO;

        // Calculate accuracy
        BigDecimal accuracy = totalForecast.compareTo(BigDecimal.ZERO) > 0 ?
                BigDecimal.valueOf(100).subtract(variancePercent.abs()) :
                BigDecimal.ZERO;

        binData.put("forecastConsumption", totalForecast);
        binData.put("actualConsumption", totalActual);
        binData.put("variance", variance);
        binData.put("variancePercent", variancePercent);
        binData.put("accuracy", accuracy);

        return binData;
    }

    /**
     * NEW API 3: Stock Distribution by Category (for donut/pie chart)
     */
    public Map<String, Object> getStockDistributionByCategory() {
        Map<String, Object> result = new HashMap<>();

        try {
            List<Item> items = itemRepository.findAll();

            // Group by category
            Map<Category, BigDecimal> categoryStockValues = new HashMap<>();

            for (Item item : items) {
                Category category = item.getCategory();
                if (category != null) {
                    BigDecimal stockValue = nullSafe(item.getCurrentQuantity())
                            .multiply(nullSafe(item.getUnitPrice()));
                    categoryStockValues.merge(category, stockValue, BigDecimal::add);
                }
            }

            // Calculate total
            BigDecimal totalStockValue = categoryStockValues.values().stream()
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            // Convert to list with percentages
            List<Map<String, Object>> distributionData = new ArrayList<>();
            for (Map.Entry<Category, BigDecimal> entry : categoryStockValues.entrySet()) {
                Map<String, Object> categoryData = new HashMap<>();
                categoryData.put("categoryId", entry.getKey().getId());
                categoryData.put("categoryName", entry.getKey().getCategoryName());
                categoryData.put("stockValue", entry.getValue());
                categoryData.put("percentage", safeDivide(
                        entry.getValue().multiply(BigDecimal.valueOf(100)),
                        totalStockValue, 2, RoundingMode.HALF_UP));
                distributionData.add(categoryData);
            }

            // Sort by stock value descending
            distributionData.sort((a, b) -> {
                BigDecimal valueA = (BigDecimal) a.get("stockValue");
                BigDecimal valueB = (BigDecimal) b.get("stockValue");
                return valueB.compareTo(valueA);
            });

            result.put("distributionData", distributionData);
            result.put("totalStockValue", totalStockValue);
            result.put("totalCategories", distributionData.size());

        } catch (Exception e) {
            System.err.println("Error in getStockDistributionByCategory: " + e.getMessage());
            e.printStackTrace();
            result.put("error", e.getMessage());
            result.put("distributionData", new ArrayList<>());
        }

        return result;
    }

    /**
     * NEW API 4: Budget KPIs Dashboard
     */
    public Map<String, Object> getBudgetKPIs(LocalDate startDate, LocalDate endDate) {
        Map<String, Object> result = new HashMap<>();

        try {
            // 1️⃣ Date range setup
            if (endDate == null || startDate == null) {
                Map<String, LocalDate> dateRange = getActualDataRange();
                startDate = (startDate == null) ? dateRange.get("minDate") : startDate;
                endDate = (endDate == null) ? dateRange.get("maxDate") : endDate;
            }

            // 2️⃣ Fetch everything in one go
            List<Item> items = itemRepository.findAll();
            List<ConsumptionRecord> allRecords =
                    consumptionRecordRepository.findByConsumptionDateBetween(startDate, endDate);

            // Group by item and month for faster lookup
            Map<Long, Map<YearMonth, BigDecimal>> consumptionByItemAndMonth = allRecords.stream()
                    .collect(Collectors.groupingBy(
                            record -> record.getItem().getId(),
                            Collectors.groupingBy(
                                    record -> YearMonth.from(record.getConsumptionDate()),
                                    Collectors.mapping(
                                            ConsumptionRecord::getConsumedQuantity,
                                            Collectors.reducing(BigDecimal.ZERO, BigDecimal::add)
                                    )
                            )
                    ));

            // 3️⃣ KPI 1: Total Stock Value
            BigDecimal totalStockValue = items.stream()
                    .map(i -> nullSafe(i.getCurrentQuantity()).multiply(nullSafe(i.getUnitPrice())))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            // 4️⃣ KPI 2: Forecast Accuracy
            BigDecimal totalAccuracy = BigDecimal.ZERO;
            int monthCount = 0;

            LocalDate current = startDate.withDayOfMonth(1);
            while (!current.isAfter(endDate)) {
                LocalDate monthEnd = current.with(TemporalAdjusters.lastDayOfMonth());
                if (monthEnd.isAfter(endDate)) monthEnd = endDate;

                BigDecimal monthForecast = BigDecimal.ZERO;
                BigDecimal monthActual = BigDecimal.ZERO;

                for (Item item : items) {
                    BigDecimal forecast = nullSafe(item.getAvgDailyConsumption())
                            .multiply(BigDecimal.valueOf(ChronoUnit.DAYS.between(current, monthEnd) + 1));
                    monthForecast = monthForecast.add(forecast);

                    BigDecimal actual = consumptionByItemAndMonth
                            .getOrDefault(item.getId(), Collections.emptyMap())
                            .getOrDefault(YearMonth.from(current), BigDecimal.ZERO);
                    monthActual = monthActual.add(actual);
                }

                if (monthForecast.compareTo(BigDecimal.ZERO) > 0) {
                    BigDecimal monthAccuracy = BigDecimal.valueOf(100).subtract(
                            safeDivide(monthActual.subtract(monthForecast).abs()
                                    .multiply(BigDecimal.valueOf(100)), monthForecast, 2, RoundingMode.HALF_UP)
                    );
                    totalAccuracy = totalAccuracy.add(monthAccuracy);
                    monthCount++;
                }

                current = current.plusMonths(1);
            }

            BigDecimal avgForecastAccuracy = monthCount > 0
                    ? safeDivide(totalAccuracy, BigDecimal.valueOf(monthCount), 2, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;

            // 5️⃣ KPI 3: Predicted Stockouts
            List<Map<String, Object>> stockoutItems = new ArrayList<>();
            int predictedStockouts = 0;
            for (Item item : items) {
                BigDecimal avgDaily = nullSafe(item.getAvgDailyConsumption());
                if (avgDaily.compareTo(BigDecimal.ZERO) > 0) {
                    BigDecimal coverageDays = safeDivide(
                            nullSafe(item.getCurrentQuantity()), avgDaily, 0, RoundingMode.UP);

                    if (coverageDays.compareTo(BigDecimal.valueOf(7)) < 0) {
                        predictedStockouts++;
                        Map<String, Object> stockoutItem = new HashMap<>();
                        stockoutItem.put("itemId", item.getId());
                        stockoutItem.put("itemName", item.getItemName());
                        stockoutItem.put("categoryName", item.getCategory().getCategoryName());
                        stockoutItem.put("currentStock", item.getCurrentQuantity());
                        stockoutItem.put("coverageDays", coverageDays.intValue());
                        stockoutItems.add(stockoutItem);
                    }
                }
            }

            // 6️⃣ KPI 4: Reorder Alerts
            long reorderAlerts = items.stream().filter(Item::needsReorder).count();

            result.put("totalStockValue", totalStockValue);
            result.put("forecastAccuracy", avgForecastAccuracy);
            result.put("predictedStockouts", predictedStockouts);
            result.put("reorderAlerts", reorderAlerts);
            result.put("stockoutItems", stockoutItems);
            result.put("totalItems", items.size());

        } catch (Exception e) {
            e.printStackTrace();
            result.put("error", e.getMessage());
        }

        return result;
    }


    /**
     * NEW API 5: Budget vs Actual Spend by Month (for area chart)
     */
    public Map<String, Object> getBudgetVsActualSpendByMonth(int year, Long categoryId) {
        Map<String, Object> result = new HashMap<>();

        try {
            result.put("year", year);

            List<Map<String, Object>> monthlyData = new ArrayList<>();

            for (int month = 1; month <= 12; month++) {
                YearMonth yearMonth = YearMonth.of(year, month);
                LocalDate monthStart = yearMonth.atDay(1);
                LocalDate monthEnd = yearMonth.atEndOfMonth();

                // Get consumption records
                List<ConsumptionRecord> records;
                if (categoryId != null) {
                    records = consumptionRecordRepository
                            .findByCategoryAndDateBetween(categoryId, monthStart, monthEnd);
                } else {
                    records = consumptionRecordRepository
                            .findByConsumptionDateBetween(monthStart, monthEnd);
                }

                // Calculate actual spend
                BigDecimal actualSpend = BigDecimal.ZERO;
                for (ConsumptionRecord record : records) {
                    BigDecimal cost = nullSafe(record.getConsumedQuantity())
                            .multiply(nullSafe(record.getItem().getUnitPrice()));
                    actualSpend = actualSpend.add(cost);
                }

                // Mock budget (in production, this would come from budget table)
                // Using 120% of actual as planned budget
                BigDecimal plannedBudget = actualSpend.multiply(BigDecimal.valueOf(1.2));

                Map<String, Object> monthData = new HashMap<>();
                monthData.put("month", month);
                monthData.put("monthName", yearMonth.format(DateTimeFormatter.ofPattern("MMM yyyy")));
                monthData.put("plannedBudget", plannedBudget);
                monthData.put("actualSpend", actualSpend);
                monthData.put("variance", actualSpend.subtract(plannedBudget));
                monthData.put("variancePercent", plannedBudget.compareTo(BigDecimal.ZERO) > 0 ?
                        safeDivide(actualSpend.subtract(plannedBudget).multiply(BigDecimal.valueOf(100)),
                                plannedBudget, 2, RoundingMode.HALF_UP) : BigDecimal.ZERO);

                monthlyData.add(monthData);
            }

            // Calculate totals
            BigDecimal totalPlanned = monthlyData.stream()
                    .map(m -> (BigDecimal) m.get("plannedBudget"))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal totalActual = monthlyData.stream()
                    .map(m -> (BigDecimal) m.get("actualSpend"))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            result.put("monthlyData", monthlyData);
            result.put("totalPlanned", totalPlanned);
            result.put("totalActual", totalActual);
            result.put("totalVariance", totalActual.subtract(totalPlanned));

        } catch (Exception e) {
            System.err.println("Error in getBudgetVsActualSpendByMonth: " + e.getMessage());
            e.printStackTrace();
            result.put("error", e.getMessage());
            result.put("monthlyData", new ArrayList<>());
        }

        return result;
    }

    /**
     * NEW API 6: Planned vs Actual Comparison (for donut chart)
     */
    public Map<String, Object> getPlannedVsActual(LocalDate startDate, LocalDate endDate, Long categoryId) {
        Map<String, Object> result = new HashMap<>();

        try {
            // Get date range
            if (endDate == null || startDate == null) {
                Map<String, LocalDate> dateRange = getActualDataRange();
                startDate = (startDate == null) ? dateRange.get("minDate") : startDate;
                endDate = (endDate == null) ? dateRange.get("maxDate") : endDate;
            }

            result.put("startDate", startDate.toString());
            result.put("endDate", endDate.toString());

            // Get consumption records
            List<ConsumptionRecord> records;
            if (categoryId != null) {
                records = consumptionRecordRepository
                        .findByCategoryAndDateBetween(categoryId, startDate, endDate);
            } else {
                records = consumptionRecordRepository
                        .findByConsumptionDateBetween(startDate, endDate);
            }

            // Calculate actual consumption/cost
            BigDecimal actualCost = BigDecimal.ZERO;
            BigDecimal actualQuantity = BigDecimal.ZERO;

            for (ConsumptionRecord record : records) {
                BigDecimal consumed = nullSafe(record.getConsumedQuantity());
                BigDecimal cost = consumed.multiply(nullSafe(record.getItem().getUnitPrice()));
                actualQuantity = actualQuantity.add(consumed);
                actualCost = actualCost.add(cost);
            }

            // Calculate planned (using forecasts)
            List<Item> items;
            if (categoryId != null) {
                items = itemRepository.findAll().stream()
                        .filter(item -> item.getCategory() != null && item.getCategory().getId().equals(categoryId))
                        .collect(Collectors.toList());
            } else {
                items = itemRepository.findAll();
            }

            long daysInPeriod = ChronoUnit.DAYS.between(startDate, endDate) + 1;
            BigDecimal plannedQuantity = BigDecimal.ZERO;
            BigDecimal plannedCost = BigDecimal.ZERO;

            for (Item item : items) {
                BigDecimal forecast = nullSafe(item.getAvgDailyConsumption())
                        .multiply(BigDecimal.valueOf(daysInPeriod));
                BigDecimal forecastCost = forecast.multiply(nullSafe(item.getUnitPrice()));

                plannedQuantity = plannedQuantity.add(forecast);
                plannedCost = plannedCost.add(forecastCost);
            }

            // Calculate variance
            BigDecimal quantityVariance = actualQuantity.subtract(plannedQuantity);
            BigDecimal costVariance = actualCost.subtract(plannedCost);

            result.put("planned", Map.of(
                    "quantity", plannedQuantity,
                    "cost", plannedCost
            ));
            result.put("actual", Map.of(
                    "quantity", actualQuantity,
                    "cost", actualCost
            ));
            result.put("variance", Map.of(
                    "quantity", quantityVariance,
                    "cost", costVariance,
                    "quantityPercent", plannedQuantity.compareTo(BigDecimal.ZERO) > 0 ?
                            safeDivide(quantityVariance.multiply(BigDecimal.valueOf(100)),
                                    plannedQuantity, 2, RoundingMode.HALF_UP) : BigDecimal.ZERO,
                    "costPercent", plannedCost.compareTo(BigDecimal.ZERO) > 0 ?
                            safeDivide(costVariance.multiply(BigDecimal.valueOf(100)),
                                    plannedCost, 2, RoundingMode.HALF_UP) : BigDecimal.ZERO
            ));

            // Accuracy percentage
            BigDecimal accuracy = plannedCost.compareTo(BigDecimal.ZERO) > 0 ?
                    BigDecimal.valueOf(100).subtract(
                            safeDivide(costVariance.abs().multiply(BigDecimal.valueOf(100)),
                                    plannedCost, 2, RoundingMode.HALF_UP)
                    ) : BigDecimal.ZERO;

            result.put("accuracy", accuracy);

        } catch (Exception e) {
            System.err.println("Error in getPlannedVsActual: " + e.getMessage());
            e.printStackTrace();
            result.put("error", e.getMessage());
        }

        return result;
    }

    /**
     * NEW API 7: Cost/Consumption Scatter Plot Data
     */
    public Map<String, Object> getCostConsumptionScatter(LocalDate startDate, LocalDate endDate, Long categoryId) {
        Map<String, Object> result = new HashMap<>();

        try {
            // Get date range
            if (endDate == null || startDate == null) {
                Map<String, LocalDate> dateRange = getActualDataRange();
                startDate = (startDate == null) ? dateRange.get("minDate") : startDate;
                endDate = (endDate == null) ? dateRange.get("maxDate") : endDate;
            }

            result.put("startDate", startDate.toString());
            result.put("endDate", endDate.toString());

            // Get items
            List<Item> items;
            if (categoryId != null) {
                items = itemRepository.findAll().stream()
                        .filter(item -> item.getCategory() != null && item.getCategory().getId().equals(categoryId))
                        .collect(Collectors.toList());
            } else {
                items = itemRepository.findAll();
            }

            // Calculate scatter data for each item
            List<Map<String, Object>> scatterData = new ArrayList<>();

            for (Item item : items) {
                List<ConsumptionRecord> records = consumptionRecordRepository
                        .findByItemAndConsumptionDateBetween(item, startDate, endDate);

                if (!records.isEmpty()) {
                    BigDecimal totalConsumption = records.stream()
                            .map(ConsumptionRecord::getConsumedQuantity)
                            .filter(Objects::nonNull)
                            .reduce(BigDecimal.ZERO, BigDecimal::add);

                    BigDecimal totalCost = totalConsumption.multiply(nullSafe(item.getUnitPrice()));

                    Map<String, Object> point = new HashMap<>();
                    point.put("itemId", item.getId());
                    point.put("itemName", item.getItemName());
                    point.put("categoryName", item.getCategory().getCategoryName());
                    point.put("consumption", totalConsumption);
                    point.put("cost", totalCost);
                    point.put("unitPrice", item.getUnitPrice());
                    point.put("avgDailyConsumption", item.getAvgDailyConsumption());

                    scatterData.add(point);
                }
            }

            // Sort by cost descending
            scatterData.sort((a, b) -> {
                BigDecimal costA = (BigDecimal) a.get("cost");
                BigDecimal costB = (BigDecimal) b.get("cost");
                return costB.compareTo(costA);
            });

            result.put("scatterData", scatterData);
            result.put("totalPoints", scatterData.size());

        } catch (Exception e) {
            System.err.println("Error in getCostConsumptionScatter: " + e.getMessage());
            e.printStackTrace();
            result.put("error", e.getMessage());
            result.put("scatterData", new ArrayList<>());
        }

        return result;
    }

    /**
     * NEW API 8: Bin Variance Analysis (Bin1 vs Bin2 comparison)
     */
    // Required imports


    // Place this inside your service class (e.g., InventoryService)
    public Map<String, Object> getBinVarianceAnalysis(Long categoryId) {
        Map<String, Object> result = new HashMap<>();

        try {
            // 1️⃣ Fetch all months with consumption data
            List<Object[]> monthsData = (categoryId != null)
                    ? consumptionRecordRepository.findRecordedMonthsByCategory(categoryId)
                    : consumptionRecordRepository.findRecordedMonths();

            if (monthsData == null || monthsData.isEmpty()) {
                result.put("error", "No consumption data available");
                result.put("allMonths", Collections.emptyList());
                result.put("lastMonth", Collections.emptyMap());
                result.put("trendSummary", Collections.emptyMap());
                result.put("formula", Map.of(
                        "consumptionVariance", "(Bin2Consumption - Bin1Consumption) / Bin1Consumption × 100",
                        "costVariance", "(Bin2Cost - Bin1Cost) / Bin1Cost × 100"
                ));
                return result;
            }

            // 2️⃣ Convert to YearMonth (and sort)
            List<YearMonth> recordedMonths = monthsData.stream()
                    .map(arr -> {
                        Number yearNum = (Number) arr[0];
                        Number monthNum = (Number) arr[1];
                        return YearMonth.of(yearNum.intValue(), monthNum.intValue());
                    })
                    .sorted()
                    .collect(Collectors.toList());

            List<Map<String, Object>> allMonths = new ArrayList<>();
            Map<String, Object> lastMonth = null;

            // Trackers for trend summary
            List<BigDecimal> consumptionVarianceList = new ArrayList<>();
            List<BigDecimal> costVarianceList = new ArrayList<>();
            BigDecimal totalConsumption = BigDecimal.ZERO;
            BigDecimal totalCost = BigDecimal.ZERO;

            // 3️⃣ Process each month
            for (YearMonth ym : recordedMonths) {
                LocalDate start = ym.atDay(1);
                LocalDate mid = ym.atDay(15);
                LocalDate end = ym.atEndOfMonth();

                // Fetch bin records (1–15 and 16–end)
                List<ConsumptionRecord> bin1Records = (categoryId != null)
                        ? consumptionRecordRepository.findByCategoryAndDateBetween(categoryId, start, mid)
                        : consumptionRecordRepository.findByConsumptionDateBetween(start, mid);

                List<ConsumptionRecord> bin2Records = (categoryId != null)
                        ? consumptionRecordRepository.findByCategoryAndDateBetween(categoryId, mid.plusDays(1), end)
                        : consumptionRecordRepository.findByConsumptionDateBetween(mid.plusDays(1), end);

                // Compute totals
                BigDecimal bin1Consumption = sumQuantity(bin1Records);
                BigDecimal bin1Cost = sumCost(bin1Records);
                BigDecimal bin2Consumption = sumQuantity(bin2Records);
                BigDecimal bin2Cost = sumCost(bin2Records);

                // Compute variances
                Map<String, BigDecimal> variance = calculateVariance(bin1Consumption, bin1Cost, bin2Consumption, bin2Cost);

                // Add variance percents for trend summary
                consumptionVarianceList.add(variance.getOrDefault("consumptionPercent", BigDecimal.ZERO));
                costVarianceList.add(variance.getOrDefault("costPercent", BigDecimal.ZERO));

                // Update totals
                totalConsumption = totalConsumption.add(bin1Consumption).add(bin2Consumption);
                totalCost = totalCost.add(bin1Cost).add(bin2Cost);

                // Build monthly record
                Map<String, Object> monthData = Map.of(
                        "year", ym.getYear(),
                        "month", ym.getMonthValue(),
                        "monthName", ym.format(DateTimeFormatter.ofPattern("MMMM yyyy")),
                        "bin1", Map.of(
                                "period", "Days 1–15",
                                "consumption", bin1Consumption,
                                "cost", bin1Cost,
                                "recordCount", bin1Records != null ? bin1Records.size() : 0
                        ),
                        "bin2", Map.of(
                                "period", "Days 16–" + end.getDayOfMonth(),
                                "consumption", bin2Consumption,
                                "cost", bin2Cost,
                                "recordCount", bin2Records != null ? bin2Records.size() : 0
                        ),
                        "variance", variance,
                        "totalMonth", Map.of(
                                "consumption", bin1Consumption.add(bin2Consumption),
                                "cost", bin1Cost.add(bin2Cost)
                        )
                );

                allMonths.add(monthData);
                lastMonth = monthData;
            }

            // 4️⃣ Build trend summary
            Map<String, Object> trendSummary = buildTrendSummary(
                    allMonths, consumptionVarianceList, costVarianceList, totalConsumption, totalCost
            );

            // 5️⃣ Final result
            result.put("allMonths", allMonths);
            result.put("lastMonth", lastMonth);
            result.put("trendSummary", trendSummary);

            // 6️⃣ Include formula
            result.put("formula", Map.of(
                    "consumptionVariance", "(Bin2Consumption - Bin1Consumption) / Bin1Consumption × 100",
                    "costVariance", "(Bin2Cost - Bin1Cost) / Bin1Cost × 100"
            ));

        } catch (Exception e) {
            e.printStackTrace();
            result.put("error", e.getMessage());
            result.put("allMonths", Collections.emptyList());
            result.put("lastMonth", Collections.emptyMap());
            result.put("trendSummary", Collections.emptyMap());
            result.put("formula", Map.of(
                    "consumptionVariance", "(Bin2Consumption - Bin1Consumption) / Bin1Consumption × 100",
                    "costVariance", "(Bin2Cost - Bin1Cost) / Bin1Cost × 100"
            ));
        }

        return result;
    }


/* -------------------------
   Helper methods (same class)
   ------------------------- */

    private BigDecimal nullSafe(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }

    private BigDecimal sumQuantity(List<ConsumptionRecord> records) {
        if (records == null || records.isEmpty()) return BigDecimal.ZERO;
        return records.stream()
                .map(ConsumptionRecord::getConsumedQuantity)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal sumCost(List<ConsumptionRecord> records) {
        if (records == null || records.isEmpty()) return BigDecimal.ZERO;
        return records.stream()
                .map(r -> nullSafe(r.getConsumedQuantity()).multiply(nullSafe(r.getItem().getUnitPrice())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private Map<String, BigDecimal> calculateVariance(BigDecimal bin1Consumption, BigDecimal bin1Cost,
                                                      BigDecimal bin2Consumption, BigDecimal bin2Cost) {

        BigDecimal b1Cons = nullSafe(bin1Consumption);
        BigDecimal b2Cons = nullSafe(bin2Consumption);
        BigDecimal b1Cost = nullSafe(bin1Cost);
        BigDecimal b2Cost = nullSafe(bin2Cost);

        BigDecimal consumptionVariance = b2Cons.subtract(b1Cons);
        BigDecimal costVariance = b2Cost.subtract(b1Cost);

        BigDecimal consumptionVariancePercent = b1Cons.compareTo(BigDecimal.ZERO) > 0
                ? consumptionVariance.multiply(BigDecimal.valueOf(100))
                .divide(b1Cons, 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        BigDecimal costVariancePercent = b1Cost.compareTo(BigDecimal.ZERO) > 0
                ? costVariance.multiply(BigDecimal.valueOf(100))
                .divide(b1Cost, 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        Map<String, BigDecimal> map = new HashMap<>();
        map.put("consumption", consumptionVariance);
        map.put("cost", costVariance);
        map.put("consumptionPercent", consumptionVariancePercent);
        map.put("costPercent", costVariancePercent);
        return map;
    }

    private Map<String, Object> buildTrendSummary(
            List<Map<String, Object>> allMonths,
            List<BigDecimal> consumptionVarianceList,
            List<BigDecimal> costVarianceList,
            BigDecimal totalConsumption,
            BigDecimal totalCost
    ) {
        if (allMonths == null || allMonths.isEmpty()) {
            return Collections.emptyMap();
        }

        BigDecimal avgConsumptionVariance = average(consumptionVarianceList);
        BigDecimal avgCostVariance = average(costVarianceList);

        // Highest and lowest by consumptionPercent
        Map<String, Object> maxMonth = allMonths.stream()
                .max(Comparator.comparing(m -> ((Map<String, BigDecimal>) m.get("variance")).get("consumptionPercent")))
                .orElse(Collections.emptyMap());

        Map<String, Object> minMonth = allMonths.stream()
                .min(Comparator.comparing(m -> ((Map<String, BigDecimal>) m.get("variance")).get("consumptionPercent")))
                .orElse(Collections.emptyMap());

        return Map.of(
                "averageConsumptionVariancePercent", avgConsumptionVariance,
                "averageCostVariancePercent", avgCostVariance,
                "totalConsumption", totalConsumption,
                "totalCost", totalCost,
                "highestVarianceMonth", maxMonth.getOrDefault("monthName", "N/A"),
                "lowestVarianceMonth", minMonth.getOrDefault("monthName", "N/A")
        );
    }

    private BigDecimal average(List<BigDecimal> list) {
        if (list == null || list.isEmpty()) return BigDecimal.ZERO;
        BigDecimal sum = list.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        return sum.divide(BigDecimal.valueOf(list.size()), 2, RoundingMode.HALF_UP);
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
     * SMART INSIGHTS ENGINE
     * Provides AI-powered analytics with anomaly detection, trend prediction, and actionable recommendations
     *
     * @param analysisDepth - "quick" (last 30 days), "standard" (last 90 days), "deep" (all available data)
     * @param categoryId - Optional filter by category
     * @param minConfidence - Minimum confidence threshold (0.0 to 1.0) for insights
     * @param includeRecommendations - Whether to include AI-generated recommendations
     * @return Comprehensive insights with priority scoring
     */
    public Map<String, Object> getSmartInsights(String analysisDepth, Long categoryId,
                                                Double minConfidence, Boolean includeRecommendations) {
        Map<String, Object> result = new HashMap<>();

        try {
            // Set defaults
            if (analysisDepth == null) analysisDepth = "standard";
            if (minConfidence == null) minConfidence = 0.7;
            if (includeRecommendations == null) includeRecommendations = true;

            // Determine analysis period
            Map<String, LocalDate> dateRange = getActualDataRange();
            LocalDate endDate = dateRange.get("maxDate");
            LocalDate startDate;

            switch (analysisDepth.toLowerCase()) {
                case "quick" -> startDate = endDate.minusDays(30);
                case "deep" -> startDate = dateRange.get("minDate");
                default -> startDate = endDate.minusDays(90);
            }

            if (startDate.isBefore(dateRange.get("minDate"))) {
                startDate = dateRange.get("minDate");
            }

            result.put("analysisDepth", analysisDepth);
            result.put("analysisPeriod", Map.of(
                    "startDate", startDate.toString(),
                    "endDate", endDate.toString(),
                    "daysAnalyzed", ChronoUnit.DAYS.between(startDate, endDate) + 1
            ));
            result.put("minConfidence", minConfidence);
            result.put("generatedAt", LocalDateTime.now().toString());

            // Get all items for analysis
            List<Item> items = categoryId != null
                    ? itemRepository.findAll().stream()
                    .filter(i -> i.getCategory() != null && i.getCategory().getId().equals(categoryId))
                    .collect(Collectors.toList())
                    : itemRepository.findAll();

            // Get consumption records
            List<ConsumptionRecord> records = categoryId != null
                    ? consumptionRecordRepository.findByCategoryAndDateBetween(categoryId, startDate, endDate)
                    : consumptionRecordRepository.findByConsumptionDateBetween(startDate, endDate);

            // ============================================
            // 1. CRITICAL ALERTS (Immediate Action Required)
            // ============================================
            List<Map<String, Object>> criticalAlerts = analyzeCriticalAlerts(items, records, startDate, endDate);

            // ============================================
            // 2. ANOMALY DETECTION
            // ============================================
            List<Map<String, Object>> anomalies = detectAnomalies(items, records, startDate, endDate, minConfidence);

            // ============================================
            // 3. TREND INSIGHTS
            // ============================================
            Map<String, Object> trendInsights = analyzeTrends(records, startDate, endDate);

            // ============================================
            // 4. COST OPTIMIZATION OPPORTUNITIES
            // ============================================
            List<Map<String, Object>> costOpportunities = identifyCostOpportunities(items, records, startDate, endDate);

            // ============================================
            // 5. FORECAST ACCURACY ANALYSIS
            // ============================================
            Map<String, Object> forecastAccuracy = analyzeForecastAccuracy(items, records, startDate, endDate);

            // ============================================
            // 6. SEASONAL PATTERNS
            // ============================================
            Map<String, Object> seasonalPatterns = detectSeasonalPatterns(records, startDate, endDate);

            // ============================================
            // 7. INVENTORY HEALTH SCORE
            // ============================================
            Map<String, Object> healthScore = calculateInventoryHealthScore(items, records, startDate, endDate);

            // ============================================
            // 8. TOP MOVERS (Items with highest impact)
            // ============================================
            Map<String, Object> topMovers = identifyTopMovers(items, records, startDate, endDate);

            // ============================================
            // 9. PREDICTIVE INSIGHTS
            // ============================================
            List<Map<String, Object>> predictions = generatePredictions(items, records, startDate, endDate);

            // ============================================
            // 10. AI RECOMMENDATIONS
            // ============================================
            List<Map<String, Object>> recommendations = includeRecommendations
                    ? generateSmartRecommendations(criticalAlerts, anomalies, costOpportunities, predictions)
                    : new ArrayList<>();

            // Build final response
            result.put("criticalAlerts", criticalAlerts);
            result.put("anomalies", anomalies);
            result.put("trendInsights", trendInsights);
            result.put("costOpportunities", costOpportunities);
            result.put("forecastAccuracy", forecastAccuracy);
            result.put("seasonalPatterns", seasonalPatterns);
            result.put("inventoryHealthScore", healthScore);
            result.put("topMovers", topMovers);
            result.put("predictions", predictions);
            result.put("recommendations", recommendations);

            // Summary statistics
            result.put("summary", Map.of(
                    "totalCriticalAlerts", criticalAlerts.size(),
                    "totalAnomalies", anomalies.size(),
                    "totalCostOpportunities", costOpportunities.size(),
                    "totalRecommendations", recommendations.size(),
                    "overallHealthScore", healthScore.get("overallScore"),
                    "itemsAnalyzed", items.size(),
                    "recordsAnalyzed", records.size()
            ));

        } catch (Exception e) {
            System.err.println("Error in getSmartInsights: " + e.getMessage());
            e.printStackTrace();
            result.put("error", e.getMessage());
        }

        return result;
    }

// ============================================
// HELPER METHODS FOR SMART INSIGHTS
// ============================================

    /**
     * Analyze critical alerts requiring immediate action
     */
    private List<Map<String, Object>> analyzeCriticalAlerts(List<Item> items, List<ConsumptionRecord> records,
                                                            LocalDate startDate, LocalDate endDate) {
        List<Map<String, Object>> alerts = new ArrayList<>();
        long daysInPeriod = ChronoUnit.DAYS.between(startDate, endDate) + 1;

        for (Item item : items) {
            // Calculate daily consumption rate
            BigDecimal totalConsumption = records.stream()
                    .filter(r -> r.getItem().getId().equals(item.getId()))
                    .map(ConsumptionRecord::getConsumedQuantity)
                    .filter(Objects::nonNull)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            BigDecimal avgDailyRate = safeDivide(totalConsumption, BigDecimal.valueOf(daysInPeriod), 2, RoundingMode.HALF_UP);

            // Critical Alert 1: Imminent Stockout (< 3 days)
            if (avgDailyRate.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal daysRemaining = safeDivide(nullSafe(item.getCurrentQuantity()), avgDailyRate, 1, RoundingMode.DOWN);

                if (daysRemaining.compareTo(BigDecimal.valueOf(3)) < 0) {
                    Map<String, Object> alert = new HashMap<>();
                    alert.put("type", "IMMINENT_STOCKOUT");
                    alert.put("severity", "CRITICAL");
                    alert.put("priority", 10);
                    alert.put("itemId", item.getId());
                    alert.put("itemName", item.getItemName());
                    alert.put("categoryName", item.getCategory().getCategoryName());
                    alert.put("daysRemaining", daysRemaining.doubleValue());
                    alert.put("currentStock", item.getCurrentQuantity());
                    alert.put("avgDailyConsumption", avgDailyRate);
                    alert.put("message", String.format("Critical: %s will run out in %.1f days",
                            item.getItemName(), daysRemaining.doubleValue()));
                    alert.put("action", "IMMEDIATE_REORDER_REQUIRED");
                    alerts.add(alert);
                }
            }

            // Critical Alert 2: Consumption Spike (>200% increase)
            List<ConsumptionRecord> recentRecords = records.stream()
                    .filter(r -> r.getItem().getId().equals(item.getId()))
                    .filter(r -> !r.getConsumptionDate().isBefore(endDate.minusDays(7)))
                    .collect(Collectors.toList());

            List<ConsumptionRecord> previousRecords = records.stream()
                    .filter(r -> r.getItem().getId().equals(item.getId()))
                    .filter(r -> r.getConsumptionDate().isBefore(endDate.minusDays(7)))
                    .filter(r -> !r.getConsumptionDate().isBefore(endDate.minusDays(14)))
                    .collect(Collectors.toList());

            if (!recentRecords.isEmpty() && !previousRecords.isEmpty()) {
                BigDecimal recentAvg = recentRecords.stream()
                        .map(ConsumptionRecord::getConsumedQuantity)
                        .filter(Objects::nonNull)
                        .reduce(BigDecimal.ZERO, BigDecimal::add)
                        .divide(BigDecimal.valueOf(recentRecords.size()), 2, RoundingMode.HALF_UP);

                BigDecimal previousAvg = previousRecords.stream()
                        .map(ConsumptionRecord::getConsumedQuantity)
                        .filter(Objects::nonNull)
                        .reduce(BigDecimal.ZERO, BigDecimal::add)
                        .divide(BigDecimal.valueOf(previousRecords.size()), 2, RoundingMode.HALF_UP);

                if (previousAvg.compareTo(BigDecimal.ZERO) > 0) {
                    BigDecimal spike = safeDivide(recentAvg.subtract(previousAvg).multiply(BigDecimal.valueOf(100)),
                            previousAvg, 1, RoundingMode.HALF_UP);

                    if (spike.compareTo(BigDecimal.valueOf(200)) > 0) {
                        Map<String, Object> alert = new HashMap<>();
                        alert.put("type", "CONSUMPTION_SPIKE");
                        alert.put("severity", "HIGH");
                        alert.put("priority", 8);
                        alert.put("itemId", item.getId());
                        alert.put("itemName", item.getItemName());
                        alert.put("categoryName", item.getCategory().getCategoryName());
                        alert.put("spikePercentage", spike.doubleValue());
                        alert.put("recentAvg", recentAvg);
                        alert.put("previousAvg", previousAvg);
                        alert.put("message", String.format("Unusual spike: %s consumption increased by %.1f%%",
                                item.getItemName(), spike.doubleValue()));
                        alert.put("action", "INVESTIGATE_CAUSE");
                        alerts.add(alert);
                    }
                }
            }
        }

        // Sort by priority
        alerts.sort((a, b) -> ((Integer) b.get("priority")).compareTo((Integer) a.get("priority")));

        return alerts;
    }

    /**
     * Detect anomalies using statistical analysis
     */
    private List<Map<String, Object>> detectAnomalies(List<Item> items, List<ConsumptionRecord> records,
                                                      LocalDate startDate, LocalDate endDate, Double minConfidence) {
        List<Map<String, Object>> anomalies = new ArrayList<>();

        // Group records by item
        Map<Long, List<ConsumptionRecord>> recordsByItem = records.stream()
                .collect(Collectors.groupingBy(r -> r.getItem().getId()));

        for (Item item : items) {
            List<ConsumptionRecord> itemRecords = recordsByItem.getOrDefault(item.getId(), new ArrayList<>());

            if (itemRecords.size() < 10) continue; // Need sufficient data

            // Calculate statistical metrics
            List<BigDecimal> quantities = itemRecords.stream()
                    .map(ConsumptionRecord::getConsumedQuantity)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());

            BigDecimal mean = quantities.stream()
                    .reduce(BigDecimal.ZERO, BigDecimal::add)
                    .divide(BigDecimal.valueOf(quantities.size()), 2, RoundingMode.HALF_UP);

            // Calculate standard deviation
            BigDecimal variance = quantities.stream()
                    .map(q -> q.subtract(mean).pow(2))
                    .reduce(BigDecimal.ZERO, BigDecimal::add)
                    .divide(BigDecimal.valueOf(quantities.size()), 2, RoundingMode.HALF_UP);

            BigDecimal stdDev = BigDecimal.valueOf(Math.sqrt(variance.doubleValue()));

            // Detect outliers (values > 2 standard deviations from mean)
            BigDecimal upperBound = mean.add(stdDev.multiply(BigDecimal.valueOf(2)));
            BigDecimal lowerBound = mean.subtract(stdDev.multiply(BigDecimal.valueOf(2)));

            List<ConsumptionRecord> outliers = itemRecords.stream()
                    .filter(r -> {
                        BigDecimal qty = nullSafe(r.getConsumedQuantity());
                        return qty.compareTo(upperBound) > 0 || qty.compareTo(lowerBound) < 0;
                    })
                    .collect(Collectors.toList());

            if (!outliers.isEmpty()) {
                double confidence = Math.min(0.99, 0.6 + (outliers.size() / (double) itemRecords.size()));

                if (confidence >= minConfidence) {
                    Map<String, Object> anomaly = new HashMap<>();
                    anomaly.put("type", "STATISTICAL_OUTLIER");
                    anomaly.put("itemId", item.getId());
                    anomaly.put("itemName", item.getItemName());
                    anomaly.put("categoryName", item.getCategory().getCategoryName());
                    anomaly.put("confidence", confidence);
                    anomaly.put("mean", mean);
                    anomaly.put("stdDev", stdDev);
                    anomaly.put("outlierCount", outliers.size());
                    anomaly.put("totalRecords", itemRecords.size());
                    anomaly.put("message", String.format("%s has %d unusual consumption patterns",
                            item.getItemName(), outliers.size()));
                    anomaly.put("outlierDates", outliers.stream()
                            .limit(5)
                            .map(r -> Map.of(
                                    "date", r.getConsumptionDate().toString(),
                                    "quantity", r.getConsumedQuantity(),
                                    "deviation", nullSafe(r.getConsumedQuantity()).subtract(mean)
                            ))
                            .collect(Collectors.toList()));
                    anomalies.add(anomaly);
                }
            }
        }

        // Sort by confidence
        anomalies.sort((a, b) -> ((Double) b.get("confidence")).compareTo((Double) a.get("confidence")));

        return anomalies;
    }

    /**
     * Analyze consumption trends
     */
    private Map<String, Object> analyzeTrends(List<ConsumptionRecord> records,
                                              LocalDate startDate, LocalDate endDate) {
        Map<String, Object> trends = new HashMap<>();

        // Monthly aggregation
        Map<String, BigDecimal> monthlyConsumption = records.stream()
                .collect(Collectors.groupingBy(
                        r -> r.getConsumptionDate().format(DateTimeFormatter.ofPattern("yyyy-MM")),
                        TreeMap::new,
                        Collectors.reducing(BigDecimal.ZERO,
                                r -> nullSafe(r.getConsumedQuantity()).multiply(nullSafe(r.getItem().getUnitPrice())),
                                BigDecimal::add)
                ));

        if (monthlyConsumption.size() >= 3) {
            List<BigDecimal> values = new ArrayList<>(monthlyConsumption.values());

            // Calculate trend direction using linear regression
            double slope = calculateTrendSlope(values);

            String trendDirection;
            String trendStrength;

            if (Math.abs(slope) < 0.05) {
                trendDirection = "STABLE";
                trendStrength = "WEAK";
            } else if (slope > 0) {
                trendDirection = "INCREASING";
                trendStrength = slope > 0.15 ? "STRONG" : "MODERATE";
            } else {
                trendDirection = "DECREASING";
                trendStrength = slope < -0.15 ? "STRONG" : "MODERATE";
            }

            trends.put("overallTrend", Map.of(
                    "direction", trendDirection,
                    "strength", trendStrength,
                    "slope", slope,
                    "interpretation", getTrendInterpretation(trendDirection, trendStrength)
            ));

            trends.put("monthlyData", monthlyConsumption);

            // Calculate volatility
            BigDecimal avgMonthly = values.stream()
                    .reduce(BigDecimal.ZERO, BigDecimal::add)
                    .divide(BigDecimal.valueOf(values.size()), 2, RoundingMode.HALF_UP);

            BigDecimal volatility = values.stream()
                    .map(v -> v.subtract(avgMonthly).abs())
                    .reduce(BigDecimal.ZERO, BigDecimal::add)
                    .divide(BigDecimal.valueOf(values.size()), 2, RoundingMode.HALF_UP);

            BigDecimal volatilityPercent = safeDivide(volatility.multiply(BigDecimal.valueOf(100)),
                    avgMonthly, 1, RoundingMode.HALF_UP);

            trends.put("volatility", Map.of(
                    "absolute", volatility,
                    "percentage", volatilityPercent,
                    "level", volatilityPercent.compareTo(BigDecimal.valueOf(20)) > 0 ? "HIGH" : "LOW"
            ));
        }

        return trends;
    }

    /**
     * Calculate trend slope using simple linear regression
     */
    private double calculateTrendSlope(List<BigDecimal> values) {
        int n = values.size();
        double sumX = 0, sumY = 0, sumXY = 0, sumX2 = 0;

        for (int i = 0; i < n; i++) {
            double x = i;
            double y = values.get(i).doubleValue();
            sumX += x;
            sumY += y;
            sumXY += x * y;
            sumX2 += x * x;
        }

        double slope = (n * sumXY - sumX * sumY) / (n * sumX2 - sumX * sumX);

        // Normalize by average value
        double avgY = sumY / n;
        return avgY != 0 ? slope / avgY : 0;
    }

    /**
     * Get human-readable trend interpretation
     */
    private String getTrendInterpretation(String direction, String strength) {
        return switch (direction) {
            case "INCREASING" -> strength.equals("STRONG")
                    ? "Consumption costs are rising rapidly - review budget allocation"
                    : "Consumption costs showing gradual increase - monitor closely";
            case "DECREASING" -> strength.equals("STRONG")
                    ? "Consumption costs declining significantly - opportunity to optimize inventory levels"
                    : "Consumption costs showing gradual decrease - good cost control";
            default -> "Consumption costs are stable - maintain current practices";
        };
    }

    /**
     * Identify cost optimization opportunities
     */
    private List<Map<String, Object>> identifyCostOpportunities(List<Item> items, List<ConsumptionRecord> records,
                                                                LocalDate startDate, LocalDate endDate) {
        List<Map<String, Object>> opportunities = new ArrayList<>();
        long daysInPeriod = ChronoUnit.DAYS.between(startDate, endDate) + 1;

        for (Item item : items) {
            BigDecimal totalConsumption = records.stream()
                    .filter(r -> r.getItem().getId().equals(item.getId()))
                    .map(ConsumptionRecord::getConsumedQuantity)
                    .filter(Objects::nonNull)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            BigDecimal totalCost = totalConsumption.multiply(nullSafe(item.getUnitPrice()));

            // Opportunity 1: High-volume items (potential for bulk discounts)
            if (totalConsumption.compareTo(BigDecimal.valueOf(1000)) > 0 &&
                    totalCost.compareTo(BigDecimal.valueOf(10000)) > 0) {

                BigDecimal potentialSavings = totalCost.multiply(BigDecimal.valueOf(0.08)); // 8% bulk discount

                Map<String, Object> opportunity = new HashMap<>();
                opportunity.put("type", "BULK_PURCHASE_OPPORTUNITY");
                opportunity.put("priority", 7);
                opportunity.put("itemId", item.getId());
                opportunity.put("itemName", item.getItemName());
                opportunity.put("categoryName", item.getCategory().getCategoryName());
                opportunity.put("currentTotalCost", totalCost);
                opportunity.put("totalConsumption", totalConsumption);
                opportunity.put("potentialSavings", potentialSavings);
                opportunity.put("savingsPercentage", 8.0);
                opportunity.put("message", String.format("Bulk purchase of %s could save $%.2f (8%% discount)",
                        item.getItemName(), potentialSavings.doubleValue()));
                opportunity.put("recommendation", "Negotiate bulk pricing with supplier");
                opportunities.add(opportunity);
            }

            // Opportunity 2: Consistent high consumption (consider contract pricing)
            BigDecimal avgDaily = safeDivide(totalConsumption, BigDecimal.valueOf(daysInPeriod), 2, RoundingMode.HALF_UP);
            BigDecimal monthlyProjection = avgDaily.multiply(BigDecimal.valueOf(30));

            if (monthlyProjection.compareTo(BigDecimal.valueOf(500)) > 0) {
                BigDecimal monthlyCost = monthlyProjection.multiply(nullSafe(item.getUnitPrice()));
                BigDecimal annualCost = monthlyCost.multiply(BigDecimal.valueOf(12));
                BigDecimal potentialSavings = annualCost.multiply(BigDecimal.valueOf(0.12)); // 12% contract discount

                Map<String, Object> opportunity = new HashMap<>();
                opportunity.put("type", "CONTRACT_PRICING_OPPORTUNITY");
                opportunity.put("priority", 6);
                opportunity.put("itemId", item.getId());
                opportunity.put("itemName", item.getItemName());
                opportunity.put("categoryName", item.getCategory().getCategoryName());
                opportunity.put("monthlyConsumption", monthlyProjection);
                opportunity.put("projectedAnnualCost", annualCost);
                opportunity.put("potentialSavings", potentialSavings);
                opportunity.put("savingsPercentage", 12.0);
                opportunity.put("message", String.format("Annual contract for %s could save $%.2f (12%% discount)",
                        item.getItemName(), potentialSavings.doubleValue()));
                opportunity.put("recommendation", "Negotiate annual supply contract");
                opportunities.add(opportunity);
            }
        }

        // Sort by potential savings
        opportunities.sort((a, b) ->
                ((BigDecimal) b.get("potentialSavings")).compareTo((BigDecimal) a.get("potentialSavings"))
        );

        return opportunities.stream().limit(10).collect(Collectors.toList());
    }

    /**
     * Analyze forecast accuracy
     */
    private Map<String, Object> analyzeForecastAccuracy(List<Item> items, List<ConsumptionRecord> records,
                                                        LocalDate startDate, LocalDate endDate) {
        Map<String, Object> accuracy = new HashMap<>();

        BigDecimal totalForecast = BigDecimal.ZERO;
        BigDecimal totalActual = BigDecimal.ZERO;
        int itemsAnalyzed = 0;

        long daysInPeriod = ChronoUnit.DAYS.between(startDate, endDate) + 1;

        for (Item item : items) {
            BigDecimal forecast = nullSafe(item.getAvgDailyConsumption())
                    .multiply(BigDecimal.valueOf(daysInPeriod));

            BigDecimal actual = records.stream()
                    .filter(r -> r.getItem().getId().equals(item.getId()))
                    .map(ConsumptionRecord::getConsumedQuantity)
                    .filter(Objects::nonNull)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            if (forecast.compareTo(BigDecimal.ZERO) > 0) {
                totalForecast = totalForecast.add(forecast);
                totalActual = totalActual.add(actual);
                itemsAnalyzed++;
            }
        }

        if (totalForecast.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal accuracyPercent = BigDecimal.valueOf(100).subtract(
                    safeDivide(totalActual.subtract(totalForecast).abs()
                            .multiply(BigDecimal.valueOf(100)), totalForecast, 2, RoundingMode.HALF_UP)
            );

            String rating;
            if (accuracyPercent.compareTo(BigDecimal.valueOf(90)) >= 0) {
                rating = "EXCELLENT";
            } else if (accuracyPercent.compareTo(BigDecimal.valueOf(80)) >= 0) {
                rating = "GOOD";
            } else if (accuracyPercent.compareTo(BigDecimal.valueOf(70)) >= 0) {
                rating = "FAIR";
            } else {
                rating = "NEEDS_IMPROVEMENT";
            }

            accuracy.put("overallAccuracy", accuracyPercent);
            accuracy.put("rating", rating);
            accuracy.put("totalForecast", totalForecast);
            accuracy.put("totalActual", totalActual);
            accuracy.put("variance", totalActual.subtract(totalForecast));
            accuracy.put("itemsAnalyzed", itemsAnalyzed);
            accuracy.put("message", getForecastAccuracyMessage(rating, accuracyPercent));
        }

        return accuracy;
    }

    private String getForecastAccuracyMessage(String rating, BigDecimal accuracy) {
        return switch (rating) {
            case "EXCELLENT" -> String.format("Outstanding forecast accuracy at %.1f%% - forecasting models are reliable",
                    accuracy.doubleValue());
            case "GOOD" -> String.format("Good forecast accuracy at %.1f%% - minor adjustments recommended",
                    accuracy.doubleValue());
            case "FAIR" -> String.format("Fair forecast accuracy at %.1f%% - review forecasting parameters",
                    accuracy.doubleValue());
            default -> String.format("Forecast accuracy at %.1f%% needs improvement - update consumption models",
                    accuracy.doubleValue());
        };
    }

    /**
     * Detect seasonal patterns
     */
    private Map<String, Object> detectSeasonalPatterns(List<ConsumptionRecord> records,
                                                       LocalDate startDate, LocalDate endDate) {
        Map<String, Object> patterns = new HashMap<>();

        // Group by month
        Map<Integer, BigDecimal> monthlyAvg = records.stream()
                .collect(Collectors.groupingBy(
                        r -> r.getConsumptionDate().getMonthValue(),
                        Collectors.reducing(BigDecimal.ZERO,
                                r -> nullSafe(r.getConsumedQuantity()).multiply(nullSafe(r.getItem().getUnitPrice())),
                                BigDecimal::add)
                ));

        if (monthlyAvg.size() >= 6) {
            BigDecimal overallAvg = monthlyAvg.values().stream()
                    .reduce(BigDecimal.ZERO, BigDecimal::add)
                    .divide(BigDecimal.valueOf(monthlyAvg.size()), 2, RoundingMode.HALF_UP);

            // Find peak and low months
            Map.Entry<Integer, BigDecimal> peakMonth = monthlyAvg.entrySet().stream()
                    .max(Map.Entry.comparingByValue())
                    .orElse(null);

            Map.Entry<Integer, BigDecimal> lowMonth = monthlyAvg.entrySet().stream()
                    .min(Map.Entry.comparingByValue())
                    .orElse(null);

            if (peakMonth != null && lowMonth != null) {
                BigDecimal variance = peakMonth.getValue().subtract(lowMonth.getValue());
                BigDecimal variancePercent = safeDivide(variance.multiply(BigDecimal.valueOf(100)),
                        overallAvg, 1, RoundingMode.HALF_UP);

                patterns.put("seasonalityDetected", variancePercent.compareTo(BigDecimal.valueOf(30)) > 0);
                patterns.put("peakMonth", Month.of(peakMonth.getKey()).name());
                patterns.put("peakValue", peakMonth.getValue());
                patterns.put("lowMonth", Month.of(lowMonth.getKey()).name());
                patterns.put("lowValue", lowMonth.getValue());
                patterns.put("variance", variance);
                patterns.put("variancePercent", variancePercent);
                patterns.put("message", variancePercent.compareTo(BigDecimal.valueOf(30)) > 0
                        ? String.format("Strong seasonal pattern: %s is %.1f%% higher than %s",
                        Month.of(peakMonth.getKey()).name(), variancePercent.doubleValue(), Month.of(lowMonth.getKey()).name())
                        : "No significant seasonal patterns detected");
            }
        }

        return patterns;
    }

    /**
     * Calculate overall inventory health score
     */
    private Map<String, Object> calculateInventoryHealthScore(List<Item> items, List<ConsumptionRecord> records,
                                                              LocalDate startDate, LocalDate endDate) {
        Map<String, Object> health = new HashMap<>();

        int totalItems = items.size();
        int healthyItems = 0;
        int warningItems = 0;
        int criticalItems = 0;

        long daysInPeriod = ChronoUnit.DAYS.between(startDate, endDate) + 1;

        for (Item item : items) {
            BigDecimal totalConsumption = records.stream()
                    .filter(r -> r.getItem().getId().equals(item.getId()))
                    .map(ConsumptionRecord::getConsumedQuantity)
                    .filter(Objects::nonNull)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            BigDecimal avgDaily = safeDivide(totalConsumption, BigDecimal.valueOf(daysInPeriod), 2, RoundingMode.HALF_UP);

            BigDecimal daysRemaining = avgDaily.compareTo(BigDecimal.ZERO) > 0
                    ? safeDivide(nullSafe(item.getCurrentQuantity()), avgDaily, 0, RoundingMode.UP)
                    : BigDecimal.valueOf(999);

            if (daysRemaining.compareTo(BigDecimal.valueOf(7)) < 0) {
                criticalItems++;
            } else if (daysRemaining.compareTo(BigDecimal.valueOf(14)) < 0) {
                warningItems++;
            } else {
                healthyItems++;
            }
        }

        // Calculate score (0-100)
        double healthScore = ((healthyItems * 100.0) + (warningItems * 50.0)) / totalItems;

        String rating;
        if (healthScore >= 80) {
            rating = "EXCELLENT";
        } else if (healthScore >= 60) {
            rating = "GOOD";
        } else if (healthScore >= 40) {
            rating = "FAIR";
        } else {
            rating = "POOR";
        }

        health.put("overallScore", Math.round(healthScore));
        health.put("rating", rating);
        health.put("healthyItems", healthyItems);
        health.put("warningItems", warningItems);
        health.put("criticalItems", criticalItems);
        health.put("totalItems", totalItems);
        health.put("healthyPercentage", Math.round((healthyItems * 100.0) / totalItems));
        health.put("message", getHealthScoreMessage(rating, healthScore));

        return health;
    }

    private String getHealthScoreMessage(String rating, double score) {
        return switch (rating) {
            case "EXCELLENT" -> String.format("Excellent inventory health at %.0f%% - well-managed stock levels", score);
            case "GOOD" -> String.format("Good inventory health at %.0f%% - some items need attention", score);
            case "FAIR" -> String.format("Fair inventory health at %.0f%% - multiple items require review", score);
            default -> String.format("Poor inventory health at %.0f%% - immediate action required", score);
        };
    }

    /**
     * Identify top movers (highest impact items)
     */
    private Map<String, Object> identifyTopMovers(List<Item> items, List<ConsumptionRecord> records,
                                                  LocalDate startDate, LocalDate endDate) {
        List<Map<String, Object>> topConsumers = new ArrayList<>();
        List<Map<String, Object>> fastestGrowing = new ArrayList<>();
        List<Map<String, Object>> highestValue = new ArrayList<>();

        long daysInPeriod = ChronoUnit.DAYS.between(startDate, endDate) + 1;
        long halfPeriod = daysInPeriod / 2;
        LocalDate midPoint = startDate.plusDays(halfPeriod);

        for (Item item : items) {
            List<ConsumptionRecord> itemRecords = records.stream()
                    .filter(r -> r.getItem().getId().equals(item.getId()))
                    .collect(Collectors.toList());

            BigDecimal totalConsumption = itemRecords.stream()
                    .map(ConsumptionRecord::getConsumedQuantity)
                    .filter(Objects::nonNull)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            BigDecimal totalValue = totalConsumption.multiply(nullSafe(item.getUnitPrice()));

            // Top consumers by volume
            topConsumers.add(Map.of(
                    "itemId", item.getId(),
                    "itemName", item.getItemName(),
                    "categoryName", item.getCategory().getCategoryName(),
                    "totalConsumption", totalConsumption,
                    "totalValue", totalValue
            ));

            // Fastest growing (first half vs second half)
            BigDecimal firstHalf = itemRecords.stream()
                    .filter(r -> r.getConsumptionDate().isBefore(midPoint))
                    .map(ConsumptionRecord::getConsumedQuantity)
                    .filter(Objects::nonNull)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            BigDecimal secondHalf = itemRecords.stream()
                    .filter(r -> !r.getConsumptionDate().isBefore(midPoint))
                    .map(ConsumptionRecord::getConsumedQuantity)
                    .filter(Objects::nonNull)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            if (firstHalf.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal growthRate = safeDivide(
                        secondHalf.subtract(firstHalf).multiply(BigDecimal.valueOf(100)),
                        firstHalf, 1, RoundingMode.HALF_UP
                );

                if (growthRate.compareTo(BigDecimal.valueOf(20)) > 0) {
                    fastestGrowing.add(Map.of(
                            "itemId", item.getId(),
                            "itemName", item.getItemName(),
                            "categoryName", item.getCategory().getCategoryName(),
                            "growthRate", growthRate,
                            "firstHalfConsumption", firstHalf,
                            "secondHalfConsumption", secondHalf
                    ));
                }
            }

            // Highest value items
            if (totalValue.compareTo(BigDecimal.valueOf(5000)) > 0) {
                highestValue.add(Map.of(
                        "itemId", item.getId(),
                        "itemName", item.getItemName(),
                        "categoryName", item.getCategory().getCategoryName(),
                        "totalValue", totalValue,
                        "totalConsumption", totalConsumption,
                        "unitPrice", item.getUnitPrice()
                ));
            }
        }

        // Sort and limit
        topConsumers.sort((a, b) ->
                ((BigDecimal) b.get("totalConsumption")).compareTo((BigDecimal) a.get("totalConsumption"))
        );
        fastestGrowing.sort((a, b) ->
                ((BigDecimal) b.get("growthRate")).compareTo((BigDecimal) a.get("growthRate"))
        );
        highestValue.sort((a, b) ->
                ((BigDecimal) b.get("totalValue")).compareTo((BigDecimal) a.get("totalValue"))
        );

        Map<String, Object> result = new HashMap<>();
        result.put("topConsumers", topConsumers.stream().limit(10).collect(Collectors.toList()));
        result.put("fastestGrowing", fastestGrowing.stream().limit(10).collect(Collectors.toList()));
        result.put("highestValue", highestValue.stream().limit(10).collect(Collectors.toList()));

        return result;
    }

    /**
     * Generate predictive insights
     */
    private List<Map<String, Object>> generatePredictions(List<Item> items, List<ConsumptionRecord> records,
                                                          LocalDate startDate, LocalDate endDate) {
        List<Map<String, Object>> predictions = new ArrayList<>();
        long daysInPeriod = ChronoUnit.DAYS.between(startDate, endDate) + 1;

        for (Item item : items) {
            BigDecimal totalConsumption = records.stream()
                    .filter(r -> r.getItem().getId().equals(item.getId()))
                    .map(ConsumptionRecord::getConsumedQuantity)
                    .filter(Objects::nonNull)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            if (totalConsumption.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal avgDaily = safeDivide(totalConsumption, BigDecimal.valueOf(daysInPeriod), 2, RoundingMode.HALF_UP);

                // 30-day prediction
                BigDecimal predicted30Days = avgDaily.multiply(BigDecimal.valueOf(30));
                BigDecimal predicted30DayCost = predicted30Days.multiply(nullSafe(item.getUnitPrice()));

                // Stockout prediction
                BigDecimal daysUntilStockout = avgDaily.compareTo(BigDecimal.ZERO) > 0
                        ? safeDivide(nullSafe(item.getCurrentQuantity()), avgDaily, 0, RoundingMode.DOWN)
                        : BigDecimal.valueOf(999);

                if (daysUntilStockout.compareTo(BigDecimal.valueOf(30)) < 0) {
                    Map<String, Object> prediction = new HashMap<>();
                    prediction.put("type", "STOCKOUT_PREDICTION");
                    prediction.put("itemId", item.getId());
                    prediction.put("itemName", item.getItemName());
                    prediction.put("categoryName", item.getCategory().getCategoryName());
                    prediction.put("daysUntilStockout", daysUntilStockout.intValue());
                    prediction.put("predictedDate", LocalDate.now().plusDays(daysUntilStockout.longValue()).toString());
                    prediction.put("currentStock", item.getCurrentQuantity());
                    prediction.put("avgDailyConsumption", avgDaily);
                    prediction.put("confidence", 0.85);
                    prediction.put("message", String.format("%s predicted to stock out in %d days",
                            item.getItemName(), daysUntilStockout.intValue()));
                    predictions.add(prediction);
                }

                // High consumption prediction
                if (predicted30DayCost.compareTo(BigDecimal.valueOf(10000)) > 0) {
                    Map<String, Object> prediction = new HashMap<>();
                    prediction.put("type", "HIGH_COST_PREDICTION");
                    prediction.put("itemId", item.getId());
                    prediction.put("itemName", item.getItemName());
                    prediction.put("categoryName", item.getCategory().getCategoryName());
                    prediction.put("predicted30DayConsumption", predicted30Days);
                    prediction.put("predicted30DayCost", predicted30DayCost);
                    prediction.put("confidence", 0.80);
                    prediction.put("message", String.format("%s predicted to cost $%.2f in next 30 days",
                            item.getItemName(), predicted30DayCost.doubleValue()));
                    predictions.add(prediction);
                }
            }
        }

        return predictions.stream().limit(15).collect(Collectors.toList());
    }

    /**
     * Generate AI-powered recommendations
     */
    private List<Map<String, Object>> generateSmartRecommendations(
            List<Map<String, Object>> criticalAlerts,
            List<Map<String, Object>> anomalies,
            List<Map<String, Object>> costOpportunities,
            List<Map<String, Object>> predictions) {

        List<Map<String, Object>> recommendations = new ArrayList<>();
        int priority = 10;

        // From critical alerts
        for (Map<String, Object> alert : criticalAlerts.stream().limit(3).collect(Collectors.toList())) {
            Map<String, Object> recommendation = new HashMap<>();
            recommendation.put("priority", priority--);
            recommendation.put("category", "CRITICAL_ACTION");
            recommendation.put("title", "Immediate Stock Replenishment Required");
            recommendation.put("description", alert.get("message"));
            recommendation.put("action", alert.get("action"));
            recommendation.put("impact", "HIGH");
            recommendation.put("effort", "LOW");
            recommendation.put("estimatedTime", "Immediate");
            recommendation.put("relatedItems", List.of(Map.of(
                    "itemId", alert.get("itemId"),
                    "itemName", alert.get("itemName")
            )));
            recommendations.add(recommendation);
        }

        // From cost opportunities
        for (Map<String, Object> opp : costOpportunities.stream().limit(3).collect(Collectors.toList())) {
            Map<String, Object> recommendation = new HashMap<>();
            recommendation.put("priority", priority--);
            recommendation.put("category", "COST_OPTIMIZATION");
            recommendation.put("title", String.format("Potential Savings: $%.2f", ((BigDecimal) opp.get("potentialSavings")).doubleValue()));
            recommendation.put("description", opp.get("message"));
            recommendation.put("action", opp.get("recommendation"));
            recommendation.put("impact", "MEDIUM");
            recommendation.put("effort", "MEDIUM");
            recommendation.put("estimatedTime", "1-2 weeks");
            recommendation.put("potentialSavings", opp.get("potentialSavings"));
            recommendation.put("relatedItems", List.of(Map.of(
                    "itemId", opp.get("itemId"),
                    "itemName", opp.get("itemName")
            )));
            recommendations.add(recommendation);
        }

        // From anomalies
        for (Map<String, Object> anomaly : anomalies.stream().limit(2).collect(Collectors.toList())) {
            Map<String, Object> recommendation = new HashMap<>();
            recommendation.put("priority", priority--);
            recommendation.put("category", "INVESTIGATE_ANOMALY");
            recommendation.put("title", "Unusual Consumption Pattern Detected");
            recommendation.put("description", anomaly.get("message"));
            recommendation.put("action", "REVIEW_CONSUMPTION_RECORDS");
            recommendation.put("impact", "MEDIUM");
            recommendation.put("effort", "LOW");
            recommendation.put("estimatedTime", "1-2 days");
            recommendation.put("confidence", anomaly.get("confidence"));
            recommendation.put("relatedItems", List.of(Map.of(
                    "itemId", anomaly.get("itemId"),
                    "itemName", anomaly.get("itemName")
            )));
            recommendations.add(recommendation);
        }

        // From predictions
        for (Map<String, Object> prediction : predictions.stream()
                .filter(p -> "STOCKOUT_PREDICTION".equals(p.get("type")))
                .limit(2)
                .collect(Collectors.toList())) {
            Map<String, Object> recommendation = new HashMap<>();
            recommendation.put("priority", priority--);
            recommendation.put("category", "PROACTIVE_PLANNING");
            recommendation.put("title", "Plan for Upcoming Stockout");
            recommendation.put("description", prediction.get("message"));
            recommendation.put("action", "SCHEDULE_REORDER");
            recommendation.put("impact", "HIGH");
            recommendation.put("effort", "LOW");
            recommendation.put("estimatedTime", "Before " + prediction.get("predictedDate"));
            recommendation.put("confidence", prediction.get("confidence"));
            recommendation.put("relatedItems", List.of(Map.of(
                    "itemId", prediction.get("itemId"),
                    "itemName", prediction.get("itemName")
            )));
            recommendations.add(recommendation);
        }

        return recommendations;
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