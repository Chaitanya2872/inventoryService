package com.bmsedge.inventory.service;

import com.bmsedge.inventory.model.FootfallData;
import com.bmsedge.inventory.repository.FootfallRepository;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Transactional
public class FootfallService {

    @Autowired
    private FootfallRepository footfallRepository;

    /**
     * MAIN FIX: Get footfall data with proper response format
     */
    public Map<String, Object> getFootfallDataWithFilters(
            LocalDate startDate, LocalDate endDate, String department,
            String sortBy, String sortOrder, int page, int size) {

        Map<String, Object> result = new HashMap<>();

        try {
            // Set default dates if null
            if (endDate == null) endDate = LocalDate.now();
            if (startDate == null) startDate = endDate.minusDays(30);

            // Ensure start date is not after end date
            if (startDate.isAfter(endDate)) {
                LocalDate temp = startDate;
                startDate = endDate;
                endDate = temp;
            }

            // Get data from repository
            List<FootfallData> footfallDataList;
            if (department != null && !department.trim().isEmpty() && !"All".equalsIgnoreCase(department.trim())) {
                footfallDataList = footfallRepository.findByDateBetweenAndDepartment(startDate, endDate, department);
            } else {
                footfallDataList = footfallRepository.findByDateBetween(startDate, endDate);
            }

            // Sort data
            if ("DESC".equalsIgnoreCase(sortOrder)) {
                footfallDataList.sort((a, b) -> b.getDate().compareTo(a.getDate()));
            } else {
                footfallDataList.sort((a, b) -> a.getDate().compareTo(b.getDate()));
            }

            // CRITICAL: Convert to simple format for API response
            List<Map<String, Object>> responseData = footfallDataList.stream().map(footfall -> {
                Map<String, Object> item = new HashMap<>();
                item.put("id", footfall.getId());
                item.put("date", footfall.getDate().toString());
                item.put("employeeCount", footfall.getEmployeeCount());
                item.put("visitorCount", footfall.getVisitorCount());
                item.put("totalFootfall", footfall.getTotalFootfall());
                item.put("department", footfall.getDepartment() != null ? footfall.getDepartment() : "All Departments");
                item.put("notes", footfall.getNotes());
                item.put("isWeekend", footfall.isWeekend());
                item.put("createdAt", footfall.getCreatedAt() != null ? footfall.getCreatedAt().toString() : null);
                item.put("updatedAt", footfall.getUpdatedAt() != null ? footfall.getUpdatedAt().toString() : null);
                return item;
            }).collect(Collectors.toList());

            // Calculate statistics
            Map<String, Object> statistics = calculateSimpleStatistics(footfallDataList);

            // Pagination
            int totalElements = responseData.size();
            int totalPages = (int) Math.ceil((double) totalElements / size);
            int fromIndex = Math.min(page * size, totalElements);
            int toIndex = Math.min(fromIndex + size, totalElements);

            List<Map<String, Object>> paginatedData = responseData.subList(fromIndex, toIndex);

            // Build response
            result.put("success", true);
            result.put("data", paginatedData);
            result.put("statistics", statistics);
            result.put("totalRecords", totalElements);

            // Pagination info
            Map<String, Object> pagination = new HashMap<>();
            pagination.put("page", page);
            pagination.put("size", size);
            pagination.put("totalElements", totalElements);
            pagination.put("totalPages", totalPages);
            pagination.put("hasNext", page < totalPages - 1);
            pagination.put("hasPrevious", page > 0);
            result.put("pagination", pagination);

            // Filter info
            Map<String, Object> filters = new HashMap<>();
            filters.put("startDate", startDate.toString());
            filters.put("endDate", endDate.toString());
            filters.put("department", department != null ? department : "All");
            filters.put("sortBy", sortBy != null ? sortBy : "date");
            filters.put("sortOrder", sortOrder != null ? sortOrder : "ASC");
            result.put("filters", filters);

        } catch (Exception e) {
            result.put("success", false);
            result.put("error", "Error retrieving footfall data: " + e.getMessage());
            result.put("data", Collections.emptyList());
            result.put("totalRecords", 0);
            e.printStackTrace();
        }

        return result;
    }

