package com.bmsedge.inventory.controller;

import com.bmsedge.inventory.service.ItemCorrelationService;
import com.bmsedge.inventory.service.StatisticalAnalysisService;
import com.bmsedge.inventory.service.NotificationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/statistics")
@CrossOrigin(origins = "*")
public class StatisticsController {

    @Autowired
    private ItemCorrelationService correlationService;

    @Autowired
    private StatisticalAnalysisService statisticalAnalysisService;

    @Autowired
    private NotificationService notificationService;

    // ============= CORRELATION ENDPOINTS =============

    /**
     * Calculate correlations for all items
     */
    @PostMapping("/correlations/calculate")
    public ResponseEntity<Map<String, Object>> calculateAllCorrelations() {
        try {
            Map<String, Object> result = correlationService.calculateAllCorrelations();
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            Map<String, Object> error = new HashMap<>();
            error.put("error", "Failed to calculate correlations");
            error.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        }
    }

    /**
     * Get correlations for a specific item
     */
    @GetMapping("/correlations/item/{itemId}")
    public ResponseEntity<Map<String, Object>> getItemCorrelations(@PathVariable Long itemId) {
        try {
            Map<String, Object> result = correlationService.calculateItemCorrelations(itemId);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            Map<String, Object> error = new HashMap<>();
            error.put("error", "Failed to get item correlations");
            error.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        }
    }

    /**
     * Get correlation statistics
     */
    @GetMapping("/correlations/summary")
    public ResponseEntity<Map<String, Object>> getCorrelationStatistics() {
        try {
            Map<String, Object> stats = correlationService.getCorrelationStatistics();
            return ResponseEntity.ok(stats);
        } catch (Exception e) {
            Map<String, Object> error = new HashMap<>();
            error.put("error", "Failed to get correlation statistics");
            error.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        }
    }

    /**
     * Get correlated items for recommendation
     */
    @GetMapping("/correlations/recommendations/{itemId}")
    public ResponseEntity<List<Map<String, Object>>> getCorrelatedRecommendations(
            @PathVariable Long itemId,
            @RequestParam(defaultValue = "5") int limit) {
        try {
            List<Map<String, Object>> recommendations = correlationService
                    .getCorrelatedItemsForRecommendation(itemId, limit);
            return ResponseEntity.ok(recommendations);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(null);
        }
    }

    // ============= STATISTICAL ANALYSIS ENDPOINTS =============

    /**
     * Get item statistics
     */
    @GetMapping("/analysis/item/{itemId}")
    public ResponseEntity<Map<String, Object>> getItemStatistics(
            @PathVariable Long itemId,
            @RequestParam(defaultValue = "30") int days) {
        try {
            Map<String, Object> stats = statisticalAnalysisService.calculateItemStatistics(itemId, days);
            return ResponseEntity.ok(stats);
        } catch (Exception e) {
            Map<String, Object> error = new HashMap<>();
            error.put("error", "Failed to calculate item statistics");
            error.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        }
    }

    /**
     * Get category statistics
     */
    @GetMapping("/analysis/category/{categoryId}")
    public ResponseEntity<Map<String, Object>> getCategoryStatistics(
            @PathVariable Long categoryId,
            @RequestParam(defaultValue = "30") int days) {
        try {
            Map<String, Object> stats = statisticalAnalysisService.calculateCategoryStatistics(categoryId, days);
            return ResponseEntity.ok(stats);
        } catch (Exception e) {
            Map<String, Object> error = new HashMap<>();
            error.put("error", "Failed to calculate category statistics");
            error.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        }
    }

    /**
     * Update statistics for all items
     */
    @PostMapping("/analysis/update-all")
    public ResponseEntity<Map<String, Object>> updateAllStatistics() {
        try {
            Map<String, Object> result = statisticalAnalysisService.updateAllItemStatistics();
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            Map<String, Object> error = new HashMap<>();
            error.put("error", "Failed to update statistics");
            error.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        }
    }

    /**
     * Update statistics for specific item
     */
    @PostMapping("/analysis/item/{itemId}/update")
    public ResponseEntity<Map<String, Object>> updateItemStatistics(@PathVariable Long itemId) {
        try {
            statisticalAnalysisService.updateItemStatistics(itemId, LocalDate.now());
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("message", "Statistics updated successfully for item " + itemId);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            Map<String, Object> error = new HashMap<>();
            error.put("error", "Failed to update item statistics");
            error.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        }
    }

