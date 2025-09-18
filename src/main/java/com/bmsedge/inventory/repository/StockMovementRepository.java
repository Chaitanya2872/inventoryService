package com.bmsedge.inventory.repository;

import com.bmsedge.inventory.model.StockMovement;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Repository
public interface StockMovementRepository extends JpaRepository<StockMovement, Long> {

    List<StockMovement> findByItemIdAndMovementTypeAndMovementDate(
            Long itemId, String movementType, LocalDate movementDate);

    List<StockMovement> findByItemIdAndMovementDateBetween(
            Long itemId, LocalDate startDate, LocalDate endDate);

    @Query("SELECT sm FROM StockMovement sm WHERE sm.item.id = :itemId " +
            "AND sm.movementDate BETWEEN :startDate AND :endDate " +
            "ORDER BY sm.movementDate DESC, sm.createdAt DESC")
    List<StockMovement> findItemMovementHistory(
            @Param("itemId") Long itemId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate);

    @Query("SELECT SUM(sm.quantity) FROM StockMovement sm " +
            "WHERE sm.item.id = :itemId AND sm.movementType = :type " +
            "AND sm.movementDate BETWEEN :startDate AND :endDate")
    BigDecimal getTotalMovementByType(
            @Param("itemId") Long itemId,
            @Param("type") String type,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate);

    // FIXED: Changed from referencing non-existent 'netChange' field to actual database fields
    @Query("SELECT sm.item.id, sm.item.itemName, " +
            "SUM(CASE WHEN sm.movementType IN ('RECEIPT', 'ADJUSTMENT_IN') THEN sm.quantity ELSE 0 END) as received, " +
            "SUM(CASE WHEN sm.movementType IN ('CONSUMPTION', 'ADJUSTMENT_OUT') THEN sm.quantity ELSE 0 END) as consumed, " +
            "(SUM(CASE WHEN sm.movementType IN ('RECEIPT', 'ADJUSTMENT_IN') THEN sm.quantity ELSE 0 END) - " +
            " SUM(CASE WHEN sm.movementType IN ('CONSUMPTION', 'ADJUSTMENT_OUT') THEN sm.quantity ELSE 0 END)) as netChange " +
            "FROM StockMovement sm " +
            "WHERE sm.movementDate BETWEEN :startDate AND :endDate " +
            "GROUP BY sm.item.id, sm.item.itemName " +
            "ORDER BY (SUM(CASE WHEN sm.movementType IN ('RECEIPT', 'ADJUSTMENT_IN') THEN sm.quantity ELSE 0 END) - " +
            "         SUM(CASE WHEN sm.movementType IN ('CONSUMPTION', 'ADJUSTMENT_OUT') THEN sm.quantity ELSE 0 END)) DESC")
    List<Object[]> getNetStockChanges(
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate);

    // Additional useful queries
    @Query("SELECT sm FROM StockMovement sm WHERE sm.movementDate = :date")
    List<StockMovement> findByMovementDate(@Param("date") LocalDate date);

    @Query("SELECT sm FROM StockMovement sm WHERE sm.movementDate BETWEEN :startDate AND :endDate " +
            "ORDER BY sm.movementDate DESC, sm.createdAt DESC")
    List<StockMovement> findAllMovementsBetween(
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate);

    @Query("SELECT sm FROM StockMovement sm WHERE sm.movementType = :movementType " +
            "AND sm.movementDate BETWEEN :startDate AND :endDate " +
            "ORDER BY sm.movementDate DESC")
    List<StockMovement> findByMovementTypeAndDateRange(
            @Param("movementType") String movementType,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate);

    @Query("SELECT SUM(sm.quantity) FROM StockMovement sm " +
            "WHERE sm.movementType = :movementType " +
            "AND sm.movementDate BETWEEN :startDate AND :endDate")
    BigDecimal getTotalQuantityByMovementType(
            @Param("movementType") String movementType,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate);

    // Missing methods that AnalyticsService is calling
    @Query("SELECT sm.movementType, SUM(sm.quantity), COUNT(sm) " +
            "FROM StockMovement sm " +
            "WHERE sm.item.id = :itemId " +
            "AND sm.movementDate BETWEEN :startDate AND :endDate " +
            "GROUP BY sm.movementType " +
            "ORDER BY sm.movementType")
    List<Object[]> getItemMovementAnalysis(
            @Param("itemId") Long itemId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate);

    @Query("SELECT sm.movementType, SUM(sm.quantity), COUNT(sm) " +
            "FROM StockMovement sm " +
            "WHERE sm.item.category.id = :categoryId " +
            "AND sm.movementDate BETWEEN :startDate AND :endDate " +
            "GROUP BY sm.movementType " +
            "ORDER BY sm.movementType")
    List<Object[]> getCategoryMovementAnalysis(
            @Param("categoryId") Long categoryId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate);

    @Query("SELECT sm.movementType, SUM(sm.quantity), COUNT(sm) " +
            "FROM StockMovement sm " +
            "WHERE sm.movementDate BETWEEN :startDate AND :endDate " +
            "GROUP BY sm.movementType " +
            "ORDER BY sm.movementType")
    List<Object[]> getAllMovementAnalysis(
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate);
}