package com.bmsedge.inventory.service;

import com.bmsedge.inventory.model.Item;
import com.bmsedge.inventory.repository.ItemRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Transactional
public class NotificationService {

    private static final Logger logger = LoggerFactory.getLogger(NotificationService.class);

    @Autowired
    private ItemRepository itemRepository;

    @Autowired
    private ItemCorrelationService correlationService;

    // In-memory storage for notifications (in production, use database)
    private final List<Notification> notifications = Collections.synchronizedList(new ArrayList<>());
    private final Map<Long, LocalDateTime> lastNotificationTime = new HashMap<>();
    private static final long NOTIFICATION_COOLDOWN_HOURS = 24;

    /**
     * Check for low stock items and generate alerts
     */
    @Scheduled(fixedDelay = 300000) // Check every 5 minutes
    public void checkLowStockAlerts() {
        List<Item> allItems = itemRepository.findAll();

        for (Item item : allItems) {
            checkAndCreateAlerts(item);
        }
    }

    /**
     * Check item and create appropriate alerts
     */
    public List<Notification> checkAndCreateAlerts(Item item) {
        List<Notification> itemNotifications = new ArrayList<>();

        // Check if we should skip due to cooldown
        if (isInCooldown(item.getId())) {
            return itemNotifications;
        }

        // 1. Critical Stock Alert
        if ("CRITICAL".equals(item.getStockAlertLevel())) {
            Notification notification = createNotification(
                    item,
                    NotificationType.CRITICAL_STOCK,
                    "Critical stock level",
                    String.format("Item '%s' has reached critical stock level. Current: %.2f, Reorder Level: %.2f",
                            item.getItemName(),
                            item.getCurrentQuantity(),
                            item.getReorderLevel())
            );
            itemNotifications.add(notification);
        }

        // 2. Reorder Alert
        else if (item.needsReorder()) {
            // Get correlated items for smart reordering
            List<Map<String, Object>> correlatedItems = correlationService
                    .getCorrelatedItemsForRecommendation(item.getId(), 3);

            String correlatedInfo = "";
            if (!correlatedItems.isEmpty()) {
                correlatedInfo = " Consider also reordering correlated items: " +
                        correlatedItems.stream()
                                .map(c -> (String) c.get("itemName"))
                                .collect(Collectors.joining(", "));
            }

            Notification notification = createNotification(
                    item,
                    NotificationType.REORDER_REQUIRED,
                    "Reorder required",
                    String.format("Item '%s' needs reordering. Current: %.2f, Reorder Level: %.2f.%s",
                            item.getItemName(),
                            item.getCurrentQuantity(),
                            item.getReorderLevel(),
                            correlatedInfo)
            );
            itemNotifications.add(notification);
        }

        // 3. High Volatility Alert
        if (Boolean.TRUE.equals(item.getIsHighlyVolatile())) {
            Notification notification = createNotification(
                    item,
                    NotificationType.HIGH_VOLATILITY,
                    "High consumption volatility",
                    String.format("Item '%s' shows high consumption volatility (CV: %.2f). Consider adjusting safety stock.",
                            item.getItemName(),
                            item.getConsumptionCV() != null ? item.getConsumptionCV() : BigDecimal.ZERO)
            );
            itemNotifications.add(notification);
        }

        // 4. Expiry Alert
        if (item.isExpiringSoon(7)) {
            Notification notification = createNotification(
                    item,
                    NotificationType.EXPIRY_WARNING,
                    "Item expiring soon",
                    String.format("Item '%s' will expire on %s. Current stock: %.2f",
                            item.getItemName(),
                            item.getExpiryDate(),
                            item.getCurrentQuantity())
            );
            itemNotifications.add(notification);
        }

        // 5. Stock Out Prediction
        if (item.getExpectedStockoutDate() != null &&
                item.getCoverageDays() != null && item.getCoverageDays() <= 7) {
            Notification notification = createNotification(
                    item,
                    NotificationType.STOCKOUT_PREDICTION,
                    "Predicted stock out",
                    String.format("Item '%s' is predicted to run out of stock by %s (in %d days)",
                            item.getItemName(),
                            item.getExpectedStockoutDate(),
                            item.getCoverageDays())
            );
            itemNotifications.add(notification);
        }

        // Add to global notifications and update cooldown
        if (!itemNotifications.isEmpty()) {
            notifications.addAll(itemNotifications);
            lastNotificationTime.put(item.getId(), LocalDateTime.now());
        }

        return itemNotifications;
    }

