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
import java.math.RoundingMode;
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

    @Autowired
    private StockReceiptRepository stockReceiptRepository;

    @Autowired(required = false)
    private BinRepository binRepository;

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
            // 1. Check duplicate item name + SKU (handle null SKU)
            Optional<Item> existingItem;
            if (request.getItemSku() != null && !request.getItemSku().trim().isEmpty()) {
                existingItem = itemRepository.findByItemNameIgnoreCaseAndItemSku(
                        request.getItemName(), request.getItemSku());
            } else {
                existingItem = itemRepository.findByItemNameIgnoreCaseAndItemSkuIsNull(
                        request.getItemName());
            }

            if (existingItem.isPresent()) {
                throw new BusinessException("Item with name '" + request.getItemName() +
                        "' and SKU '" + request.getItemSku() + "' already exists");
            }

            // 2. Get category
            Category category = categoryRepository.findById(request.getCategoryId())
                    .orElseThrow(() -> new ResourceNotFoundException("Category not found with id: " + request.getCategoryId()));

            // 3. Build Item
            Item item = new Item();
            item.setItemName(request.getItemName());
            item.setItemSku(request.getItemSku());
            item.setItemDescription(request.getItemDescription());
            item.setCurrentQuantity(BigDecimal.valueOf(request.getCurrentQuantity()));
            item.setOpeningStock(BigDecimal.valueOf(request.getCurrentQuantity()));
            item.setClosingStock(BigDecimal.valueOf(request.getCurrentQuantity()));
            item.setReorderLevel(Optional.ofNullable(request.getReorderLevel()).orElse(BigDecimal.valueOf(10)));
            item.setReorderQuantity(Optional.ofNullable(request.getReorderQuantity()).orElse(BigDecimal.valueOf(50)));
            item.setUnitOfMeasurement(request.getUnitOfMeasurement());
            item.setUnitPrice(request.getUnitPrice());
            item.setCategory(category);
            item.setExpiryDate(request.getExpiryDate());
            item.setCreatedBy(userId);
            if (request.getPrimaryBinId() != null) item.setPrimaryBinId(request.getPrimaryBinId());
            if (request.getSecondaryBinId() != null) item.setSecondaryBinId(request.getSecondaryBinId());

            // 4. Update stock status
            item.updateStockStatus();

            Item savedItem = itemRepository.save(item);
            logger.info("Created item: {} (SKU: {})", savedItem.getItemName(), savedItem.getItemSku());

            return convertToResponse(savedItem);

        } catch (DataIntegrityViolationException e) {
            if (e.getMessage().contains("uk_item_name_sku")) {
                throw new BusinessException("Item with this name and SKU combination already exists");
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

            // Check for duplicate name + SKU (excluding current item)
            Optional<Item> duplicateItem = itemRepository.findAll().stream()
                    .filter(i -> !i.getId().equals(id) &&
                            i.getItemName().equalsIgnoreCase(request.getItemName()) &&
                            Objects.equals(i.getItemSku(), request.getItemSku()))
                    .findFirst();

            if (duplicateItem.isPresent()) {
                throw new BusinessException("Item with name '" + request.getItemName() +
                        "' and SKU '" + request.getItemSku() + "' already exists");
            }

            if (request.getCategoryId() != null) {
                Category category = categoryRepository.findById(request.getCategoryId())
                        .orElseThrow(() -> new ResourceNotFoundException("Category not found"));
                item.setCategory(category);
            }


            item.setItemName(request.getItemName());
            item.setItemSku(request.getItemSku());
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

            // Update bin locations if provided
            if (request.getPrimaryBinId() != null) {
                item.setPrimaryBinId(request.getPrimaryBinId());
            }
            if (request.getSecondaryBinId() != null) {
                item.setSecondaryBinId(request.getSecondaryBinId());
            }

            Item updatedItem = itemRepository.save(item);

            // Check for notifications after update
            if (notificationService != null) {
                notificationService.checkAndCreateAlerts(updatedItem);
            }

            return convertToResponse(updatedItem);
        } catch (DataIntegrityViolationException e) {
            if (e.getMessage().contains("uk_item_name_sku")) {
                throw new BusinessException("Item with this name and SKU combination already exists");
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
        BigDecimal previousQuantity = item.getCurrentQuantity();
        item.recordConsumption(quantity);

        // Create stock movement
        StockMovement movement = new StockMovement(
                item, "CONSUMPTION", LocalDate.now(), quantity, userId
        );
        movement.setDepartment(department);
        movement.setOpeningBalance(previousQuantity);
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
            consumptionRecord = new ConsumptionRecord(item, today, previousQuantity);
            consumptionRecord.setConsumedQuantity(quantity);
        }

        consumptionRecord.setClosingStock(item.getCurrentQuantity());
        consumptionRecord.setDepartment(department);
        consumptionRecordRepository.save(consumptionRecord);

        Item updatedItem = itemRepository.save(item);

        // Update statistics
        List<ConsumptionRecord> allRecords = consumptionRecordRepository.findByItem(updatedItem);
        calculateAndUpdateStatistics(updatedItem, allRecords);
        itemRepository.save(updatedItem);

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
     * Record receipt using StockReceipt entity
     */
    @Transactional
    public ItemResponse recordReceipt(Long itemId, BigDecimal quantity, BigDecimal unitPrice,
                                      String supplierName, String invoiceNumber,
                                      String batchNumber, LocalDate expiryDate, Long userId) {
        Item item = itemRepository.findById(itemId)
                .orElseThrow(() -> new ResourceNotFoundException("Item not found with id: " + itemId));

        BigDecimal previousQuantity = item.getCurrentQuantity();

        // Update item stock
        item.recordReceipt(quantity);
        if (unitPrice != null && unitPrice.compareTo(BigDecimal.ZERO) > 0) {
            item.setUnitPrice(unitPrice);
        }

        // Create stock receipt record
        StockReceipt receipt = new StockReceipt(item, LocalDate.now(), quantity);
        receipt.setUnitPrice(unitPrice);
        receipt.setSupplierName(supplierName);
        receipt.setInvoiceNumber(invoiceNumber);
        receipt.setBatchNumber(batchNumber);
        receipt.setExpiryDate(expiryDate);
        receipt.setReceivedBy(userId);
        stockReceiptRepository.save(receipt);

        // Create stock movement
        StockMovement movement = new StockMovement(
                item, "RECEIPT", LocalDate.now(), quantity, userId
        );
        movement.setReferenceNumber(invoiceNumber);
        movement.setUnitPrice(unitPrice);
        movement.setOpeningBalance(previousQuantity);
        movement.setClosingBalance(item.getCurrentQuantity());
        stockMovementRepository.save(movement);

        // Create or update consumption record with received quantity
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
            consumptionRecord = new ConsumptionRecord(item, today, previousQuantity);
            consumptionRecord.setReceivedQuantity(quantity);
        }

        consumptionRecord.setClosingStock(item.getCurrentQuantity());
        consumptionRecordRepository.save(consumptionRecord);

        Item updatedItem = itemRepository.save(item);

        // Update statistics
        List<ConsumptionRecord> allRecords = consumptionRecordRepository.findByItem(updatedItem);
        calculateAndUpdateStatistics(updatedItem, allRecords);
        itemRepository.save(updatedItem);

        logger.info("Recorded receipt of {} {} for item {} from supplier {}",
                quantity, item.getUnitOfMeasurement(), item.getItemName(), supplierName);

        return convertToResponse(updatedItem);
    }

    /**
     * Simplified recordReceipt for backward compatibility
     */
    @Transactional
    public ItemResponse recordReceipt(Long itemId, BigDecimal quantity, BigDecimal unitPrice,
                                      String referenceNumber, Long userId) {
        return recordReceipt(itemId, quantity, unitPrice, null, referenceNumber, null, null, userId);
    }

    /**
     * Get items by category with pagination
     */
    public Page<ItemResponse> getItemsByCategoryPaginated(Long categoryId, int page, int size,
                                                          String sortBy, String sortDirection) {
        categoryRepository.findById(categoryId)
                .orElseThrow(() -> new ResourceNotFoundException("Category not found with id: " + categoryId));

        Sort.Direction direction = sortDirection.equalsIgnoreCase("DESC") ?
                Sort.Direction.DESC : Sort.Direction.ASC;
        Pageable pageable = PageRequest.of(page, size, Sort.by(direction, sortBy));

        Page<Item> itemPage = itemRepository.findByCategoryId(categoryId, pageable);
        return itemPage.map(this::convertToResponse);
    }

    /**
     * Get items by category
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
     * Calculate and update all statistics for an item
     */
    private void calculateAndUpdateStatistics(Item item, List<ConsumptionRecord> records) {
        if (records == null || records.isEmpty()) {
            item.setAvgDailyConsumption(BigDecimal.ZERO);
            item.setStdDailyConsumption(BigDecimal.ZERO);
            item.setConsumptionCV(BigDecimal.ZERO);
            item.setCoverageDays(0);
            item.setVolatilityClassification("UNKNOWN");
            return;
        }

        try {
            // Filter out records with null or zero consumption
            List<BigDecimal> consumptionValues = records.stream()
                    .map(ConsumptionRecord::getConsumedQuantity)
                    .filter(Objects::nonNull)
                    .filter(val -> val.compareTo(BigDecimal.ZERO) > 0)
                    .collect(Collectors.toList());

            if (consumptionValues.isEmpty()) {
                item.setAvgDailyConsumption(BigDecimal.ZERO);
                item.setCoverageDays(0);
                return;
            }

            // Calculate average daily consumption
            BigDecimal sum = consumptionValues.stream()
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal avgConsumption = sum.divide(
                    BigDecimal.valueOf(consumptionValues.size()),
                    2,
                    RoundingMode.HALF_UP
            );
            item.setAvgDailyConsumption(avgConsumption);

            // Calculate standard deviation
            if (consumptionValues.size() > 1) {
                double mean = avgConsumption.doubleValue();
                double variance = consumptionValues.stream()
                        .mapToDouble(BigDecimal::doubleValue)
                        .map(val -> Math.pow(val - mean, 2))
                        .average()
                        .orElse(0.0);

                BigDecimal stdDev = BigDecimal.valueOf(Math.sqrt(variance))
                        .setScale(2, RoundingMode.HALF_UP);
                item.setStdDailyConsumption(stdDev);

                // Calculate coefficient of variation (CV)
                if (avgConsumption.compareTo(BigDecimal.ZERO) > 0) {
                    BigDecimal cv = stdDev.divide(avgConsumption, 4, RoundingMode.HALF_UP);
                    item.setConsumptionCV(cv);

                    // Classify volatility based on CV
                    if (cv.compareTo(BigDecimal.valueOf(0.5)) > 0) {
                        item.setVolatilityClassification("VERY_HIGH");
                    } else if (cv.compareTo(BigDecimal.valueOf(0.3)) > 0) {
                        item.setVolatilityClassification("HIGH");
                    } else if (cv.compareTo(BigDecimal.valueOf(0.15)) > 0) {
                        item.setVolatilityClassification("MEDIUM");
                    } else {
                        item.setVolatilityClassification("LOW");
                    }
                }
            } else {
                item.setStdDailyConsumption(BigDecimal.ZERO);
                item.setConsumptionCV(BigDecimal.ZERO);
                item.setVolatilityClassification("LOW");
            }

            // Calculate coverage days
            if (avgConsumption.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal currentQty = item.getCurrentQuantity() != null ?
                        item.getCurrentQuantity() : BigDecimal.ZERO;

                int coverageDays = currentQty.divide(avgConsumption, 0, RoundingMode.UP).intValue();
                item.setCoverageDays(coverageDays);

                if (coverageDays > 0) {
                    item.setExpectedStockoutDate(LocalDate.now().plusDays(coverageDays));
                }
            } else {
                item.setCoverageDays(0);
                item.setExpectedStockoutDate(null);
            }

            item.setLastStatisticsUpdate(LocalDateTime.now());

            logger.info("Updated statistics for item {}: avgDaily={}, coverage={} days",
                    item.getItemName(), item.getAvgDailyConsumption(), item.getCoverageDays());

        } catch (Exception e) {
            logger.error("Error calculating statistics for item {}: {}",
                    item.getId(), e.getMessage());
        }
    }

    /**
     * Convert Item to ItemResponse with complete calculations
     */
    private ItemResponse convertToResponse(Item item) {
        // Get bin codes if bins are assigned
        String primaryBinCode = null;
        String secondaryBinCode = null;

        if (binRepository != null) {
            if (item.getPrimaryBinId() != null) {
                primaryBinCode = binRepository.findById(item.getPrimaryBinId())
                        .map(Bin::getBinCode)
                        .orElse(null);
            }
            if (item.getSecondaryBinId() != null) {
                secondaryBinCode = binRepository.findById(item.getSecondaryBinId())
                        .map(Bin::getBinCode)
                        .orElse(null);
            }
        }

        // Get consumption records
        List<ConsumptionRecord> allRecords = consumptionRecordRepository.findByItem(item);

        // Calculate totals from consumption records
        BigDecimal totalReceivedStock = allRecords.stream()
                .map(ConsumptionRecord::getReceivedQuantity)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalConsumedStock = allRecords.stream()
                .map(ConsumptionRecord::getConsumedQuantity)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // Calculate current month's stock (last 30 days)
        LocalDate thirtyDaysAgo = LocalDate.now().minusDays(30);
        List<ConsumptionRecord> recentRecords = allRecords.stream()
                .filter(r -> r.getConsumptionDate() != null &&
                        r.getConsumptionDate().isAfter(thirtyDaysAgo))
                .collect(Collectors.toList());

        BigDecimal monthReceivedStock = recentRecords.stream()
                .map(ConsumptionRecord::getReceivedQuantity)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal monthConsumedStock = recentRecords.stream()
                .map(ConsumptionRecord::getConsumedQuantity)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // Calculate and update statistics
        calculateAndUpdateStatistics(item, allRecords);
        try {
            itemRepository.save(item);
        } catch (Exception e) {
            logger.error("Could not save statistics for item {}: {}", item.getId(), e.getMessage());
        }

        // Convert consumption records to response format
        List<Map<String, Object>> consumptionRecordsData = allRecords.stream()
                .sorted(Comparator.comparing(ConsumptionRecord::getConsumptionDate).reversed())
                .limit(50)
                .map(record -> {
                    Map<String, Object> recordMap = new HashMap<>();
                    recordMap.put("id", record.getId());
                    recordMap.put("date", record.getConsumptionDate());
                    recordMap.put("consumedQuantity", record.getConsumedQuantity());
                    recordMap.put("receivedQuantity", record.getReceivedQuantity());
                    recordMap.put("openingStock", record.getOpeningStock());
                    recordMap.put("closingStock", record.getClosingStock());
                    recordMap.put("department", record.getDepartment());
                    recordMap.put("notes", record.getNotes());
                    return recordMap;
                })
                .collect(Collectors.toList());

        // Build response
        ItemResponse response = new ItemResponse();
        response.setId(item.getId());
        response.setItemName(item.getItemName());
        response.setItemSku(item.getItemSku());
        response.setItemDescription(item.getItemDescription());

        // Ensure stock status is always set
        if (item.getStockStatus() == null) {
            item.updateStockStatus();
            try {
                itemRepository.save(item);
            } catch (Exception e) {
                logger.debug("Could not update stock status for item {}", item.getId());
            }
        }

        // Stock fields
        response.setCurrentQuantity(item.getCurrentQuantity() != null ?
                item.getCurrentQuantity().intValue() : 0);
        response.setOpeningStock(item.getOpeningStock() != null ?
                item.getOpeningStock().intValue() : 0);
        response.setClosingStock(item.getClosingStock() != null ?
                item.getClosingStock().intValue() : 0);

        // Calculated totals
        response.setTotalReceivedStock(totalReceivedStock);
        response.setTotalConsumedStock(totalConsumedStock);
        response.setMonthReceivedStock(monthReceivedStock);
        response.setMonthConsumedStock(monthConsumedStock);

        // Reorder fields
        response.setReorderLevel(item.getReorderLevel() != null ?
                item.getReorderLevel().intValue() : 10);
        response.setReorderQuantity(item.getReorderQuantity() != null ?
                item.getReorderQuantity().intValue() : 50);

        // Statistics fields
        response.setAvgDailyConsumption(item.getAvgDailyConsumption());
        response.setStdDailyConsumption(item.getStdDailyConsumption());
        response.setConsumptionCV(item.getConsumptionCV());
        response.setVolatilityClassification(item.getVolatilityClassification());
        response.setIsHighlyVolatile(
                "HIGH".equals(item.getVolatilityClassification()) ||
                        "VERY_HIGH".equals(item.getVolatilityClassification())
        );

        // Other fields
        response.setUnitOfMeasurement(item.getUnitOfMeasurement());
        response.setUnitPrice(item.getUnitPrice());
        response.setTotalValue(item.getTotalValue());
        response.setStockAlertLevel(item.getStockAlertLevel());
        response.setStockStatus(item.getStockStatus());
        response.setCoverageDays(item.getCoverageDays() != null ? item.getCoverageDays() : 0);
        response.setExpectedStockoutDate(item.getExpectedStockoutDate());

        // Category
        response.setCategoryId(item.getCategory() != null ? item.getCategory().getId() : null);
        response.setCategoryName(item.getCategory() != null ? item.getCategory().getCategoryName() : null);

        // Dates
        response.setExpiryDate(item.getExpiryDate());
        response.setLastReceivedDate(item.getLastReceivedDate());
        response.setLastConsumptionDate(item.getLastConsumptionDate());
        response.setLastStatisticsUpdate(item.getLastStatisticsUpdate());
        response.setCreatedAt(item.getCreatedAt());
        response.setUpdatedAt(item.getUpdatedAt());

        // Analytics fields
        response.setConsumptionPattern(item.getConsumptionPattern() != null ?
                item.getConsumptionPattern() : "UNKNOWN");
        response.setTrend(item.getTrend() != null ? item.getTrend() : "STABLE");
        response.setForecastNextPeriod(item.getForecastNextPeriod());
        response.setNeedsReorder(item.needsReorder());
        response.setIsCritical(item.getCurrentQuantity() != null &&
                item.getReorderLevel() != null &&
                item.getCurrentQuantity().compareTo(item.getReorderLevel()) <= 0);

        // Consumption records
        response.setConsumptionRecords(consumptionRecordsData);

        // Bin information
        response.setPrimaryBinCode(primaryBinCode);
        response.setSecondaryBinCode(secondaryBinCode);

        return response;
    }
}