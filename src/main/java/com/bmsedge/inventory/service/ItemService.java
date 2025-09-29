package com.bmsedge.inventory.service;

import com.bmsedge.inventory.dto.ItemRequest;
import com.bmsedge.inventory.dto.ItemResponse;
import com.bmsedge.inventory.exception.BusinessException;
import com.bmsedge.inventory.exception.ResourceNotFoundException;
import com.bmsedge.inventory.model.*;
import com.bmsedge.inventory.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Transactional
public class ItemService {
    private static final Logger logger = LoggerFactory.getLogger(ItemService.class);

    @Autowired
    private ItemRepository itemRepository;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private StockMovementRepository stockMovementRepository;

    @Autowired
    private ConsumptionRecordRepository consumptionRecordRepository;

    @Autowired(required = false)
    private StatisticalAnalysisService statisticalAnalysisService;

    @Autowired(required = false)
    private ItemCorrelationService correlationService;

    @Autowired(required = false)
    private NotificationService notificationService;

    /**
     * Create a new item
     */
    public ItemResponse createItem(ItemRequest request, Long userId) {
        try {
            // Check for duplicate item name
            Optional<Item> existingItem = itemRepository.findAll().stream()
                    .filter(item -> item.getItemName().equalsIgnoreCase(request.getItemName()))
                    .findFirst();

            if (existingItem.isPresent()) {
                throw new BusinessException("Item with name '" + request.getItemName() + "' already exists");
            }

            Category category = categoryRepository.findById(request.getCategoryId())
                    .orElseThrow(() -> new ResourceNotFoundException("Category not found with id: " + request.getCategoryId()));

            Item item = new Item();
            item.setItemCode(request.getItemCode());
            item.setItemName(request.getItemName());
            item.setItemDescription(request.getItemDescription());
            item.setCurrentQuantity(BigDecimal.valueOf(request.getCurrentQuantity()));
            item.setOpeningStock(BigDecimal.valueOf(request.getCurrentQuantity()));
            item.setClosingStock(BigDecimal.valueOf(request.getCurrentQuantity()));

            // Use reorder level instead of min/max stock
            BigDecimal reorderLevel = request.getReorderLevel() != null ?
                    request.getReorderLevel() : BigDecimal.valueOf(10);
            item.setReorderLevel(reorderLevel);

            BigDecimal reorderQuantity = request.getReorderQuantity() != null ?
                    request.getReorderQuantity() : BigDecimal.valueOf(50);
            item.setReorderQuantity(reorderQuantity);

            item.setUnitOfMeasurement(request.getUnitOfMeasurement());
            item.setUnitPrice(request.getUnitPrice());
            item.setCategory(category);
            item.setExpiryDate(request.getExpiryDate());
            item.setCreatedBy(userId);

            Item savedItem = itemRepository.save(item);
            logger.info("Created item: {}", savedItem.getItemName());

            return convertToResponse(savedItem);
        } catch (DataIntegrityViolationException e) {
            if (e.getMessage().contains("uk_item_name")) {
                throw new BusinessException("Item with this name already exists");
            }
            throw e;
        }
    }

    /**
     * Get all items with pagination
     */
    public Page<ItemResponse> getAllItemsPaginated(int page, int size, String sortBy, String sortDirection) {
        Sort.Direction direction = sortDirection.equalsIgnoreCase("DESC") ?
                Sort.Direction.DESC : Sort.Direction.ASC;
        Pageable pageable = PageRequest.of(page, size, Sort.by(direction, sortBy));

        Page<Item> itemPage = itemRepository.findAll(pageable);

        return itemPage.map(this::convertToResponse);
    }

    /**
     * Get all items (for backward compatibility)
     */
    public List<ItemResponse> getAllItems() {
        List<Item> items = itemRepository.findAll();
        return items.stream()
                .map(this::convertToResponse)
                .collect(Collectors.toList());
    }

