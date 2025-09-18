package com.bmsedge.inventory.service;

import com.bmsedge.inventory.dto.ItemRequest;
import com.bmsedge.inventory.dto.ItemResponse;
import com.bmsedge.inventory.dto.CategoryResponse;
import com.bmsedge.inventory.exception.BusinessException;
import com.bmsedge.inventory.exception.ResourceNotFoundException;
import com.bmsedge.inventory.model.*;
import com.bmsedge.inventory.repository.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

@Service
@Transactional
public class ItemService {

    @Autowired
    private ItemRepository itemRepository;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private StockMovementRepository stockMovementRepository;

    @Autowired
    private ConsumptionRecordRepository consumptionRecordRepository;

    @Autowired
    private BinRepository binRepository;

    /**
     * Create a new item with all BMS tracking features
     */
    public ItemResponse createItem(ItemRequest itemRequest, Long userId) {
        // Validate category exists
        Category category = categoryRepository.findById(itemRequest.getCategoryId())
                .orElseThrow(() -> new ResourceNotFoundException("Category not found with ID: " + itemRequest.getCategoryId()));

        // Convert Integer to BigDecimal for backward compatibility
        BigDecimal currentQuantity = itemRequest.getCurrentQuantity() != null
                ? BigDecimal.valueOf(itemRequest.getCurrentQuantity())
                : BigDecimal.ZERO;

        BigDecimal maxStockLevel = itemRequest.getMaxStockLevel() != null
                ? BigDecimal.valueOf(itemRequest.getMaxStockLevel())
                : BigDecimal.valueOf(100);

        BigDecimal minStockLevel = itemRequest.getMinStockLevel() != null
                ? BigDecimal.valueOf(itemRequest.getMinStockLevel())
                : BigDecimal.valueOf(5);

        // Business validations
        if (minStockLevel.compareTo(maxStockLevel) >= 0) {
            throw new BusinessException("Minimum stock level must be less than maximum stock level");
        }

        if (currentQuantity.compareTo(BigDecimal.ZERO) < 0) {
            throw new BusinessException("Current quantity cannot be negative");
        }

        // Validate unit of measurement
        String unit = itemRequest.getUnitOfMeasurement();
        if (unit == null || unit.trim().isEmpty()) {
            unit = "pcs"; // Default fallback
        }

        // Handle expiry date - convert LocalDate to LocalDateTime if not null
        LocalDateTime expiryDateTime = null;
        if (itemRequest.getExpiryDate() != null) {
            expiryDateTime = itemRequest.getExpiryDate().atStartOfDay();
        }

        // Create new item with enhanced fields
        Item item = new Item(
                itemRequest.getItemName(),
                itemRequest.getItemDescription(),
                currentQuantity,
                maxStockLevel,
                minStockLevel,
                unit.trim(),
                expiryDateTime,
                category,
                userId
        );

        // Set additional BMS fields
        item.setItemCode(generateItemCode(category));
        item.setOpeningStock(currentQuantity);
        item.setClosingStock(currentQuantity);

        // Set old stock quantity if provided
        if (itemRequest.getOldStockQuantity() != null) {
            item.setOldStockQuantity(BigDecimal.valueOf(itemRequest.getOldStockQuantity()));
        }

        // Set pricing if available
        if (itemRequest.getUnitPrice() != null) {
            item.setUnitPrice(itemRequest.getUnitPrice());
            item.calculateTotalValue();
        }

        // Set reorder levels (default: reorder at 2x minimum, order up to 80% of max)
        item.setReorderLevel(minStockLevel.multiply(BigDecimal.valueOf(2)));
        item.setReorderQuantity(maxStockLevel.multiply(BigDecimal.valueOf(0.8))
                .subtract(minStockLevel.multiply(BigDecimal.valueOf(2))));

        // Assign bins if provided and valid
        if (itemRequest.getPrimaryBinId() != null && itemRequest.getPrimaryBinId() > 0) {
            validateBinExists(itemRequest.getPrimaryBinId());
            item.setPrimaryBinId(itemRequest.getPrimaryBinId());
        }
        if (itemRequest.getSecondaryBinId() != null && itemRequest.getSecondaryBinId() > 0) {
            validateBinExists(itemRequest.getSecondaryBinId());
            item.setSecondaryBinId(itemRequest.getSecondaryBinId());
        }

        // Calculate initial metrics
        item.updateStockAlertLevel();
        item.setLastReceivedDate(LocalDateTime.now());

        // Save item
        Item savedItem = itemRepository.save(item);

        // Create initial stock movement record
        createStockMovement(savedItem, "RECEIPT", currentQuantity, userId, "Initial stock");

        // Create initial consumption record for today
        createInitialConsumptionRecord(savedItem);

        // Calculate average daily consumption if historical data exists
        updateAverageDailyConsumption(savedItem);

        return convertToItemResponse(savedItem);
    }

