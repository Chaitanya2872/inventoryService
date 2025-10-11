package com.bmsedge.inventory.service;

import com.bmsedge.inventory.dto.ItemRequest;
import com.bmsedge.inventory.dto.ItemResponse;
import com.bmsedge.inventory.exception.BusinessException;
import com.bmsedge.inventory.model.Category;
import com.bmsedge.inventory.model.Item;
import com.bmsedge.inventory.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for ItemService with SKU and Stock Status support
 */
class ItemServiceTest {

    @Mock
    private ItemRepository itemRepository;

    @Mock
    private CategoryRepository categoryRepository;

    @Mock
    private ConsumptionRecordRepository consumptionRecordRepository;

    @Mock
    private StockReceiptRepository stockReceiptRepository;

    @Mock
    private StockMovementRepository stockMovementRepository;

    @InjectMocks
    private ItemService itemService;

    private Category testCategory;
    private Long userId = 1L;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);

        testCategory = new Category();
        testCategory.setId(1L);
        testCategory.setCategoryName("Test Category");
    }

    @Test
    @DisplayName("Should create item with SKU successfully")
    void testCreateItemWithSku() {
        // Arrange
        ItemRequest request = new ItemRequest();
        request.setItemName("Pril-Dishwash");
        request.setItemSku("125ml");
        request.setCategoryId(1L);
        request.setCurrentQuantity(50);
        request.setReorderLevel(BigDecimal.valueOf(10));
        request.setUnitOfMeasurement("Bottle");
        request.setUnitPrice(BigDecimal.valueOf(17.00));

        when(categoryRepository.findById(1L)).thenReturn(Optional.of(testCategory));
        when(itemRepository.findAll()).thenReturn(Arrays.asList());
        when(itemRepository.save(any(Item.class))).thenAnswer(invocation -> {
            Item item = invocation.getArgument(0);
            item.setId(1L);
            return item;
        });
        when(consumptionRecordRepository.findByItem(any(Item.class))).thenReturn(Arrays.asList());

        // Act
        ItemResponse response = itemService.createItem(request, userId);

        // Assert
        assertNotNull(response);
        assertEquals("Pril-Dishwash", response.getItemName());
        assertEquals("125ml", response.getItemSku());
        assertEquals("Pril-Dishwash (125ml)", response.getFullDisplayName());
        verify(itemRepository, times(2)).save(any(Item.class)); // Once for create, once for stats update
    }

    @Test
    @DisplayName("Should create item without SKU successfully")
    void testCreateItemWithoutSku() {
        // Arrange
        ItemRequest request = new ItemRequest();
        request.setItemName("Generic Item");
        request.setItemSku(null);
        request.setCategoryId(1L);
        request.setCurrentQuantity(100);
        request.setUnitOfMeasurement("pcs");

        when(categoryRepository.findById(1L)).thenReturn(Optional.of(testCategory));
        when(itemRepository.findAll()).thenReturn(Arrays.asList());
        when(itemRepository.save(any(Item.class))).thenAnswer(invocation -> {
            Item item = invocation.getArgument(0);
            item.setId(2L);
            return item;
        });
        when(consumptionRecordRepository.findByItem(any(Item.class))).thenReturn(Arrays.asList());

        // Act
        ItemResponse response = itemService.createItem(request, userId);

        // Assert
        assertNotNull(response);
        assertEquals("Generic Item", response.getItemName());
        assertNull(response.getItemSku());
        assertEquals("Generic Item", response.getFullDisplayName());
    }

    @Test
    @DisplayName("Should throw exception for duplicate item name and SKU")
    void testCreateItemWithDuplicateNameAndSku() {
        // Arrange
        ItemRequest request = new ItemRequest();
        request.setItemName("Pril-Dishwash");
        request.setItemSku("125ml");
        request.setCategoryId(1L);
        request.setCurrentQuantity(50);

        Item existingItem = new Item();
        existingItem.setId(1L);
        existingItem.setItemName("Pril-Dishwash");
        existingItem.setItemSku("125ml");
        existingItem.setCategory(testCategory);

        when(categoryRepository.findById(1L)).thenReturn(Optional.of(testCategory));
        when(itemRepository.findAll()).thenReturn(Arrays.asList(existingItem));

        // Act & Assert
        BusinessException exception = assertThrows(BusinessException.class, () -> {
            itemService.createItem(request, userId);
        });

        assertTrue(exception.getMessage().contains("already exists"));
    }

    @Test
    @DisplayName("Should allow same item name with different SKU")
    void testCreateItemSameNameDifferentSku() {
        // Arrange
        ItemRequest request = new ItemRequest();
        request.setItemName("Pril-Dishwash");
        request.setItemSku("500ml"); // Different SKU
        request.setCategoryId(1L);
        request.setCurrentQuantity(30);
        request.setUnitOfMeasurement("Bottle");

        Item existingItem = new Item();
        existingItem.setId(1L);
        existingItem.setItemName("Pril-Dishwash");
        existingItem.setItemSku("125ml"); // Existing SKU
        existingItem.setCategory(testCategory);

        when(categoryRepository.findById(1L)).thenReturn(Optional.of(testCategory));
        when(itemRepository.findAll()).thenReturn(Arrays.asList(existingItem));
        when(itemRepository.save(any(Item.class))).thenAnswer(invocation -> {
            Item item = invocation.getArgument(0);
            item.setId(2L);
            return item;
        });
        when(consumptionRecordRepository.findByItem(any(Item.class))).thenReturn(Arrays.asList());

        // Act
        ItemResponse response = itemService.createItem(request, userId);

        // Assert
        assertNotNull(response);
        assertEquals("Pril-Dishwash", response.getItemName());
        assertEquals("500ml", response.getItemSku());
        assertNotEquals(existingItem.getItemSku(), response.getItemSku());
    }

    @Test
    @DisplayName("Should calculate stock status correctly - IN_STOCK")
    void testStockStatusInStock() {
        // Arrange
        Item item = new Item();
        item.setId(1L);
        item.setItemName("Test Item");
        item.setCurrentQuantity(BigDecimal.valueOf(100));
        item.setReorderLevel(BigDecimal.valueOf(20));
        item.setCategory(testCategory);

        // Act
        item.updateStockStatus();

        // Assert
        assertEquals("IN_STOCK", item.getStockStatus());
        assertFalse(item.isOutOfStock());
        assertFalse(item.isCriticallyLow());
    }

    @Test
    @DisplayName("Should calculate stock status correctly - LOW_STOCK")
    void testStockStatusLowStock() {
        // Arrange
        Item item = new Item();
        item.setId(1L);
        item.setItemName("Test Item");
        item.setCurrentQuantity(BigDecimal.valueOf(15)); // Below reorder level
        item.setReorderLevel(BigDecimal.valueOf(20));
        item.setCategory(testCategory);

        // Act
        item.updateStockStatus();

        // Assert
        assertEquals("LOW_STOCK", item.getStockStatus());
        assertFalse(item.isOutOfStock());
        assertFalse(item.isCriticallyLow());
    }

    @Test
    @DisplayName("Should calculate stock status correctly - CRITICAL")
    void testStockStatusCritical() {
        // Arrange
        Item item = new Item();
        item.setId(1L);
        item.setItemName("Test Item");
        item.setCurrentQuantity(BigDecimal.valueOf(8)); // Below 50% of reorder level
        item.setReorderLevel(BigDecimal.valueOf(20));
        item.setCategory(testCategory);

        // Act
        item.updateStockStatus();

        // Assert
        assertEquals("CRITICAL", item.getStockStatus());
        assertFalse(item.isOutOfStock());
        assertTrue(item.isCriticallyLow());
    }

    @Test
    @DisplayName("Should calculate stock status correctly - OUT_OF_STOCK")
    void testStockStatusOutOfStock() {
        // Arrange
        Item item = new Item();
        item.setId(1L);
        item.setItemName("Test Item");
        item.setCurrentQuantity(BigDecimal.ZERO);
        item.setReorderLevel(BigDecimal.valueOf(20));
        item.setCategory(testCategory);

        // Act
        item.updateStockStatus();

        // Assert
        assertEquals("OUT_OF_STOCK", item.getStockStatus());
        assertTrue(item.isOutOfStock());
        assertFalse(item.isCriticallyLow());
    }

    @Test
    @DisplayName("Should update stock status after consumption")
    void testStockStatusAfterConsumption() {
        // Arrange
        Item item = new Item();
        item.setId(1L);
        item.setItemName("Test Item");
        item.setCurrentQuantity(BigDecimal.valueOf(25));
        item.setReorderLevel(BigDecimal.valueOf(20));
        item.setCategory(testCategory);
        item.updateStockStatus();
        assertEquals("IN_STOCK", item.getStockStatus());

        // Act - Consume to bring below reorder level
        item.recordConsumption(BigDecimal.valueOf(10));

        // Assert
        assertEquals("LOW_STOCK", item.getStockStatus());
        assertEquals(BigDecimal.valueOf(15), item.getCurrentQuantity());
    }

    @Test
    @DisplayName("Should update stock status after receipt")
    void testStockStatusAfterReceipt() {
        // Arrange
        Item item = new Item();
        item.setId(1L);
        item.setItemName("Test Item");
        item.setCurrentQuantity(BigDecimal.valueOf(5));
        item.setReorderLevel(BigDecimal.valueOf(20));
        item.setCategory(testCategory);
        item.updateStockStatus();
        assertEquals("CRITICAL", item.getStockStatus());

        // Act - Receive stock
        item.recordReceipt(BigDecimal.valueOf(50));

        // Assert
        assertEquals("IN_STOCK", item.getStockStatus());
        assertEquals(BigDecimal.valueOf(55), item.getCurrentQuantity());
    }

    @Test
    @DisplayName("Should get full display name with SKU")
    void testGetFullDisplayName() {
        // Test with SKU
        Item itemWithSku = new Item();
        itemWithSku.setItemName("Pril-Dishwash");
        itemWithSku.setItemSku("125ml");
        assertEquals("Pril-Dishwash (125ml)", itemWithSku.getFullDisplayName());

        // Test without SKU
        Item itemWithoutSku = new Item();
        itemWithoutSku.setItemName("Generic Item");
        itemWithoutSku.setItemSku(null);
        assertEquals("Generic Item", itemWithoutSku.getFullDisplayName());

        // Test with empty SKU
        Item itemEmptySku = new Item();
        itemEmptySku.setItemName("Another Item");
        itemEmptySku.setItemSku("");
        assertEquals("Another Item", itemEmptySku.getFullDisplayName());
    }

    @Test
    @DisplayName("Should handle bin locations in item creation")
    void testItemCreationWithBinLocations() {
        // Arrange
        ItemRequest request = new ItemRequest();
        request.setItemName("Test Item");
        request.setCategoryId(1L);
        request.setCurrentQuantity(50);
        request.setUnitOfMeasurement("pcs");
        request.setPrimaryBinId(100L);
        request.setSecondaryBinId(101L);

        when(categoryRepository.findById(1L)).thenReturn(Optional.of(testCategory));
        when(itemRepository.findAll()).thenReturn(Arrays.asList());
        when(itemRepository.save(any(Item.class))).thenAnswer(invocation -> {
            Item item = invocation.getArgument(0);
            item.setId(1L);
            return item;
        });
        when(consumptionRecordRepository.findByItem(any(Item.class))).thenReturn(Arrays.asList());

        // Act
        ItemResponse response = itemService.createItem(request, userId);

        // Assert
        verify(itemRepository, times(2)).save(argThat(item ->
                item.getPrimaryBinId() != null &&
                        item.getPrimaryBinId().equals(100L) &&
                        item.getSecondaryBinId() != null &&
                        item.getSecondaryBinId().equals(101L)
        ));
    }

    @Test
    @DisplayName("Should validate ItemRequest with SKU")
    void testItemRequestValidation() {
        ItemRequest request = new ItemRequest();
        request.setItemName("Test Item");
        request.setItemSku("100ml");

        assertTrue(request.isValid());
        assertTrue(request.isVariantItem());
        assertEquals("Test Item (100ml)", request.getFullDisplayName());
    }

    @Test
    @DisplayName("Should validate ItemRequest without SKU")
    void testItemRequestValidationNoSku() {
        ItemRequest request = new ItemRequest();
        request.setItemName("Test Item");
        request.setItemSku(null);

        assertTrue(request.isValid());
        assertFalse(request.isVariantItem());
        assertEquals("Test Item", request.getFullDisplayName());
    }
}