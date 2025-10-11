package com.bmsedge.inventory.repository;

import com.bmsedge.inventory.model.Category;
import com.bmsedge.inventory.model.Item;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface ItemRepository extends JpaRepository<Item, Long> {

    // Basic finders
    List<Item> findByCategory(Category category);
    List<Item> findByCreatedBy(Long createdBy);
    List<Item> findByItemNameContainingIgnoreCase(String itemName);

    // Category-based queries
    List<Item> findByCategoryId(Long categoryId);
    Page<Item> findByCategoryId(Long categoryId, Pageable pageable);

    @Query("SELECT i FROM Item i JOIN i.category c WHERE c.categoryName = :categoryName")
    List<Item> findByCategoryName(@Param("categoryName") String categoryName);

    // SKU-based queries
    @Query("SELECT i FROM Item i WHERE i.itemSku = :sku")
    List<Item> findByItemSku(@Param("sku") String sku);

    @Query("SELECT i FROM Item i WHERE i.itemName = :itemName AND i.itemSku = :sku")
    Optional<Item> findByItemNameAndItemSku(@Param("itemName") String itemName, @Param("sku") String sku);

    @Query("SELECT i FROM Item i WHERE LOWER(i.itemSku) LIKE LOWER(CONCAT('%', :sku, '%'))")
    List<Item> findByItemSkuContaining(@Param("sku") String sku);

    // Stock status queries
    @Query("SELECT i FROM Item i WHERE i.stockStatus = :status")
    List<Item> findByStockStatus(@Param("status") String status);

    @Query("SELECT i FROM Item i WHERE i.stockStatus = :status")
    Page<Item> findByStockStatus(@Param("status") String status, Pageable pageable);

    @Query("SELECT i FROM Item i WHERE i.stockStatus IN :statuses")
    List<Item> findByStockStatusIn(@Param("statuses") List<String> statuses);

    @Query("SELECT COUNT(i) FROM Item i WHERE i.stockStatus = :status")
    Long countByStockStatus(@Param("status") String status);

    // Low stock queries (using reorder level)
    @Query("SELECT i FROM Item i WHERE i.currentQuantity <= i.reorderLevel")
    List<Item> findLowStockItems();

    @Query("SELECT i FROM Item i WHERE i.currentQuantity <= :threshold")
    List<Item> findLowStockItems(@Param("threshold") Integer threshold);

    @Query("SELECT COUNT(i) FROM Item i WHERE i.currentQuantity <= i.reorderLevel")
    Long countLowStockItems();

    // Stock alert level queries
    @Query("SELECT i FROM Item i WHERE i.stockAlertLevel = :level")
    List<Item> findByStockAlertLevel(@Param("level") String level);

    @Query("SELECT COUNT(i) FROM Item i WHERE i.stockAlertLevel = :level")
    Long countByStockAlertLevel(@Param("level") String level);

    // Search queries
    @Query("SELECT i FROM Item i WHERE LOWER(i.itemName) LIKE LOWER(CONCAT('%', :searchTerm, '%')) " +
            "OR LOWER(i.itemDescription) LIKE LOWER(CONCAT('%', :searchTerm, '%')) " +
            "OR LOWER(i.itemSku) LIKE LOWER(CONCAT('%', :searchTerm, '%'))")
    List<Item> findByItemNameOrDescriptionContaining(@Param("searchTerm") String searchTerm);

    @Query("SELECT i FROM Item i WHERE LOWER(i.itemName) LIKE LOWER(CONCAT('%', :searchTerm, '%')) " +
            "OR LOWER(i.itemDescription) LIKE LOWER(CONCAT('%', :searchTerm, '%')) " +
            "OR LOWER(i.itemSku) LIKE LOWER(CONCAT('%', :searchTerm, '%'))")
    Page<Item> searchItems(@Param("searchTerm") String searchTerm, Pageable pageable);

    // Expiry date queries
    @Query("SELECT i FROM Item i WHERE i.expiryDate <= :date")
    List<Item> findExpiredItems(@Param("date") LocalDateTime date);

    @Query("SELECT i FROM Item i WHERE i.expiryDate BETWEEN :startDate AND :endDate")
    List<Item> findExpiringItems(@Param("startDate") LocalDateTime startDate,
                                 @Param("endDate") LocalDateTime endDate);

    @Query("SELECT COUNT(i) FROM Item i WHERE i.expiryDate <= :date")
    Long countExpiredItems(@Param("date") LocalDateTime date);

    @Query("SELECT COUNT(i) FROM Item i WHERE i.expiryDate BETWEEN :startDate AND :endDate")
    Long countExpiringItems(@Param("startDate") LocalDateTime startDate,
                            @Param("endDate") LocalDateTime endDate);

    // Date range queries
    @Query("SELECT i FROM Item i WHERE i.createdAt BETWEEN :startDate AND :endDate")
    List<Item> findItemsCreatedBetween(@Param("startDate") LocalDateTime startDate,
                                       @Param("endDate") LocalDateTime endDate);

    @Query("SELECT i FROM Item i WHERE i.updatedAt BETWEEN :startDate AND :endDate")
    List<Item> findItemsUpdatedBetween(@Param("startDate") LocalDateTime startDate,
                                       @Param("endDate") LocalDateTime endDate);

    // Inventory value queries
    @Query("SELECT SUM(i.totalValue) FROM Item i")
    BigDecimal getTotalInventoryValue();

    @Query("SELECT c.categoryName, SUM(i.totalValue), COUNT(i) " +
            "FROM Item i JOIN i.category c " +
            "GROUP BY c.id, c.categoryName")
    List<Object[]> getCategoryWiseValue();

    // Quantity queries
    @Query("SELECT SUM(i.currentQuantity) FROM Item i")
    Long getTotalQuantity();

    @Query("SELECT c.categoryName, SUM(i.currentQuantity) " +
            "FROM Item i JOIN i.category c " +
            "GROUP BY c.id, c.categoryName")
    List<Object[]> getCategoryWiseStock();

    @Query("SELECT SUM(i.currentQuantity) FROM Item i JOIN i.category c " +
            "WHERE c.categoryName = :categoryName")
    BigDecimal getTotalStockByCategory(@Param("categoryName") String categoryName);

    // Bin location queries
    @Query("SELECT i FROM Item i WHERE i.primaryBinId = :binId OR i.secondaryBinId = :binId")
    List<Item> findByBinId(@Param("binId") Long binId);

    @Query("SELECT i FROM Item i WHERE i.primaryBinId = :binId")
    List<Item> findByPrimaryBinId(@Param("binId") Long binId);

    @Query("SELECT i FROM Item i WHERE i.secondaryBinId = :binId")
    List<Item> findBySecondaryBinId(@Param("binId") Long binId);

    // Volatility queries
    @Query("SELECT i FROM Item i WHERE i.volatilityClassification = :classification")
    List<Item> findByVolatilityClassification(@Param("classification") String classification);

    @Query("SELECT i FROM Item i WHERE i.isHighlyVolatile = true")
    List<Item> findHighlyVolatileItems();

    // Coverage queries
    @Query("SELECT i FROM Item i WHERE i.coverageDays <= :days AND i.coverageDays > 0")
    List<Item> findItemsWithLowCoverage(@Param("days") Integer days);

    @Query("SELECT i FROM Item i WHERE i.expectedStockoutDate <= :date")
    List<Item> findItemsStockingOutBefore(@Param("date") java.time.LocalDate date);

    // Reorder suggestions
    @Query("SELECT i FROM Item i WHERE i.currentQuantity <= i.reorderLevel ORDER BY i.coverageDays ASC")
    List<Item> findItemsNeedingReorder();

    @Query("SELECT i FROM Item i WHERE i.currentQuantity <= i.reorderLevel ORDER BY i.coverageDays ASC")
    Page<Item> findItemsNeedingReorder(Pageable pageable);

    // Statistics queries
    @Query("SELECT i FROM Item i WHERE i.lastStatisticsUpdate IS NULL OR i.lastStatisticsUpdate < :date")
    List<Item> findItemsNeedingStatisticsUpdate(@Param("date") LocalDateTime date);

    // Trend analysis queries
    @Query("SELECT i FROM Item i WHERE i.trend = :trend")
    List<Item> findByTrend(@Param("trend") String trend);

    @Query("SELECT i FROM Item i WHERE i.consumptionPattern = :pattern")
    List<Item> findByConsumptionPattern(@Param("pattern") String pattern);

    // Combined queries for dashboard
    @Query("SELECT i FROM Item i WHERE " +
            "(i.stockStatus = 'CRITICAL' OR i.stockStatus = 'OUT_OF_STOCK') " +
            "OR i.currentQuantity <= i.reorderLevel " +
            "OR (i.expiryDate IS NOT NULL AND i.expiryDate <= :expiryDate)")
    List<Item> findItemsRequiringAttention(@Param("expiryDate") LocalDateTime expiryDate);

    // Price range queries
    @Query("SELECT i FROM Item i WHERE i.unitPrice BETWEEN :minPrice AND :maxPrice")
    List<Item> findByPriceRange(@Param("minPrice") BigDecimal minPrice,
                                @Param("maxPrice") BigDecimal maxPrice);

    // Quantity range queries
    @Query("SELECT i FROM Item i WHERE i.currentQuantity BETWEEN :minQty AND :maxQty")
    List<Item> findByQuantityRange(@Param("minQty") BigDecimal minQty,
                                   @Param("maxQty") BigDecimal maxQty);



    Optional<Object> findByItemNameIgnoreCaseAndItemSku(@NotBlank(message = "Item name is required") @Size(max = 100, message = "Item name must not exceed 100 characters") String itemName, @Size(max = 100, message = "Item SKU must not exceed 100 characters") String itemSku);
}