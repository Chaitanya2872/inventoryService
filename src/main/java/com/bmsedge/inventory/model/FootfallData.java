package com.bmsedge.inventory.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Min;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "footfall_data")
public class FootfallData {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotNull
    @Column(name = "date", unique = true)
    private LocalDate date;

    @NotNull
    @Min(0)
    @Column(name = "employee_count")
    private Integer employeeCount;

    @Min(0)
    @Column(name = "visitor_count")
    private Integer visitorCount = 0;

    // SIMPLIFIED: Remove calculated field complexity
    @Column(name = "total_footfall")
    private Integer totalFootfall;

    @Column(name = "department")
    private String department;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    // Constructors
    public FootfallData() {}

    public FootfallData(LocalDate date, Integer employeeCount) {
        this.date = date;
        this.employeeCount = employeeCount;
        this.visitorCount = 0;
        this.totalFootfall = employeeCount;
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    public FootfallData(LocalDate date, Integer employeeCount, Integer visitorCount) {
        this.date = date;
        this.employeeCount = employeeCount;
        this.visitorCount = visitorCount != null ? visitorCount : 0;
        this.totalFootfall = employeeCount + this.visitorCount;
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        updatedAt = LocalDateTime.now();
        calculateAndSetTotalFootfall();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
        calculateAndSetTotalFootfall();
    }

    // SIMPLIFIED: Direct calculation without complexity
    private void calculateAndSetTotalFootfall() {
        int empCount = employeeCount != null ? employeeCount : 0;
        int visCount = visitorCount != null ? visitorCount : 0;
        this.totalFootfall = empCount + visCount;
    }

    // Getters and Setters
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public LocalDate getDate() {
        return date;
    }

    public void setDate(LocalDate date) {
        this.date = date;
    }

    public Integer getEmployeeCount() {
        return employeeCount;
    }

    public void setEmployeeCount(Integer employeeCount) {
        this.employeeCount = employeeCount;
        calculateAndSetTotalFootfall();
    }

    public Integer getVisitorCount() {
        return visitorCount != null ? visitorCount : 0;
    }

    public void setVisitorCount(Integer visitorCount) {
        this.visitorCount = visitorCount != null ? visitorCount : 0;
        calculateAndSetTotalFootfall();
    }

    public Integer getTotalFootfall() {
        // Always calculate fresh to ensure consistency
        if (totalFootfall == null) {
            calculateAndSetTotalFootfall();
        }
        return totalFootfall;
    }

    public void setTotalFootfall(Integer totalFootfall) {
        this.totalFootfall = totalFootfall;
    }

    public String getDepartment() {
        return department;
    }

    public void setDepartment(String department) {
        this.department = department;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
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

    // Utility methods
    public boolean isWeekend() {
        if (date == null) return false;
        int dayOfWeek = date.getDayOfWeek().getValue();
        return dayOfWeek == 6 || dayOfWeek == 7;
    }

    public boolean isWeekday() {
        return !isWeekend();
    }

    public double getEmployeeToVisitorRatio() {
        int visCount = getVisitorCount();
        if (visCount == 0) {
            return employeeCount != null ? employeeCount.doubleValue() : 0.0;
        }
        return employeeCount != null ? (double) employeeCount / visCount : 0.0;
    }

    public boolean isAboveThreshold(int threshold) {
        return getTotalFootfall() > threshold;
    }

    public String getFootfallCategory() {
        int total = getTotalFootfall();
        if (total == 0) return "No Footfall";
        if (total <= 10) return "Low";
        if (total <= 50) return "Medium";
        if (total <= 100) return "High";
        return "Very High";
    }

    @Override
    public String toString() {
        return "FootfallData{" +
                "id=" + id +
                ", date=" + date +
                ", employeeCount=" + employeeCount +
                ", visitorCount=" + getVisitorCount() +
                ", totalFootfall=" + getTotalFootfall() +
                ", department='" + department + '\'' +
                ", notes='" + notes + '\'' +
                '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof FootfallData)) return false;
        FootfallData that = (FootfallData) o;
        return date != null && date.equals(that.date);
    }

    @Override
    public int hashCode() {
        return date != null ? date.hashCode() : 0;
    }
}