    // ============= NOTIFICATION ENDPOINTS =============

    /**
     * Get active notifications
     */
    @GetMapping("/notifications")
    public ResponseEntity<List<NotificationService.Notification>> getActiveNotifications() {
        try {
            List<NotificationService.Notification> notifications = notificationService.getActiveNotifications();
            return ResponseEntity.ok(notifications);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(null);
        }
    }

    /**
     * Get notifications by severity
     */
    @GetMapping("/notifications/severity/{severity}")
    public ResponseEntity<List<NotificationService.Notification>> getNotificationsBySeverity(
            @PathVariable String severity) {
        try {
            List<NotificationService.Notification> notifications = notificationService
                    .getNotificationsBySeverity(severity.toUpperCase());
            return ResponseEntity.ok(notifications);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(null);
        }
    }

    /**
     * Get notification summary
     */
    @GetMapping("/notifications/summary")
    public ResponseEntity<Map<String, Object>> getNotificationSummary() {
        try {
            Map<String, Object> summary = notificationService.getNotificationSummary();
            return ResponseEntity.ok(summary);
        } catch (Exception e) {
            Map<String, Object> error = new HashMap<>();
            error.put("error", "Failed to get notification summary");
            error.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        }
    }

    /**
     * Mark notification as read
     */
    @PutMapping("/notifications/{notificationId}/read")
    public ResponseEntity<Map<String, Object>> markNotificationAsRead(@PathVariable String notificationId) {
        try {
            boolean success = notificationService.markAsRead(notificationId);
            Map<String, Object> result = new HashMap<>();
            result.put("success", success);
            result.put("message", success ? "Notification marked as read" : "Notification not found");
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            Map<String, Object> error = new HashMap<>();
            error.put("error", "Failed to mark notification as read");
            error.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        }
    }

    /**
     * Mark all notifications as read
     */
    @PutMapping("/notifications/read-all")
    public ResponseEntity<Map<String, Object>> markAllNotificationsAsRead() {
        try {
            notificationService.markAllAsRead();
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("message", "All notifications marked as read");
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            Map<String, Object> error = new HashMap<>();
            error.put("error", "Failed to mark notifications as read");
            error.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        }
    }

    /**
     * Check stock levels and get alerts
     */
    @GetMapping("/notifications/stock-check")
    public ResponseEntity<Map<String, Object>> checkStockLevels() {
        try {
            Map<String, Object> stockStatus = notificationService.checkStockLevels();
            return ResponseEntity.ok(stockStatus);
        } catch (Exception e) {
            Map<String, Object> error = new HashMap<>();
            error.put("error", "Failed to check stock levels");
            error.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        }
    }

    // ============= COMBINED ANALYTICS ENDPOINT =============

    /**
     * Get comprehensive analytics for an item (statistics + correlations + notifications)
     */
    @GetMapping("/comprehensive/item/{itemId}")
    public ResponseEntity<Map<String, Object>> getComprehensiveItemAnalytics(
            @PathVariable Long itemId,
            @RequestParam(defaultValue = "30") int days) {
        try {
            Map<String, Object> analytics = new HashMap<>();

            // Get statistics
            Map<String, Object> statistics = statisticalAnalysisService.calculateItemStatistics(itemId, days);
            analytics.put("statistics", statistics);

            // Get correlations
            Map<String, Object> correlations = correlationService.calculateItemCorrelations(itemId);
            analytics.put("correlations", correlations);

            // Get recommendations
            List<Map<String, Object>> recommendations = correlationService
                    .getCorrelatedItemsForRecommendation(itemId, 5);
            analytics.put("recommendations", recommendations);

            analytics.put("itemId", itemId);
            analytics.put("analysisDate", LocalDate.now());

            return ResponseEntity.ok(analytics);
        } catch (Exception e) {
            Map<String, Object> error = new HashMap<>();
            error.put("error", "Failed to get comprehensive analytics");
            error.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        }
    }

    /**
     * Health check endpoint
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> healthCheck() {
        Map<String, Object> health = new HashMap<>();
        health.put("status", "UP");
        health.put("timestamp", LocalDate.now().toString());
        health.put("services", new String[]{"correlations", "statistics", "notifications"});
        return ResponseEntity.ok(health);
    }
}