    /**
     * Get footfall by specific date - FIXED
     */
    public Map<String, Object> getFootfallByDate(LocalDate date) {
        Map<String, Object> result = new HashMap<>();

        try {
            Optional<FootfallData> footfallOpt = footfallRepository.findByDate(date);

            if (footfallOpt.isPresent()) {
                FootfallData footfall = footfallOpt.get();
                result.put("success", true);
                result.put("found", true);
                result.put("data", createFootfallResponse(footfall));
            } else {
                result.put("success", true);
                result.put("found", false);
                result.put("message", "No footfall data found for " + date.toString());
                result.put("data", null);
            }
        } catch (Exception e) {
            result.put("success", false);
            result.put("found", false);
            result.put("error", "Error retrieving footfall data: " + e.getMessage());
            result.put("data", null);
            e.printStackTrace();
        }

        return result;
    }

    /**
     * Get footfall statistics - FIXED
     */
    public Map<String, Object> getFootfallStatistics(LocalDate startDate, LocalDate endDate) {
        Map<String, Object> result = new HashMap<>();

        try {
            // Set defaults
            if (endDate == null) endDate = LocalDate.now();
            if (startDate == null) startDate = endDate.minusDays(30);

            // Swap if necessary
            if (startDate.isAfter(endDate)) {
                LocalDate temp = startDate;
                startDate = endDate;
                endDate = temp;
            }

            List<FootfallData> data = footfallRepository.findByDateBetween(startDate, endDate);

            result.put("success", true);
            result.put("period", Map.of("startDate", startDate.toString(), "endDate", endDate.toString()));
            result.put("totalRecords", data.size());

            if (data.isEmpty()) {
                result.put("message", "No footfall data found for the specified period");
                result.put("statistics", getEmptyStatistics());
            } else {
                result.put("statistics", calculateSimpleStatistics(data));
            }

        } catch (Exception e) {
            result.put("success", false);
            result.put("error", "Error calculating statistics: " + e.getMessage());
            result.put("statistics", getEmptyStatistics());
            e.printStackTrace();
        }

        return result;
    }

    /**
     * Debug method to check data
     */
    public Map<String, Object> debugFootfallData() {
        Map<String, Object> debug = new HashMap<>();

        try {
            long totalCount = footfallRepository.count();
            debug.put("totalRecords", totalCount);

            if (totalCount > 0) {
                // Get sample records
                List<FootfallData> sampleRecords = footfallRepository.findAll()
                        .stream().limit(10).collect(Collectors.toList());

                List<Map<String, Object>> samples = sampleRecords.stream()
                        .map(this::createFootfallResponse)
                        .collect(Collectors.toList());

                debug.put("sampleRecords", samples);

                // Get date range
                List<LocalDate> allDates = footfallRepository.findAll().stream()
                        .map(FootfallData::getDate)
                        .sorted()
                        .collect(Collectors.toList());

                if (!allDates.isEmpty()) {
                    Map<String, String> dateRange = new HashMap<>();
                    dateRange.put("minDate", allDates.get(0).toString());
                    dateRange.put("maxDate", allDates.get(allDates.size() - 1).toString());
                    debug.put("dateRange", dateRange);
                }
            } else {
                debug.put("message", "No footfall data found in database");
                debug.put("sampleRecords", Collections.emptyList());
                debug.put("dateRange", Collections.emptyMap());
            }

        } catch (Exception e) {
            debug.put("error", "Error retrieving debug data: " + e.getMessage());
            debug.put("totalRecords", 0);
            debug.put("sampleRecords", Collections.emptyList());
            e.printStackTrace();
        }

        return debug;
    }