    /**
     * Get item by ID
     */
    public ItemResponse getItemById(Long id) {
        Item item = itemRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Item not found with id: " + id));
        return convertToResponse(item);
    }

    /**
     * Update item
     */
    public ItemResponse updateItem(Long id, ItemRequest request, Long userId) {
        try {
            Item item = itemRepository.findById(id)
                    .orElseThrow(() -> new ResourceNotFoundException("Item not found with id: " + id));

            // Check for duplicate name (excluding current item)
            Optional<Item> duplicateItem = itemRepository.findAll().stream()
                    .filter(i -> !i.getId().equals(id) &&
                            i.getItemName().equalsIgnoreCase(request.getItemName()))
                    .findFirst();

            if (duplicateItem.isPresent()) {
                throw new BusinessException("Item with name '" + request.getItemName() + "' already exists");
            }

            if (request.getCategoryId() != null) {
                Category category = categoryRepository.findById(request.getCategoryId())
                        .orElseThrow(() -> new ResourceNotFoundException("Category not found"));
                item.setCategory(category);
            }

            item.setItemCode(request.getItemCode());
            item.setItemName(request.getItemName());
            item.setItemDescription(request.getItemDescription());
            item.setCurrentQuantity(BigDecimal.valueOf(request.getCurrentQuantity()));

            BigDecimal reorderLevel = request.getReorderLevel() != null ?
                    request.getReorderLevel() : item.getReorderLevel();
            item.setReorderLevel(reorderLevel);

            BigDecimal reorderQuantity = request.getReorderQuantity() != null ?
                    request.getReorderQuantity() : item.getReorderQuantity();
            item.setReorderQuantity(reorderQuantity);

            item.setUnitOfMeasurement(request.getUnitOfMeasurement());
            item.setUnitPrice(request.getUnitPrice());
            item.setExpiryDate(request.getExpiryDate());

            Item updatedItem = itemRepository.save(item);

            // Check for notifications after update
            if (notificationService != null) {
                notificationService.checkAndCreateAlerts(updatedItem);
            }

            return convertToResponse(updatedItem);
        } catch (DataIntegrityViolationException e) {
            if (e.getMessage().contains("uk_item_name")) {
                throw new BusinessException("Item with this name already exists");
            }
            throw e;
        }
    }

    /**
     * Record consumption with statistics update
     */
    @Transactional
    public ItemResponse recordConsumption(Long itemId, BigDecimal quantity, String department, Long userId) {
        Item item = itemRepository.findById(itemId)
                .orElseThrow(() -> new ResourceNotFoundException("Item not found with id: " + itemId));

        if (item.getCurrentQuantity().compareTo(quantity) < 0) {
            throw new BusinessException("Insufficient stock. Available: " + item.getCurrentQuantity());
        }

        // Update item stock
        item.recordConsumption(quantity);

        // Create stock movement
        StockMovement movement = new StockMovement(
                item, "CONSUMPTION", LocalDate.now(), quantity, userId
        );
        movement.setDepartment(department);
        movement.setOpeningBalance(item.getCurrentQuantity().add(quantity));
        movement.setClosingBalance(item.getCurrentQuantity());
        stockMovementRepository.save(movement);

        // Create or update consumption record
        LocalDate today = LocalDate.now();
        Optional<ConsumptionRecord> existingRecord = consumptionRecordRepository
                .findByItemAndConsumptionDate(item, today);

        ConsumptionRecord consumptionRecord;
        if (existingRecord.isPresent()) {
            consumptionRecord = existingRecord.get();
            BigDecimal currentConsumption = consumptionRecord.getConsumedQuantity() != null ?
                    consumptionRecord.getConsumedQuantity() : BigDecimal.ZERO;
            consumptionRecord.setConsumedQuantity(currentConsumption.add(quantity));
        } else {
            consumptionRecord = new ConsumptionRecord(item, today, item.getCurrentQuantity().add(quantity));
            consumptionRecord.setConsumedQuantity(quantity);
        }

        consumptionRecord.setClosingStock(item.getCurrentQuantity());
        consumptionRecord.setDepartment(department);
        consumptionRecordRepository.save(consumptionRecord);

        Item updatedItem = itemRepository.save(item);

        // Update statistics and correlations asynchronously
        if (statisticalAnalysisService != null) {
            try {
                statisticalAnalysisService.updateItemStatistics(itemId, today);
            } catch (Exception e) {
                logger.error("Failed to update statistics for item {}: {}", itemId, e.getMessage());
            }
        }

        if (correlationService != null) {
            try {
                correlationService.updateCorrelationsForItem(itemId);
            } catch (Exception e) {
                logger.error("Failed to update correlations for item {}: {}", itemId, e.getMessage());
            }
        }

        // Check for notifications
        if (notificationService != null) {
            notificationService.checkAndCreateAlerts(updatedItem);
        }

        logger.info("Recorded consumption of {} {} for item {}", quantity, item.getUnitOfMeasurement(), item.getItemName());

        return convertToResponse(updatedItem);
    }

