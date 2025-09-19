package com.bmsedge.inventory.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Comprehensive response DTOs for budget consumption and cost distribution analysis
 */
public class BudgetCostAnalysisResponse {

    @Getter
    @Setter
    public static class BudgetConsumptionResponse {
        private String period;
        private String budgetType;
        private String startDate;
        private String endDate;
        private Map<String, BigDecimal> budgetAllocations;
        private ActualDataSummary actualData;
        private VarianceAnalysis varianceAnalysis;
        private List<BudgetTimeSeriesData> timeSeriesData;
        private BudgetProjections projections;
        private BudgetSummaryMetrics summary;
        private LocalDateTime generatedAt;

        public BudgetConsumptionResponse() {
            this.generatedAt = LocalDateTime.now();
        }
    }

    @Getter
    @Setter
    public static class EnhancedCostDistributionResponse {
        private String period;
        private String breakdown;
        private String startDate;
        private String endDate;
        private CostBreakdownSummary costBreakdown;
        private List<CostTrendData> costTrends;
        private List<CostDriverAnalysis> topCostDrivers;
        private CostEfficiencyMetrics efficiencyMetrics;
        private CostProjections projections;
        private ComparativeAnalysis comparativeAnalysis;
        private LocalDateTime generatedAt;

        public EnhancedCostDistributionResponse() {
            this.generatedAt = LocalDateTime.now();
        }
    }

    @Getter
    @Setter
    public static class BudgetVsActualResponse {
        private Integer year;
        private String granularity;
        private Integer quarter;
        private String period;
        private Map<String, BigDecimal> budgetedAmounts;
        private List<BudgetActualComparison> actualVsBudget;
        private VarianceMetrics varianceMetrics;
        private BudgetForecasts forecasts;
        private PerformanceIndicators performanceIndicators;
        private LocalDateTime generatedAt;

        public BudgetVsActualResponse() {
            this.generatedAt = LocalDateTime.now();
        }
    }

    @Getter
    @Setter
    public static class CostOptimizationResponse {
        private String analysisType;
        private Double threshold;
        private String period;
        private Double minSavings;
        private List<CostPatternAnalysis> costAnalysis;
        private List<OptimizationRecommendation> recommendations;
        private SavingsAnalysis savingsAnalysis;
        private Integer totalRecommendations;
        private LocalDateTime generatedAt;

        public CostOptimizationResponse() {
            this.generatedAt = LocalDateTime.now();
        }
    }

    // Supporting DTOs
    @Getter
    @Setter
    public static class ActualDataSummary {
        private BigDecimal totalCost;
        private BigDecimal totalQuantity;
        private String period;
        private String startDate;
        private String endDate;
        private Integer itemCount;
        private BigDecimal averageUnitCost;
    }

    @Getter
    @Setter
    public static class VarianceAnalysis {
        private BigDecimal budgetAmount;
        private BigDecimal actualAmount;
        private BigDecimal varianceAmount;
        private BigDecimal variancePercentage;
        private String status; // OVER_BUDGET, UNDER_BUDGET, ON_TARGET
        private String severity; // LOW, MEDIUM, HIGH, CRITICAL
        private List<String> recommendations;
    }

    @Getter
    @Setter
    public static class BudgetTimeSeriesData {
        private String date;
        private String period;
        private BigDecimal budgetAmount;
        private BigDecimal actualAmount;
        private BigDecimal variance;
        private BigDecimal cumulativeBudget;
        private BigDecimal cumulativeActual;
        private BigDecimal utilizationPercentage;
    }

    @Getter
    @Setter
    public static class BudgetProjections {
        private BigDecimal projectedMonthlySpend;
        private BigDecimal projectedYearEndSpend;
        private String projectionDate;
        private BigDecimal projectedVariance;
        private String projectedStatus;
        private Double confidenceLevel;
        private List<ProjectionScenario> scenarios;
    }

    @Getter
    @Setter
    public static class ProjectionScenario {
        private String scenario; // CONSERVATIVE, REALISTIC, AGGRESSIVE
        private BigDecimal projectedAmount;
        private BigDecimal projectedVariance;
        private Double probability;
    }

    @Getter
    @Setter
    public static class BudgetSummaryMetrics {
        private BigDecimal budgetUtilization;
        private BigDecimal remainingBudget;
        private Long daysInPeriod;
        private Long remainingDays;
        private BigDecimal dailyBurnRate;
        private BigDecimal projectedOverrun;
        private String riskLevel;
    }

    @Getter
    @Setter
    public static class CostBreakdownSummary {
        private BigDecimal totalCost;
        private Map<String, BigDecimal> categoryBreakdown;
        private Map<String, BigDecimal> departmentBreakdown;
        private Map<String, BigDecimal> itemTypeBreakdown;
        private List<CostCategory> topCategories;
        private BigDecimal averageMonthlyCost;
        private String costTrend;
    }

    @Getter
    @Setter
    public static class CostCategory {
        private String categoryName;
        private BigDecimal amount;
        private BigDecimal percentage;
        private String trend; // INCREASING, DECREASING, STABLE
        private BigDecimal monthOverMonthChange;
    }

    @Getter
    @Setter
    public static class CostTrendData {
        private String period;
        private String date;
        private BigDecimal totalCost;
        private BigDecimal quantity;
        private BigDecimal averageUnitCost;
        private BigDecimal periodOverPeriodChange;
        private String trendDirection;
    }

