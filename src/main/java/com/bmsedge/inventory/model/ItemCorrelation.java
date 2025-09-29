package com.bmsedge.inventory.model;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "item_correlations",
        indexes = {
                @Index(name = "idx_item1_correlation", columnList = "item1_id,correlation_coefficient"),
                @Index(name = "idx_item2_correlation", columnList = "item2_id,correlation_coefficient"),
                @Index(name = "idx_correlation_type", columnList = "correlation_type"),
                @Index(name = "idx_category_correlation", columnList = "category_id"),
                @Index(name = "idx_correlation_strength", columnList = "correlation_coefficient")
        },
        uniqueConstraints = {
                @UniqueConstraint(columnNames = {"item1_id", "item2_id"})
        }
)
public class ItemCorrelation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "item1_id", nullable = false)
    private Item item1;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "item2_id", nullable = false)
    private Item item2;

    @Column(name = "correlation_coefficient", nullable = false, precision = 5, scale = 4)
    private BigDecimal correlationCoefficient;

    @Column(name = "correlation_type", length = 20)
    @Enumerated(EnumType.STRING)
    private CorrelationType correlationType;

    @Column(name = "confidence_level", precision = 5, scale = 2)
    private BigDecimal confidenceLevel;

    @Column(name = "data_points")
    private Integer dataPoints;

    @Column(name = "last_calculated")
    private LocalDateTime lastCalculated;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id")
    private Category category;

    @Column(name = "co_consumption_count")
    private Integer coConsumptionCount;

    @Column(name = "average_time_gap_days", precision = 10, scale = 2)
    private BigDecimal averageTimeGapDays;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    @Column(name = "is_active")
    private Boolean isActive = true;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    // Constructors
    public ItemCorrelation() {}

    public ItemCorrelation(Item item1, Item item2, BigDecimal correlationCoefficient) {
        this.item1 = item1;
        this.item2 = item2;
        this.correlationCoefficient = correlationCoefficient;
        this.lastCalculated = LocalDateTime.now();
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
        classifyCorrelation();
    }

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        lastCalculated = LocalDateTime.now();

        if (correlationCoefficient != null) {
            classifyCorrelation();
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
        if (correlationCoefficient != null) {
            classifyCorrelation();
        }
    }

    /**
     * Classify correlation based on coefficient value
     */
    private void classifyCorrelation() {
        BigDecimal absValue = correlationCoefficient.abs();

        if (absValue.compareTo(BigDecimal.valueOf(0.7)) >= 0) {
            correlationType = correlationCoefficient.compareTo(BigDecimal.ZERO) > 0
                    ? CorrelationType.STRONG_POSITIVE : CorrelationType.STRONG_NEGATIVE;
        } else if (absValue.compareTo(BigDecimal.valueOf(0.4)) >= 0) {
            correlationType = correlationCoefficient.compareTo(BigDecimal.ZERO) > 0
                    ? CorrelationType.MODERATE_POSITIVE : CorrelationType.MODERATE_NEGATIVE;
        } else if (absValue.compareTo(BigDecimal.valueOf(0.2)) >= 0) {
            correlationType = correlationCoefficient.compareTo(BigDecimal.ZERO) > 0
                    ? CorrelationType.WEAK_POSITIVE : CorrelationType.WEAK_NEGATIVE;
        } else {
            correlationType = CorrelationType.NO_CORRELATION;
        }
    }

    /**
     * Check if correlation is significant
     */
    public boolean isSignificant(double threshold) {
        return correlationCoefficient != null &&
                correlationCoefficient.abs().compareTo(BigDecimal.valueOf(threshold)) >= 0;
    }

    // Enums
    public enum CorrelationType {
        STRONG_POSITIVE,
        MODERATE_POSITIVE,
        WEAK_POSITIVE,
        NO_CORRELATION,
        WEAK_NEGATIVE,
        MODERATE_NEGATIVE,
        STRONG_NEGATIVE
    }

    // Getters and Setters
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Item getItem1() {
        return item1;
    }

    public void setItem1(Item item1) {
        this.item1 = item1;
    }

    public Item getItem2() {
        return item2;
    }

    public void setItem2(Item item2) {
        this.item2 = item2;
    }

    public BigDecimal getCorrelationCoefficient() {
        return correlationCoefficient;
    }

    public void setCorrelationCoefficient(BigDecimal correlationCoefficient) {
        this.correlationCoefficient = correlationCoefficient;
        classifyCorrelation();
    }

    public CorrelationType getCorrelationType() {
        return correlationType;
    }

    public void setCorrelationType(CorrelationType correlationType) {
        this.correlationType = correlationType;
    }

    public BigDecimal getConfidenceLevel() {
        return confidenceLevel;
    }

    public void setConfidenceLevel(BigDecimal confidenceLevel) {
        this.confidenceLevel = confidenceLevel;
    }

    public Integer getDataPoints() {
        return dataPoints;
    }

    public void setDataPoints(Integer dataPoints) {
        this.dataPoints = dataPoints;
    }

    public LocalDateTime getLastCalculated() {
        return lastCalculated;
    }

    public void setLastCalculated(LocalDateTime lastCalculated) {
        this.lastCalculated = lastCalculated;
    }

    public Category getCategory() {
        return category;
    }

    public void setCategory(Category category) {
        this.category = category;
    }

    public Integer getCoConsumptionCount() {
        return coConsumptionCount;
    }

    public void setCoConsumptionCount(Integer coConsumptionCount) {
        this.coConsumptionCount = coConsumptionCount;
    }

    public BigDecimal getAverageTimeGapDays() {
        return averageTimeGapDays;
    }

    public void setAverageTimeGapDays(BigDecimal averageTimeGapDays) {
        this.averageTimeGapDays = averageTimeGapDays;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public Boolean getIsActive() {
        return isActive;
    }

    public void setIsActive(Boolean isActive) {
        this.isActive = isActive;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    @Override
    public String toString() {
        return "ItemCorrelation{" +
                "id=" + id +
                ", item1=" + (item1 != null ? item1.getItemName() : null) +
                ", item2=" + (item2 != null ? item2.getItemName() : null) +
                ", correlationCoefficient=" + correlationCoefficient +
                ", correlationType=" + correlationType +
                ", dataPoints=" + dataPoints +
                '}';
    }
}