    /**
     * Record receipt - FIXED VERSION
     */
    @Transactional
    public ItemResponse recordReceipt(Long itemId, BigDecimal quantity, BigDecimal unitPrice, String referenceNumber, Long userId) {
        Item item = itemRepository.findById(itemId)
                .orElseThrow(() -> new ResourceNotFoundException("Item not found with id: " + itemId));

        // Update item stock
        item.recordReceipt(quantity);

        if (unitPrice != null) {
            item.setUnitPrice(unitPrice);
        }

        // Create stock movement
        StockMovement movement = new StockMovement(
                item, "RECEIPT", LocalDate.now(), quantity, userId
        );
        movement.setReferenceNumber(referenceNumber);
        movement.setUnitPrice(unitPrice);
        movement.setOpeningBalance(item.getCurrentQuantity().subtract(quantity));
        movement.setClosingBalance(item.getCurrentQuantity());
        stockMovementRepository.save(movement);

        // CRITICAL FIX: Create or update consumption record with received quantity
        LocalDate today = LocalDate.now();
        Optional<ConsumptionRecord> existingRecord = consumptionRecordRepository
                .findByItemAndConsumptionDate(item, today);

        ConsumptionRecord consumptionRecord;
        if (existingRecord.isPresent()) {
            consumptionRecord = existingRecord.get();
            BigDecimal currentReceived = consumptionRecord.getReceivedQuantity() != null ?
                    consumptionRecord.getReceivedQuantity() : BigDecimal.ZERO;
            consumptionRecord.setReceivedQuantity(currentReceived.add(quantity));
        } else {
            consumptionRecord = new ConsumptionRecord(item, today, item.getCurrentQuantity().subtract(quantity));
            consumptionRecord.setReceivedQuantity(quantity);
        }

        consumptionRecord.setClosingStock(item.getCurrentQuantity());
        consumptionRecordRepository.save(consumptionRecord);

        Item updatedItem = itemRepository.save(item);

        logger.info("Recorded receipt of {} {} for item {}", quantity, item.getUnitOfMeasurement(), item.getItemName());

        return convertToResponse(updatedItem);
    }

    /**
     * Get items by category with pagination
     */
    public Page<ItemResponse> getItemsByCategoryPaginated(Long categoryId, int page, int size, String sortBy, String sortDirection) {
        Category category = categoryRepository.findById(categoryId)
                .orElseThrow(() -> new ResourceNotFoundException("Category not found with id: " + categoryId));

        Sort.Direction direction = sortDirection.equalsIgnoreCase("DESC") ?
                Sort.Direction.DESC : Sort.Direction.ASC;
        Pageable pageable = PageRequest.of(page, size, Sort.by(direction, sortBy));

        Page<Item> itemPage = itemRepository.findAll(pageable);
        Page<Item> filteredPage = (Page<Item>) itemPage.filter(item -> item.getCategory().getId().equals(categoryId));

        return filteredPage.map(this::convertToResponse);
    }