    /**
     * Get all items with enhanced BMS fields
     */
    @Transactional(readOnly = true)
    public List<ItemResponse> getAllItems() {
        List<Item> items = itemRepository.findAll();

        // Update metrics for all items before returning
        items.forEach(this::updateItemMetrics);

        return items.stream()
                .map(this::convertToItemResponse)
                .collect(Collectors.toList());
    }

    /**
     * Get item by ID with all calculations
     */
    @Transactional(readOnly = true)
    public ItemResponse getItemById(Long id) {
        Item item = itemRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Item not found with ID: " + id));

        // Update metrics before returning
        updateItemMetrics(item);

        return convertToItemResponse(item);
    }

    /**
     * Get items by category
     */
    @Transactional(readOnly = true)
    public List<ItemResponse> getItemsByCategory(Long categoryId) {
        Category category = categoryRepository.findById(categoryId)
                .orElseThrow(() -> new ResourceNotFoundException("Category not found with ID: " + categoryId));

        List<Item> items = itemRepository.findByCategory(category);

        // Update metrics for all items
        items.forEach(this::updateItemMetrics);

        return items.stream()
                .map(this::convertToItemResponse)
                .collect(Collectors.toList());
    }

    /**
     * Search items by name
     */
    @Transactional(readOnly = true)
    public List<ItemResponse> searchItems(String query) {
        List<Item> items = itemRepository.findByItemNameContainingIgnoreCase(query);
        return items.stream()
                .map(this::convertToItemResponse)
                .collect(Collectors.toList());
    }

    /**
     * Get low stock items based on threshold or alert level
     */
    @Transactional(readOnly = true)
    public List<ItemResponse> getLowStockItems(Integer threshold) {
        List<Item> items;

        if (threshold != null) {
            // Get items below specific threshold
            items = itemRepository.findLowStockItems(threshold);
        } else {
            // Get items with HIGH or MEDIUM alert levels
            List<Item> highRisk = itemRepository.findByStockAlertLevel("HIGH");
            List<Item> mediumRisk = itemRepository.findByStockAlertLevel("MEDIUM");
            items = new ArrayList<>();
            items.addAll(highRisk);
            items.addAll(mediumRisk);
        }

        return items.stream()
                .map(this::convertToItemResponse)
                .collect(Collectors.toList());
    }

    /**
     * Get items that are expiring within specified days
     */
    @Transactional(readOnly = true)
    public List<ItemResponse> getExpiringItems(int days) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime futureDate = now.plusDays(days);

        List<Item> expiringItems = itemRepository.findExpiringItems(now, futureDate);