    /**
     * Upload footfall data - SIMPLIFIED VERSION
     */
    public Map<String, Object> uploadFootfallData(MultipartFile file) throws IOException {
        Map<String, Object> result = new HashMap<>();
        List<String> errors = new ArrayList<>();
        int recordsCreated = 0;
        int recordsUpdated = 0;

        if (file.isEmpty()) {
            result.put("success", false);
            result.put("message", "File is empty");
            return result;
        }

        try (Workbook workbook = new XSSFWorkbook(file.getInputStream())) {
            Sheet sheet = workbook.getSheetAt(0); // Use first sheet

            // Find header row
            Row headerRow = null;
            int headerRowIndex = -1;

            for (int i = 0; i <= Math.min(5, sheet.getLastRowNum()); i++) {
                Row row = sheet.getRow(i);
                if (row != null && isHeaderRow(row)) {
                    headerRow = row;
                    headerRowIndex = i;
                    break;
                }
            }

            if (headerRow == null) {
                errors.add("Could not find header row with Date and Employee Count columns");
            } else {
                // Map columns
                Map<String, Integer> columnMap = mapColumns(headerRow);

                if (!columnMap.containsKey("date") || !columnMap.containsKey("employeeCount")) {
                    errors.add("Required columns not found: Date, Employee Count");
                } else {
                    // Process data rows
                    for (int rowIndex = headerRowIndex + 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                        Row row = sheet.getRow(rowIndex);
                        if (row == null || isRowEmpty(row)) continue;

                        try {
                            LocalDate date = parseDateFromCell(row.getCell(columnMap.get("date")));
                            if (date == null) continue;

                            int employeeCount = getCellValueAsInt(row.getCell(columnMap.get("employeeCount")));
                            if (employeeCount <= 0) continue;

                            // Optional visitor count
                            int visitorCount = 0;
                            if (columnMap.containsKey("visitorCount")) {
                                visitorCount = getCellValueAsInt(row.getCell(columnMap.get("visitorCount")));
                            }

                            // Save or update
                            boolean isNew = saveOrUpdateFootfall(date, employeeCount, visitorCount,
                                    "Imported from " + file.getOriginalFilename());

                            if (isNew) {
                                recordsCreated++;
                            } else {
                                recordsUpdated++;
                            }

                        } catch (Exception e) {
                            errors.add("Error processing row " + (rowIndex + 1) + ": " + e.getMessage());
                        }
                    }
                }
            }

        } catch (Exception e) {
            errors.add("Error processing file: " + e.getMessage());
            e.printStackTrace();
        }

        result.put("success", recordsCreated > 0 || recordsUpdated > 0);
        result.put("recordsCreated", recordsCreated);
        result.put("recordsUpdated", recordsUpdated);
        result.put("totalProcessed", recordsCreated + recordsUpdated);
        result.put("errors", errors);

        if (recordsCreated > 0 || recordsUpdated > 0) {
            result.put("message", String.format(
                    "Successfully imported: %d created, %d updated", recordsCreated, recordsUpdated));
        } else {
            result.put("message", "No records were imported. Check file format and data.");
        }

        return result;
    }

    // HELPER METHODS

    private Map<String, Object> createFootfallResponse(FootfallData footfall) {
        Map<String, Object> response = new HashMap<>();
        response.put("id", footfall.getId());
        response.put("date", footfall.getDate().toString());
        response.put("employeeCount", footfall.getEmployeeCount());
        response.put("visitorCount", footfall.getVisitorCount());
        response.put("totalFootfall", footfall.getTotalFootfall());
        response.put("department", footfall.getDepartment());
        response.put("notes", footfall.getNotes());
        response.put("isWeekend", footfall.isWeekend());
        response.put("createdAt", footfall.getCreatedAt() != null ? footfall.getCreatedAt().toString() : null);
        response.put("updatedAt", footfall.getUpdatedAt() != null ? footfall.getUpdatedAt().toString() : null);
        return response;
    }

    private Map<String, Object> calculateSimpleStatistics(List<FootfallData> data) {
        Map<String, Object> stats = new HashMap<>();

        if (data.isEmpty()) {
            return getEmptyStatistics();
        }

        int totalEmployees = data.stream().mapToInt(FootfallData::getEmployeeCount).sum();
        int totalVisitors = data.stream().mapToInt(FootfallData::getVisitorCount).sum();
        double avgEmployees = data.stream().mapToInt(FootfallData::getEmployeeCount).average().orElse(0.0);
        double avgVisitors = data.stream().mapToInt(FootfallData::getVisitorCount).average().orElse(0.0);

        FootfallData maxDay = data.stream()
                .max(Comparator.comparingInt(FootfallData::getTotalFootfall))
                .orElse(null);
        FootfallData minDay = data.stream()
                .min(Comparator.comparingInt(FootfallData::getTotalFootfall))
                .orElse(null);

        stats.put("totalDays", data.size());
        stats.put("totalEmployees", totalEmployees);
        stats.put("totalVisitors", totalVisitors);
        stats.put("totalFootfall", totalEmployees + totalVisitors);
        stats.put("averageEmployeesPerDay", Math.round(avgEmployees * 100.0) / 100.0);
        stats.put("averageVisitorsPerDay", Math.round(avgVisitors * 100.0) / 100.0);
        stats.put("averageTotalPerDay", Math.round((avgEmployees + avgVisitors) * 100.0) / 100.0);

        if (maxDay != null) {
            stats.put("peakDay", Map.of(
                    "date", maxDay.getDate().toString(),
                    "count", maxDay.getTotalFootfall(),
                    "employees", maxDay.getEmployeeCount(),
                    "visitors", maxDay.getVisitorCount()
            ));
        }

        if (minDay != null) {
            stats.put("lowDay", Map.of(
                    "date", minDay.getDate().toString(),
                    "count", minDay.getTotalFootfall(),
                    "employees", minDay.getEmployeeCount(),
                    "visitors", minDay.getVisitorCount()
            ));
        }

        return stats;
    }

