package com.bmsedge.inventory.controller;

import com.bmsedge.inventory.dto.AnalyticsResponse;
import com.bmsedge.inventory.model.Item;
import com.bmsedge.inventory.repository.ConsumptionRecordRepository;
import com.bmsedge.inventory.repository.ItemRepository;
import com.bmsedge.inventory.service.AnalyticsService;
import com.bmsedge.inventory.service.FootfallService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/analytics")
@CrossOrigin(origins = "*")
public class AnalyticsController {

    @Autowired
    private AnalyticsService analyticsService;

    @Autowired
    private FootfallService footfallService;

    @Autowired
    private ItemRepository itemRepository;

    @Autowired
    private ConsumptionRecordRepository consumptionRecordRepository;

    /**
     * Legacy endpoint - Get basic analytics
     */
    @GetMapping
    public ResponseEntity<AnalyticsResponse> getAnalytics() {
        AnalyticsResponse analytics = analyticsService.getAnalytics();
        return ResponseEntity.ok(analytics);
    }

    /**
     * Legacy endpoint - Get dashboard statistics
     */
    @GetMapping("/dashboard")
    public ResponseEntity<Map<String, Object>> getDashboardStats() {
        Map<String, Object> stats = analyticsService.getDashboardStats();
        return ResponseEntity.ok(stats);
    }

    /**
     * GRAPH 1: Consumption Trends
     * Get consumption data for daily/weekly/monthly periods grouped by category or items
     */
    @GetMapping("/consumption-trends")
    public ResponseEntity<Map<String, Object>> getConsumptionTrends(
            @RequestParam(value = "period", defaultValue = "daily") String period,  // daily, weekly, monthly
            @RequestParam(value = "groupBy", defaultValue = "category") String groupBy,  // category, items
            @RequestParam(value = "categoryId", required = false) Long categoryId) {  // optional filter by category

        Map<String, Object> trends = analyticsService.getConsumptionTrends(period, groupBy, categoryId);
        return ResponseEntity.ok(trends);
    }

    /**
     * GRAPH 2: Stock Usage Analysis with Risk Assessment
     * Get stock usage patterns and risk levels
     */
    @GetMapping("/stock-usage")
    public ResponseEntity<Map<String, Object>> getStockUsageAnalysis(
            @RequestParam(value = "categoryId", required = false) Long categoryId) {

        Map<String, Object> stockUsage = analyticsService.getStockUsageAnalysis(categoryId);
        return ResponseEntity.ok(stockUsage);
    }

    /**
     * GRAPH 3: Top Consuming Items
     * Get top consuming items with trend analysis
     */
    @GetMapping("/top-consuming-items")
    public ResponseEntity<Map<String, Object>> getTopConsumingItems(
            @RequestParam(value = "days", defaultValue = "30") int days,
            @RequestParam(value = "limit", defaultValue = "10") int limit) {

        Map<String, Object> topItems = analyticsService.getTopConsumingItems(days, limit);
        return ResponseEntity.ok(topItems);
    }