    /**
     * Create a notification
     */
    private Notification createNotification(Item item, NotificationType type, String title, String message) {
        Notification notification = new Notification();
        notification.setId(UUID.randomUUID().toString());
        notification.setItemId(item.getId());
        notification.setItemName(item.getItemName());
        notification.setCategoryName(item.getCategory() != null ? item.getCategory().getCategoryName() : "");
        notification.setType(type);
        notification.setTitle(title);
        notification.setMessage(message);
        notification.setSeverity(determineSeverity(type));
        notification.setCreatedAt(LocalDateTime.now());
        notification.setRead(false);
        notification.setActionable(true);

        // Add suggested actions
        notification.setSuggestedActions(getSuggestedActions(type, item));

        logger.info("Created {} notification for item {}", type, item.getItemName());

        return notification;
    }

    /**
     * Get all active notifications
     */
    public List<Notification> getActiveNotifications() {
        // Remove old notifications (older than 7 days)
        LocalDateTime cutoff = LocalDateTime.now().minusDays(7);
        notifications.removeIf(n -> n.getCreatedAt().isBefore(cutoff));

        return notifications.stream()
                .filter(n -> !n.isRead())
                .sorted((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()))
                .collect(Collectors.toList());
    }

    /**
     * Get notifications by severity
     */
    public List<Notification> getNotificationsBySeverity(String severity) {
        return notifications.stream()
                .filter(n -> severity.equals(n.getSeverity()))
                .filter(n -> !n.isRead())
                .sorted((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()))
                .collect(Collectors.toList());
    }

    /**
     * Mark notification as read
     */
    public boolean markAsRead(String notificationId) {
        Optional<Notification> notification = notifications.stream()
                .filter(n -> n.getId().equals(notificationId))
                .findFirst();

        if (notification.isPresent()) {
            notification.get().setRead(true);
            notification.get().setReadAt(LocalDateTime.now());
            return true;
        }

        return false;
    }

    /**
     * Mark all notifications as read
     */
    public void markAllAsRead() {
        LocalDateTime now = LocalDateTime.now();
        notifications.forEach(n -> {
            n.setRead(true);
            n.setReadAt(now);
        });
    }

    /**
     * Get notification summary
     */
    public Map<String, Object> getNotificationSummary() {
        Map<String, Object> summary = new HashMap<>();

        List<Notification> active = getActiveNotifications();

        summary.put("totalActive", active.size());
        summary.put("critical", active.stream()
                .filter(n -> "CRITICAL".equals(n.getSeverity()))
                .count());
        summary.put("high", active.stream()
                .filter(n -> "HIGH".equals(n.getSeverity()))
                .count());
        summary.put("medium", active.stream()
                .filter(n -> "MEDIUM".equals(n.getSeverity()))
                .count());
        summary.put("low", active.stream()
                .filter(n -> "LOW".equals(n.getSeverity()))
                .count());

        // Group by type
        Map<String, Long> byType = active.stream()
                .collect(Collectors.groupingBy(
                        n -> n.getType().toString(),
                        Collectors.counting()
                ));
        summary.put("byType", byType);

        // Recent notifications
        summary.put("recentNotifications", active.stream()
                .limit(5)
                .map(this::notificationToMap)
                .collect(Collectors.toList()));

        return summary;
    }

    /**
     * Check stock levels and return alerts
     */
    public Map<String, Object> checkStockLevels() {
        Map<String, Object> result = new HashMap<>();
        List<Map<String, Object>> alerts = new ArrayList<>();

        List<Item> allItems = itemRepository.findAll();

        int criticalCount = 0;
        int reorderCount = 0;
        int safeCount = 0;

        for (Item item : allItems) {
            Map<String, Object> itemStatus = new HashMap<>();
            itemStatus.put("itemId", item.getId());
            itemStatus.put("itemName", item.getItemName());
            itemStatus.put("currentQuantity", item.getCurrentQuantity());
            itemStatus.put("reorderLevel", item.getReorderLevel());
            itemStatus.put("stockAlertLevel", item.getStockAlertLevel());
            itemStatus.put("coverageDays", item.getCoverageDays());

            if ("CRITICAL".equals(item.getStockAlertLevel()) || "HIGH".equals(item.getStockAlertLevel())) {
                criticalCount++;
                itemStatus.put("status", "CRITICAL");
                alerts.add(itemStatus);
            } else if (item.needsReorder()) {
                reorderCount++;
                itemStatus.put("status", "REORDER");
                alerts.add(itemStatus);
            } else {
                safeCount++;
            }
        }

        result.put("totalItems", allItems.size());
        result.put("criticalItems", criticalCount);
        result.put("reorderRequired", reorderCount);
        result.put("safeStock", safeCount);
        result.put("alerts", alerts);
        result.put("timestamp", LocalDateTime.now());

        return result;
    }

