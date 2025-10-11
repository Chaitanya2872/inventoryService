package com.bmsedge.inventory.repository;

import com.bmsedge.inventory.model.StockReceipt;
import com.bmsedge.inventory.model.Item;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface StockReceiptRepository extends JpaRepository<StockReceipt, Long> {

    /**
     * Find all receipts for a specific item
     */
    List<StockReceipt> findByItemOrderByReceiptDateDesc(Item item);

    /**
     * Find all receipts for an item within date range
     */
    List<StockReceipt> findByItemAndReceiptDateBetween(
            Item item,
            LocalDate startDate,
            LocalDate endDate
    );

    /**
     * Find receipts by supplier
     */
    List<StockReceipt> findBySupplierNameContainingIgnoreCase(String supplierName);

    /**
     * Find receipts by invoice number
     */
    Optional<StockReceipt> findByInvoiceNumber(String invoiceNumber);

    /**
     * Find receipts by batch number
     */
    List<StockReceipt> findByBatchNumber(String batchNumber);

    /**
     * Find receipts within date range
     */
    List<StockReceipt> findByReceiptDateBetween(LocalDate startDate, LocalDate endDate);

    /**
     * Get total quantity received for an item in date range
     */
    @Query("SELECT COALESCE(SUM(sr.quantityReceived), 0) FROM StockReceipt sr " +
            "WHERE sr.item.id = :itemId " +
            "AND sr.receiptDate BETWEEN :startDate AND :endDate")
    BigDecimal getTotalReceivedByItemAndDateRange(
            @Param("itemId") Long itemId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate
    );

    /**
     * Get total value of receipts for an item
     */
    @Query("SELECT COALESCE(SUM(sr.quantityReceived * sr.unitPrice), 0) FROM StockReceipt sr " +
            "WHERE sr.item.id = :itemId " +
            "AND sr.receiptDate BETWEEN :startDate AND :endDate")
    BigDecimal getTotalValueByItemAndDateRange(
            @Param("itemId") Long itemId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate
    );

    /**
     * Find receipts expiring soon
     */
    @Query("SELECT sr FROM StockReceipt sr " +
            "WHERE sr.expiryDate BETWEEN :startDate AND :endDate " +
            "ORDER BY sr.expiryDate ASC")
    List<StockReceipt> findReceiptsExpiringSoon(
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate
    );

    /**
     * Find expired receipts
     */
    @Query("SELECT sr FROM StockReceipt sr " +
            "WHERE sr.expiryDate < :date " +
            "ORDER BY sr.expiryDate ASC")
    List<StockReceipt> findExpiredReceipts(@Param("date") LocalDate date);

    /**
     * Get supplier-wise receipt summary
     */
    @Query("SELECT sr.supplierName, " +
            "COUNT(sr), " +
            "SUM(sr.quantityReceived), " +
            "SUM(sr.quantityReceived * sr.unitPrice) " +
            "FROM StockReceipt sr " +
            "WHERE sr.receiptDate BETWEEN :startDate AND :endDate " +
            "GROUP BY sr.supplierName " +
            "ORDER BY SUM(sr.quantityReceived * sr.unitPrice) DESC")
    List<Object[]> getSupplierWiseSummary(
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate
    );

    /**
     * Find latest receipt for an item
     */
    @Query("SELECT sr FROM StockReceipt sr " +
            "WHERE sr.item.id = :itemId " +
            "ORDER BY sr.receiptDate DESC, sr.createdAt DESC")
    Optional<StockReceipt> findLatestByItemId(@Param("itemId") Long itemId);

    /**
     * Get monthly receipt summary
     */
    @Query(value = "SELECT " +
            "DATE_TRUNC('month', receipt_date) as month, " +
            "COUNT(*) as receipt_count, " +
            "SUM(quantity_received) as total_quantity, " +
            "SUM(quantity_received * unit_price) as total_value " +
            "FROM stock_receipts " +
            "WHERE receipt_date BETWEEN :startDate AND :endDate " +
            "GROUP BY DATE_TRUNC('month', receipt_date) " +
            "ORDER BY month",
            nativeQuery = true)
    List<Object[]> getMonthlyReceiptSummary(
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate
    );

    /**
     * Find receipts by received_by user
     */
    List<StockReceipt> findByReceivedBy(Long userId);

    /**
     * Find pending approval receipts
     */
    @Query("SELECT sr FROM StockReceipt sr " +
            "WHERE sr.approvedBy IS NULL " +
            "ORDER BY sr.receiptDate DESC")
    List<StockReceipt> findPendingApproval();

    /**
     * Count receipts for an item
     */
    long countByItem(Item item);

    /**
     * Delete old receipts before a certain date
     */
    void deleteByReceiptDateBefore(LocalDate date);
}