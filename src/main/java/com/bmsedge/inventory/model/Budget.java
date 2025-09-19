package com.bmsedge.inventory.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "budgets")
public class Budget {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotNull
    @Column(name = "category_id")
    private Long categoryId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id", insertable = false, updatable = false)
    private Category category;

    @NotNull
    @Column(name = "fiscal_year")
    private Integer fiscalYear;

    @Size(max = 50)
    @Column(name = "period_type")
    private String periodType; // MONTHLY, QUARTERLY, YEARLY

    @NotNull
    @Column(name = "start_date")
    private LocalDate startDate;

    @NotNull
    @Column(name = "end_date")
    private LocalDate endDate;

    @NotNull
    @Min(0)
    @Column(name = "allocated_amount", precision = 12, scale = 2)
    private BigDecimal allocatedAmount;

    @Column(name = "spent_amount", precision = 12, scale = 2)
    private BigDecimal spentAmount = BigDecimal.ZERO;

    @Column(name = "committed_amount", precision = 12, scale = 2)
    private BigDecimal committedAmount = BigDecimal.ZERO;

    @Column(name = "available_amount", precision = 12, scale = 2)
    private BigDecimal availableAmount;

    @Column(name = "rollover_amount", precision = 12, scale = 2)
    private BigDecimal rolloverAmount = BigDecimal.ZERO;

    @Size(max = 100)
    @Column(name = "department")
    private String department;

    @Size(max = 50)
    @Column(name = "cost_center")
    private String costCenter;

    @Size(max = 50)
    @Column(name = "approval_status")
    private String approvalStatus = "DRAFT"; // DRAFT, PENDING, APPROVED, REJECTED

    @Size(max = 100)
    @Column(name = "approved_by")
    private String approvedBy;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    @Size(max = 500)
    @Column(name = "description")
    private String description;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    @Column(name = "is_active")
    private Boolean isActive = true;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    // Constructors
    public Budget() {}