    @Getter
    @Setter
    public static class CostDriverAnalysis {
        private String itemName;
        private String categoryName;
        private BigDecimal totalCost;
        private BigDecimal quantity;
        private BigDecimal unitCost;
        private BigDecimal percentageOfTotal;
        private String impactLevel; // HIGH, MEDIUM, LOW
        private List<String> optimizationOpportunities;
    }

    @Getter
    @Setter
    public static class CostEfficiencyMetrics {
        private BigDecimal costPerEmployee;
        private BigDecimal costPerUnit;
        private BigDecimal inventoryTurnover;
        private BigDecimal wastePercentage;
        private Map<String, BigDecimal> efficiencyRatios;
        private String overallEfficiencyRating;
    }

    @Getter
    @Setter
    public static class CostProjections {
        private List<ProjectedCostData> nextPeriods;
        private BigDecimal totalProjectedCost;
        private String projectionMethod;
        private Double accuracyRating;
    }

    @Getter
    @Setter
    public static class ProjectedCostData {
        private String period;
        private BigDecimal projectedCost;
        private BigDecimal confidenceInterval;
        private List<String> assumptions;
    }

    @Getter
    @Setter
    public static class ComparativeAnalysis {
        private Map<String, BigDecimal> yearOverYearComparison;
        private Map<String, BigDecimal> quarterOverQuarterComparison;
        private BenchmarkComparison industryBenchmarks;
        private String performanceRating;
    }

    @Getter
    @Setter
    public static class BenchmarkComparison {
        private BigDecimal industryAverage;
        private BigDecimal companyPerformance;
        private BigDecimal variance;
        private String rating; // ABOVE_AVERAGE, AVERAGE, BELOW_AVERAGE
    }

    @Getter
    @Setter
    public static class BudgetActualComparison {
        private String period;
        private String date;
        private BigDecimal budgetAmount;
        private BigDecimal actualAmount;
        private BigDecimal variance;
        private BigDecimal variancePercentage;
        private BigDecimal cumulativeBudget;
        private BigDecimal cumulativeActual;
        private String status;
    }

    @Getter
    @Setter
    public static class VarianceMetrics {
        private BigDecimal totalVariance;
        private BigDecimal averageVariance;
        private BigDecimal maxVariance;
        private BigDecimal minVariance;
        private String maxVariancePeriod;
        private String minVariancePeriod;
        private Double varianceStandardDeviation;
    }

    @Getter
    @Setter
    public static class BudgetForecasts {
        private BigDecimal yearEndProjection;
        private BigDecimal remainingBudgetNeeded;
        private String projectionAccuracy;
        private List<ForecastScenario> scenarios;
    }

    @Getter
    @Setter
    public static class ForecastScenario {
        private String scenarioName;
        private BigDecimal projectedTotal;
        private BigDecimal budgetVariance;
        private Double likelihood;
    }

    @Getter
    @Setter
    public static class PerformanceIndicators {
        private BigDecimal budgetCompliance;
        private BigDecimal spendingEfficiency;
        private String overallGrade; // A, B, C, D, F
        private List<String> keyAchievements;
        private List<String> areasForImprovement;
    }

    @Getter
    @Setter
    public static class CostPatternAnalysis {
        private String pattern;
        private String description;
        private BigDecimal impact;
        private String frequency;
        private List<String> affectedItems;
        private String riskLevel;
    }

    @Getter
    @Setter
    public static class OptimizationRecommendation {
        private String title;
        private String description;
        private String category;
        private BigDecimal potentialSavings;
        private String implementationDifficulty; // LOW, MEDIUM, HIGH
        private String timeframe; // IMMEDIATE, SHORT_TERM, LONG_TERM
        private String priority; // HIGH, MEDIUM, LOW
        private List<String> actionItems;
        private List<String> riskFactors;
    }

    @Getter
    @Setter
    public static class SavingsAnalysis {
        private BigDecimal totalPotentialSavings;
        private BigDecimal annualizedSavings;
        private BigDecimal implementationCost;
        private BigDecimal netSavings;
        private String paybackPeriod;
        private BigDecimal roi; // Return on Investment
    }

    @Getter
    @Setter
    public static class BurnRateMetrics {
        private BigDecimal currentBurnRate;
        private BigDecimal averageBurnRate;
        private BigDecimal projectedBurnRate;
        private String burnRateTrend;
        private BigDecimal daysToDepletion;
        private String riskAssessment;
    }

    @Getter
    @Setter
    public static class CostPerEmployeeMetrics {
        private BigDecimal totalCostPerEmployee;
        private BigDecimal averageCostPerEmployee;
        private Map<String, BigDecimal> categoryWiseCostPerEmployee;
        private String trend;
        private BigDecimal benchmarkComparison;
    }

    @Getter
    @Setter
    public static class SeasonalPattern {
        private String season;
        private BigDecimal averageCost;
        private BigDecimal seasonalIndex;
        private String trend;
        private BigDecimal yearOverYearGrowth;
    }

    @Getter
    @Setter
    public static class VarianceAlert {
        private String alertId;
        private String severity; // LOW, MEDIUM, HIGH, CRITICAL
        private String category;
        private String description;
        private BigDecimal varianceAmount;
        private BigDecimal variancePercentage;
        private String status; // ACTIVE, RESOLVED, ACKNOWLEDGED
        private List<String> recommendedActions;
        private LocalDateTime alertDate;
    }
}