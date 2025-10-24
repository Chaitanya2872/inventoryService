package com.bmsedge.inventory.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Getter
@Entity
@Table(name = "consumption_records",
        uniqueConstraints = @UniqueConstraint(
                columnNames = {"item_id", "consumption_date"}
        ),
        indexes = {
                @Index(name = "idx_consumption_date", columnList = "consumption_date"),
                @Index(name = "idx_item_date", columnList = "item_id, consumption_date")
        })
public class ConsumptionRecord {
    // Getters and Setters
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "item_id", nullable = false)
    @NotNull
    private Item item;

    @NotNull
    @Column(name = "consumption_date")
    private LocalDate consumptionDate;

    @Column(name = "opening_stock", precision = 10, scale = 2)
    private BigDecimal openingStock;

    @Column(name = "received_quantity", precision = 10, scale = 2)
    private BigDecimal receivedQuantity = BigDecimal.ZERO;

    @Column(name = "consumed_quantity", precision = 10, scale = 2)
    private BigDecimal consumedQuantity = BigDecimal.ZERO;

    @Column(name = "closing_stock", precision = 10, scale = 2)
    private BigDecimal closingStock;

    @Column(name = "department", length = 100)
    private String department;

    @Column(name = "cost_center", length = 50)
    private String costCenter;

    @Column(name = "employee_count")
    private Integer employeeCount;

    @Column(name = "consumption_per_capita", precision = 10, scale = 4)
    private BigDecimal consumptionPerCapita;

    @Column(name = "notes", length = 500)
    private String notes;

    @Column(name = "daily_consumption_mean")
    private Double dailyConsumptionMean;

    @Column(name = "daily_consumption_std")
    private Double dailyConsumptionStd;

    @Column(name = "daily_consumption_cv")
    private Double dailyConsumptionCv;

    @Column(name = "volatility_classification")
    private String volatilityClassification;

    @Column(name = "is_verified")
    private Boolean isVerified = false;

    @Column(name = "waste_percentage")
    private Double wastePercentage;

    @Column(name = "efficiency_score")
    private Double efficiencyScore;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    // Constructors
    public ConsumptionRecord() {}

    public ConsumptionRecord(Item item, LocalDate consumptionDate,
                             BigDecimal openingStock) {
        this.item = item;
        this.consumptionDate = consumptionDate;
        this.openingStock = openingStock;
        this.closingStock = openingStock;
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        calculateClosingStock();
        calculateConsumptionPerCapita();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
        calculateClosingStock();
        calculateConsumptionPerCapita();
    }

    // Business methods
    public void calculateClosingStock() {
        if (openingStock != null) {
            BigDecimal total = openingStock;
            if (receivedQuantity != null) {
                total = total.add(receivedQuantity);
            }
            if (consumedQuantity != null) {
                total = total.subtract(consumedQuantity);
            }
            this.closingStock = total;
        }
    }

    public void calculateConsumptionPerCapita() {
        if (consumedQuantity != null && employeeCount != null && employeeCount > 0) {
            this.consumptionPerCapita = consumedQuantity.divide(
                    BigDecimal.valueOf(employeeCount),
                    4,
                    BigDecimal.ROUND_HALF_UP
            );
        }
    }

    public void setId(Long id) { this.id = id; }

    public void setItem(Item item) { this.item = item; }

    public void setConsumptionDate(LocalDate consumptionDate) {
        this.consumptionDate = consumptionDate;
    }

    public void setOpeningStock(BigDecimal openingStock) {
        this.openingStock = openingStock;
        calculateClosingStock();
    }

    public void setReceivedQuantity(BigDecimal receivedQuantity) {
        this.receivedQuantity = receivedQuantity;
        calculateClosingStock();
    }

    public void setConsumedQuantity(BigDecimal consumedQuantity) {
        this.consumedQuantity = consumedQuantity;
        calculateClosingStock();
        calculateConsumptionPerCapita();
    }

    public void setClosingStock(BigDecimal closingStock) {
        this.closingStock = closingStock;
    }

    public void setDepartment(String department) {
        this.department = department;
    }

    public void setCostCenter(String costCenter) {
        this.costCenter = costCenter;
    }

    public void setEmployeeCount(Integer employeeCount) {
        this.employeeCount = employeeCount;
        calculateConsumptionPerCapita();
    }

    public void setConsumptionPerCapita(BigDecimal consumptionPerCapita) {
        this.consumptionPerCapita = consumptionPerCapita;
    }

    public void setNotes(String notes) { this.notes = notes; }

    public void setDailyConsumptionMean(Double dailyConsumptionMean) {
        this.dailyConsumptionMean = dailyConsumptionMean;
    }

    public void setDailyConsumptionStd(Double dailyConsumptionStd) {
        this.dailyConsumptionStd = dailyConsumptionStd;
    }

    public void setDailyConsumptionCv(Double dailyConsumptionCv) {
        this.dailyConsumptionCv = dailyConsumptionCv;
    }

    public void setVolatilityClassification(String volatilityClassification) {
        this.volatilityClassification = volatilityClassification;
    }

    public void setIsVerified(Boolean isVerified) {
        this.isVerified = isVerified;
    }

    public void setWastePercentage(Double wastePercentage) {
        this.wastePercentage = wastePercentage;
    }

    public void setEfficiencyScore(Double efficiencyScore) {
        this.efficiencyScore = efficiencyScore;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }


}
