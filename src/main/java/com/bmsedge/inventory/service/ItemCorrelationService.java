package com.bmsedge.inventory.service;

import com.bmsedge.inventory.model.Item;
import com.bmsedge.inventory.model.ItemCorrelation;
import com.bmsedge.inventory.model.ConsumptionRecord;
import com.bmsedge.inventory.repository.ItemCorrelationRepository;
import com.bmsedge.inventory.repository.ItemRepository;
import com.bmsedge.inventory.repository.ConsumptionRecordRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.scheduling.annotation.Async;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Transactional
public class ItemCorrelationService {

    private static final Logger logger = LoggerFactory.getLogger(ItemCorrelationService.class);
    private static final BigDecimal SIGNIFICANCE_THRESHOLD = BigDecimal.valueOf(0.3);
    private static final int MIN_DATA_POINTS = 5; // REDUCED from 10 for testing

    @Autowired
    private ItemCorrelationRepository correlationRepository;

    @Autowired
    private ItemRepository itemRepository;

    @Autowired
    private ConsumptionRecordRepository consumptionRepository;

    /**
     * Calculate and update correlations for all items - IMPROVED VERSION
     */
    @Transactional
    public Map<String, Object> calculateAllCorrelations() {
        Map<String, Object> result = new HashMap<>();
        List<Item> allItems = itemRepository.findAll();
        int totalPairs = 0;
        int significantCorrelations = 0;
        List<Map<String, Object>> correlationDetails = new ArrayList<>();

        logger.info("Starting correlation calculation for {} items", allItems.size());

        if (allItems.size() < 2) {
            result.put("error", "Need at least 2 items to calculate correlations");
            return result;
        }

        // Calculate for each pair of items
        for (int i = 0; i < allItems.size(); i++) {
            for (int j = i + 1; j < allItems.size(); j++) {
                Item item1 = allItems.get(i);
                Item item2 = allItems.get(j);

                try {
                    BigDecimal correlation = calculateCorrelation(item1.getId(), item2.getId());

                    if (correlation != null) {
                        totalPairs++;

                        // Save or update correlation
                        ItemCorrelation itemCorrelation = saveOrUpdateCorrelation(
                                item1, item2, correlation
                        );

                        if (itemCorrelation.isSignificant(SIGNIFICANCE_THRESHOLD.doubleValue())) {
                            significantCorrelations++;

                            Map<String, Object> detail = new HashMap<>();
                            detail.put("item1", item1.getItemName());
                            detail.put("item2", item2.getItemName());
                            detail.put("correlation", correlation);
                            detail.put("type", itemCorrelation.getCorrelationType());
                            correlationDetails.add(detail);
                        }

                        logger.debug("Correlation between {} and {}: {}",
                                item1.getItemName(), item2.getItemName(), correlation);
                    } else {
                        logger.debug("No correlation calculated between {} and {} (insufficient data)",
                                item1.getItemName(), item2.getItemName());
                    }
                } catch (Exception e) {
                    logger.error("Error calculating correlation between {} and {}: {}",
                            item1.getItemName(), item2.getItemName(), e.getMessage());
                }
            }
        }

        result.put("totalItems", allItems.size());
        result.put("totalPairs", totalPairs);
        result.put("significantCorrelations", significantCorrelations);
        result.put("threshold", SIGNIFICANCE_THRESHOLD);
        result.put("topCorrelations", correlationDetails.stream()
                .sorted((a, b) -> {
                    BigDecimal corr1 = (BigDecimal) a.get("correlation");
                    BigDecimal corr2 = (BigDecimal) b.get("correlation");
                    return corr2.abs().compareTo(corr1.abs());
                })
                .limit(10)
                .collect(Collectors.toList()));
        result.put("lastUpdated", LocalDateTime.now());

        logger.info("Calculated {} correlations, {} significant", totalPairs, significantCorrelations);

        return result;
    }