    /**
     * Get items by category (for backward compatibility)
     */
    public List<ItemResponse> getItemsByCategory(Long categoryId) {
        categoryRepository.findById(categoryId)
                .orElseThrow(() -> new ResourceNotFoundException("Category not found with id: " + categoryId));

        List<Item> items = itemRepository.findByCategoryId(categoryId);
        return items.stream()
                .map(this::convertToResponse)
                .collect(Collectors.toList());
    }

    /**
     * Search items
     */
    public List<ItemResponse> searchItems(String query) {
        List<Item> items = itemRepository.findByItemNameOrDescriptionContaining(query);
        return items.stream()
                .map(this::convertToResponse)
                .collect(Collectors.toList());
    }

    /**
     * Get low stock items
     */
    public List<ItemResponse> getLowStockItems(Integer threshold) {
        List<Item> items;
        if (threshold != null) {
            items = itemRepository.findLowStockItems(threshold);
        } else {
            items = itemRepository.findLowStockItems();
        }
        return items.stream()
                .map(this::convertToResponse)
                .collect(Collectors.toList());
    }

    /**
     * Get expiring items
     */
    public List<ItemResponse> getExpiringItems(int days) {
        LocalDateTime startDate = LocalDateTime.now();
        LocalDateTime endDate = startDate.plusDays(days);
        List<Item> items = itemRepository.findExpiringItems(startDate, endDate);
        return items.stream()
                .map(this::convertToResponse)
                .collect(Collectors.toList());
    }

    /**
     * Get expired items
     */
    public List<ItemResponse> getExpiredItems() {
        List<Item> items = itemRepository.findExpiredItems(LocalDateTime.now());
        return items.stream()
                .map(this::convertToResponse)
                .collect(Collectors.toList());
    }

    /**
     * Delete item
     */
    public void deleteItem(Long id, Long userId) {
        Item item = itemRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Item not found with id: " + id));

        itemRepository.delete(item);
        logger.info("Deleted item: {}", item.getItemName());
    }

