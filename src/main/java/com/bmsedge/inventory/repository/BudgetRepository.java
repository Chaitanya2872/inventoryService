package com.bmsedge.inventory.repository;

import com.bmsedge.inventory.model.Budget;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface BudgetRepository extends JpaRepository<Budget, Long> {

    // Find budgets by category
    List<Budget> findByCategoryId(Long categoryId);

    // Find budgets by department
    List<Budget> findByDepartment(String department);

    // Find budgets by fiscal year
    List<Budget> findByFiscalYear(Integer fiscalYear);

    // Find budget for specific category and period
    Optional<Budget> findByCategoryIdAndFiscalYearAndPeriodType(Long categoryId, Integer fiscalYear, String periodType);

    // Find budgets by date range
    @Query("SELECT b FROM Budget b WHERE b.startDate <= :endDate AND b.endDate >= :startDate")
    List<Budget> findByDateRange(@Param("startDate") LocalDate startDate, @Param("endDate") LocalDate endDate);

    // Get total budget amount for category in fiscal year
    @Query("SELECT SUM(b.allocatedAmount) FROM Budget b WHERE b.categoryId = :categoryId AND b.fiscalYear = :fiscalYear")
    BigDecimal getTotalBudgetByCategoryAndYear(@Param("categoryId") Long categoryId, @Param("fiscalYear") Integer fiscalYear);

    // Get total budget amount for department in fiscal year
    @Query("SELECT SUM(b.allocatedAmount) FROM Budget b WHERE b.department = :department AND b.fiscalYear = :fiscalYear")
    BigDecimal getTotalBudgetByDepartmentAndYear(@Param("department") String department, @Param("fiscalYear") Integer fiscalYear);

    // Get budget utilization
    @Query("SELECT b.id, b.allocatedAmount, b.spentAmount, " +
            "(b.spentAmount / b.allocatedAmount * 100) as utilizationPercentage " +
            "FROM Budget b WHERE b.fiscalYear = :fiscalYear")
    List<Object[]> getBudgetUtilization(@Param("fiscalYear") Integer fiscalYear);

    // Find over-budget categories
    @Query("SELECT b FROM Budget b WHERE b.spentAmount > b.allocatedAmount AND b.fiscalYear = :fiscalYear")
    List<Budget> findOverBudgetCategories(@Param("fiscalYear") Integer fiscalYear);

    // Find budgets approaching limit (within threshold percentage)
    @Query("SELECT b FROM Budget b WHERE " +
            "(b.spentAmount / b.allocatedAmount) >= :threshold AND " +
            "(b.spentAmount / b.allocatedAmount) < 1.0 AND " +
            "b.fiscalYear = :fiscalYear")
    List<Budget> findBudgetsApproachingLimit(@Param("threshold") Double threshold, @Param("fiscalYear") Integer fiscalYear);

    // Get budget summary by fiscal year
    @Query("SELECT b.fiscalYear, SUM(b.allocatedAmount) as totalBudget, " +
            "SUM(b.spentAmount) as totalSpent, " +
            "(SUM(b.spentAmount) / SUM(b.allocatedAmount) * 100) as overallUtilization " +
            "FROM Budget b " +
            "GROUP BY b.fiscalYear " +
            "ORDER BY b.fiscalYear DESC")
    List<Object[]> getBudgetSummaryByYear();

    // Get monthly budget breakdown
    @Query("SELECT FUNCTION('MONTH', b.startDate) as month, " +
            "SUM(b.allocatedAmount) as monthlyBudget, " +
            "SUM(b.spentAmount) as monthlySpent " +
            "FROM Budget b " +
            "WHERE b.fiscalYear = :fiscalYear " +
            "GROUP BY FUNCTION('MONTH', b.startDate) " +
            "ORDER BY FUNCTION('MONTH', b.startDate)")
    List<Object[]> getMonthlyBudgetBreakdown(@Param("fiscalYear") Integer fiscalYear);

    // Find budgets by approval status
    List<Budget> findByApprovalStatus(String approvalStatus);

    // Get department-wise budget allocation
    @Query("SELECT b.department, SUM(b.allocatedAmount) as totalBudget, " +
            "COUNT(b.id) as budgetCount " +
            "FROM Budget b " +
            "WHERE b.fiscalYear = :fiscalYear " +
            "GROUP BY b.department " +
            "ORDER BY SUM(b.allocatedAmount) DESC")
    List<Object[]> getDepartmentWiseBudgetAllocation(@Param("fiscalYear") Integer fiscalYear);

    // Get category-wise budget allocation
    @Query("SELECT c.categoryName, SUM(b.allocatedAmount) as totalBudget, " +
            "SUM(b.spentAmount) as totalSpent " +
            "FROM Budget b JOIN Category c ON b.categoryId = c.id " +
            "WHERE b.fiscalYear = :fiscalYear " +
            "GROUP BY c.categoryName " +
            "ORDER BY SUM(b.allocatedAmount) DESC")
    List<Object[]> getCategoryWiseBudgetAllocation(@Param("fiscalYear") Integer fiscalYear);

    // Get budget variance analysis
    @Query("SELECT b.categoryId, c.categoryName, b.allocatedAmount, b.spentAmount, " +
            "(b.spentAmount - b.allocatedAmount) as variance, " +
            "((b.spentAmount - b.allocatedAmount) / b.allocatedAmount * 100) as variancePercentage " +
            "FROM Budget b JOIN Category c ON b.categoryId = c.id " +
            "WHERE b.fiscalYear = :fiscalYear " +
            "ORDER BY ((b.spentAmount - b.allocatedAmount) / b.allocatedAmount) DESC")
    List<Object[]> getBudgetVarianceAnalysis(@Param("fiscalYear") Integer fiscalYear);

    // Get quarterly budget performance
    @Query("SELECT FUNCTION('QUARTER', b.startDate) as quarter, " +
            "SUM(b.allocatedAmount) as quarterlyBudget, " +
            "SUM(b.spentAmount) as quarterlySpent, " +
            "(SUM(b.spentAmount) / SUM(b.allocatedAmount) * 100) as quarterlyUtilization " +
            "FROM Budget b " +
            "WHERE b.fiscalYear = :fiscalYear " +
            "GROUP BY FUNCTION('QUARTER', b.startDate) " +
            "ORDER BY FUNCTION('QUARTER', b.startDate)")
    List<Object[]> getQuarterlyBudgetPerformance(@Param("fiscalYear") Integer fiscalYear);

    // Find active budgets for current period
    @Query("SELECT b FROM Budget b WHERE :currentDate BETWEEN b.startDate AND b.endDate")
    List<Budget> findActiveBudgets(@Param("currentDate") LocalDate currentDate);

    // Get budget rollover information
    @Query("SELECT b FROM Budget b WHERE b.fiscalYear = :fiscalYear AND b.rolloverAmount > 0")
    List<Budget> findBudgetsWithRollover(@Param("fiscalYear") Integer fiscalYear);

    // Check if budget exists for specific criteria
    boolean existsByCategoryIdAndFiscalYearAndPeriodType(Long categoryId, Integer fiscalYear, String periodType);

    // Get budget history for a category
    @Query("SELECT b FROM Budget b WHERE b.categoryId = :categoryId ORDER BY b.fiscalYear DESC, b.startDate DESC")
    List<Budget> findBudgetHistoryByCategory(@Param("categoryId") Long categoryId);

    // Get upcoming budget expirations
    @Query("SELECT b FROM Budget b WHERE b.endDate BETWEEN :startDate AND :endDate ORDER BY b.endDate ASC")
    List<Budget> findUpcomingBudgetExpirations(@Param("startDate") LocalDate startDate, @Param("endDate") LocalDate endDate);
}