    /**
     * Calculate correlation for specific item - IMPROVED VERSION
     */
    @Transactional
    public Map<String, Object> calculateItemCorrelations(Long itemId) {
        Map<String, Object> result = new HashMap<>();

        Item targetItem = itemRepository.findById(itemId)
                .orElseThrow(() -> new RuntimeException("Item not found: " + itemId));

        List<Item> otherItems = itemRepository.findAll().stream()
                .filter(item -> !item.getId().equals(itemId))
                .collect(Collectors.toList());

        List<Map<String, Object>> correlations = new ArrayList<>();

        logger.info("Calculating correlations for item: {}", targetItem.getItemName());

        for (Item otherItem : otherItems) {
            try {
                BigDecimal correlation = calculateCorrelation(itemId, otherItem.getId());

                if (correlation != null) {
                    ItemCorrelation itemCorrelation = saveOrUpdateCorrelation(
                            targetItem, otherItem, correlation
                    );

                    Map<String, Object> corrData = new HashMap<>();
                    corrData.put("itemId", otherItem.getId());
                    corrData.put("itemName", otherItem.getItemName());
                    corrData.put("category", otherItem.getCategory() != null ?
                            otherItem.getCategory().getCategoryName() : "Unknown");
                    corrData.put("correlation", correlation);
                    corrData.put("type", itemCorrelation.getCorrelationType());
                    corrData.put("isSignificant", itemCorrelation.isSignificant(SIGNIFICANCE_THRESHOLD.doubleValue()));
                    correlations.add(corrData);
                }
            } catch (Exception e) {
                logger.error("Error calculating correlation between {} and {}: {}",
                        targetItem.getItemName(), otherItem.getItemName(), e.getMessage());
            }
        }

        // Sort by absolute correlation value
        correlations.sort((a, b) -> {
            BigDecimal corr1 = (BigDecimal) a.get("correlation");
            BigDecimal corr2 = (BigDecimal) b.get("correlation");
            return corr2.abs().compareTo(corr1.abs());
        });

        result.put("itemId", itemId);
        result.put("itemName", targetItem.getItemName());
        result.put("totalCorrelations", correlations.size());
        result.put("correlations", correlations);
        result.put("strongPositive", correlations.stream()
                .filter(c -> ((BigDecimal) c.get("correlation")).compareTo(BigDecimal.valueOf(0.7)) > 0)
                .collect(Collectors.toList()));
        result.put("strongNegative", correlations.stream()
                .filter(c -> ((BigDecimal) c.get("correlation")).compareTo(BigDecimal.valueOf(-0.7)) < 0)
                .collect(Collectors.toList()));

        return result;
    }

    /**
     * Calculate Pearson correlation coefficient between two items - IMPROVED VERSION
     */
    private BigDecimal calculateCorrelation(Long itemId1, Long itemId2) {
        // Get last 90 days of data (more flexible date range)
        LocalDate endDate = LocalDate.now();
        LocalDate startDate = endDate.minusDays(90);

        List<ConsumptionRecord> records1 = consumptionRepository
                .findByItemIdAndConsumptionDateBetween(itemId1, startDate, endDate);
        List<ConsumptionRecord> records2 = consumptionRepository
                .findByItemIdAndConsumptionDateBetween(itemId2, startDate, endDate);

        logger.debug("Found {} records for item1={}, {} records for item2={}",
                records1.size(), itemId1, records2.size(), itemId2);

        if (records1.size() < MIN_DATA_POINTS || records2.size() < MIN_DATA_POINTS) {
            logger.debug("Insufficient data points: item1={}, item2={}", records1.size(), records2.size());
            return null;
        }

        // Create maps for easy lookup with better handling of null values
        Map<LocalDate, BigDecimal> consumption1 = new HashMap<>();
        Map<LocalDate, BigDecimal> consumption2 = new HashMap<>();

        for (ConsumptionRecord record : records1) {
            BigDecimal consumed = record.getConsumedQuantity();
            if (consumed == null) consumed = BigDecimal.ZERO;
            consumption1.put(record.getConsumptionDate(), consumed);
        }

        for (ConsumptionRecord record : records2) {
            BigDecimal consumed = record.getConsumedQuantity();
            if (consumed == null) consumed = BigDecimal.ZERO;
            consumption2.put(record.getConsumptionDate(), consumed);
        }

        // Get all dates from both sets and fill missing dates with zero
        Set<LocalDate> allDates = new HashSet<>();
        allDates.addAll(consumption1.keySet());
        allDates.addAll(consumption2.keySet());

        if (allDates.size() < MIN_DATA_POINTS) {
            logger.debug("Insufficient common dates: {}", allDates.size());
            return null;
        }

        // Prepare data for correlation calculation
        List<BigDecimal> values1 = new ArrayList<>();
        List<BigDecimal> values2 = new ArrayList<>();

        for (LocalDate date : allDates) {
            values1.add(consumption1.getOrDefault(date, BigDecimal.ZERO));
            values2.add(consumption2.getOrDefault(date, BigDecimal.ZERO));
        }

        return calculatePearsonCorrelation(values1, values2);
    }

    /**
     * Calculate Pearson correlation coefficient - IMPROVED VERSION
     */
    private BigDecimal calculatePearsonCorrelation(List<BigDecimal> x, List<BigDecimal> y) {
        if (x.size() != y.size() || x.isEmpty()) {
            return BigDecimal.ZERO;
        }

        int n = x.size();

        // Calculate means
        BigDecimal meanX = x.stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(n), 10, RoundingMode.HALF_UP);

