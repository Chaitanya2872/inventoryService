package com.bmsedge.inventory.controller;

import com.bmsedge.inventory.dto.AnalyticsResponse;
import com.bmsedge.inventory.model.Item;
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
     * NEW ENDPOINT: Get item consumption heatmap
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
     * NEW ENDPOINT: Get cost distribution by category (for donut chart)
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
     * NEW ENDPOINT: Get stock movement analysis
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
     * ENHANCED ENDPOINT: Get core inventory stock levels with detailed metrics
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
                items = itemRepository.findByCategoryId(categoryId);
            } else {
                items = itemRepository.findAll();
            }

            // Filter by query if provided
            if (query != null && !query.trim().isEmpty()) {
                String lowerQuery = query.toLowerCase().trim();
                items = items.stream()
                        .filter(item ->
                                item.getItemName().toLowerCase().contains(lowerQuery) ||
                                        (item.getItemCode() != null && item.getItemCode().toLowerCase().contains(lowerQuery)))
                        .collect(Collectors.toList());
            }

            List<Map<String, Object>> searchResults = items.stream()
                    .limit(20) // Limit results for performance
                    .map(item -> {
                        Map<String, Object> result = new HashMap<>();
                        result.put("id", item.getId());
                        result.put("itemName", item.getItemName());
                        result.put("itemCode", item.getItemCode());
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
     * FOOTFALL INTEGRATION ENDPOINTS
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

    @GetMapping("/footfall/per-employee-consumption")
    public ResponseEntity<Map<String, Object>> getPerEmployeeConsumption(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) Long itemId) {

        try {
            // Use realistic defaults
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