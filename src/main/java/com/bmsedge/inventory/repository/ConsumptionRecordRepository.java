package com.bmsedge.inventory.repository;

import com.bmsedge.inventory.model.ConsumptionRecord;
import com.bmsedge.inventory.model.Item;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface ConsumptionRecordRepository extends JpaRepository<ConsumptionRecord, Long> {

    Optional<ConsumptionRecord> findByItemIdAndConsumptionDate(Long itemId, LocalDate consumptionDate);

    // ADDED: Method used by ConsumptionDataImportService
    Optional<ConsumptionRecord> findByItemAndConsumptionDate(Item item, LocalDate consumptionDate);

    // Total consumption by item and date
    @Query("SELECT SUM(cr.consumedQuantity) FROM ConsumptionRecord cr WHERE cr.item.id = :itemId AND cr.consumptionDate = :date")
    BigDecimal getTotalConsumptionByItemAndDate(@Param("itemId") Long itemId, @Param("date") LocalDate date);

    // Total consumption by category and date
    @Query("SELECT SUM(cr.consumedQuantity) FROM ConsumptionRecord cr WHERE cr.item.category.categoryName = :categoryName AND cr.consumptionDate = :date")
    BigDecimal getTotalConsumptionByCategoryAndDate(@Param("categoryName") String categoryName, @Param("date") LocalDate date);

    // Total consumption in period
    @Query("SELECT SUM(cr.consumedQuantity) FROM ConsumptionRecord cr WHERE cr.consumptionDate >= :startDate AND cr.consumptionDate <= :endDate")
    BigDecimal getTotalConsumptionInPeriod(@Param("startDate") LocalDate startDate, @Param("endDate") LocalDate endDate);

    // Total consumption by item in period
    @Query("SELECT SUM(cr.consumedQuantity) FROM ConsumptionRecord cr WHERE cr.item.id = :itemId AND cr.consumptionDate >= :startDate AND cr.consumptionDate <= :endDate")
    BigDecimal getTotalConsumptionByItemInPeriod(@Param("itemId") Long itemId, @Param("startDate") LocalDate startDate, @Param("endDate") LocalDate endDate);

    // Total consumption by category in period
    @Query("SELECT SUM(cr.consumedQuantity) FROM ConsumptionRecord cr WHERE cr.item.category.categoryName = :categoryName AND cr.consumptionDate >= :startDate AND cr.consumptionDate <= :endDate")
    BigDecimal getTotalConsumptionByCategoryInPeriod(@Param("categoryName") String categoryName, @Param("startDate") LocalDate startDate, @Param("endDate") LocalDate endDate);

    // Average daily consumption for an item
    @Query("SELECT AVG(cr.consumedQuantity) FROM ConsumptionRecord cr WHERE cr.item.id = :itemId AND cr.consumptionDate >= :startDate")
    BigDecimal getAverageDailyConsumption(@Param("itemId") Long itemId, @Param("startDate") LocalDate startDate);

    // Top consuming items in period - using Pageable instead of limit parameter
    @Query("SELECT i.itemName, c.categoryName, SUM(cr.consumedQuantity) as totalQty, " +
            "SUM(cr.consumedQuantity * i.unitPrice) as totalCost, i.id " +
            "FROM ConsumptionRecord cr " +
            "JOIN cr.item i " +
            "JOIN i.category c " +
            "WHERE cr.consumptionDate >= :startDate AND cr.consumptionDate <= :endDate " +
            "GROUP BY i.itemName, c.categoryName, i.id " +
            "ORDER BY totalQty DESC")
    List<Object[]> getTopConsumersInPeriod(@Param("startDate") LocalDate startDate,
                                           @Param("endDate") LocalDate endDate,
                                           Pageable pageable);

    // NEW: Method for category consumption with cost calculation
    @Query("SELECT c.categoryName, " +
            "SUM(cr.consumedQuantity) as totalQty, " +
            "AVG(i.unitPrice) as avgPrice " +
            "FROM ConsumptionRecord cr " +
            "JOIN cr.item i " +
            "JOIN i.category c " +
            "WHERE cr.consumptionDate >= :startDate AND cr.consumptionDate <= :endDate " +
            "GROUP BY c.categoryName " +
            "ORDER BY totalQty DESC")
    List<Object[]> getCategoryConsumptionWithCost(@Param("startDate") LocalDate startDate,
                                                  @Param("endDate") LocalDate endDate);

    // NEW: Method for item consumption between dates
    @Query("SELECT cr FROM ConsumptionRecord cr " +
            "WHERE cr.item = :item AND cr.consumptionDate >= :startDate AND cr.consumptionDate <= :endDate " +
            "ORDER BY cr.consumptionDate ASC")
    List<ConsumptionRecord> findByItemAndConsumptionDateBetween(@Param("item") Item item,
                                                                @Param("startDate") LocalDate startDate,
                                                                @Param("endDate") LocalDate endDate);

    // ADDITIONAL USEFUL METHODS for enhanced analytics

    // Get consumption records by category and date range
    @Query("SELECT cr FROM ConsumptionRecord cr " +
            "WHERE cr.item.category.id = :categoryId AND cr.consumptionDate >= :startDate AND cr.consumptionDate <= :endDate " +
            "ORDER BY cr.consumptionDate ASC")
    List<ConsumptionRecord> findByCategoryAndDateBetween(@Param("categoryId") Long categoryId,
                                                         @Param("startDate") LocalDate startDate,
                                                         @Param("endDate") LocalDate endDate);

    // Get all consumption records in date range
    @Query("SELECT cr FROM ConsumptionRecord cr WHERE cr.consumptionDate >= :startDate AND cr.consumptionDate <= :endDate ORDER BY cr.consumptionDate ASC")
    List<ConsumptionRecord> findByConsumptionDateBetween(@Param("startDate") LocalDate startDate, @Param("endDate") LocalDate endDate);

    // Get consumption records with employee count
    @Query("SELECT cr FROM ConsumptionRecord cr WHERE cr.employeeCount IS NOT NULL AND cr.consumptionDate >= :startDate AND cr.consumptionDate <= :endDate")
    List<ConsumptionRecord> findRecordsWithEmployeeCount(@Param("startDate") LocalDate startDate, @Param("endDate") LocalDate endDate);

    // Get monthly consumption totals
    @Query("SELECT FUNCTION('DATE_FORMAT', cr.consumptionDate, '%Y-%m') as month, " +
            "SUM(cr.consumedQuantity) as totalConsumption, " +
            "COUNT(DISTINCT cr.item.id) as uniqueItems " +
            "FROM ConsumptionRecord cr " +
            "WHERE cr.consumptionDate >= :startDate AND cr.consumptionDate <= :endDate " +
            "GROUP BY FUNCTION('DATE_FORMAT', cr.consumptionDate, '%Y-%m') " +
            "ORDER BY month ASC")
    List<Object[]> getMonthlyConsumptionTotals(@Param("startDate") LocalDate startDate, @Param("endDate") LocalDate endDate);

    // Get weekly consumption totals
    @Query("SELECT FUNCTION('YEARWEEK', cr.consumptionDate) as week, " +
            "SUM(cr.consumedQuantity) as totalConsumption, " +
            "COUNT(DISTINCT cr.item.id) as uniqueItems " +
            "FROM ConsumptionRecord cr " +
            "WHERE cr.consumptionDate >= :startDate AND cr.consumptionDate <= :endDate " +
            "GROUP BY FUNCTION('YEARWEEK', cr.consumptionDate) " +
            "ORDER BY week ASC")
    List<Object[]> getWeeklyConsumptionTotals(@Param("startDate") LocalDate startDate, @Param("endDate") LocalDate endDate);

    // Get consumption with cost calculation
    @Query("SELECT cr.consumptionDate, cr.consumedQuantity, " +
            "(cr.consumedQuantity * i.unitPrice) as consumptionCost, " +
            "i.itemName, c.categoryName " +
            "FROM ConsumptionRecord cr " +
            "JOIN cr.item i " +
            "JOIN i.category c " +
            "WHERE cr.consumptionDate >= :startDate AND cr.consumptionDate <= :endDate " +
            "ORDER BY cr.consumptionDate ASC")
    List<Object[]> getConsumptionWithCost(@Param("startDate") LocalDate startDate, @Param("endDate") LocalDate endDate);

    // Get zero consumption items (items not consumed in period)
    @Query("SELECT i FROM Item i WHERE i.id NOT IN (" +
            "SELECT DISTINCT cr.item.id FROM ConsumptionRecord cr " +
            "WHERE cr.consumptionDate >= :startDate AND cr.consumptionDate <= :endDate)")
    List<Item> getZeroConsumptionItems(@Param("startDate") LocalDate startDate, @Param("endDate") LocalDate endDate);

    // Get highest consumption day for an item
    @Query("SELECT cr.consumptionDate, MAX(cr.consumedQuantity) " +
            "FROM ConsumptionRecord cr " +
            "WHERE cr.item.id = :itemId AND cr.consumptionDate >= :startDate AND cr.consumptionDate <= :endDate " +
            "GROUP BY cr.consumptionDate " +
            "ORDER BY MAX(cr.consumedQuantity) DESC")
    List<Object[]> getHighestConsumptionDay(@Param("itemId") Long itemId,
                                            @Param("startDate") LocalDate startDate,
                                            @Param("endDate") LocalDate endDate);

    // Get consumption pattern by day of week
    @Query("SELECT FUNCTION('DAYNAME', cr.consumptionDate) as dayOfWeek, " +
            "AVG(cr.consumedQuantity) as avgConsumption, " +
            "COUNT(cr.id) as recordCount " +
            "FROM ConsumptionRecord cr " +
            "WHERE cr.item.id = :itemId AND cr.consumptionDate >= :startDate AND cr.consumptionDate <= :endDate " +
            "GROUP BY FUNCTION('DAYOFWEEK', cr.consumptionDate), FUNCTION('DAYNAME', cr.consumptionDate) " +
            "ORDER BY FUNCTION('DAYOFWEEK', cr.consumptionDate)")
    List<Object[]> getConsumptionPatternByDayOfWeek(@Param("itemId") Long itemId,
                                                    @Param("startDate") LocalDate startDate,
                                                    @Param("endDate") LocalDate endDate);

    // Get consumption trend (comparing periods)
    @Query("SELECT " +
            "SUM(CASE WHEN cr.consumptionDate >= :currentPeriodStart THEN cr.consumedQuantity ELSE 0 END) as currentPeriodConsumption, " +
            "SUM(CASE WHEN cr.consumptionDate < :currentPeriodStart THEN cr.consumedQuantity ELSE 0 END) as previousPeriodConsumption " +
            "FROM ConsumptionRecord cr " +
            "WHERE cr.item.id = :itemId AND cr.consumptionDate >= :previousPeriodStart AND cr.consumptionDate <= :currentPeriodEnd")
    List<Object[]> getConsumptionTrend(@Param("itemId") Long itemId,
                                       @Param("previousPeriodStart") LocalDate previousPeriodStart,
                                       @Param("currentPeriodStart") LocalDate currentPeriodStart,
                                       @Param("currentPeriodEnd") LocalDate currentPeriodEnd);
}