        BigDecimal meanY = y.stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(n), 10, RoundingMode.HALF_UP);

        // Calculate correlation components
        BigDecimal numerator = BigDecimal.ZERO;
        BigDecimal sumSqX = BigDecimal.ZERO;
        BigDecimal sumSqY = BigDecimal.ZERO;

        for (int i = 0; i < n; i++) {
            BigDecimal diffX = x.get(i).subtract(meanX);
            BigDecimal diffY = y.get(i).subtract(meanY);

            numerator = numerator.add(diffX.multiply(diffY));
            sumSqX = sumSqX.add(diffX.multiply(diffX));
            sumSqY = sumSqY.add(diffY.multiply(diffY));
        }

        // Check for zero variance
        if (sumSqX.compareTo(BigDecimal.ZERO) == 0 || sumSqY.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }

        // Calculate correlation
        BigDecimal denominator = sqrt(sumSqX).multiply(sqrt(sumSqY));

        if (denominator.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }

        BigDecimal correlation = numerator.divide(denominator, 4, RoundingMode.HALF_UP);

        // Ensure correlation is between -1 and 1
        if (correlation.compareTo(BigDecimal.ONE) > 0) {
            correlation = BigDecimal.ONE;
        } else if (correlation.compareTo(BigDecimal.valueOf(-1)) < 0) {
            correlation = BigDecimal.valueOf(-1);
        }

        return correlation;
    }

    /**
     * Square root calculation for BigDecimal - IMPROVED VERSION
     */
    private BigDecimal sqrt(BigDecimal value) {
        if (value.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }

        if (value.compareTo(BigDecimal.ZERO) < 0) {
            throw new ArithmeticException("Square root of negative number");
        }

        // Use Newton's method for square root
        BigDecimal x = new BigDecimal(Math.sqrt(value.doubleValue()));

        // Refine using Newton's method for better precision
        for (int i = 0; i < 10; i++) {
            BigDecimal nx = x.add(value.divide(x, 10, RoundingMode.HALF_UP))
                    .divide(BigDecimal.valueOf(2), 10, RoundingMode.HALF_UP);
            if (nx.subtract(x).abs().compareTo(BigDecimal.valueOf(0.0001)) < 0) {
                break;
            }
            x = nx;
        }

        return x;
    }

    /**
     * Save or update correlation - IMPROVED VERSION
     */
    private ItemCorrelation saveOrUpdateCorrelation(Item item1, Item item2, BigDecimal correlation) {
        Optional<ItemCorrelation> existing = correlationRepository
                .findByItemPair(item1.getId(), item2.getId());

        ItemCorrelation itemCorrelation;

        if (existing.isPresent()) {
            itemCorrelation = existing.get();
            itemCorrelation.setCorrelationCoefficient(correlation);
            itemCorrelation.setLastCalculated(LocalDateTime.now());
        } else {
            itemCorrelation = new ItemCorrelation();
            itemCorrelation.setItem1(item1);
            itemCorrelation.setItem2(item2);
            itemCorrelation.setCorrelationCoefficient(correlation);
            itemCorrelation.setCategory(item1.getCategory());
            itemCorrelation.setConfidenceLevel(BigDecimal.valueOf(95));
            itemCorrelation.setIsActive(true);
            itemCorrelation.setDataPoints(calculateDataPoints(item1.getId(), item2.getId()));
        }

        return correlationRepository.save(itemCorrelation);
    }

    /**
     * Calculate number of data points used for correlation
     */
    private Integer calculateDataPoints(Long itemId1, Long itemId2) {
        LocalDate endDate = LocalDate.now();
        LocalDate startDate = endDate.minusDays(90);

        List<ConsumptionRecord> records1 = consumptionRepository
                .findByItemIdAndConsumptionDateBetween(itemId1, startDate, endDate);
        List<ConsumptionRecord> records2 = consumptionRepository
                .findByItemIdAndConsumptionDateBetween(itemId2, startDate, endDate);

        Set<LocalDate> dates1 = records1.stream()
                .map(ConsumptionRecord::getConsumptionDate)
                .collect(Collectors.toSet());
        Set<LocalDate> dates2 = records2.stream()
                .map(ConsumptionRecord::getConsumptionDate)
                .collect(Collectors.toSet());

        Set<LocalDate> commonDates = new HashSet<>(dates1);
        commonDates.retainAll(dates2);

        return commonDates.size();
    }

    /**
     * Get correlated items for recommendation - IMPROVED VERSION
     */
    public List<Map<String, Object>> getCorrelatedItemsForRecommendation(Long itemId, int limit) {
        List<ItemCorrelation> correlations = correlationRepository
                .findSignificantCorrelations(itemId, SIGNIFICANCE_THRESHOLD.doubleValue());

        logger.info("Found {} significant correlations for item {}", correlations.size(), itemId);

        return correlations.stream()
                .limit(limit)
                .map(corr -> {
                    Map<String, Object> recommendation = new HashMap<>();
                    Item relatedItem = corr.getItem1().getId().equals(itemId) ?
                            corr.getItem2() : corr.getItem1();

                    recommendation.put("itemId", relatedItem.getId());
                    recommendation.put("itemName", relatedItem.getItemName());
                    recommendation.put("correlation", corr.getCorrelationCoefficient());
                    recommendation.put("correlationType", corr.getCorrelationType());
                    recommendation.put("currentStock", relatedItem.getCurrentQuantity());
                    recommendation.put("reorderLevel", relatedItem.getReorderLevel());
                    recommendation.put("needsReorder", relatedItem.needsReorder());

                    return recommendation;
                })
                .collect(Collectors.toList());
    }

    /**
     * Update correlations when consumption is recorded - ASYNC VERSION
     */
    @Async
    @Transactional
    public void updateCorrelationsForItem(Long itemId) {
        try {
            logger.info("Updating correlations for item {}", itemId);
            calculateItemCorrelations(itemId);
        } catch (Exception e) {
            logger.error("Failed to update correlations for item {}: {}", itemId, e.getMessage());
        }
    }

    /**
     * Get correlation statistics - IMPROVED VERSION
     */
    public Map<String, Object> getCorrelationStatistics() {
        Map<String, Object> stats = new HashMap<>();

        List<ItemCorrelation> allCorrelations = correlationRepository.findByIsActiveTrue();

        if (allCorrelations.isEmpty()) {
            stats.put("totalCorrelations", 0);
            stats.put("message", "No correlations calculated yet");
            return stats;
        }

        // Calculate statistics
        BigDecimal avgCorrelation = allCorrelations.stream()
                .map(ItemCorrelation::getCorrelationCoefficient)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(allCorrelations.size()), 4, RoundingMode.HALF_UP);

        BigDecimal maxCorrelation = allCorrelations.stream()
                .map(ItemCorrelation::getCorrelationCoefficient)
                .max(BigDecimal::compareTo)
                .orElse(BigDecimal.ZERO);

        BigDecimal minCorrelation = allCorrelations.stream()
                .map(ItemCorrelation::getCorrelationCoefficient)
                .min(BigDecimal::compareTo)
                .orElse(BigDecimal.ZERO);

        long strongPositive = allCorrelations.stream()
                .filter(c -> c.getCorrelationType() == ItemCorrelation.CorrelationType.STRONG_POSITIVE)
                .count();

        long strongNegative = allCorrelations.stream()
                .filter(c -> c.getCorrelationType() == ItemCorrelation.CorrelationType.STRONG_NEGATIVE)
                .count();

        long significantCount = allCorrelations.stream()
                .filter(c -> c.isSignificant(SIGNIFICANCE_THRESHOLD.doubleValue()))
                .count();

        stats.put("totalCorrelations", allCorrelations.size());
        stats.put("averageCorrelation", avgCorrelation);
        stats.put("maxCorrelation", maxCorrelation);
        stats.put("minCorrelation", minCorrelation);
        stats.put("strongPositiveCount", strongPositive);
        stats.put("strongNegativeCount", strongNegative);
        stats.put("significantCount", significantCount);
        stats.put("significantThreshold", SIGNIFICANCE_THRESHOLD);
        stats.put("minDataPoints", MIN_DATA_POINTS);
        stats.put("lastUpdated", LocalDateTime.now());

        return stats;
    }

    /**
     * NEW: Force recalculate all correlations (for testing)
     */
    @Transactional
    public Map<String, Object> forceRecalculateAllCorrelations() {
        logger.info("Force recalculating all correlations...");

        // Delete existing correlations
        correlationRepository.deleteAll();

        // Recalculate
        return calculateAllCorrelations();
    }

    /**
     * NEW: Get correlation debug info
     */
    public Map<String, Object> getCorrelationDebugInfo() {
        Map<String, Object> debug = new HashMap<>();

        long totalItems = itemRepository.count();
        long totalConsumptionRecords = consumptionRepository.count();
        long totalCorrelations = correlationRepository.count();

        // Check recent consumption data
        LocalDate endDate = LocalDate.now();
        LocalDate startDate = endDate.minusDays(30);

        long recentRecords = consumptionRepository.findByConsumptionDateBetween(startDate, endDate).size();

        debug.put("totalItems", totalItems);
        debug.put("totalConsumptionRecords", totalConsumptionRecords);
        debug.put("totalCorrelations", totalCorrelations);
        debug.put("recentConsumptionRecords", recentRecords);
        debug.put("minDataPointsRequired", MIN_DATA_POINTS);
        debug.put("significanceThreshold", SIGNIFICANCE_THRESHOLD);

        return debug;
    }
}