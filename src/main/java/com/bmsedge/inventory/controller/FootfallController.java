package com.bmsedge.inventory.controller;

import com.bmsedge.inventory.service.FootfallService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/footfall")
@CrossOrigin(origins = "*")
public class FootfallController {

    @Autowired
    private FootfallService footfallService;

    /**
     * MAIN ENDPOINT: Get footfall data with filters
     * FIXED: Default dates now use your actual data range (2025-01-01 onwards)
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> getFootfallData(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(required = false) String department,
            @RequestParam(defaultValue = "date") String sortBy,
            @RequestParam(defaultValue = "ASC") String sortOrder,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {

        try {
            // FIXED: Use realistic default dates based on your actual data
            if (endDate == null) {
                // Instead of LocalDate.now() (2025-09-17), use a date within your data range
                endDate = LocalDate.of(2025, 7, 31); // End of July 2025 (reasonable end date)
            }
            if (startDate == null) {
                // Start from your actual data start date
                startDate = LocalDate.of(2025, 1, 1); // Your data starts from 2025-01-01
            }

            // Validate parameters
            if (size <= 0 || size > 1000) size = 50;
            if (page < 0) page = 0;

            Map<String, Object> result = footfallService.getFootfallDataWithFilters(
                    startDate, endDate, department, sortBy, sortOrder, page, size);

            return ResponseEntity.ok(result);

        } catch (DateTimeParseException e) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("error", "Invalid date format. Please use YYYY-MM-DD format");
            errorResponse.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(errorResponse);
        } catch (Exception e) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("error", "Error retrieving footfall data");
            errorResponse.put("message", e.getMessage());
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }

    /**
     * DEBUG: Check existing footfall data
     */
    @GetMapping("/debug")
    public ResponseEntity<Map<String, Object>> debugFootfallData() {
        Map<String, Object> debug = footfallService.debugFootfallData();
        return ResponseEntity.ok(debug);
    }

    /**
     * Upload footfall data from Excel/CSV
     */
    @PostMapping("/upload")
    public ResponseEntity<Map<String, Object>> uploadFootfallData(
            @RequestParam("file") MultipartFile file) {
        try {
            if (file.isEmpty()) {
                Map<String, Object> errorResponse = new HashMap<>();
                errorResponse.put("success", false);
                errorResponse.put("message", "Please select a file to upload");
                return ResponseEntity.badRequest().body(errorResponse);
            }

            Map<String, Object> result = footfallService.uploadFootfallData(file);
            boolean success = (boolean) result.getOrDefault("success", false);
            HttpStatus status = success ? HttpStatus.OK : HttpStatus.PARTIAL_CONTENT;
            return ResponseEntity.status(status).body(result);
        } catch (IOException e) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("message", "Error processing file: " + e.getMessage());
            return ResponseEntity.badRequest().body(errorResponse);
        } catch (Exception e) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("message", "Unexpected error: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }

    /**
     * Get footfall by specific date
     */
    @GetMapping("/date/{date}")
    public ResponseEntity<Map<String, Object>> getFootfallByDate(
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {

        try {
            Map<String, Object> result = footfallService.getFootfallByDate(date);

            boolean found = (boolean) result.getOrDefault("found", false);
            if (!found) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(result);
            }
            return ResponseEntity.ok(result);

        } catch (DateTimeParseException e) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("error", "Invalid date format");
            errorResponse.put("message", "Date must be in YYYY-MM-DD format");
            return ResponseEntity.badRequest().body(errorResponse);
        } catch (Exception e) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("error", "Error retrieving footfall data");
            errorResponse.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }

    /**
     * Get footfall statistics for a period
     * FIXED: Better default dates
     */
    @GetMapping("/statistics")
    public ResponseEntity<Map<String, Object>> getFootfallStatistics(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {

        try {
            // FIXED: Use realistic default dates
            if (endDate == null) {
                endDate = LocalDate.of(2025, 7, 31); // End of July 2025
            }
            if (startDate == null) {
                startDate = LocalDate.of(2025, 1, 1); // Start of your data
            }

            Map<String, Object> stats = footfallService.getFootfallStatistics(startDate, endDate);
            return ResponseEntity.ok(stats);

        } catch (DateTimeParseException e) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("error", "Invalid date format. Please use YYYY-MM-DD format");
            errorResponse.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(errorResponse);
        } catch (Exception e) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("error", "Error calculating statistics");
            errorResponse.put("message", e.getMessage());
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }

