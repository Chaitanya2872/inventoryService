package com.bmsedge.inventory.repository;

import com.bmsedge.inventory.model.ItemCorrelation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface ItemCorrelationRepository extends JpaRepository<ItemCorrelation, Long> {

    /**
     * Find correlation between two specific items
     */
    @Query("SELECT ic FROM ItemCorrelation ic WHERE " +
            "(ic.item1.id = :item1Id AND ic.item2.id = :item2Id) OR " +
            "(ic.item1.id = :item2Id AND ic.item2.id = :item1Id)")
    Optional<ItemCorrelation> findByItemPair(@Param("item1Id") Long item1Id,
                                             @Param("item2Id") Long item2Id);

    /**
     * Find all correlations for a specific item
     */
    @Query("SELECT ic FROM ItemCorrelation ic WHERE " +
            "ic.item1.id = :itemId OR ic.item2.id = :itemId " +
            "ORDER BY ic.correlationCoefficient DESC")
    List<ItemCorrelation> findByItemId(@Param("itemId") Long itemId);

    /**
     * Find significant correlations for an item
     */
    @Query("SELECT ic FROM ItemCorrelation ic WHERE " +
            "(ic.item1.id = :itemId OR ic.item2.id = :itemId) AND " +
            "ABS(ic.correlationCoefficient) >= :threshold " +
            "ORDER BY ABS(ic.correlationCoefficient) DESC")
    List<ItemCorrelation> findSignificantCorrelations(@Param("itemId") Long itemId,
                                                      @Param("threshold") double threshold);

    /**
     * Find correlations by category
     */
    @Query("SELECT ic FROM ItemCorrelation ic WHERE ic.category.id = :categoryId " +
            "ORDER BY ic.correlationCoefficient DESC")
    List<ItemCorrelation> findByCategoryId(@Param("categoryId") Long categoryId);

    /**
     * Find strong positive correlations
     */
    @Query("SELECT ic FROM ItemCorrelation ic WHERE " +
            "ic.correlationType IN ('STRONG_POSITIVE', 'MODERATE_POSITIVE') " +
            "AND ic.isActive = true " +
            "ORDER BY ic.correlationCoefficient DESC")
    List<ItemCorrelation> findStrongPositiveCorrelations();

    /**
     * Find correlations that need recalculation
     */
    @Query("SELECT ic FROM ItemCorrelation ic WHERE " +
            "ic.lastCalculated < :cutoffDate OR ic.lastCalculated IS NULL")
    List<ItemCorrelation> findCorrelationsNeedingUpdate(@Param("cutoffDate") LocalDateTime cutoffDate);

    /**
     * Delete correlations for an item
     */
    void deleteByItem1IdOrItem2Id(Long item1Id, Long item2Id);

    /**
     * Count correlations for an item
     */
    @Query("SELECT COUNT(ic) FROM ItemCorrelation ic WHERE " +
            "ic.item1.id = :itemId OR ic.item2.id = :itemId")
    long countByItemId(@Param("itemId") Long itemId);

    /**
     * Get average correlation strength for a category
     */
    @Query("SELECT AVG(ABS(ic.correlationCoefficient)) FROM ItemCorrelation ic " +
            "WHERE ic.category.id = :categoryId")
    BigDecimal getAverageCorrelationStrength(@Param("categoryId") Long categoryId);

    /**
     * Find top positive correlations
     */
    @Query("SELECT ic FROM ItemCorrelation ic WHERE " +
            "ic.correlationCoefficient > 0 " +
            "ORDER BY ic.correlationCoefficient DESC")
    List<ItemCorrelation> findTopPositiveCorrelations();

    /**
     * Find top negative correlations
     */
    @Query("SELECT ic FROM ItemCorrelation ic WHERE " +
            "ic.correlationCoefficient < 0 " +
            "ORDER BY ic.correlationCoefficient ASC")
    List<ItemCorrelation> findTopNegativeCorrelations();

    /**
     * Find correlations by strength range
     */
    @Query("SELECT ic FROM ItemCorrelation ic WHERE " +
            "ABS(ic.correlationCoefficient) BETWEEN :minStrength AND :maxStrength " +
            "ORDER BY ABS(ic.correlationCoefficient) DESC")
    List<ItemCorrelation> findByCorrelationStrengthRange(@Param("minStrength") BigDecimal minStrength,
                                                         @Param("maxStrength") BigDecimal maxStrength);

    /**
     * Check if correlation exists between two items
     */
    @Query("SELECT COUNT(ic) > 0 FROM ItemCorrelation ic WHERE " +
            "(ic.item1.id = :item1Id AND ic.item2.id = :item2Id) OR " +
            "(ic.item1.id = :item2Id AND ic.item2.id = :item1Id)")
    boolean existsByItemPair(@Param("item1Id") Long item1Id, @Param("item2Id") Long item2Id);

    /**
     * Get correlations updated after a specific date
     */
    List<ItemCorrelation> findByLastCalculatedAfter(LocalDateTime date);

    /**
     * Get all active correlations
     */
    List<ItemCorrelation> findByIsActiveTrue();

    /**
     * Get correlations by correlation type
     */
    List<ItemCorrelation> findByCorrelationType(ItemCorrelation.CorrelationType type);

    /**
     * Find correlations with minimum data points
     */
    @Query("SELECT ic FROM ItemCorrelation ic WHERE " +
            "ic.dataPoints >= :minDataPoints " +
            "ORDER BY ic.dataPoints DESC")
    List<ItemCorrelation> findByMinimumDataPoints(@Param("minDataPoints") Integer minDataPoints);
}