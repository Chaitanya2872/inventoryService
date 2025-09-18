package com.bmsedge.inventory.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "consumption_records",
        uniqueConstraints = @UniqueConstraint(columnNames = {"item_id", "consumption_date"}))
public class ConsumptionRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "item_id")
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

    @Size(max = 100)
    @Column(name = "department")
    private String department;

    @Size(max = 50)
    @Column(name = "cost_center")
    private String costCenter;

    @Column(name = "employee_count")
    private Integer employeeCount;

    @Column(name = "consumption_per_capita", precision = 10, scale = 4)
    private BigDecimal consumptionPerCapita;

    // ADDED: Notes field for import tracking
    @Size(max = 500)
    @Column(name = "notes")
    private String notes;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    // Constructors
    public ConsumptionRecord() {}

    public ConsumptionRecord(Item item, LocalDate consumptionDate, BigDecimal openingStock) {
        this.item = item;
        this.consumptionDate = consumptionDate;
        this.openingStock = openingStock;
        this.closingStock = openingStock; // Initially same as opening
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
                    BigDecimal.valueOf(employeeCount), 4, BigDecimal.ROUND_HALF_UP
            );
        }
    }

    // Helper method to check if consumption is abnormal (spike or drop)
    public boolean isAbnormalConsumption(BigDecimal avgConsumption) {
        if (consumedQuantity == null || avgConsumption == null ||
                avgConsumption.compareTo(BigDecimal.ZERO) == 0) return false;

        // Check if consumption is 50% above or below average
        BigDecimal ratio = consumedQuantity.divide(avgConsumption, 2, BigDecimal.ROUND_HALF_UP);
        return ratio.compareTo(BigDecimal.valueOf(1.5)) > 0 ||
                ratio.compareTo(BigDecimal.valueOf(0.5)) < 0;
    }

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Item getItem() { return item; }
    public void setItem(Item item) { this.item = item; }

    public LocalDate getConsumptionDate() { return consumptionDate; }
    public void setConsumptionDate(LocalDate consumptionDate) { this.consumptionDate = consumptionDate; }

    public BigDecimal getOpeningStock() { return openingStock; }
    public void setOpeningStock(BigDecimal openingStock) {
        this.openingStock = openingStock;
        calculateClosingStock();
    }

    public BigDecimal getReceivedQuantity() { return receivedQuantity; }
    public void setReceivedQuantity(BigDecimal receivedQuantity) {
        this.receivedQuantity = receivedQuantity;
        calculateClosingStock();
    }

    public BigDecimal getConsumedQuantity() { return consumedQuantity; }
    public void setConsumedQuantity(BigDecimal consumedQuantity) {
        this.consumedQuantity = consumedQuantity;
        calculateClosingStock();
        calculateConsumptionPerCapita();
    }

    // ADDED: Alias method for compatibility with import service
    public void setQuantityConsumed(BigDecimal quantityConsumed) {
        setConsumedQuantity(quantityConsumed);
    }

    public BigDecimal getQuantityConsumed() {
        return getConsumedQuantity();
    }

    public BigDecimal getClosingStock() { return closingStock; }
    public void setClosingStock(BigDecimal closingStock) { this.closingStock = closingStock; }

    public String getDepartment() { return department; }
    public void setDepartment(String department) { this.department = department; }

    public String getCostCenter() { return costCenter; }
    public void setCostCenter(String costCenter) { this.costCenter = costCenter; }

    public Integer getEmployeeCount() { return employeeCount; }
    public void setEmployeeCount(Integer employeeCount) {
        this.employeeCount = employeeCount;
        calculateConsumptionPerCapita();
    }

    public BigDecimal getConsumptionPerCapita() { return consumptionPerCapita; }
    public void setConsumptionPerCapita(BigDecimal consumptionPerCapita) {
        this.consumptionPerCapita = consumptionPerCapita;
    }

    // ADDED: Notes getter and setter
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}