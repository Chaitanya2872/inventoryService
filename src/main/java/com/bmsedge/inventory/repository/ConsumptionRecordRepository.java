package com.bmsedge.inventory.repository;

import com.bmsedge.inventory.model.ConsumptionRecord;
import com.bmsedge.inventory.model.Item;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Enhanced ConsumptionRecordRepository with all required queries
 */
@Repository
public interface ConsumptionRecordRepository extends JpaRepository<ConsumptionRecord, Long> {

    /**
     * Find record by item and date (unique combination)
     */
    @Query("SELECT cr FROM ConsumptionRecord cr WHERE cr.item.id = :itemId AND cr.consumptionDate = :date")
    Optional<ConsumptionRecord> findByItemIdAndConsumptionDate(@Param("itemId") Long itemId,
                                                               @Param("date") LocalDate date);

    /**
     * Find all records for an item within date range
     */
    List<ConsumptionRecord> findByItemAndConsumptionDateBetween(Item item,
                                                                LocalDate startDate,
                                                                LocalDate endDate);

    /**
     * Find records by item ID and date range
     */
    @Query("SELECT cr FROM ConsumptionRecord cr WHERE cr.item.id = :itemId " +
            "AND cr.consumptionDate BETWEEN :startDate AND :endDate " +
            "ORDER BY cr.consumptionDate")
    List<ConsumptionRecord> findByItemIdAndConsumptionDateBetween(@Param("itemId") Long itemId,
                                                                  @Param("startDate") LocalDate startDate,
                                                                  @Param("endDate") LocalDate endDate);

    /**
     * Find all records within date range
     */
    List<ConsumptionRecord> findByConsumptionDateBetween(LocalDate startDate, LocalDate endDate);

    /**
     * Find records by category and date range
     */
    @Query("SELECT cr FROM ConsumptionRecord cr WHERE cr.item.category.id = :categoryId " +
            "AND cr.consumptionDate BETWEEN :startDate AND :endDate " +
            "ORDER BY cr.consumptionDate")
    List<ConsumptionRecord> findByCategoryAndDateBetween(@Param("categoryId") Long categoryId,
                                                         @Param("startDate") LocalDate startDate,
                                                         @Param("endDate") LocalDate endDate);

    /**
     * Alternative method name for category date range query (THIS IS THE MISSING METHOD)
     */
    @Query("SELECT cr FROM ConsumptionRecord cr WHERE cr.item.category.id = :categoryId " +
            "AND cr.consumptionDate BETWEEN :startDate AND :endDate")
    List<ConsumptionRecord> findByCategoryIdAndDateRange(@Param("categoryId") Long categoryId,
                                                         @Param("startDate") LocalDate startDate,
                                                         @Param("endDate") LocalDate endDate);

    /**
     * Calculate average daily consumption for an item
     */
    @Query("SELECT AVG(cr.consumedQuantity) FROM ConsumptionRecord cr " +
            "WHERE cr.item.id = :itemId AND cr.consumptionDate >= :startDate")
    BigDecimal getAverageDailyConsumption(@Param("itemId") Long itemId,
                                          @Param("startDate") LocalDate startDate);

    /**
     * Get total consumption for an item in date range
     */
    @Query("SELECT SUM(cr.consumedQuantity) FROM ConsumptionRecord cr " +
            "WHERE cr.item.id = :itemId AND cr.consumptionDate BETWEEN :startDate AND :endDate")
    BigDecimal getTotalConsumption(@Param("itemId") Long itemId,
                                   @Param("startDate") LocalDate startDate,
                                   @Param("endDate") LocalDate endDate);

    /**
     * Get total received quantity for an item in date range
     */
    @Query("SELECT SUM(cr.receivedQuantity) FROM ConsumptionRecord cr " +
            "WHERE cr.item.id = :itemId AND cr.consumptionDate BETWEEN :startDate AND :endDate")
    BigDecimal getTotalReceived(@Param("itemId") Long itemId,
                                @Param("startDate") LocalDate startDate,
                                @Param("endDate") LocalDate endDate);