    // Helper methods

    private boolean isInCooldown(Long itemId) {
        if (!lastNotificationTime.containsKey(itemId)) {
            return false;
        }

        LocalDateTime lastTime = lastNotificationTime.get(itemId);
        return lastTime.plusHours(NOTIFICATION_COOLDOWN_HOURS).isAfter(LocalDateTime.now());
    }

    private String determineSeverity(NotificationType type) {
        switch (type) {
            case CRITICAL_STOCK:
            case STOCKOUT_PREDICTION:
                return "CRITICAL";
            case REORDER_REQUIRED:
            case EXPIRY_WARNING:
                return "HIGH";
            case HIGH_VOLATILITY:
                return "MEDIUM";
            default:
                return "LOW";
        }
    }

    private List<String> getSuggestedActions(NotificationType type, Item item) {
        List<String> actions = new ArrayList<>();

        switch (type) {
            case CRITICAL_STOCK:
                actions.add("Place emergency order immediately");
                actions.add("Check alternative suppliers");
                actions.add("Review consumption patterns");
                break;
            case REORDER_REQUIRED:
                BigDecimal reorderQty = item.getReorderQuantity() != null ?
                        item.getReorderQuantity() : BigDecimal.valueOf(100);
                actions.add(String.format("Create purchase order for %.0f units", reorderQty));
                actions.add("Review and update reorder level");
                break;
            case HIGH_VOLATILITY:
                actions.add("Increase safety stock level");
                actions.add("Review consumption forecast");
                actions.add("Implement more frequent monitoring");
                break;
            case EXPIRY_WARNING:
                actions.add("Prioritize consumption of expiring stock");
                actions.add("Consider discounting or promotion");
                actions.add("Plan replacement order");
                break;
            case STOCKOUT_PREDICTION:
                actions.add("Place order immediately");
                actions.add(String.format("Estimated requirement: %.0f units",
                        item.getAvgDailyConsumption().multiply(BigDecimal.valueOf(30))));
                break;
        }

        return actions;
    }

    private Map<String, Object> notificationToMap(Notification notification) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", notification.getId());
        map.put("type", notification.getType());
        map.put("title", notification.getTitle());
        map.put("message", notification.getMessage());
        map.put("severity", notification.getSeverity());
        map.put("itemId", notification.getItemId());
        map.put("itemName", notification.getItemName());
        map.put("createdAt", notification.getCreatedAt());
        map.put("suggestedActions", notification.getSuggestedActions());
        return map;
    }

    // Notification entity (inner class for simplicity)
    public static class Notification {
        private String id;
        private Long itemId;
        private String itemName;
        private String categoryName;
        private NotificationType type;
        private String title;
        private String message;
        private String severity;
        private LocalDateTime createdAt;
        private boolean read;
        private LocalDateTime readAt;
        private boolean actionable;
        private List<String> suggestedActions;

        // Getters and setters
        public String getId() { return id; }
        public void setId(String id) { this.id = id; }

        public Long getItemId() { return itemId; }
        public void setItemId(Long itemId) { this.itemId = itemId; }

        public String getItemName() { return itemName; }
        public void setItemName(String itemName) { this.itemName = itemName; }

        public String getCategoryName() { return categoryName; }
        public void setCategoryName(String categoryName) { this.categoryName = categoryName; }

        public NotificationType getType() { return type; }
        public void setType(NotificationType type) { this.type = type; }

        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }

        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }

        public String getSeverity() { return severity; }
        public void setSeverity(String severity) { this.severity = severity; }

        public LocalDateTime getCreatedAt() { return createdAt; }
        public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

        public boolean isRead() { return read; }
        public void setRead(boolean read) { this.read = read; }

        public LocalDateTime getReadAt() { return readAt; }
        public void setReadAt(LocalDateTime readAt) { this.readAt = readAt; }

        public boolean isActionable() { return actionable; }
        public void setActionable(boolean actionable) { this.actionable = actionable; }

        public List<String> getSuggestedActions() { return suggestedActions; }
        public void setSuggestedActions(List<String> suggestedActions) { this.suggestedActions = suggestedActions; }
    }

    public enum NotificationType {
        CRITICAL_STOCK,
        REORDER_REQUIRED,
        HIGH_VOLATILITY,
        EXPIRY_WARNING,
        STOCKOUT_PREDICTION,
        CONSUMPTION_ANOMALY,
        CORRELATION_ALERT
    }
}