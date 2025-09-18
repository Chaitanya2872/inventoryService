package com.bmsedge.inventory.repository;

import com.bmsedge.inventory.model.Category;
import com.bmsedge.inventory.model.Item;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface ItemRepository extends JpaRepository<Item, Long> {
    List<Item> findByCategory(Category category);
    List<Item> findByCreatedBy(Long createdBy);
    List<Item> findByItemNameContainingIgnoreCase(String itemName);

    @Query("SELECT i FROM Item i WHERE i.currentQuantity <= i.minStockLevel")
    List<Item> findLowStockItems();

    @Query("SELECT i FROM Item i WHERE i.currentQuantity <= :threshold")
    List<Item> findLowStockItems(@Param("threshold") Integer threshold);

    // FIXED: Changed i.description to i.itemDescription to match the actual field name
    @Query("SELECT i FROM Item i WHERE LOWER(i.itemName) LIKE LOWER(CONCAT('%', :searchTerm, '%')) OR LOWER(i.itemDescription) LIKE LOWER(CONCAT('%', :searchTerm, '%'))")
    List<Item> findByItemNameOrDescriptionContaining(@Param("searchTerm") String searchTerm);

    // Add these methods to existing ItemRepository
    @Query("SELECT i FROM Item i WHERE i.stockAlertLevel = :level")
    List<Item> findByStockAlertLevel(@Param("level") String level);

    @Query("SELECT SUM(i.totalValue) FROM Item i")
    BigDecimal getTotalInventoryValue();

    @Query("SELECT c.categoryName, SUM(i.totalValue), COUNT(i) " +
            "FROM Item i JOIN i.category c " +
            "GROUP BY c.id, c.categoryName")
    List<Object[]> getCategoryWiseValue();

    @Query("SELECT i FROM Item i WHERE i.expiryDate <= :date")
    List<Item> findExpiredItems(@Param("date") LocalDateTime date);

    @Query("SELECT i FROM Item i WHERE i.expiryDate BETWEEN :startDate AND :endDate")
    List<Item> findExpiringItems(@Param("startDate") LocalDateTime startDate, @Param("endDate") LocalDateTime endDate);

    @Query("SELECT SUM(i.currentQuantity) FROM Item i")
    Long getTotalQuantity();

    @Query("SELECT c.categoryName, SUM(i.currentQuantity) FROM Item i JOIN i.category c GROUP BY c.id, c.categoryName")
    List<Object[]> getCategoryWiseStock();

    @Query("SELECT i FROM Item i WHERE i.createdAt BETWEEN :startDate AND :endDate")
    List<Item> findItemsCreatedBetween(@Param("startDate") LocalDateTime startDate, @Param("endDate") LocalDateTime endDate);

    @Query("SELECT i FROM Item i WHERE i.updatedAt BETWEEN :startDate AND :endDate")
    List<Item> findItemsUpdatedBetween(@Param("startDate") LocalDateTime startDate, @Param("endDate") LocalDateTime endDate);

    // Additional useful queries
    @Query("SELECT COUNT(i) FROM Item i WHERE i.currentQuantity <= i.minStockLevel")
    Long countLowStockItems();

    @Query("SELECT COUNT(i) FROM Item i WHERE i.expiryDate <= :date")
    Long countExpiredItems(@Param("date") LocalDateTime date);

    @Query("SELECT COUNT(i) FROM Item i WHERE i.expiryDate BETWEEN :startDate AND :endDate")
    Long countExpiringItems(@Param("startDate") LocalDateTime startDate, @Param("endDate") LocalDateTime endDate);

    List<Item> findByCategoryId(Long categoryId);

    @Query("SELECT i FROM Item i JOIN i.category c WHERE c.categoryName = :categoryName")
    List<Item> findByCategoryName(@Param("categoryName") String categoryName);

    @Query("SELECT SUM(i.currentQuantity) FROM Item i JOIN i.category c " +
            "WHERE c.categoryName = :categoryName")
    BigDecimal getTotalStockByCategory(@Param("categoryName") String categoryName);

    @Query("SELECT COUNT(i) FROM Item i WHERE i.stockAlertLevel = :level")
    Long countByStockAlertLevel(@Param("level") String level);
}