    /**
     * Find high consumption records
     */
    @Query("SELECT cr FROM ConsumptionRecord cr " +
            "WHERE cr.consumedQuantity > cr.dailyConsumptionMean * 1.5 " +
            "AND cr.consumptionDate >= :startDate")
    List<ConsumptionRecord> findHighConsumptionRecords(@Param("startDate") LocalDate startDate);

    /**
     * Find records with high volatility
     */
    @Query("SELECT cr FROM ConsumptionRecord cr " +
            "WHERE cr.volatilityClassification IN ('HIGH', 'VERY_HIGH') " +
            "AND cr.consumptionDate >= :startDate")
    List<ConsumptionRecord> findHighVolatilityRecords(@Param("startDate") LocalDate startDate);

    /**
     * Get consumption statistics for an item
     */
    @Query("SELECT " +
            "MIN(cr.consumedQuantity) as min, " +
            "MAX(cr.consumedQuantity) as max, " +
            "AVG(cr.consumedQuantity) as avg, " +
            "COUNT(cr) as count " +
            "FROM ConsumptionRecord cr " +
            "WHERE cr.item.id = :itemId AND cr.consumptionDate >= :startDate")
    Object[] getConsumptionStatistics(@Param("itemId") Long itemId,
                                      @Param("startDate") LocalDate startDate);

    /**
     * Find records by department
     */
    List<ConsumptionRecord> findByDepartmentAndConsumptionDateBetween(String department,
                                                                      LocalDate startDate,
                                                                      LocalDate endDate);

    /**
     * Count records needing verification
     */
    @Query("SELECT COUNT(cr) FROM ConsumptionRecord cr " +
            "WHERE cr.isVerified = false AND cr.consumptionDate >= :startDate")
    long countUnverifiedRecords(@Param("startDate") LocalDate startDate);

    /**
     * Find records with waste above threshold
     */
    @Query("SELECT cr FROM ConsumptionRecord cr " +
            "WHERE cr.wastePercentage > :threshold " +
            "AND cr.consumptionDate >= :startDate")
    List<ConsumptionRecord> findHighWasteRecords(@Param("threshold") BigDecimal threshold,
                                                 @Param("startDate") LocalDate startDate);

    /**
     * Get latest consumption record for an item
     */
    @Query("SELECT cr FROM ConsumptionRecord cr " +
            "WHERE cr.item.id = :itemId " +
            "ORDER BY cr.consumptionDate DESC")
    Optional<ConsumptionRecord> findLatestByItemId(@Param("itemId") Long itemId);

    /**
     * Delete old records before a certain date
     */
    void deleteByConsumptionDateBefore(LocalDate date);

    /**
     * Find records with no consumption
     */
    @Query("SELECT cr FROM ConsumptionRecord cr " +
            "WHERE (cr.consumedQuantity = 0 OR cr.consumedQuantity IS NULL) " +
            "AND cr.consumptionDate BETWEEN :startDate AND :endDate")
    List<ConsumptionRecord> findZeroConsumptionRecords(@Param("startDate") LocalDate startDate,
                                                       @Param("endDate") LocalDate endDate);

    /**
     * Get consumption trend data for analytics
     */
    @Query("SELECT cr.consumptionDate, SUM(cr.consumedQuantity), COUNT(DISTINCT cr.item.id) " +
            "FROM ConsumptionRecord cr " +
            "WHERE cr.consumptionDate BETWEEN :startDate AND :endDate " +
            "GROUP BY cr.consumptionDate " +
            "ORDER BY cr.consumptionDate")
    List<Object[]> getConsumptionTrend(@Param("startDate") LocalDate startDate,
                                       @Param("endDate") LocalDate endDate);

    /**
     * Find records for multiple items
     */
    @Query("SELECT cr FROM ConsumptionRecord cr " +
            "WHERE cr.item.id IN :itemIds " +
            "AND cr.consumptionDate BETWEEN :startDate AND :endDate " +
            "ORDER BY cr.consumptionDate, cr.item.id")
    List<ConsumptionRecord> findByItemIdsAndDateRange(@Param("itemIds") List<Long> itemIds,
                                                      @Param("startDate") LocalDate startDate,
                                                      @Param("endDate") LocalDate endDate);