    /**
     * Convert Item to ItemResponse - FULLY FIXED VERSION WITH ALL NULLS HANDLED
     */
    private ItemResponse convertToResponse(Item item) {
        ItemResponse response = new ItemResponse();
        response.setId(item.getId());

        // Fix itemCode - generate if null
        response.setItemCode(item.getItemCode() != null ? item.getItemCode() :
                "ITEM-" + item.getId());

        response.setItemName(item.getItemName());
        response.setItemDescription(item.getItemDescription());
        response.setCurrentQuantity(item.getCurrentQuantity() != null ?
                item.getCurrentQuantity().intValue() : 0);
        response.setOpeningStock(item.getOpeningStock() != null ?
                item.getOpeningStock().intValue() : 0);
        response.setClosingStock(item.getClosingStock() != null ?
                item.getClosingStock().intValue() : 0);
        response.setReorderLevel(item.getReorderLevel() != null ?
                item.getReorderLevel().intValue() : 10);
        response.setReorderQuantity(item.getReorderQuantity() != null ?
                item.getReorderQuantity().intValue() : 50);
        response.setUnitOfMeasurement(item.getUnitOfMeasurement() != null ?
                item.getUnitOfMeasurement() : "pcs");
        response.setUnitPrice(item.getUnitPrice() != null ? item.getUnitPrice() : BigDecimal.ZERO);
        response.setTotalValue(item.getTotalValue() != null ? item.getTotalValue() : BigDecimal.ZERO);
        response.setStockAlertLevel(item.getStockAlertLevel() != null ?
                item.getStockAlertLevel() : "SAFE");
        response.setExpiryDate(item.getExpiryDate());
        response.setCreatedAt(item.getCreatedAt());
        response.setUpdatedAt(item.getUpdatedAt());

        // Fix statistical fields with calculations and defaults
        response.setVolatilityClassification(item.getVolatilityClassification() != null ?
                item.getVolatilityClassification() : "MEDIUM");
        response.setIsHighlyVolatile(item.getIsHighlyVolatile() != null ?
                item.getIsHighlyVolatile() : false);

        // Calculate or default stock totals
        response.setTotalReceivedStock(item.getTotalReceivedStock() != null ?
                item.getTotalReceivedStock() : BigDecimal.ZERO);
        response.setTotalConsumedStock(item.getTotalConsumedStock() != null ?
                item.getTotalConsumedStock() : BigDecimal.ZERO);

        // Calculate statistics from consumption records AND include them in response
        List<Map<String, Object>> consumptionRecords = new ArrayList<>();
        try {
            LocalDate startOfMonth = LocalDate.now().withDayOfMonth(1);
            LocalDate endOfMonth = LocalDate.now().withDayOfMonth(LocalDate.now().lengthOfMonth());
            LocalDate last30Days = LocalDate.now().minusDays(30);

            // Get ALL consumption records for this item (last 6 months for debugging)
            LocalDate last6Months = LocalDate.now().minusMonths(6);
            List<ConsumptionRecord> allRecords = consumptionRecordRepository
                    .findByItemAndConsumptionDateBetween(item, last6Months, LocalDate.now());

            logger.debug("Found {} consumption records for item {}", allRecords.size(), item.getId());

            // Convert consumption records to response format
            for (ConsumptionRecord record : allRecords) {
                Map<String, Object> recordMap = new HashMap<>();
                recordMap.put("id", record.getId());
                recordMap.put("date", record.getConsumptionDate());
                recordMap.put("openingStock", record.getOpeningStock());
                recordMap.put("receivedQuantity", record.getReceivedQuantity());
                recordMap.put("consumedQuantity", record.getConsumedQuantity());
                recordMap.put("closingStock", record.getClosingStock());
                recordMap.put("department", record.getDepartment());
                recordMap.put("notes", record.getNotes());
                consumptionRecords.add(recordMap);
            }

            // Calculate totals from ALL records
            BigDecimal totalReceived = BigDecimal.ZERO;
            BigDecimal totalConsumed = BigDecimal.ZERO;

            for (ConsumptionRecord record : allRecords) {
                if (record.getReceivedQuantity() != null) {
                    totalReceived = totalReceived.add(record.getReceivedQuantity());
                }
                if (record.getConsumedQuantity() != null) {
                    totalConsumed = totalConsumed.add(record.getConsumedQuantity());
                }
            }

            logger.debug("Calculated totals for item {}: received={}, consumed={}",
                    item.getId(), totalReceived, totalConsumed);

            response.setTotalReceivedStock(totalReceived);
            response.setTotalConsumedStock(totalConsumed);

            // Calculate monthly totals from current month records
            BigDecimal monthlyReceived = BigDecimal.ZERO;
            BigDecimal monthlyConsumed = BigDecimal.ZERO;

            List<ConsumptionRecord> monthRecords = allRecords.stream()
                    .filter(r -> !r.getConsumptionDate().isBefore(startOfMonth) &&
                            !r.getConsumptionDate().isAfter(endOfMonth))
                    .collect(Collectors.toList());

            for (ConsumptionRecord record : monthRecords) {
                if (record.getReceivedQuantity() != null) {
                    monthlyReceived = monthlyReceived.add(record.getReceivedQuantity());
                }
                if (record.getConsumedQuantity() != null) {
                    monthlyConsumed = monthlyConsumed.add(record.getConsumedQuantity());
                }
            }

            response.setMonthReceivedStock(monthlyReceived);
            response.setMonthConsumedStock(monthlyConsumed);

            // Calculate average daily consumption from last 30 days
            List<ConsumptionRecord> last30DaysRecords = allRecords.stream()
                    .filter(r -> !r.getConsumptionDate().isBefore(last30Days))
                    .collect(Collectors.toList());

            BigDecimal totalConsumption30Days = BigDecimal.ZERO;
            int daysWithConsumption = 0;

            for (ConsumptionRecord record : last30DaysRecords) {
                if (record.getConsumedQuantity() != null && record.getConsumedQuantity().compareTo(BigDecimal.ZERO) > 0) {
                    totalConsumption30Days = totalConsumption30Days.add(record.getConsumedQuantity());
                    daysWithConsumption++;
                }
            }

            BigDecimal avgDailyConsumption = BigDecimal.ZERO;
            if (daysWithConsumption > 0) {
                avgDailyConsumption = totalConsumption30Days.divide(
                        BigDecimal.valueOf(daysWithConsumption), 2, BigDecimal.ROUND_HALF_UP);
            }

            response.setAvgDailyConsumption(avgDailyConsumption);

            // Calculate standard deviation (simplified)
            BigDecimal stdDev = BigDecimal.ZERO;
            if (daysWithConsumption > 1) {
                BigDecimal sumSquaredDiffs = BigDecimal.ZERO;
                for (ConsumptionRecord record : last30DaysRecords) {
                    if (record.getConsumedQuantity() != null && record.getConsumedQuantity().compareTo(BigDecimal.ZERO) > 0) {
                        BigDecimal diff = record.getConsumedQuantity().subtract(avgDailyConsumption);
                        sumSquaredDiffs = sumSquaredDiffs.add(diff.multiply(diff));
                    }
                }
                BigDecimal variance = sumSquaredDiffs.divide(
                        BigDecimal.valueOf(daysWithConsumption - 1), 4, BigDecimal.ROUND_HALF_UP);
                stdDev = new BigDecimal(Math.sqrt(variance.doubleValue()));
            }

            response.setStdDailyConsumption(stdDev);

            // Calculate coefficient of variation
            BigDecimal cv = BigDecimal.ZERO;
            if (avgDailyConsumption.compareTo(BigDecimal.ZERO) > 0) {
                cv = stdDev.divide(avgDailyConsumption, 4, BigDecimal.ROUND_HALF_UP);
            }
            response.setConsumptionCV(cv);

            // Calculate coverage days
            Integer coverageDays = null;
            if (avgDailyConsumption.compareTo(BigDecimal.ZERO) > 0 && item.getCurrentQuantity() != null) {
                coverageDays = item.getCurrentQuantity().divide(avgDailyConsumption, 0, BigDecimal.ROUND_DOWN).intValue();
            }
            response.setCoverageDays(coverageDays != null ? coverageDays : 0);

            // Calculate expected stockout date
            if (coverageDays != null && coverageDays > 0) {
                response.setExpectedStockoutDate(LocalDate.now().plusDays(coverageDays));
            } else {
                response.setExpectedStockoutDate(null);
            }

            // Determine consumption pattern
            String pattern = "UNKNOWN";
            if (daysWithConsumption > 0) {
                double activityRate = (double) daysWithConsumption / 30.0;
                if (activityRate > 0.8) {
                    pattern = "REGULAR";
                } else if (activityRate > 0.3) {
                    pattern = "IRREGULAR";
                } else {
                    pattern = "SPORADIC";
                }
            }
            response.setConsumptionPattern(pattern);

            // Determine trend (simple approach)
            String trend = "STABLE";
            if (last30DaysRecords.size() >= 7) {
                // Compare first week vs last week consumption
                List<ConsumptionRecord> firstWeek = last30DaysRecords.stream()
                        .filter(r -> r.getConsumptionDate().isAfter(last30Days) &&
                                r.getConsumptionDate().isBefore(last30Days.plusDays(7)))
                        .collect(Collectors.toList());
                List<ConsumptionRecord> lastWeek = last30DaysRecords.stream()
                        .filter(r -> r.getConsumptionDate().isAfter(LocalDate.now().minusDays(7)))
                        .collect(Collectors.toList());

                if (!firstWeek.isEmpty() && !lastWeek.isEmpty()) {
                    BigDecimal firstWeekAvg = firstWeek.stream()
                            .map(r -> r.getConsumedQuantity() != null ? r.getConsumedQuantity() : BigDecimal.ZERO)
                            .reduce(BigDecimal.ZERO, BigDecimal::add)
                            .divide(BigDecimal.valueOf(firstWeek.size()), 2, BigDecimal.ROUND_HALF_UP);

                    BigDecimal lastWeekAvg = lastWeek.stream()
                            .map(r -> r.getConsumedQuantity() != null ? r.getConsumedQuantity() : BigDecimal.ZERO)
                            .reduce(BigDecimal.ZERO, BigDecimal::add)
                            .divide(BigDecimal.valueOf(lastWeek.size()), 2, BigDecimal.ROUND_HALF_UP);

                    if (lastWeekAvg.compareTo(firstWeekAvg.multiply(BigDecimal.valueOf(1.2))) > 0) {
                        trend = "INCREASING";
                    } else if (lastWeekAvg.compareTo(firstWeekAvg.multiply(BigDecimal.valueOf(0.8))) < 0) {
                        trend = "DECREASING";
                    }
                }
            }
            response.setTrend(trend);

            // Simple forecast for next period
            BigDecimal forecast = avgDailyConsumption;
            if ("INCREASING".equals(trend)) {
                forecast = avgDailyConsumption.multiply(BigDecimal.valueOf(1.1));
            } else if ("DECREASING".equals(trend)) {
                forecast = avgDailyConsumption.multiply(BigDecimal.valueOf(0.9));
            }
            response.setForecastNextPeriod(forecast);

            // Set last dates
            if (!allRecords.isEmpty()) {
                Optional<ConsumptionRecord> lastConsumption = allRecords.stream()
                        .filter(r -> r.getConsumedQuantity() != null && r.getConsumedQuantity().compareTo(BigDecimal.ZERO) > 0)
                        .max((r1, r2) -> r1.getConsumptionDate().compareTo(r2.getConsumptionDate()));

                if (lastConsumption.isPresent()) {
                    response.setLastConsumptionDate(lastConsumption.get().getConsumptionDate().atStartOfDay());
                }

                Optional<ConsumptionRecord> lastReceipt = allRecords.stream()
                        .filter(r -> r.getReceivedQuantity() != null && r.getReceivedQuantity().compareTo(BigDecimal.ZERO) > 0)
                        .max((r1, r2) -> r1.getConsumptionDate().compareTo(r2.getConsumptionDate()));

                if (lastReceipt.isPresent()) {
                    response.setLastReceivedDate(lastReceipt.get().getConsumptionDate().atStartOfDay());
                }
            }

            // Set last statistics update to now if we calculated statistics
            response.setLastStatisticsUpdate(LocalDateTime.now());

        } catch (Exception e) {
            logger.error("Error calculating statistics for item {}: {}", item.getId(), e.getMessage());
            // Set defaults for all calculated fields
            response.setMonthReceivedStock(BigDecimal.ZERO);
            response.setMonthConsumedStock(BigDecimal.ZERO);
            response.setAvgDailyConsumption(BigDecimal.ZERO);
            response.setStdDailyConsumption(BigDecimal.ZERO);
            response.setConsumptionCV(BigDecimal.ZERO);
            response.setCoverageDays(0);
            response.setExpectedStockoutDate(null);
            response.setConsumptionPattern("UNKNOWN");
            response.setTrend("STABLE");
            response.setForecastNextPeriod(BigDecimal.ZERO);
            response.setTotalReceivedStock(BigDecimal.ZERO);
            response.setTotalConsumedStock(BigDecimal.ZERO);
        }

        // Calculate business logic fields
        boolean needsReorder = item.getCurrentQuantity() != null && item.getReorderLevel() != null &&
                item.getCurrentQuantity().compareTo(item.getReorderLevel()) <= 0;
        response.setNeedsReorder(needsReorder);

        boolean isCritical = "CRITICAL".equals(item.getStockAlertLevel()) ||
                "HIGH".equals(item.getStockAlertLevel());
        response.setIsCritical(isCritical);

        if (item.getCategory() != null) {
            response.setCategoryId(item.getCategory().getId());
            response.setCategoryName(item.getCategory().getCategoryName());
        }

        // Set the consumption records in the response
        response.setConsumptionRecords(consumptionRecords);

        return response;
    }