    /**
     * ITEM HEATMAP ENDPOINT: Get item consumption heatmap
     * Usage: /api/analytics/item-heatmap/{itemId}?period=daily&startDate=2025-01-01&endDate=2025-07-31
     */
    @GetMapping("/item-heatmap/{itemId}")
    public ResponseEntity<Map<String, Object>> getItemHeatmap(
            @PathVariable Long itemId,
            @RequestParam(value = "period", defaultValue = "daily") String period,
            @RequestParam(value = "startDate", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(value = "endDate", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {

        try {
            Map<String, Object> heatmapData = analyticsService.getItemConsumptionHeatmap(itemId, period, startDate, endDate);
            return ResponseEntity.ok(heatmapData);
        } catch (Exception e) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("error", "Error generating item heatmap");
            errorResponse.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }

    /**
     * FIXED ENDPOINT: Get cost distribution by category (for donut chart)
     * This now properly handles data from January 2025 and provides actual costs
     * Usage: /api/analytics/cost-distribution?period=monthly&startDate=2025-01-01&endDate=2025-07-31
     */
    @GetMapping("/cost-distribution")
    public ResponseEntity<Map<String, Object>> getCostDistribution(
            @RequestParam(value = "period", defaultValue = "monthly") String period,
            @RequestParam(value = "startDate", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(value = "endDate", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {

        try {
            Map<String, Object> costDistribution = analyticsService.getCostDistributionByCategory(period, startDate, endDate);
            return ResponseEntity.ok(costDistribution);
        } catch (Exception e) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("error", "Error calculating cost distribution");
            errorResponse.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }

    /**
     * STOCK MOVEMENTS ENDPOINT: Get stock movement analysis
     * Usage: /api/analytics/stock-movements?period=monthly&categoryId=1&itemId=5
     */
    @GetMapping("/stock-movements")
    public ResponseEntity<Map<String, Object>> getStockMovementAnalysis(
            @RequestParam(value = "period", defaultValue = "monthly") String period,
            @RequestParam(value = "startDate", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(value = "endDate", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(value = "categoryId", required = false) Long categoryId,
            @RequestParam(value = "itemId", required = false) Long itemId) {

        try {
            Map<String, Object> movementAnalysis = analyticsService.getStockMovementAnalysis(period, startDate, endDate, categoryId, itemId);
            return ResponseEntity.ok(movementAnalysis);
        } catch (Exception e) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("error", "Error analyzing stock movements");
            errorResponse.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }

    /**
     * STOCK LEVELS ENDPOINT: Get core inventory stock levels with detailed metrics
     * Usage: /api/analytics/stock-levels?categoryId=1&alertLevel=CRITICAL&sortBy=coverageDays&sortOrder=asc
     */
    @GetMapping("/stock-levels")
    public ResponseEntity<Map<String, Object>> getCoreStockLevels(
            @RequestParam(value = "categoryId", required = false) Long categoryId,
            @RequestParam(value = "alertLevel", required = false) String alertLevel,
            @RequestParam(value = "sortBy", defaultValue = "stockAlertLevel") String sortBy,
            @RequestParam(value = "sortOrder", defaultValue = "asc") String sortOrder) {

        try {
            Map<String, Object> stockLevels = analyticsService.getCoreInventoryStockLevels(categoryId, alertLevel, sortBy, sortOrder);
            return ResponseEntity.ok(stockLevels);
        } catch (Exception e) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("error", "Error retrieving stock levels");
            errorResponse.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }

    // Add these NEW endpoints to your existing AnalyticsController.java
// Place them at the end of the class, before the closing brace

    /**
     * NEW ENDPOINT 1: Monthly Stock Value Trend
     * GET /api/analytics/monthly-stock-value-trend
     * Usage: ?startDate=2025-01-01&endDate=2025-12-31&categoryId=1
     */
    @GetMapping("/monthly-stock-value-trend")
    public ResponseEntity<Map<String, Object>> getMonthlyStockValueTrend(
            @RequestParam(value = "startDate", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(value = "endDate", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(value = "categoryId", required = false) Long categoryId) {

        try {
            Map<String, Object> trendData = analyticsService.getMonthlyStockValueTrend(startDate, endDate, categoryId);
            return ResponseEntity.ok(trendData);
        } catch (Exception e) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("error", "Error retrieving monthly stock value trend");
            errorResponse.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }

    /**
     * NEW ENDPOINT 2: Forecast vs Actual with Bins
     * GET /api/analytics/forecast-vs-actual-bins
     * Usage: ?year=2025&month=7&categoryId=1
     */
    @GetMapping("/forecast-vs-actual-bins")
    public ResponseEntity<Map<String, Object>> getForecastVsActualWithBins(
            @RequestParam(value = "year", defaultValue = "2025") int year,
            @RequestParam(value = "month", defaultValue = "7") int month,
            @RequestParam(value = "categoryId", required = false) Long categoryId) {

        try {
            Map<String, Object> forecastData = analyticsService.getForecastVsActualWithBins(year, month, categoryId);
            return ResponseEntity.ok(forecastData);
        } catch (Exception e) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("error", "Error retrieving forecast vs actual data");
            errorResponse.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }
    /**
     * NEW ENDPOINT 3: Stock Distribution by Category
     * GET /api/analytics/stock-distribution-category
     * Usage: /api/analytics/stock-distribution-category
     */
    @GetMapping("/stock-distribution-category")
    public ResponseEntity<Map<String, Object>> getStockDistributionByCategory() {
        try {
            Map<String, Object> distributionData = analyticsService.getStockDistributionByCategory();
            return ResponseEntity.ok(distributionData);
        } catch (Exception e) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("error", "Error retrieving stock distribution");
            errorResponse.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }

    /**
     * NEW ENDPOINT 4: Budget KPIs Dashboard
     * GET /api/analytics/budget-kpis
     * Usage: ?startDate=2025-01-01&endDate=2025-12-31
     */
    @GetMapping("/budget-kpis")
    public ResponseEntity<Map<String, Object>> getBudgetKPIs(
            @RequestParam(value = "startDate", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(value = "endDate", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {

        try {
            Map<String, Object> kpis = analyticsService.getBudgetKPIs(startDate, endDate);
            return ResponseEntity.ok(kpis);
        } catch (Exception e) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("error", "Error retrieving budget KPIs");
            errorResponse.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }

    /**
     * NEW ENDPOINT 5: Budget vs Actual Spend by Month
     * GET /api/analytics/budget-vs-actual-spend-monthly
     * Usage: ?year=2025&categoryId=1
     */
    @GetMapping("/budget-vs-actual-spend-monthly")
    public ResponseEntity<Map<String, Object>> getBudgetVsActualSpendByMonth(
            @RequestParam(value = "year", defaultValue = "2025") int year,
            @RequestParam(value = "categoryId", required = false) Long categoryId) {

        try {
            Map<String, Object> spendData = analyticsService.getBudgetVsActualSpendByMonth(year, categoryId);
            return ResponseEntity.ok(spendData);
        } catch (Exception e) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("error", "Error retrieving budget vs actual spend");
            errorResponse.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }

    /**
     * NEW ENDPOINT 6: Planned vs Actual Comparison
     * GET /api/analytics/planned-vs-actual
     * Usage: ?startDate=2025-01-01&endDate=2025-12-31&categoryId=1
     */
    @GetMapping("/planned-vs-actual")
    public ResponseEntity<Map<String, Object>> getPlannedVsActual(
            @RequestParam(value = "startDate", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(value = "endDate", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(value = "categoryId", required = false) Long categoryId) {

        try {
            Map<String, Object> comparisonData = analyticsService.getPlannedVsActual(startDate, endDate, categoryId);
            return ResponseEntity.ok(comparisonData);
        } catch (Exception e) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("error", "Error retrieving planned vs actual comparison");
            errorResponse.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }

    /**
     * NEW ENDPOINT 7: Cost/Consumption Scatter Plot
     * GET /api/analytics/cost-consumption-scatter
     * Usage: ?startDate=2025-01-01&endDate=2025-12-31&categoryId=1
     */
    @GetMapping("/cost-consumption-scatter")
    public ResponseEntity<Map<String, Object>> getCostConsumptionScatter(
            @RequestParam(value = "startDate", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(value = "endDate", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(value = "categoryId", required = false) Long categoryId) {

        try {
            Map<String, Object> scatterData = analyticsService.getCostConsumptionScatter(startDate, endDate, categoryId);
            return ResponseEntity.ok(scatterData);
        } catch (Exception e) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("error", "Error retrieving cost/consumption scatter data");
            errorResponse.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }

    /**
     * NEW ENDPOINT 8: Bin Variance Analysis
     * GET /api/analytics/bin-variance-analysis
     * Usage: ?year=2025&month=7&categoryId=1
     */
    @GetMapping("/bin-variance-analysis")
    public ResponseEntity<Map<String, Object>> getBinVarianceAnalysis(
            @RequestParam(value = "categoryId", required = false) Long categoryId) {

        try {
            // Call the updated service method that returns all months and lastMonth by default
            Map<String, Object> binAnalysis = analyticsService.getBinVarianceAnalysis(categoryId);
            return ResponseEntity.ok(binAnalysis);

        } catch (Exception e) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("error", "Error analyzing bin variance");
            errorResponse.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }


    /**
     * UTILITY ENDPOINT: Get Available Date Range for Analytics
     * GET /api/analytics/available-date-range
     */
    @GetMapping("/available-date-range")
    public ResponseEntity<Map<String, Object>> getAvailableDateRange() {
        try {
            Map<String, Object> result = new HashMap<>();

            // Get actual data range from consumption records
            LocalDate minDate = consumptionRecordRepository.findAll().stream()
                    .map(record -> record.getConsumptionDate())
                    .min(LocalDate::compareTo)
                    .orElse(LocalDate.of(2025, 1, 1));

            LocalDate maxDate = consumptionRecordRepository.findAll().stream()
                    .map(record -> record.getConsumptionDate())
                    .max(LocalDate::compareTo)
                    .orElse(LocalDate.of(2025, 7, 31));

            result.put("minDate", minDate.toString());
            result.put("maxDate", maxDate.toString());
            result.put("availableMonths", java.time.temporal.ChronoUnit.MONTHS.between(
                    minDate.withDayOfMonth(1),
                    maxDate.withDayOfMonth(1)) + 1);

            return ResponseEntity.ok(result);
        } catch (Exception e) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("error", "Error retrieving date range");
            errorResponse.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }

    /**
     * BULK ANALYTICS ENDPOINT: Get all dashboard data in one call
     * GET /api/analytics/dashboard-bulk
     */
    @GetMapping("/dashboard-bulk")
    public ResponseEntity<Map<String, Object>> getDashboardBulkData(
            @RequestParam(value = "year", defaultValue = "2025") int year,
            @RequestParam(value = "month", defaultValue = "7") int month,
            @RequestParam(value = "categoryId", required = false) Long categoryId) {

        try {
            Map<String, Object> bulkData = new HashMap<>();

            // Get all essential dashboard data
            bulkData.put("kpis", analyticsService.getBudgetKPIs(null, null));
            bulkData.put("stockDistribution", analyticsService.getStockDistributionByCategory());
            bulkData.put("forecastVsActual", analyticsService.getForecastVsActualWithBins(year, month, categoryId));
            bulkData.put("binVariance", analyticsService.getBinVarianceAnalysis(categoryId));
            bulkData.put("monthlyTrend", analyticsService.getMonthlyStockValueTrend(null, null, categoryId));

            return ResponseEntity.ok(bulkData);
        } catch (Exception e) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("error", "Error retrieving bulk dashboard data");
            errorResponse.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }

    /**
     * UTILITY ENDPOINT: Get quick item search for heatmap
     * Usage: /api/analytics/item-search?query=cleaner&categoryId=1
     */
    @GetMapping("/item-search")
    public ResponseEntity<List<Map<String, Object>>> searchItemsForAnalytics(
            @RequestParam(value = "query", required = false) String query,
            @RequestParam(value = "categoryId", required = false) Long categoryId) {

        try {
            List<Item> items;

            if (categoryId != null) {
                items = itemRepository.findAll().stream()
                        .filter(item -> item.getCategory() != null && item.getCategory().getId().equals(categoryId))
                        .collect(Collectors.toList());
            } else {
                items = itemRepository.findAll();
            }

            // Filter by query if provided
            if (query != null && !query.trim().isEmpty()) {
                String lowerQuery = query.toLowerCase().trim();
                items = items.stream()
                        .filter(item ->
                                item.getItemName().toLowerCase().contains(lowerQuery) ||
                                        (item.getItemName() != null && item.getItemName().toLowerCase().contains(lowerQuery)))
                        .collect(Collectors.toList());
            }

            List<Map<String, Object>> searchResults = items.stream()
                    .limit(20) // Limit results for performance
                    .map(item -> {
                        Map<String, Object> result = new HashMap<>();
                        result.put("id", item.getId());
                        result.put("itemName", item.getItemName());
                        result.put("categoryName", item.getCategory().getCategoryName());
                        result.put("currentQuantity", item.getCurrentQuantity());
                        result.put("unitPrice", item.getUnitPrice());
                        return result;
                    })
                    .collect(Collectors.toList());

            return ResponseEntity.ok(searchResults);
        } catch (Exception e) {
            List<Map<String, Object>> errorResponse = new ArrayList<>();
            Map<String, Object> error = new HashMap<>();
            error.put("error", "Error searching items");
            error.put("message", e.getMessage());
            errorResponse.add(error);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }

    /**
     * BUDGET CONSUMPTION ENDPOINT: Budget consumption analysis
     * Usage: /api/analytics/budget-consumption?period=monthly&budgetType=category
     */
    @GetMapping("/budget-consumption")
    public ResponseEntity<Map<String, Object>> getBudgetConsumptionAnalysis(
            @RequestParam(value = "period", defaultValue = "monthly") String period,
            @RequestParam(value = "startDate", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(value = "endDate", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(value = "budgetType", defaultValue = "category") String budgetType,
            @RequestParam(value = "categoryId", required = false) Long categoryId,
            @RequestParam(value = "department", required = false) String department,
            @RequestParam(value = "includeProjections", defaultValue = "true") boolean includeProjections) {

        try {
            Map<String, Object> budgetAnalysis = analyticsService.getBudgetConsumptionAnalysis(
                    period, startDate, endDate, budgetType, categoryId, department, includeProjections);
            return ResponseEntity.ok(budgetAnalysis);
        } catch (Exception e) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("error", "Error analyzing budget consumption");
            errorResponse.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }

    /**
     * FIXED ENDPOINT: Enhanced Cost Distribution Analysis
     * This now provides the detailed monthly breakdown you requested with:
     * - Every month in the period
     * - Every category within each month
     * - Every item within each category
     * - Costs, quantities, percentages for all levels
     *
     * Usage: /api/analytics/enhanced-cost-distribution?breakdown=detailed&includeProjections=true
     * The monthlyBreakdown field in the response contains the structure you need
     */
    @GetMapping("/enhanced-cost-distribution")
    public ResponseEntity<Map<String, Object>> getEnhancedCostDistribution(
            @RequestParam(value = "period", defaultValue = "monthly") String period,
            @RequestParam(value = "startDate", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(value = "endDate", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(value = "breakdown", defaultValue = "summary") String breakdown,
            @RequestParam(value = "includeProjections", defaultValue = "false") boolean includeProjections,
            @RequestParam(value = "categoryId", required = false) Long categoryId,
            @RequestParam(value = "department", required = false) String department,
            @RequestParam(value = "costCenter", required = false) String costCenter) {

        try {
            Map<String, Object> enhancedCostDistribution = analyticsService.getEnhancedCostDistribution(
                    period, startDate, endDate, breakdown, includeProjections, categoryId, department, costCenter);
            return ResponseEntity.ok(enhancedCostDistribution);
        } catch (Exception e) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("error", "Error calculating enhanced cost distribution");
            errorResponse.put("message", e.getMessage());
            errorResponse.put("details", "The system is now properly configured to use actual data from January 2025. " +
                    "Check the monthlyBreakdown field in the response for detailed monthly cost analysis by category and items.");
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }

    /**
     * BUDGET VS ACTUAL ENDPOINT: Compare budgeted amounts with actual consumption and costs
     * Usage: /api/analytics/budget-vs-actual?year=2025&granularity=monthly&includeVariance=true
     */
    @GetMapping("/budget-vs-actual")
    public ResponseEntity<Map<String, Object>> getBudgetVsActualComparison(
            @RequestParam(value = "year", defaultValue = "2025") int year,
            @RequestParam(value = "granularity", defaultValue = "monthly") String granularity,
            @RequestParam(value = "categoryId", required = false) Long categoryId,
            @RequestParam(value = "department", required = false) String department,
            @RequestParam(value = "includeForecasts", defaultValue = "true") boolean includeForecasts,
            @RequestParam(value = "includeVariance", defaultValue = "true") boolean includeVariance,
            @RequestParam(value = "quarter", required = false) Integer quarter) {

        try {
            Map<String, Object> budgetComparison = analyticsService.getBudgetVsActualComparison(
                    year, granularity, categoryId, department, includeForecasts, includeVariance, quarter);
            return ResponseEntity.ok(budgetComparison);
        } catch (Exception e) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("error", "Error comparing budget vs actual");
            errorResponse.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }

    /**
     * COST OPTIMIZATION ENDPOINT: AI-driven cost optimization suggestions based on consumption patterns
     * Usage: /api/analytics/cost-optimization?analysisType=variance&threshold=0.15
     */
    @GetMapping("/cost-optimization")
    public ResponseEntity<Map<String, Object>> getCostOptimizationRecommendations(
            @RequestParam(value = "analysisType", defaultValue = "variance") String analysisType,
            @RequestParam(value = "threshold", defaultValue = "0.10") double threshold,
            @RequestParam(value = "period", defaultValue = "monthly") String period,
            @RequestParam(value = "categoryId", required = false) Long categoryId,
            @RequestParam(value = "minSavings", defaultValue = "100") double minSavings,
            @RequestParam(value = "includeAlternatives", defaultValue = "true") boolean includeAlternatives) {

        try {
            Map<String, Object> optimization = analyticsService.getCostOptimizationRecommendations(
                    analysisType, threshold, period, categoryId, minSavings, includeAlternatives);
            return ResponseEntity.ok(optimization);
        } catch (Exception e) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("error", "Error generating cost optimization recommendations");
            errorResponse.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }

    /**
     * BURN RATE ANALYSIS ENDPOINT: Analyze current spending rate vs budget timeline
     * Usage: /api/analytics/burn-rate-analysis?targetDate=2025-12-31&includeTrends=true
     */
    @GetMapping("/burn-rate-analysis")
    public ResponseEntity<Map<String, Object>> getBurnRateAnalysis(
            @RequestParam(value = "targetDate", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate targetDate,
            @RequestParam(value = "includeTrends", defaultValue = "true") boolean includeTrends,
            @RequestParam(value = "categoryId", required = false) Long categoryId,
            @RequestParam(value = "department", required = false) String department,
            @RequestParam(value = "alertThreshold", defaultValue = "0.80") double alertThreshold) {

        try {
            Map<String, Object> burnRateAnalysis = analyticsService.getBurnRateAnalysis(
                    targetDate, includeTrends, categoryId, department, alertThreshold);
            return ResponseEntity.ok(burnRateAnalysis);
        } catch (Exception e) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("error", "Error analyzing burn rate");
            errorResponse.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }

    /**
     * COST PER EMPLOYEE ENDPOINT: Calculate cost efficiency metrics per employee with footfall integration
     * Usage: /api/analytics/cost-per-employee?period=monthly&includeComparisons=true
     */
    @GetMapping("/cost-per-employee")
    public ResponseEntity<Map<String, Object>> getCostPerEmployeeAnalysis(
            @RequestParam(value = "period", defaultValue = "monthly") String period,
            @RequestParam(value = "startDate", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(value = "endDate", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(value = "categoryId", required = false) Long categoryId,
            @RequestParam(value = "department", required = false) String department,
            @RequestParam(value = "includeComparisons", defaultValue = "true") boolean includeComparisons) {

        try {
            Map<String, Object> costPerEmployeeAnalysis = analyticsService.getCostPerEmployeeAnalysis(
                    period, startDate, endDate, categoryId, department, includeComparisons);
            return ResponseEntity.ok(costPerEmployeeAnalysis);
        } catch (Exception e) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("error", "Error calculating cost per employee");
            errorResponse.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }

    /**
     * SEASONAL COST ANALYSIS ENDPOINT: Analyze seasonal patterns in consumption and costs
     * Usage: /api/analytics/seasonal-cost-analysis?years=2&includeForecasts=true
     */
    @GetMapping("/seasonal-cost-analysis")
    public ResponseEntity<Map<String, Object>> getSeasonalCostAnalysis(
            @RequestParam(value = "years", defaultValue = "2") int years,
            @RequestParam(value = "includeForecasts", defaultValue = "true") boolean includeForecasts,
            @RequestParam(value = "categoryId", required = false) Long categoryId,
            @RequestParam(value = "granularity", defaultValue = "monthly") String granularity) {

        try {
            Map<String, Object> seasonalAnalysis = analyticsService.getSeasonalCostAnalysis(
                    years, includeForecasts, categoryId, granularity);
            return ResponseEntity.ok(seasonalAnalysis);
        } catch (Exception e) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("error", "Error analyzing seasonal cost patterns");
            errorResponse.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }

    /**
     * COST VARIANCE ALERTS ENDPOINT: Get alerts for significant cost variances and budget overruns
     * Usage: /api/analytics/cost-variance-alerts?severity=high&activeOnly=true
     */
    @GetMapping("/cost-variance-alerts")
    public ResponseEntity<Map<String, Object>> getCostVarianceAlerts(
            @RequestParam(value = "severity", defaultValue = "all") String severity,
            @RequestParam(value = "activeOnly", defaultValue = "true") boolean activeOnly,
            @RequestParam(value = "categoryId", required = false) Long categoryId,
            @RequestParam(value = "department", required = false) String department,
            @RequestParam(value = "threshold", defaultValue = "0.10") double threshold) {

        try {
            Map<String, Object> varianceAlerts = analyticsService.getCostVarianceAlerts(
                    severity, activeOnly, categoryId, department, threshold);
            return ResponseEntity.ok(varianceAlerts);
        } catch (Exception e) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("error", "Error retrieving cost variance alerts");
            errorResponse.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }

    // ===== FOOTFALL INTEGRATION ENDPOINTS =====

    /**
     * FOOTFALL TRENDS ENDPOINT: Get footfall trends analysis
     */
    @GetMapping("/footfall-trends")
    public ResponseEntity<Map<String, Object>> getFootfallTrends(
            @RequestParam(value = "period", defaultValue = "daily") String period,
            @RequestParam(value = "startDate", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(value = "endDate", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {

        try {
            Map<String, Object> footfallTrends = footfallService.getFootfallTrends(period, startDate, endDate);
            return ResponseEntity.ok(footfallTrends);
        } catch (Exception e) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("error", "Error retrieving footfall trends");
            errorResponse.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }

    /**
     * FOOTFALL UPLOAD ENDPOINT: Upload footfall data
     */
    @PostMapping("/footfall/upload")
    public ResponseEntity<Map<String, Object>> uploadFootfallData(
            @RequestParam("file") MultipartFile file) {

        try {
            Map<String, Object> result = footfallService.uploadFootfallData(file);
            return ResponseEntity.ok(result);
        } catch (IOException e) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("error", "Error uploading footfall data");
            errorResponse.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        } catch (Exception e) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("error", "Error processing footfall data");
            errorResponse.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }

    /**
     * FOOTFALL CHECK ENDPOINT: Check if footfall data exists for a date
     */
    @GetMapping("/footfall/check")
    public ResponseEntity<Map<String, Object>> checkFootfallData(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {

        try {
            Map<String, Object> footfallInfo = footfallService.checkFootfallData(date);

            Map<String, Object> response = new HashMap<>();
            response.put("hasData", footfallInfo.get("hasData"));
            response.put("message", footfallInfo.get("hasData").equals(true) ?
                    "Footfall data found" : "No footfall data found");

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("error", "Error checking footfall data");
            errorResponse.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }

    /**
     * FOOTFALL DATA RANGE ENDPOINT: Get available footfall data range
     */
    @GetMapping("/footfall/data-range")
    public ResponseEntity<Map<String, Object>> getFootfallDataRange() {
        try {
            Map<String, Object> debug = footfallService.debugFootfallData();

            Map<String, Object> response = new HashMap<>();
            response.put("totalRecords", debug.get("totalRecords"));
            response.put("dateRange", debug.get("dateRange"));

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("error", "Error retrieving data range");
            errorResponse.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }

    /**
     * PER-EMPLOYEE CONSUMPTION ENDPOINT: Calculate consumption metrics per employee
     */
    @GetMapping("/footfall/per-employee-consumption")
    public ResponseEntity<Map<String, Object>> getPerEmployeeConsumption(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) Long itemId) {

        try {
            // Use realistic defaults based on actual data range
            if (endDate == null) endDate = LocalDate.of(2025, 7, 31);
            if (startDate == null) startDate = LocalDate.of(2025, 1, 1);

            Map<String, Object> metrics = footfallService.calculatePerEmployeeConsumption(
                    startDate, endDate, categoryId, itemId);

            return ResponseEntity.ok(metrics);

        } catch (Exception e) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("error", "Error calculating per-employee consumption");
            errorResponse.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }
}