    private Map<String, Object> getEmptyStatistics() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("totalDays", 0);
        stats.put("totalEmployees", 0);
        stats.put("totalVisitors", 0);
        stats.put("totalFootfall", 0);
        stats.put("averageEmployeesPerDay", 0.0);
        stats.put("averageVisitorsPerDay", 0.0);
        stats.put("averageTotalPerDay", 0.0);
        stats.put("peakDay", null);
        stats.put("lowDay", null);
        return stats;
    }

    private boolean isHeaderRow(Row row) {
        int dateColumns = 0;
        int countColumns = 0;

        for (Cell cell : row) {
            String value = getCellValueAsString(cell).toLowerCase();
            if (value.contains("date") || value.contains("day")) dateColumns++;
            if (value.contains("employee") || value.contains("count") || value.contains("footfall")) countColumns++;
        }

        return dateColumns > 0 && countColumns > 0;
    }

    private Map<String, Integer> mapColumns(Row headerRow) {
        Map<String, Integer> columnMap = new HashMap<>();

        for (Cell cell : headerRow) {
            String header = getCellValueAsString(cell).toLowerCase().trim();
            int colIndex = cell.getColumnIndex();

            if (header.contains("date") || header.contains("day")) {
                columnMap.put("date", colIndex);
            } else if ((header.contains("employee") && header.contains("count")) ||
                    header.equals("employee_count") || header.equals("employeecount")) {
                columnMap.put("employeeCount", colIndex);
            } else if (header.contains("visitor") && header.contains("count")) {
                columnMap.put("visitorCount", colIndex);
            } else if (header.contains("department")) {
                columnMap.put("department", colIndex);
            } else if (header.contains("note")) {
                columnMap.put("notes", colIndex);
            }
        }

        return columnMap;
    }

    private boolean saveOrUpdateFootfall(LocalDate date, int employeeCount, int visitorCount, String notes) {
        Optional<FootfallData> existing = footfallRepository.findByDate(date);

        if (existing.isPresent()) {
            FootfallData footfall = existing.get();
            footfall.setEmployeeCount(employeeCount);
            footfall.setVisitorCount(visitorCount);
            footfall.setTotalFootfall(employeeCount + visitorCount);
            if (notes != null) footfall.setNotes(notes);
            footfallRepository.save(footfall);
            return false; // Updated
        } else {
            FootfallData footfall = new FootfallData();
            footfall.setDate(date);
            footfall.setEmployeeCount(employeeCount);
            footfall.setVisitorCount(visitorCount);
            footfall.setTotalFootfall(employeeCount + visitorCount);
            footfall.setDepartment("All Departments");
            footfall.setNotes(notes);
            footfallRepository.save(footfall);
            return true; // Created
        }
    }

    private LocalDate parseDateFromCell(Cell cell) {
        if (cell == null) return null;

        try {
            if (cell.getCellType() == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell)) {
                return cell.getLocalDateTimeCellValue().toLocalDate();
            }

            String dateStr = getCellValueAsString(cell).trim();
            if (dateStr.isEmpty()) return null;

            String[] patterns = {
                    "yyyy-MM-dd", "dd/MM/yyyy", "MM/dd/yyyy", "dd-MM-yyyy",
                    "yyyy/MM/dd", "d/M/yyyy", "d-M-yyyy", "dd.MM.yyyy",
                    "M/d/yyyy", "M-d-yyyy", "yyyy.MM.dd"
            };

            for (String pattern : patterns) {
                try {
                    return LocalDate.parse(dateStr, DateTimeFormatter.ofPattern(pattern));
                } catch (DateTimeParseException e) {
                    // Try next pattern
                }
            }
        } catch (Exception e) {
            // Log error but don't fail
        }

        return null;
    }

    private int getCellValueAsInt(Cell cell) {
        if (cell == null) return 0;

        try {
            switch (cell.getCellType()) {
                case NUMERIC:
                    return (int) Math.round(cell.getNumericCellValue());
                case STRING:
                    String strValue = cell.getStringCellValue().trim().replaceAll("[^0-9]", "");
                    return strValue.isEmpty() ? 0 : Integer.parseInt(strValue);
                case FORMULA:
                    return (int) Math.round(cell.getNumericCellValue());
                default:
                    return 0;
            }
        } catch (Exception e) {
            return 0;
        }
    }

    private String getCellValueAsString(Cell cell) {
        if (cell == null) return "";

        try {
            switch (cell.getCellType()) {
                case STRING:
                    return cell.getStringCellValue();
                case NUMERIC:
                    if (DateUtil.isCellDateFormatted(cell)) {
                        return cell.getLocalDateTimeCellValue().toString();
                    }
                    double numericValue = cell.getNumericCellValue();
                    return numericValue == (int) numericValue ?
                            String.valueOf((int) numericValue) : String.valueOf(numericValue);
                case BOOLEAN:
                    return String.valueOf(cell.getBooleanCellValue());
                case FORMULA:
                    return String.valueOf(cell.getNumericCellValue());
                default:
                    return "";
            }
        } catch (Exception e) {
            return "";
        }
    }

    private boolean isRowEmpty(Row row) {
        if (row == null) return true;
        for (int cellNum = 0; cellNum < row.getLastCellNum(); cellNum++) {
            Cell cell = row.getCell(cellNum);
            if (cell != null && !getCellValueAsString(cell).trim().isEmpty()) {
                return false;
            }
        }
        return true;
    }

    // Placeholder methods for interface compatibility
    public boolean deleteFootfallByDate(LocalDate date) {
        try {
            Optional<FootfallData> footfallOpt = footfallRepository.findByDate(date);
            if (footfallOpt.isPresent()) {
                footfallRepository.delete(footfallOpt.get());
                return true;
            }
            return false;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    public Map<String, Object> calculatePerEmployeeConsumption(LocalDate startDate, LocalDate endDate, Long categoryId, Long itemId) {
        Map<String, Object> result = new HashMap<>();
        result.put("message", "Per-employee consumption calculation coming soon");
        return result;
    }

    public Map<String, Object> getFootfallConsumptionCorrelation(LocalDate startDate, LocalDate endDate, String period) {
        Map<String, Object> result = new HashMap<>();
        result.put("message", "Correlation analysis coming soon");
        return result;
    }


    /**
     * Get footfall trends with aggregation support
     */
    public Map<String, Object> getFootfallTrends(String period, LocalDate startDate, LocalDate endDate) {
        Map<String, Object> result = new HashMap<>();

        try {
            // Set default dates if null
            if (endDate == null) endDate = LocalDate.now();
            if (startDate == null) startDate = endDate.minusDays(30);

            // Ensure start date is not after end date
            if (startDate.isAfter(endDate)) {
                LocalDate temp = startDate;
                startDate = endDate;
                endDate = temp;
            }

            List<FootfallData> data = footfallRepository.findByDateBetween(startDate, endDate);

            if (data.isEmpty()) {
                result.put("success", true);
                result.put("message", "No footfall data found for the specified period");
                result.put("data", Collections.emptyList());
                result.put("period", period);
                result.put("dateRange", Map.of(
                        "startDate", startDate.toString(),
                        "endDate", endDate.toString()
                ));
                return result;
            }

            // Process data based on period
            List<Map<String, Object>> trends = new ArrayList<>();
            Map<String, Map<String, Object>> aggregatedData = new LinkedHashMap<>();

            DateTimeFormatter formatter;
            switch (period.toLowerCase()) {
                case "weekly":
                    formatter = DateTimeFormatter.ofPattern("yyyy-'W'ww");
                    break;
                case "monthly":
                    formatter = DateTimeFormatter.ofPattern("yyyy-MM");
                    break;
                default: // daily
                    formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");
                    break;
            }

            // Aggregate data by period
            for (FootfallData footfall : data) {
                String periodKey = footfall.getDate().format(formatter);

                aggregatedData.computeIfAbsent(periodKey, k -> {
                    Map<String, Object> periodData = new HashMap<>();
                    periodData.put("period", periodKey);
                    periodData.put("totalEmployees", 0);
                    periodData.put("totalVisitors", 0);
                    periodData.put("totalFootfall", 0);
                    periodData.put("dayCount", 0);
                    periodData.put("averageEmployees", 0.0);
                    periodData.put("averageVisitors", 0.0);
                    periodData.put("averageTotal", 0.0);
                    return periodData;
                });

                Map<String, Object> periodData = aggregatedData.get(periodKey);
                int currentEmployees = (Integer) periodData.get("totalEmployees");
                int currentVisitors = (Integer) periodData.get("totalVisitors");
                int currentTotal = (Integer) periodData.get("totalFootfall");
                int currentDayCount = (Integer) periodData.get("dayCount");

                periodData.put("totalEmployees", currentEmployees + footfall.getEmployeeCount());
                periodData.put("totalVisitors", currentVisitors + footfall.getVisitorCount());
                periodData.put("totalFootfall", currentTotal + footfall.getTotalFootfall());
                periodData.put("dayCount", currentDayCount + 1);

                // Calculate averages
                int newDayCount = currentDayCount + 1;
                periodData.put("averageEmployees", Math.round(((double)(currentEmployees + footfall.getEmployeeCount()) / newDayCount) * 100.0) / 100.0);
                periodData.put("averageVisitors", Math.round(((double)(currentVisitors + footfall.getVisitorCount()) / newDayCount) * 100.0) / 100.0);
                periodData.put("averageTotal", Math.round(((double)(currentTotal + footfall.getTotalFootfall()) / newDayCount) * 100.0) / 100.0);
            }

            trends.addAll(aggregatedData.values());

            result.put("success", true);
            result.put("data", trends);
            result.put("period", period);
            result.put("totalPeriods", trends.size());
            result.put("dateRange", Map.of(
                    "startDate", startDate.toString(),
                    "endDate", endDate.toString()
            ));

        } catch (Exception e) {
            result.put("success", false);
            result.put("error", "Error retrieving footfall trends: " + e.getMessage());
            result.put("data", Collections.emptyList());
            e.printStackTrace();
        }

        return result;
    }

    /**
     * Check if footfall data exists for a specific date
     */
    public Map<String, Object> checkFootfallData(LocalDate date) {
        Map<String, Object> result = new HashMap<>();

        try {
            if (date == null) {
                date = LocalDate.now();
            }

            Optional<FootfallData> footfallOpt = footfallRepository.findByDate(date);

            result.put("success", true);
            result.put("date", date.toString());
            result.put("hasData", footfallOpt.isPresent());

            if (footfallOpt.isPresent()) {
                FootfallData footfall = footfallOpt.get();
                result.put("data", createFootfallResponse(footfall));
                result.put("message", "Footfall data found for " + date.toString());
            } else {
                result.put("data", null);
                result.put("message", "No footfall data found for " + date.toString());
            }

        } catch (Exception e) {
            result.put("success", false);
            result.put("hasData", false);
            result.put("error", "Error checking footfall data: " + e.getMessage());
            result.put("data", null);
            e.printStackTrace();
        }

        return result;
    }

    public Map<String, Object> getAggregatedFootfall(LocalDate startDate, LocalDate endDate, String aggregation) {
        Map<String, Object> result = new HashMap<>();
        result.put("message", "Aggregated footfall coming soon");
        return result;
    }

    public Map<String, Object> analyzeFootfallTrends(LocalDate startDate, LocalDate endDate) {
        Map<String, Object> result = new HashMap<>();
        result.put("message", "Trend analysis coming soon");
        return result;
    }

    public Map<String, Object> analyzePeakOffPeak(LocalDate startDate, LocalDate endDate) {
        Map<String, Object> result = new HashMap<>();
        result.put("message", "Peak analysis coming soon");
        return result;
    }
}