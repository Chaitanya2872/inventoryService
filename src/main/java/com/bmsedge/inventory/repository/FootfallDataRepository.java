package com.bmsedge.inventory.repository;

import com.bmsedge.inventory.model.FootfallData;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface FootfallDataRepository extends JpaRepository<FootfallData, Long> {

    // Basic CRUD methods
    Optional<FootfallData> findByDate(LocalDate date);
    List<FootfallData> findByDateBetween(LocalDate startDate, LocalDate endDate);
    List<FootfallData> findByDateBetweenOrderByDateAsc(LocalDate startDate, LocalDate endDate);
    List<FootfallData> findByDepartment(String department);
    List<FootfallData> findByDepartmentAndDateBetween(String department, LocalDate startDate, LocalDate endDate);
    boolean existsByDate(LocalDate date);

    // Analytical queries for footfall trends
    @Query("SELECT AVG(f.employeeCount) FROM FootfallData f WHERE f.date >= :startDate AND f.date <= :endDate")
    Double getAverageEmployeeCountInPeriod(@Param("startDate") LocalDate startDate, @Param("endDate") LocalDate endDate);

    @Query("SELECT AVG(f.visitorCount) FROM FootfallData f WHERE f.date >= :startDate AND f.date <= :endDate")
    Double getAverageVisitorCountInPeriod(@Param("startDate") LocalDate startDate, @Param("endDate") LocalDate endDate);

    @Query("SELECT AVG(f.totalFootfall) FROM FootfallData f WHERE f.date >= :startDate AND f.date <= :endDate")
    Double getAverageTotalFootfallInPeriod(@Param("startDate") LocalDate startDate, @Param("endDate") LocalDate endDate);

    @Query("SELECT SUM(f.employeeCount) FROM FootfallData f WHERE f.date >= :startDate AND f.date <= :endDate")
    Long getTotalEmployeeCountInPeriod(@Param("startDate") LocalDate startDate, @Param("endDate") LocalDate endDate);

    @Query("SELECT SUM(f.totalFootfall) FROM FootfallData f WHERE f.date >= :startDate AND f.date <= :endDate")
    Long getTotalFootfallInPeriod(@Param("startDate") LocalDate startDate, @Param("endDate") LocalDate endDate);

    // Weekly aggregation
    @Query(value = "SELECT DATE_TRUNC('week', date) as week_start, " +
            "SUM(employee_count) as total_employees, " +
            "SUM(visitor_count) as total_visitors, " +
            "SUM(total_footfall) as total_footfall, " +
            "AVG(employee_count) as avg_employees, " +
            "AVG(visitor_count) as avg_visitors, " +
            "AVG(total_footfall) as avg_footfall " +
            "FROM footfall_data " +
            "WHERE date >= :startDate AND date <= :endDate " +
            "GROUP BY week_start " +
            "ORDER BY week_start", nativeQuery = true)
    List<Object[]> getWeeklyFootfall(@Param("startDate") LocalDate startDate,
                                     @Param("endDate") LocalDate endDate);

    // Monthly aggregation
    @Query(value = "SELECT DATE_TRUNC('month', date) as month_start, " +
            "SUM(employee_count) as total_employees, " +
            "SUM(visitor_count) as total_visitors, " +
            "SUM(total_footfall) as total_footfall, " +
            "AVG(employee_count) as avg_employees, " +
            "AVG(visitor_count) as avg_visitors, " +
            "AVG(total_footfall) as avg_footfall, " +
            "COUNT(*) as days_recorded " +
            "FROM footfall_data " +
            "WHERE date >= :startDate AND date <= :endDate " +
            "GROUP BY month_start " +
            "ORDER BY month_start", nativeQuery = true)
    List<Object[]> getMonthlyFootfall(@Param("startDate") LocalDate startDate,
                                      @Param("endDate") LocalDate endDate);

    // Daily pattern analysis
    @Query(value = "SELECT EXTRACT(DOW FROM date) as day_of_week, " +
            "CASE EXTRACT(DOW FROM date) " +
            "WHEN 0 THEN 'Sunday' " +
            "WHEN 1 THEN 'Monday' " +
            "WHEN 2 THEN 'Tuesday' " +
            "WHEN 3 THEN 'Wednesday' " +
            "WHEN 4 THEN 'Thursday' " +
            "WHEN 5 THEN 'Friday' " +
            "WHEN 6 THEN 'Saturday' " +
            "END as day_name, " +
            "AVG(employee_count) as avg_employees, " +
            "AVG(total_footfall) as avg_footfall, " +
            "COUNT(*) as record_count " +
            "FROM footfall_data " +
            "WHERE date >= :startDate AND date <= :endDate " +
            "GROUP BY EXTRACT(DOW FROM date) " +
            "ORDER BY EXTRACT(DOW FROM date)", nativeQuery = true)
    List<Object[]> getDayOfWeekPattern(@Param("startDate") LocalDate startDate,
                                       @Param("endDate") LocalDate endDate);

    // Find peak and low footfall days
    @Query("SELECT f.date, f.totalFootfall FROM FootfallData f " +
            "WHERE f.date >= :startDate AND f.date <= :endDate " +
            "ORDER BY f.totalFootfall DESC")
    List<Object[]> findPeakFootfallDays(@Param("startDate") LocalDate startDate,
                                        @Param("endDate") LocalDate endDate);

    @Query("SELECT f.date, f.totalFootfall FROM FootfallData f " +
            "WHERE f.date >= :startDate AND f.date <= :endDate " +
            "AND f.totalFootfall > 0 " +
            "ORDER BY f.totalFootfall ASC")
    List<Object[]> findLowFootfallDays(@Param("startDate") LocalDate startDate,
                                       @Param("endDate") LocalDate endDate);

    // Get footfall data with statistics
    @Query("SELECT f, " +
            "(SELECT AVG(f2.totalFootfall) FROM FootfallData f2 WHERE f2.date >= :startDate AND f2.date <= :endDate) as avgFootfall, " +
            "(SELECT MAX(f2.totalFootfall) FROM FootfallData f2 WHERE f2.date >= :startDate AND f2.date <= :endDate) as maxFootfall, " +
            "(SELECT MIN(f2.totalFootfall) FROM FootfallData f2 WHERE f2.date >= :startDate AND f2.date <= :endDate AND f2.totalFootfall > 0) as minFootfall " +
            "FROM FootfallData f " +
            "WHERE f.date >= :startDate AND f.date <= :endDate " +
            "ORDER BY f.date ASC")
    List<Object[]> getFootfallWithStatistics(@Param("startDate") LocalDate startDate,
                                             @Param("endDate") LocalDate endDate);

    // Get footfall trends (comparing periods)
    @Query("SELECT " +
            "AVG(CASE WHEN f.date >= :currentPeriodStart THEN f.totalFootfall ELSE NULL END) as currentPeriodAvg, " +
            "AVG(CASE WHEN f.date < :currentPeriodStart THEN f.totalFootfall ELSE NULL END) as previousPeriodAvg, " +
            "COUNT(CASE WHEN f.date >= :currentPeriodStart THEN 1 ELSE NULL END) as currentPeriodDays, " +
            "COUNT(CASE WHEN f.date < :currentPeriodStart THEN 1 ELSE NULL END) as previousPeriodDays " +
            "FROM FootfallData f " +
            "WHERE f.date >= :previousPeriodStart AND f.date <= :currentPeriodEnd")
    List<Object[]> getFootfallTrend(@Param("previousPeriodStart") LocalDate previousPeriodStart,
                                    @Param("currentPeriodStart") LocalDate currentPeriodStart,
                                    @Param("currentPeriodEnd") LocalDate currentPeriodEnd);

    // Get department-wise footfall comparison
    @Query("SELECT f.department, " +
            "COUNT(f.id) as recordCount, " +
            "AVG(f.employeeCount) as avgEmployees, " +
            "AVG(f.totalFootfall) as avgFootfall, " +
            "SUM(f.totalFootfall) as totalFootfall " +
            "FROM FootfallData f " +
            "WHERE f.date >= :startDate AND f.date <= :endDate " +
            "AND f.department IS NOT NULL " +
            "GROUP BY f.department " +
            "ORDER BY totalFootfall DESC")
    List<Object[]> getDepartmentWiseFootfall(@Param("startDate") LocalDate startDate,
                                             @Param("endDate") LocalDate endDate);

    // Get footfall data with missing dates
    @Query(value = "SELECT generate_series(:startDate\\:\\:date, :endDate\\:\\:date, '1 day'\\:\\:interval)\\:\\:date as date, " +
            "COALESCE(f.employee_count, 0) as employee_count, " +
            "COALESCE(f.visitor_count, 0) as visitor_count, " +
            "COALESCE(f.total_footfall, 0) as total_footfall " +
            "FROM generate_series(:startDate\\:\\:date, :endDate\\:\\:date, '1 day'\\:\\:interval) gs(date) " +
            "LEFT JOIN footfall_data f ON f.date = gs.date " +
            "ORDER BY gs.date", nativeQuery = true)
    List<Object[]> getFootfallDataWithMissingDates(@Param("startDate") LocalDate startDate,
                                                   @Param("endDate") LocalDate endDate);

    // Get recent footfall data for dashboard
    @Query("SELECT f FROM FootfallData f WHERE f.date >= :sinceDate ORDER BY f.date DESC")
    List<FootfallData> getRecentFootfallData(@Param("sinceDate") LocalDate sinceDate);

    // Get data range information
    @Query("SELECT MIN(f.date) as minDate, MAX(f.date) as maxDate, COUNT(f.id) as totalRecords FROM FootfallData f")
    List<Object[]> getDataRangeInfo();

    // Get footfall data for specific days of week
    @Query(value = "SELECT f.* FROM footfall_data f " +
            "WHERE EXTRACT(DOW FROM f.date) IN :daysOfWeek " +
            "AND f.date >= :startDate AND f.date <= :endDate " +
            "ORDER BY f.date ASC", nativeQuery = true)
    List<FootfallData> getFootfallForDaysOfWeek(@Param("daysOfWeek") List<Integer> daysOfWeek,
                                                @Param("startDate") LocalDate startDate,
                                                @Param("endDate") LocalDate endDate);

    // Get footfall above/below threshold
    @Query("SELECT f FROM FootfallData f " +
            "WHERE f.date >= :startDate AND f.date <= :endDate " +
            "AND f.totalFootfall >= :threshold " +
            "ORDER BY f.totalFootfall DESC")
    List<FootfallData> getHighFootfallDays(@Param("startDate") LocalDate startDate,
                                           @Param("endDate") LocalDate endDate,
                                           @Param("threshold") Integer threshold);

    @Query("SELECT f FROM FootfallData f " +
            "WHERE f.date >= :startDate AND f.date <= :endDate " +
            "AND f.totalFootfall <= :threshold AND f.totalFootfall > 0 " +
            "ORDER BY f.totalFootfall ASC")
    List<FootfallData> getLowFootfallDays(@Param("startDate") LocalDate startDate,
                                          @Param("endDate") LocalDate endDate,
                                          @Param("threshold") Integer threshold);

    // Get monthly growth rates
    @Query(value = "WITH monthly_data AS (" +
            "  SELECT DATE_TRUNC('month', date) as month, " +
            "         AVG(total_footfall) as avg_footfall " +
            "  FROM footfall_data " +
            "  WHERE date >= :startDate AND date <= :endDate " +
            "  GROUP BY DATE_TRUNC('month', date) " +
            ") " +
            "SELECT month, avg_footfall, " +
            "       LAG(avg_footfall) OVER (ORDER BY month) as previous_month, " +
            "       CASE " +
            "         WHEN LAG(avg_footfall) OVER (ORDER BY month) > 0 THEN " +
            "           ((avg_footfall - LAG(avg_footfall) OVER (ORDER BY month)) / LAG(avg_footfall) OVER (ORDER BY month)) * 100 " +
            "         ELSE NULL " +
            "       END as growth_rate " +
            "FROM monthly_data " +
            "ORDER BY month", nativeQuery = true)
    List<Object[]> getMonthlyGrowthRates(@Param("startDate") LocalDate startDate,
                                         @Param("endDate") LocalDate endDate);
}