        return expiringItems.stream()
                .map(this::convertToItemResponse)
                .collect(Collectors.toList());
    }

    /**
     * Get items that have already expired
     */
    @Transactional(readOnly = true)
    public List<ItemResponse> getExpiredItems() {
        List<Item> expiredItems = itemRepository.findExpiredItems(LocalDateTime.now());

        return expiredItems.stream()
                .map(this::convertToItemResponse)
                .collect(Collectors.toList());
    }

    /**
     * Update an existing item
     */
    public ItemResponse updateItem(Long id, ItemRequest itemRequest, Long userId) {
        Item existingItem = itemRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Item not found with ID: " + id));

        // Handle category change if needed
        Category category = existingItem.getCategory();
        if (!existingItem.getCategory().getId().equals(itemRequest.getCategoryId())) {
            category = categoryRepository.findById(itemRequest.getCategoryId())
                    .orElseThrow(() -> new ResourceNotFoundException("Category not found with ID: " + itemRequest.getCategoryId()));
        }

        // Convert quantities to BigDecimal
        BigDecimal newQuantity = itemRequest.getCurrentQuantity() != null
                ? BigDecimal.valueOf(itemRequest.getCurrentQuantity())
                : existingItem.getCurrentQuantity();

        BigDecimal maxStockLevel = itemRequest.getMaxStockLevel() != null
                ? BigDecimal.valueOf(itemRequest.getMaxStockLevel())
                : existingItem.getMaxStockLevel();

        BigDecimal minStockLevel = itemRequest.getMinStockLevel() != null
                ? BigDecimal.valueOf(itemRequest.getMinStockLevel())
                : existingItem.getMinStockLevel();

        // Business validations
        if (minStockLevel.compareTo(maxStockLevel) >= 0) {
            throw new BusinessException("Minimum stock level must be less than maximum stock level");
        }

        if (newQuantity.compareTo(BigDecimal.ZERO) < 0) {
            throw new BusinessException("Current quantity cannot be negative");
        }

        // Track quantity change for stock movement
        BigDecimal quantityChange = newQuantity.subtract(existingItem.getCurrentQuantity());

        // Update basic fields
        existingItem.setItemName(itemRequest.getItemName());
        existingItem.setItemDescription(itemRequest.getItemDescription());
        existingItem.setCurrentQuantity(newQuantity);
        existingItem.setClosingStock(newQuantity);

        if (itemRequest.getOldStockQuantity() != null) {
            existingItem.setOldStockQuantity(BigDecimal.valueOf(itemRequest.getOldStockQuantity()));
        }

        existingItem.setMaxStockLevel(maxStockLevel);
        existingItem.setMinStockLevel(minStockLevel);

        String unit = itemRequest.getUnitOfMeasurement();
        if (unit != null && !unit.trim().isEmpty()) {
            existingItem.setUnitOfMeasurement(unit.trim());
        }

        // Handle expiry date - convert LocalDate to LocalDateTime if not null
        if (itemRequest.getExpiryDate() != null) {
            existingItem.setExpiryDate(itemRequest.getExpiryDate().atStartOfDay());
        } else {
            existingItem.setExpiryDate(null);
        }

        existingItem.setCategory(category);

        // Update pricing if provided
        if (itemRequest.getUnitPrice() != null) {
            existingItem.setUnitPrice(itemRequest.getUnitPrice());
        }

        // Update bins if provided and valid
        if (itemRequest.getPrimaryBinId() != null && itemRequest.getPrimaryBinId() > 0) {
            validateBinExists(itemRequest.getPrimaryBinId());
            existingItem.setPrimaryBinId(itemRequest.getPrimaryBinId());
        }
        if (itemRequest.getSecondaryBinId() != null && itemRequest.getSecondaryBinId() > 0) {
            validateBinExists(itemRequest.getSecondaryBinId());
            existingItem.setSecondaryBinId(itemRequest.getSecondaryBinId());
        }

        // Recalculate metrics
        existingItem.updateStockAlertLevel();
        existingItem.calculateTotalValue();
        existingItem.calculateCoverageDays();
        existingItem.setUpdatedAt(LocalDateTime.now());

        // Create stock movement record if quantity changed
        if (quantityChange.compareTo(BigDecimal.ZERO) != 0) {
            String movementType = quantityChange.compareTo(BigDecimal.ZERO) > 0 ? "ADJUSTMENT_IN" : "ADJUSTMENT_OUT";
            createStockMovement(existingItem, movementType, quantityChange.abs(), userId, "Manual adjustment");
        }

        Item updatedItem = itemRepository.save(existingItem);

        // Update consumption metrics
        updateAverageDailyConsumption(updatedItem);

        return convertToItemResponse(updatedItem);
    }

    /**
     * Delete an item
     */
    public void deleteItem(Long id, Long userId) {
        Item item = itemRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Item not found with ID: " + id));

        // Create final stock movement record before deletion
        if (item.getCurrentQuantity().compareTo(BigDecimal.ZERO) > 0) {
            createStockMovement(item, "WRITE_OFF", item.getCurrentQuantity(), userId, "Item deleted");
        }

        itemRepository.delete(item);
    }

    /**
     * Record stock consumption
     */
    @Transactional
    public ItemResponse recordConsumption(Long itemId, BigDecimal quantity, String department, Long userId) {
        Item item = itemRepository.findById(itemId)
                .orElseThrow(() -> new ResourceNotFoundException("Item not found with ID: " + itemId));

        if (quantity.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("Consumption quantity must be positive");
        }

        if (quantity.compareTo(item.getCurrentQuantity()) > 0) {
            throw new BusinessException("Insufficient stock. Available: " + item.getCurrentQuantity());
        }

        // Update item quantity
        BigDecimal newQuantity = item.getCurrentQuantity().subtract(quantity);
        item.setCurrentQuantity(newQuantity);
        item.setClosingStock(newQuantity);
        item.setLastConsumptionDate(LocalDateTime.now());

        // Create stock movement record
        StockMovement movement = new StockMovement(item, "CONSUMPTION", LocalDate.now(), quantity, userId);
        movement.setDepartment(department);
        movement.setOpeningBalance(item.getCurrentQuantity().add(quantity));
        movement.setClosingBalance(newQuantity);
        stockMovementRepository.save(movement);

        // Update or create consumption record for today
        LocalDate today = LocalDate.now();
        ConsumptionRecord consumptionRecord = consumptionRecordRepository
                .findByItemIdAndConsumptionDate(itemId, today)
                .orElse(new ConsumptionRecord(item, today, item.getOpeningStock()));

        consumptionRecord.setConsumedQuantity(consumptionRecord.getConsumedQuantity().add(quantity));
        consumptionRecord.setClosingStock(newQuantity);
        consumptionRecord.setDepartment(department);
        consumptionRecordRepository.save(consumptionRecord);

        // Update metrics
        item.updateStockAlertLevel();
        item.calculateTotalValue();
        updateAverageDailyConsumption(item);

        Item savedItem = itemRepository.save(item);
        return convertToItemResponse(savedItem);
    }

    /**
     * Record stock receipt
     */
    @Transactional
    public ItemResponse recordReceipt(Long itemId, BigDecimal quantity, BigDecimal unitPrice,
                                      String referenceNumber, Long userId) {
        Item item = itemRepository.findById(itemId)
                .orElseThrow(() -> new ResourceNotFoundException("Item not found with ID: " + itemId));

        if (quantity.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("Receipt quantity must be positive");
        }

        // Update item quantity and price
        BigDecimal oldQuantity = item.getCurrentQuantity();
        BigDecimal newQuantity = oldQuantity.add(quantity);
        item.setCurrentQuantity(newQuantity);
        item.setClosingStock(newQuantity);
        item.setLastReceivedDate(LocalDateTime.now());

        if (unitPrice != null && unitPrice.compareTo(BigDecimal.ZERO) > 0) {
            item.setUnitPrice(unitPrice);
        }

        // Create stock movement record
        StockMovement movement = new StockMovement(item, "RECEIPT", LocalDate.now(), quantity, userId);
        movement.setReferenceNumber(referenceNumber);
        movement.setOpeningBalance(oldQuantity);
        movement.setClosingBalance(newQuantity);
        movement.setUnitPrice(unitPrice);
        stockMovementRepository.save(movement);

        // Update consumption record for today
        LocalDate today = LocalDate.now();
        ConsumptionRecord consumptionRecord = consumptionRecordRepository
                .findByItemIdAndConsumptionDate(itemId, today)
                .orElse(new ConsumptionRecord(item, today, oldQuantity));

        consumptionRecord.setReceivedQuantity(consumptionRecord.getReceivedQuantity().add(quantity));
        consumptionRecord.setClosingStock(newQuantity);
        consumptionRecordRepository.save(consumptionRecord);

        // Update metrics
        item.updateStockAlertLevel();
        item.calculateTotalValue();
        item.calculateCoverageDays();

        Item savedItem = itemRepository.save(item);
        return convertToItemResponse(savedItem);
    }

    // Helper methods

    private String generateItemCode(Category category) {
        String prefix = category.getCategoryName().length() >= 3
                ? category.getCategoryName().substring(0, 3).toUpperCase()
                : category.getCategoryName().toUpperCase();

        long count = itemRepository.count() + 1;
        return String.format("%s-%04d", prefix, count);
    }

    private void validateBinExists(Long binId) {
        if (!binRepository.existsById(binId)) {
            throw new ResourceNotFoundException("Bin not found with ID: " + binId);
        }
    }

    private void createStockMovement(Item item, String movementType, BigDecimal quantity,
                                     Long userId, String notes) {
        StockMovement movement = new StockMovement(item, movementType, LocalDate.now(), quantity, userId);
        movement.setNotes(notes);
        movement.setOpeningBalance(item.getCurrentQuantity().subtract(quantity));
        movement.setClosingBalance(item.getCurrentQuantity());
        movement.setUnitPrice(item.getUnitPrice());
        stockMovementRepository.save(movement);
    }

    private void createInitialConsumptionRecord(Item item) {
        LocalDate today = LocalDate.now();
        if (!consumptionRecordRepository.findByItemIdAndConsumptionDate(item.getId(), today).isPresent()) {
            ConsumptionRecord record = new ConsumptionRecord(item, today, item.getCurrentQuantity());
            record.setClosingStock(item.getCurrentQuantity());
            consumptionRecordRepository.save(record);
        }
    }

    private void updateAverageDailyConsumption(Item item) {
        LocalDate thirtyDaysAgo = LocalDate.now().minusDays(30);
        BigDecimal avgConsumption = consumptionRecordRepository
                .getAverageDailyConsumption(item.getId(), thirtyDaysAgo);

        if (avgConsumption != null) {
            item.setAvgDailyConsumption(avgConsumption);
            item.calculateCoverageDays();
        }
    }

    private void updateItemMetrics(Item item) {
        updateAverageDailyConsumption(item);
        item.updateStockAlertLevel();
        item.calculateTotalValue();
        item.calculateCoverageDays();
    }

    private ItemResponse convertToItemResponse(Item item) {
        // Get bin codes if bins are assigned
        AtomicReference<String> primaryBinCode = new AtomicReference<>();
        AtomicReference<String> secondaryBinCode = new AtomicReference<>();

        if (item.getPrimaryBinId() != null) {
            binRepository.findById(item.getPrimaryBinId())
                    .ifPresent(bin -> primaryBinCode.set(bin.getBinCode()));
        }

        if (item.getSecondaryBinId() != null) {
            binRepository.findById(item.getSecondaryBinId())
                    .ifPresent(bin -> secondaryBinCode.set(bin.getBinCode()));
        }

        CategoryResponse categoryResponse = null;
        if (item.getCategory() != null) {
            categoryResponse = new CategoryResponse(
                    item.getCategory().getId(),
                    item.getCategory().getCategoryName(),
                    item.getCategory().getCategoryDescription(),
                    item.getCategory().getCreatedBy(),
                    item.getCategory().getCreatedAt(),
                    item.getCategory().getUpdatedAt(),
                    null
            );
        }

        // Calculate percentage of stock
        BigDecimal percentageOfStock = BigDecimal.ZERO;
        if (item.getMaxStockLevel() != null && item.getMaxStockLevel().compareTo(BigDecimal.ZERO) > 0) {
            percentageOfStock = item.getCurrentQuantity()
                    .divide(item.getMaxStockLevel(), 2, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100));
        }

        return new ItemResponse.Builder()
                .id(item.getId())
                .itemCode(item.getItemCode())
                .itemName(item.getItemName())
                .itemDescription(item.getItemDescription())
                .currentQuantity(item.getCurrentQuantity())
                .openingStock(item.getOpeningStock())
                .closingStock(item.getClosingStock())
                .oldStockQuantity(item.getOldStockQuantity())
                .maxStockLevel(item.getMaxStockLevel())
                .minStockLevel(item.getMinStockLevel())
                .reorderLevel(item.getReorderLevel())
                .reorderQuantity(item.getReorderQuantity())
                .unitOfMeasurement(item.getUnitOfMeasurement())
                .unitPrice(item.getUnitPrice())
                .totalValue(item.getTotalValue())
                .avgDailyConsumption(item.getAvgDailyConsumption())
                .coverageDays(item.getCoverageDays())
                .stockAlertLevel(item.getStockAlertLevel())
                .expectedStockoutDate(item.getExpectedStockoutDate())
                .primaryBinId(item.getPrimaryBinId())
                .secondaryBinId(item.getSecondaryBinId())
                .primaryBinCode(primaryBinCode.get())
                .secondaryBinCode(secondaryBinCode.get())
                .expiryDate(item.getExpiryDate())
                .lastReceivedDate(item.getLastReceivedDate())
                .lastConsumptionDate(item.getLastConsumptionDate())
                .qrCodePath(item.getQrCodePath())
                .qrCodeData(item.getQrCodeData())
                .category(categoryResponse)
                .createdBy(item.getCreatedBy())
                .createdAt(item.getCreatedAt())
                .updatedAt(item.getUpdatedAt())
                .needsReorder(item.needsReorder())
                .isExpired(item.isExpired())
                .isExpiringSoon(item.isExpiringSoon(7))
                .percentageOfStock(percentageOfStock)
                .build();
    }
}