    /**
     * Calculate per capita consumption for a date range
     */
    @Query("SELECT AVG(cr.consumptionPerCapita) FROM ConsumptionRecord cr " +
            "WHERE cr.consumptionDate BETWEEN :startDate AND :endDate " +
            "AND cr.consumptionPerCapita IS NOT NULL")
    BigDecimal getAveragePerCapitaConsumption(@Param("startDate") LocalDate startDate,
                                              @Param("endDate") LocalDate endDate);

    /**
     * Get efficiency metrics
     */
    @Query("SELECT AVG(cr.efficiencyScore), AVG(cr.wastePercentage) " +
            "FROM ConsumptionRecord cr " +
            "WHERE cr.item.category.id = :categoryId " +
            "AND cr.consumptionDate BETWEEN :startDate AND :endDate")
    Object[] getCategoryEfficiencyMetrics(@Param("categoryId") Long categoryId,
                                          @Param("startDate") LocalDate startDate,
                                          @Param("endDate") LocalDate endDate);

    /**
     * Find consumption records with specific volatility classification
     */
    List<ConsumptionRecord> findByVolatilityClassificationAndConsumptionDateBetween(
            String volatilityClassification,
            LocalDate startDate,
            LocalDate endDate
    );

    /**
     * Get records that need review
     */
    @Query("SELECT cr FROM ConsumptionRecord cr " +
            "WHERE (cr.isVerified = false " +
            "OR cr.wastePercentage > 10 " +
            "OR cr.volatilityClassification = 'VERY_HIGH') " +
            "AND cr.consumptionDate >= :startDate")
    List<ConsumptionRecord> findRecordsNeedingReview(@Param("startDate") LocalDate startDate);

    /**
     * Find by item and consumption date
     */
    Optional<ConsumptionRecord> findByItemAndConsumptionDate(Item item, LocalDate consumptionDate);

    /**
     * Get consumption by category for a specific date
     */
    @Query("SELECT cr FROM ConsumptionRecord cr " +
            "WHERE cr.item.category.id = :categoryId " +
            "AND cr.consumptionDate = :date")
    List<ConsumptionRecord> findByCategoryIdAndDate(@Param("categoryId") Long categoryId,
                                                    @Param("date") LocalDate date);

    /**
     * Get total category consumption for a period
     */
    @Query("SELECT SUM(cr.consumedQuantity) FROM ConsumptionRecord cr " +
            "WHERE cr.item.category.id = :categoryId " +
            "AND cr.consumptionDate BETWEEN :startDate AND :endDate")
    BigDecimal getTotalCategoryConsumption(@Param("categoryId") Long categoryId,
                                           @Param("startDate") LocalDate startDate,
                                           @Param("endDate") LocalDate endDate);

    /**
     * Find consumption patterns by day of week
     */
    @Query("SELECT FUNCTION('DAYOFWEEK', cr.consumptionDate) as dayOfWeek, " +
            "AVG(cr.consumedQuantity) as avgConsumption " +
            "FROM ConsumptionRecord cr " +
            "WHERE cr.item.id = :itemId " +
            "AND cr.consumptionDate BETWEEN :startDate AND :endDate " +
            "GROUP BY FUNCTION('DAYOFWEEK', cr.consumptionDate)")
    List<Object[]> getConsumptionByDayOfWeek(@Param("itemId") Long itemId,
                                             @Param("startDate") LocalDate startDate,
                                             @Param("endDate") LocalDate endDate);

    /**
     * Check if consumption data exists for an item in a date range
     */
    @Query("SELECT COUNT(cr) > 0 FROM ConsumptionRecord cr " +
            "WHERE cr.item.id = :itemId " +
            "AND cr.consumptionDate BETWEEN :startDate AND :endDate")
    boolean existsByItemIdAndDateRange(@Param("itemId") Long itemId,
                                       @Param("startDate") LocalDate startDate,
                                       @Param("endDate") LocalDate endDate);

    List<ConsumptionRecord> findByItem(Item item);
}