    public Budget(Long categoryId, Integer fiscalYear, String periodType, LocalDate startDate,
                  LocalDate endDate, BigDecimal allocatedAmount, String department, Long createdBy) {
        this.categoryId = categoryId;
        this.fiscalYear = fiscalYear;
        this.periodType = periodType;
        this.startDate = startDate;
        this.endDate = endDate;
        this.allocatedAmount = allocatedAmount;
        this.department = department;
        this.createdBy = createdBy;
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
        calculateAvailableAmount();
    }

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        calculateAvailableAmount();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
        calculateAvailableAmount();
    }

    // Business methods
    public void calculateAvailableAmount() {
        BigDecimal spent = spentAmount != null ? spentAmount : BigDecimal.ZERO;
        BigDecimal committed = committedAmount != null ? committedAmount : BigDecimal.ZERO;
        BigDecimal rollover = rolloverAmount != null ? rolloverAmount : BigDecimal.ZERO;

        this.availableAmount = allocatedAmount.add(rollover).subtract(spent).subtract(committed);
    }

    public BigDecimal getUtilizationPercentage() {
        if (allocatedAmount.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        return spentAmount.divide(allocatedAmount, 4, BigDecimal.ROUND_HALF_UP).multiply(BigDecimal.valueOf(100));
    }

    public BigDecimal getVarianceAmount() {
        return spentAmount.subtract(allocatedAmount);
    }

    public BigDecimal getVariancePercentage() {
        if (allocatedAmount.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        return getVarianceAmount().divide(allocatedAmount, 4, BigDecimal.ROUND_HALF_UP).multiply(BigDecimal.valueOf(100));
    }

    public boolean isOverBudget() {
        return spentAmount.compareTo(allocatedAmount) > 0;
    }

    public boolean isNearBudgetLimit(double threshold) {
        double utilization = getUtilizationPercentage().doubleValue();
        return utilization >= (threshold * 100) && utilization < 100.0;
    }

    public String getBudgetStatus() {
        double utilization = getUtilizationPercentage().doubleValue();

        if (utilization >= 100.0) {
            return "OVER_BUDGET";
        } else if (utilization >= 90.0) {
            return "CRITICAL";
        } else if (utilization >= 75.0) {
            return "WARNING";
        } else if (utilization >= 50.0) {
            return "ON_TRACK";
        } else {
            return "UNDER_UTILIZED";
        }
    }

    public void updateSpentAmount(BigDecimal additionalSpending) {
        this.spentAmount = this.spentAmount.add(additionalSpending);
        calculateAvailableAmount();
    }

    public boolean canSpend(BigDecimal amount) {
        return availableAmount.compareTo(amount) >= 0;
    }

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getCategoryId() { return categoryId; }
    public void setCategoryId(Long categoryId) { this.categoryId = categoryId; }

    public Category getCategory() { return category; }
    public void setCategory(Category category) { this.category = category; }

    public Integer getFiscalYear() { return fiscalYear; }
    public void setFiscalYear(Integer fiscalYear) { this.fiscalYear = fiscalYear; }

    public String getPeriodType() { return periodType; }
    public void setPeriodType(String periodType) { this.periodType = periodType; }

    public LocalDate getStartDate() { return startDate; }
    public void setStartDate(LocalDate startDate) { this.startDate = startDate; }

    public LocalDate getEndDate() { return endDate; }
    public void setEndDate(LocalDate endDate) { this.endDate = endDate; }

    public BigDecimal getAllocatedAmount() { return allocatedAmount; }
    public void setAllocatedAmount(BigDecimal allocatedAmount) {
        this.allocatedAmount = allocatedAmount;
        calculateAvailableAmount();
    }

    public BigDecimal getSpentAmount() { return spentAmount; }
    public void setSpentAmount(BigDecimal spentAmount) {
        this.spentAmount = spentAmount;
        calculateAvailableAmount();
    }

    public BigDecimal getCommittedAmount() { return committedAmount; }
    public void setCommittedAmount(BigDecimal committedAmount) {
        this.committedAmount = committedAmount;
        calculateAvailableAmount();
    }

    public BigDecimal getAvailableAmount() { return availableAmount; }
    public void setAvailableAmount(BigDecimal availableAmount) { this.availableAmount = availableAmount; }

    public BigDecimal getRolloverAmount() { return rolloverAmount; }
    public void setRolloverAmount(BigDecimal rolloverAmount) {
        this.rolloverAmount = rolloverAmount;
        calculateAvailableAmount();
    }

    public String getDepartment() { return department; }
    public void setDepartment(String department) { this.department = department; }

    public String getCostCenter() { return costCenter; }
    public void setCostCenter(String costCenter) { this.costCenter = costCenter; }

    public String getApprovalStatus() { return approvalStatus; }
    public void setApprovalStatus(String approvalStatus) { this.approvalStatus = approvalStatus; }

    public String getApprovedBy() { return approvedBy; }
    public void setApprovedBy(String approvedBy) { this.approvedBy = approvedBy; }

    public LocalDateTime getApprovedAt() { return approvedAt; }
    public void setApprovedAt(LocalDateTime approvedAt) { this.approvedAt = approvedAt; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }

    public Boolean getIsActive() { return isActive; }
    public void setIsActive(Boolean isActive) { this.isActive = isActive; }

    public Long getCreatedBy() { return createdBy; }
    public void setCreatedBy(Long createdBy) { this.createdBy = createdBy; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    @Override
    public String toString() {
        return "Budget{" +
                "id=" + id +
                ", categoryId=" + categoryId +
                ", fiscalYear=" + fiscalYear +
                ", periodType='" + periodType + '\'' +
                ", allocatedAmount=" + allocatedAmount +
                ", spentAmount=" + spentAmount +
                ", availableAmount=" + availableAmount +
                ", department='" + department + '\'' +
                ", approvalStatus='" + approvalStatus + '\'' +
                '}';
    }
}