    /**
     * NEW METHOD: Bulk import from Excel data
     */
    @Transactional
    public Map<String, Object> importInventoryData(List<Map<String, Object>> inventoryData, Long userId) {
        Map<String, Object> result = new HashMap<>();
        int imported = 0;
        int updated = 0;
        int failed = 0;
        List<String> errors = new ArrayList<>();

        for (Map<String, Object> row : inventoryData) {
            try {
                String itemName = (String) row.get("item_name");
                String categoryName = (String) row.get("category");
                BigDecimal openingStock = new BigDecimal(row.get("opening_stock").toString());
                BigDecimal receivedStock = new BigDecimal(row.get("received_stock").toString());
                BigDecimal totalConsumption = new BigDecimal(row.get("consumption").toString());
                BigDecimal stockInHand = new BigDecimal(row.get("sih").toString());

                // Find or create item
                Optional<Item> existingItem = itemRepository.findAll().stream()
                        .filter(item -> item.getItemName().equalsIgnoreCase(itemName))
                        .findFirst();

                Item item;
                if (existingItem.isPresent()) {
                    item = existingItem.get();
                    item.setCurrentQuantity(stockInHand);
                    item.setOpeningStock(openingStock);
                    item.setTotalReceivedStock(item.getTotalReceivedStock().add(receivedStock));
                    item.setTotalConsumedStock(item.getTotalConsumedStock().add(totalConsumption));
                    updated++;
                } else {
                    // Create new item
                    item = new Item();
                    item.setItemName(itemName);
                    item.setCurrentQuantity(stockInHand);
                    item.setOpeningStock(openingStock);
                    item.setTotalReceivedStock(receivedStock);
                    item.setTotalConsumedStock(totalConsumption);
                    item.setCreatedBy(userId);
                    imported++;
                }

                itemRepository.save(item);

                // Create consumption records if daily data exists
                for (int day = 1; day <= 31; day++) {
                    String dayKey = "day_" + day;
                    if (row.containsKey(dayKey) && row.get(dayKey) != null) {
                        BigDecimal dailyConsumption = new BigDecimal(row.get(dayKey).toString());
                        if (dailyConsumption.compareTo(BigDecimal.ZERO) > 0) {
                            LocalDate consumptionDate = LocalDate.now().withDayOfMonth(day);

                            Optional<ConsumptionRecord> existingRecord = consumptionRecordRepository
                                    .findByItemAndConsumptionDate(item, consumptionDate);

                            ConsumptionRecord record;
                            if (existingRecord.isPresent()) {
                                record = existingRecord.get();
                            } else {
                                record = new ConsumptionRecord(item, consumptionDate, openingStock);
                            }

                            record.setConsumedQuantity(dailyConsumption);
                            if (day == 1) {
                                record.setReceivedQuantity(receivedStock);
                            }
                            consumptionRecordRepository.save(record);
                        }
                    }
                }

            } catch (Exception e) {
                failed++;
                errors.add("Row error: " + e.getMessage());
                logger.error("Failed to import row: {}", e.getMessage());
            }
        }

        result.put("imported", imported);
        result.put("updated", updated);
        result.put("failed", failed);
        result.put("errors", errors);
        result.put("timestamp", LocalDateTime.now());

        return result;
    }
}