    /**
     * Simple endpoint to check if footfall data exists
     */
    @GetMapping("/exists")
    public ResponseEntity<Map<String, Object>> checkFootfallExists(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {

        try {
            if (date == null) date = LocalDate.of(2025, 1, 15); // Use a date that exists in your data

            Map<String, Object> result = footfallService.getFootfallByDate(date);
            boolean exists = (boolean) result.getOrDefault("found", false);

            Map<String, Object> response = new HashMap<>();
            response.put("date", date.toString());
            response.put("exists", exists);
            response.put("message", exists ? "Footfall data found" : "No footfall data found");

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("error", "Error checking footfall data");
            errorResponse.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }

    /**
     * Get data range where footfall data exists
     */
    @GetMapping("/data-range")
    public ResponseEntity<Map<String, Object>> getFootfallDataRange() {
        try {
            Map<String, Object> debug = footfallService.debugFootfallData();

            Map<String, Object> response = new HashMap<>();
            response.put("totalRecords", debug.get("totalRecords"));
            response.put("dateRange", debug.get("dateRange"));

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("error", "Error retrieving data range");
            errorResponse.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }

    /**
     * Get per-employee consumption metrics
     */
    @GetMapping("/per-employee-consumption")
    public ResponseEntity<Map<String, Object>> getPerEmployeeConsumption(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) Long itemId) {

        try {
            // FIXED: Use realistic defaults
            if (endDate == null) endDate = LocalDate.of(2025, 7, 31);
            if (startDate == null) startDate = LocalDate.of(2025, 1, 1);

            Map<String, Object> metrics = footfallService.calculatePerEmployeeConsumption(
                    startDate, endDate, categoryId, itemId);

            return ResponseEntity.ok(metrics);

        } catch (DateTimeParseException e) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("error", "Invalid date format. Please use YYYY-MM-DD format");
            errorResponse.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(errorResponse);
        } catch (Exception e) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("error", "Error calculating per-employee consumption");
            errorResponse.put("message", e.getMessage());
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }

    /**
     * Get footfall vs consumption correlation analysis
     */
    @GetMapping("/consumption-correlation")
    public ResponseEntity<Map<String, Object>> getConsumptionCorrelation(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(defaultValue = "daily") String period) {

        try {
            // FIXED: Use realistic defaults
            if (endDate == null) endDate = LocalDate.of(2025, 7, 31);
            if (startDate == null) startDate = LocalDate.of(2025, 1, 1);

            Map<String, Object> correlation = footfallService.getFootfallConsumptionCorrelation(
                    startDate, endDate, period);

            return ResponseEntity.ok(correlation);

        } catch (DateTimeParseException e) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("error", "Invalid date format. Please use YYYY-MM-DD format");
            errorResponse.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(errorResponse);
        } catch (Exception e) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("error", "Error calculating correlation");
            errorResponse.put("message", e.getMessage());
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }

    /**
     * Get aggregated footfall data (daily/weekly/monthly)
     */
    @GetMapping("/aggregated")
    public ResponseEntity<Map<String, Object>> getAggregatedFootfall(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(defaultValue = "daily") String aggregation) {

        try {
            // FIXED: Use realistic defaults
            if (endDate == null) endDate = LocalDate.of(2025, 7, 31);
            if (startDate == null) startDate = LocalDate.of(2025, 1, 1);

            Map<String, Object> aggregatedData = footfallService.getAggregatedFootfall(
                    startDate, endDate, aggregation);

            return ResponseEntity.ok(aggregatedData);

        } catch (DateTimeParseException e) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("error", "Invalid date format. Please use YYYY-MM-DD format");
            errorResponse.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(errorResponse);
        } catch (Exception e) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("error", "Error retrieving aggregated data");
            errorResponse.put("message", e.getMessage());
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }

    /**
     * Get footfall trends and patterns
     */
    @GetMapping("/trends")
    public ResponseEntity<Map<String, Object>> getFootfallTrends(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {

        try {
            // FIXED: Use realistic defaults
            if (endDate == null) endDate = LocalDate.of(2025, 7, 31);
            if (startDate == null) startDate = LocalDate.of(2025, 1, 1);

            Map<String, Object> trends = footfallService.analyzeFootfallTrends(startDate, endDate);

            return ResponseEntity.ok(trends);

        } catch (DateTimeParseException e) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("error", "Invalid date format. Please use YYYY-MM-DD format");
            errorResponse.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(errorResponse);
        } catch (Exception e) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("error", "Error analyzing trends");
            errorResponse.put("message", e.getMessage());
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }

    /**
     * Get peak/off-peak analysis
     */
    @GetMapping("/peak-analysis")
    public ResponseEntity<Map<String, Object>> getPeakAnalysis(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {

        try {
            // FIXED: Use realistic defaults
            if (endDate == null) endDate = LocalDate.of(2025, 7, 31);
            if (startDate == null) startDate = LocalDate.of(2025, 1, 1);

            Map<String, Object> peakAnalysis = footfallService.analyzePeakOffPeak(startDate, endDate);

            return ResponseEntity.ok(peakAnalysis);

        } catch (DateTimeParseException e) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("error", "Invalid date format. Please use YYYY-MM-DD format");
            errorResponse.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(errorResponse);
        } catch (Exception e) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("error", "Error analyzing peak periods");
            errorResponse.put("message", e.getMessage());
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }

    /**
     * Delete footfall data for a specific date
     */
    @DeleteMapping("/date/{date}")
    public ResponseEntity<Map<String, Object>> deleteFootfallByDate(
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {

        try {
            boolean deleted = footfallService.deleteFootfallByDate(date);

            Map<String, Object> response = new HashMap<>();
            if (deleted) {
                response.put("success", true);
                response.put("message", "Footfall data deleted for " + date);
                return ResponseEntity.ok(response);
            } else {
                response.put("success", false);
                response.put("message", "No footfall data found for " + date);
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
            }

        } catch (DateTimeParseException e) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("error", "Invalid date format. Please use YYYY-MM-DD format");
            errorResponse.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(errorResponse);
        } catch (Exception e) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("error", "Error deleting footfall data");
            errorResponse.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }

    /**
     * Health check endpoint
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> healthCheck() {
        Map<String, Object> health = new HashMap<>();
        health.put("status", "UP");
        health.put("timestamp", LocalDate.now().toString());
        health.put("service", "FootfallService");
        return ResponseEntity.ok(health);
    }
}