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
public interface FootfallRepository extends JpaRepository<FootfallData, Long> {

    Optional<FootfallData> findByDate(LocalDate date);

    List<FootfallData> findByDateBetween(LocalDate startDate, LocalDate endDate);

    List<FootfallData> findByDateBetweenAndDepartment(LocalDate startDate, LocalDate endDate, String department);

    @Query("SELECT f FROM FootfallData f WHERE f.date >= :startDate AND f.date <= :endDate ORDER BY f.date")
    List<FootfallData> findFootfallInPeriod(@Param("startDate") LocalDate startDate,
                                            @Param("endDate") LocalDate endDate);

    @Query("SELECT AVG(f.employeeCount) FROM FootfallData f WHERE f.date >= :startDate AND f.date <= :endDate")
    Double getAverageFootfallInPeriod(@Param("startDate") LocalDate startDate,
                                      @Param("endDate") LocalDate endDate);

    @Query("SELECT SUM(f.employeeCount) FROM FootfallData f WHERE f.date >= :startDate AND f.date <= :endDate")
    Long getTotalFootfallInPeriod(@Param("startDate") LocalDate startDate,
                                  @Param("endDate") LocalDate endDate);

    // Weekly aggregation
    @Query(value = "SELECT DATE_TRUNC('week', date) as week_start, " +
            "SUM(employee_count) as total_footfall, " +
            "AVG(employee_count) as avg_footfall " +
            "FROM footfall_data " +
            "WHERE date >= :startDate AND date <= :endDate " +
            "GROUP BY week_start " +
            "ORDER BY week_start", nativeQuery = true)
    List<Object[]> getWeeklyFootfall(@Param("startDate") LocalDate startDate,
                                     @Param("endDate") LocalDate endDate);

    // Monthly aggregation
    @Query(value = "SELECT DATE_TRUNC('month', date) as month_start, " +
            "SUM(employee_count) as total_footfall, " +
            "AVG(employee_count) as avg_footfall " +
            "FROM footfall_data " +
            "WHERE date >= :startDate AND date <= :endDate " +
            "GROUP BY month_start " +
            "ORDER BY month_start", nativeQuery = true)
    List<Object[]> getMonthlyFootfall(@Param("startDate") LocalDate startDate,
                                      @Param("endDate") LocalDate endDate);

    // Check if data exists for a date
    boolean existsByDate(LocalDate date);
}