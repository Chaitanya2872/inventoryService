package com.bmsedge.inventory.service;

import com.bmsedge.inventory.model.FootfallData;
import com.bmsedge.inventory.repository.FootfallDataRepository;
import com.bmsedge.inventory.repository.ConsumptionRecordRepository;
import com.bmsedge.inventory.repository.ItemRepository;
import com.bmsedge.inventory.model.ConsumptionRecord;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Transactional
public class FootfallService {

    @Autowired
    private FootfallDataRepository footfallRepository;

    @Autowired(required = false)
    private ConsumptionRecordRepository consumptionRecordRepository;

    @Autowired(required = false)
    private ItemRepository itemRepository;

    /**
     * ENHANCED Upload footfall data - Handles multi-column Excel format
     * Fixed to handle Date, Employee columns (not requiring "Count" in name)
     */
    public Map<String, Object> uploadFootfallData(MultipartFile file) throws IOException {
        Map<String, Object> result = new HashMap<>();
        List<String> errors = new ArrayList<>();
        int recordsCreated = 0;
        int recordsUpdated = 0;
        Map<LocalDate, Integer[]> allData = new TreeMap<>(); // Store all date -> [employee, visitor] data

        if (file.isEmpty()) {
            result.put("success", false);
            result.put("message", "File is empty");
            return result;
        }

        try (Workbook workbook = new XSSFWorkbook(file.getInputStream())) {
            Sheet sheet = workbook.getSheetAt(0);

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
                errors.add("Could not find header row with Date and Employee columns");
            } else {
                // Find ALL column sets in the multi-column format
                List<ColumnSet> columnSets = findAllColumnSets(headerRow);

                if (columnSets.isEmpty()) {
                    errors.add("No valid Date-Employee column pairs found");
                    result.put("debug", "Headers found: " + getHeadersList(headerRow));
                } else {
                    result.put("columnSetsFound", columnSets.size());

                    // Process each column set
                    for (ColumnSet columnSet : columnSets) {
                        // Process data rows for this column set
                        for (int rowIndex = headerRowIndex + 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                            Row row = sheet.getRow(rowIndex);
                            if (row == null) continue;

                            try {
                                Cell dateCell = row.getCell(columnSet.dateColumn);
                                Cell empCell = row.getCell(columnSet.employeeColumn);

                                if (dateCell == null || empCell == null) continue;

                                LocalDate date = parseDateFromCell(dateCell);
                                if (date == null) continue;

                                int employeeCount = getCellValueAsInt(empCell);
                                int visitorCount = columnSet.visitorColumn != null ?
                                        getCellValueAsInt(row.getCell(columnSet.visitorColumn)) : 0;

                                // Skip negative or zero values
                                if (employeeCount <= 0) continue;

                                // Store or update the data (handles duplicates by keeping highest value)
                                if (!allData.containsKey(date) ||
                                        (employeeCount > 0 && employeeCount > allData.get(date)[0])) {
                                    allData.put(date, new Integer[]{employeeCount, visitorCount});
                                }
                            } catch (Exception e) {
                                // Skip problematic cells but don't fail entire process
                            }
                        }
                    }

                    // Now save all collected data to database
                    for (Map.Entry<LocalDate, Integer[]> entry : allData.entrySet()) {
                        LocalDate date = entry.getKey();
                        Integer[] counts = entry.getValue();
                        int employeeCount = counts[0];
                        int visitorCount = counts[1];

                        try {
                            boolean isNew = saveOrUpdateFootfall(date, employeeCount, visitorCount,
                                    "Imported from " + file.getOriginalFilename());

                            if (isNew) {
                                recordsCreated++;
                            } else {
                                recordsUpdated++;
                            }
                        } catch (Exception e) {
                            errors.add("Error saving date " + date + ": " + e.getMessage());
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
        result.put("uniqueDatesFound", allData.size());
        result.put("errors", errors);

        if (recordsCreated > 0 || recordsUpdated > 0) {
            result.put("message", String.format(
                    "Successfully imported: %d created, %d updated from %d unique dates",
                    recordsCreated, recordsUpdated, allData.size()));
        } else {
            result.put("message", "No records were imported. Check file format and data.");
        }

        return result;
    }

    /**
     * Helper class to store column set information
     */
    private static class ColumnSet {
        int dateColumn;
        int employeeColumn;
        Integer visitorColumn;

        ColumnSet(int date, int employee, Integer visitor) {
            this.dateColumn = date;
            this.employeeColumn = employee;
            this.visitorColumn = visitor;
        }

        @Override
        public String toString() {
            return String.format("Date:%d, Employee:%d, Visitor:%s",
                    dateColumn, employeeColumn, visitorColumn != null ? visitorColumn : "none");
        }
    }

    /**
     * Find all Date-Employee column pairs in the header row
     */
    /**
     * FIXED: Find all Date-Employee column pairs in the header row
     * This version properly handles multiple column sets in formats like:
     * Date | Employee | Date.1 | Employee.1 | Date.2 | Employee.2 etc.
     */
    private List<ColumnSet> findAllColumnSets(Row headerRow) {
        List<ColumnSet> columnSets = new ArrayList<>();
        Map<Integer, Integer> dateToEmployeeMap = new HashMap<>();

        // First pass: Find all Date columns
        List<Integer> dateColumns = new ArrayList<>();
        List<Integer> employeeColumns = new ArrayList<>();
        List<Integer> visitorColumns = new ArrayList<>();

        for (Cell cell : headerRow) {
            String header = getCellValueAsString(cell).trim();
            int colIndex = cell.getColumnIndex();

            // More flexible date column detection
            if (header.toLowerCase().contains("date") ||
                    header.equalsIgnoreCase("Date") ||
                    header.matches("(?i)date\\.?\\d*")) { // Matches Date, Date.1, Date.2, etc.
                dateColumns.add(colIndex);
            }
            // More flexible employee column detection
            else if (header.toLowerCase().contains("employee") ||
                    header.equalsIgnoreCase("Employee") ||
                    header.matches("(?i)employee\\.?\\d*")) { // Matches Employee, Employee.1, etc.
                employeeColumns.add(colIndex);
            }
            // Visitor columns (optional)
            else if (header.toLowerCase().contains("visitor")) {
                visitorColumns.add(colIndex);
            }
        }

        // Debug logging
        System.out.println("Found " + dateColumns.size() + " date columns: " + dateColumns);
        System.out.println("Found " + employeeColumns.size() + " employee columns: " + employeeColumns);

        // Method 1: Match by suffix pattern (Date.1 with Employee.1)
        Map<String, List<Integer>> dateBySuffix = new HashMap<>();
        Map<String, List<Integer>> employeeBySuffix = new HashMap<>();

        for (Cell cell : headerRow) {
            String header = getCellValueAsString(cell).trim();
            int colIndex = cell.getColumnIndex();

            // Extract suffix (empty string for no suffix, ".1", ".2", etc.)
            String suffix = "";
            if (header.contains(".")) {
                suffix = header.substring(header.lastIndexOf("."));
            } else if (header.matches(".*\\d+$")) {
                // Handle cases like Date1, Employee2
                suffix = header.replaceAll("\\D+(\\d+)$", ".$1");
            }

            if (header.toLowerCase().startsWith("date")) {
                dateBySuffix.computeIfAbsent(suffix, k -> new ArrayList<>()).add(colIndex);
            } else if (header.toLowerCase().startsWith("employee")) {
                employeeBySuffix.computeIfAbsent(suffix, k -> new ArrayList<>()).add(colIndex);
            }
        }

        // Pair up by suffix
        for (String suffix : dateBySuffix.keySet()) {
            if (employeeBySuffix.containsKey(suffix)) {
                List<Integer> dates = dateBySuffix.get(suffix);
                List<Integer> employees = employeeBySuffix.get(suffix);

                // Pair them up
                int pairs = Math.min(dates.size(), employees.size());
                for (int i = 0; i < pairs; i++) {
                    Integer visitorCol = findNearbyVisitorColumn(headerRow, dates.get(i), employees.get(i), visitorColumns);
                    columnSets.add(new ColumnSet(dates.get(i), employees.get(i), visitorCol));

                    // Mark as used
                    dateToEmployeeMap.put(dates.get(i), employees.get(i));
                }
            }
        }

        // Method 2: Match by proximity (Date followed by Employee within 3 columns)
        for (Integer dateCol : dateColumns) {
            if (!dateToEmployeeMap.containsKey(dateCol)) {
                // Find nearest unused employee column
                Integer nearestEmployee = null;
                int minDistance = Integer.MAX_VALUE;

                for (Integer empCol : employeeColumns) {
                    if (!dateToEmployeeMap.containsValue(empCol)) {
                        int distance = Math.abs(empCol - dateCol);
                        if (distance < minDistance && distance <= 3) {
                            minDistance = distance;
                            nearestEmployee = empCol;
                        }
                    }
                }

                if (nearestEmployee != null) {
                    Integer visitorCol = findNearbyVisitorColumn(headerRow, dateCol, nearestEmployee, visitorColumns);
                    columnSets.add(new ColumnSet(dateCol, nearestEmployee, visitorCol));
                    dateToEmployeeMap.put(dateCol, nearestEmployee);
                }
            }
        }

        // Method 3: If still no matches, try sequential pairing
        if (columnSets.isEmpty() && !dateColumns.isEmpty() && !employeeColumns.isEmpty()) {
            int pairs = Math.min(dateColumns.size(), employeeColumns.size());
            for (int i = 0; i < pairs; i++) {
                if (!dateToEmployeeMap.containsKey(dateColumns.get(i))) {
                    Integer visitorCol = i < visitorColumns.size() ? visitorColumns.get(i) : null;
                    columnSets.add(new ColumnSet(dateColumns.get(i), employeeColumns.get(i), visitorCol));
                }
            }
        }

        System.out.println("Identified " + columnSets.size() + " column sets:");
        for (int i = 0; i < columnSets.size(); i++) {
            System.out.println("  Set " + (i+1) + ": " + columnSets.get(i));
        }

        return columnSets;
    }

    /**
     * Helper method to find visitor column near a Date-Employee pair
     */
    private Integer findNearbyVisitorColumn(Row headerRow, int dateCol, int employeeCol, List<Integer> visitorColumns) {
        if (visitorColumns.isEmpty()) return null;

        int avgPosition = (dateCol + employeeCol) / 2;
        Integer nearestVisitor = null;
        int minDistance = Integer.MAX_VALUE;

        for (Integer visCol : visitorColumns) {
            int distance = Math.abs(visCol - avgPosition);
            if (distance < minDistance && distance <= 4) { // Within 4 columns of the pair
                minDistance = distance;
                nearestVisitor = visCol;
            }
        }

        return nearestVisitor;
    }

    /**
     * ALSO FIX: Process data rows to handle empty cells and date validation better
     */
    private void processDataRows(Sheet sheet, int headerRowIndex, ColumnSet columnSet,
                                 Map<LocalDate, Integer[]> allData, List<String> errors) {
        int emptyRowCount = 0;
        int maxEmptyRows = 5; // Stop after 5 consecutive empty rows

        for (int rowIndex = headerRowIndex + 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (row == null) {
                emptyRowCount++;
                if (emptyRowCount >= maxEmptyRows) break;
                continue;
            }

            try {
                Cell dateCell = row.getCell(columnSet.dateColumn);
                Cell empCell = row.getCell(columnSet.employeeColumn);

                // Skip if both cells are empty
                if ((dateCell == null || getCellValueAsString(dateCell).trim().isEmpty()) &&
                        (empCell == null || getCellValueAsString(empCell).trim().isEmpty())) {
                    emptyRowCount++;
                    if (emptyRowCount >= maxEmptyRows) break;
                    continue;
                }

                emptyRowCount = 0; // Reset counter on non-empty row

                LocalDate date = parseDateFromCell(dateCell);
                if (date == null) {
                    // Try to provide more helpful error message
                    String cellValue = getCellValueAsString(dateCell);
                    if (!cellValue.trim().isEmpty()) {
                        errors.add("Row " + (rowIndex + 1) + " Col " + (columnSet.dateColumn + 1) +
                                ": Cannot parse date '" + cellValue + "'");
                    }
                    continue;
                }

                // Validate date is reasonable (not too far in future or past)
                LocalDate now = LocalDate.now();
                if (date.isAfter(now.plusYears(2)) || date.isBefore(now.minusYears(5))) {
                    errors.add("Row " + (rowIndex + 1) + ": Date " + date + " seems invalid (too far from current date)");
                    continue;
                }

                int employeeCount = getCellValueAsInt(empCell);
                int visitorCount = columnSet.visitorColumn != null ?
                        getCellValueAsInt(row.getCell(columnSet.visitorColumn)) : 0;

                // Skip negative values but allow zero (for holidays/closures)
                if (employeeCount < 0) {
                    errors.add("Row " + (rowIndex + 1) + ": Negative employee count " + employeeCount);
                    continue;
                }

                // Store or update the data (keep the highest non-zero value for duplicates)
                Integer[] existing = allData.get(date);
                if (existing == null ||
                        (employeeCount > 0 && employeeCount > existing[0]) ||
                        (existing[0] == 0 && employeeCount >= 0)) {
                    allData.put(date, new Integer[]{employeeCount, Math.max(0, visitorCount)});
                }

            } catch (Exception e) {
                errors.add("Row " + (rowIndex + 1) + ": " + e.getMessage());
            }
        }
    }

    /**
     * Check if this is a header row
     */
    private boolean isHeaderRow(Row row) {
        int dateColumns = 0;
        int employeeColumns = 0;

        for (Cell cell : row) {
            String value = getCellValueAsString(cell).trim();
            // Check for Date columns (including Date.1, Date.2, etc.)
            if (value.startsWith("Date")) dateColumns++;
            // Check for Employee columns (including Employee.1, Employee.2, etc.)
            if (value.startsWith("Employee")) employeeColumns++;
        }

        // If we have at least one Date and one Employee column, it's a header row
        return dateColumns > 0 && employeeColumns > 0;
    }

    /**
     * Get list of headers for debugging
     */
    private List<String> getHeadersList(Row headerRow) {
        List<String> headers = new ArrayList<>();
        for (Cell cell : headerRow) {
            headers.add(getCellValueAsString(cell));
        }
        return headers;
    }

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

            // Get data from repository - FIXED METHOD NAME
            List<FootfallData> footfallDataList;
            if (department != null && !department.trim().isEmpty() && !"All".equalsIgnoreCase(department.trim())) {
                footfallDataList = footfallRepository.findByDepartmentAndDateBetween(department, startDate, endDate);
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
            List<Map<String, Object>> responseData = footfallDataList.stream().map(this::createFootfallResponse)
                    .collect(Collectors.toList());

            // Calculate statistics using repository methods
            Map<String, Object> statistics = calculateEnhancedStatistics(footfallDataList, startDate, endDate);

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
     * Get footfall by specific date
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
     * Get footfall statistics - ENHANCED with repository methods
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
                result.put("statistics", calculateEnhancedStatistics(data, startDate, endDate));
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
     * Debug method to check data - ENHANCED with repository method
     */
    public Map<String, Object> debugFootfallData() {
        Map<String, Object> debug = new HashMap<>();

        try {
            long totalCount = footfallRepository.count();
            debug.put("totalRecords", totalCount);

            if (totalCount > 0) {
                // Get data range info using repository method
                List<Object[]> rangeInfo = footfallRepository.getDataRangeInfo();
                if (!rangeInfo.isEmpty()) {
                    Object[] info = rangeInfo.get(0);
                    debug.put("dateRange", Map.of(
                            "minDate", info[0] != null ? info[0].toString() : "N/A",
                            "maxDate", info[1] != null ? info[1].toString() : "N/A",
                            "totalRecords", info[2] != null ? info[2].toString() : "0"
                    ));
                }

                // Get sample records
                List<FootfallData> sampleRecords = footfallRepository.findAll()
                        .stream().limit(10).collect(Collectors.toList());

                List<Map<String, Object>> samples = sampleRecords.stream()
                        .map(this::createFootfallResponse)
                        .collect(Collectors.toList());

                debug.put("sampleRecords", samples);
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
     * Get footfall trends with aggregation support - ENHANCED with repository methods
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

            List<Map<String, Object>> trends = new ArrayList<>();

            switch (period.toLowerCase()) {
                case "weekly":
                    List<Object[]> weeklyData = footfallRepository.getWeeklyFootfall(startDate, endDate);
                    for (Object[] row : weeklyData) {
                        Map<String, Object> weekData = new HashMap<>();
                        weekData.put("weekStart", row[0] != null ? row[0].toString() : "");
                        weekData.put("totalEmployees", row[1] != null ? ((Number) row[1]).intValue() : 0);
                        weekData.put("totalVisitors", row[2] != null ? ((Number) row[2]).intValue() : 0);
                        weekData.put("totalFootfall", row[3] != null ? ((Number) row[3]).intValue() : 0);
                        weekData.put("averageEmployees", row[4] != null ? ((Number) row[4]).doubleValue() : 0.0);
                        weekData.put("averageVisitors", row[5] != null ? ((Number) row[5]).doubleValue() : 0.0);
                        weekData.put("averageTotal", row[6] != null ? ((Number) row[6]).doubleValue() : 0.0);
                        trends.add(weekData);
                    }
                    break;

                case "monthly":
                    List<Object[]> monthlyData = footfallRepository.getMonthlyFootfall(startDate, endDate);
                    for (Object[] row : monthlyData) {
                        Map<String, Object> monthData = new HashMap<>();
                        monthData.put("monthStart", row[0] != null ? row[0].toString() : "");
                        monthData.put("totalEmployees", row[1] != null ? ((Number) row[1]).intValue() : 0);
                        monthData.put("totalVisitors", row[2] != null ? ((Number) row[2]).intValue() : 0);
                        monthData.put("totalFootfall", row[3] != null ? ((Number) row[3]).intValue() : 0);
                        monthData.put("averageEmployees", row[4] != null ? ((Number) row[4]).doubleValue() : 0.0);
                        monthData.put("averageVisitors", row[5] != null ? ((Number) row[5]).doubleValue() : 0.0);
                        monthData.put("averageTotal", row[6] != null ? ((Number) row[6]).doubleValue() : 0.0);
                        monthData.put("daysRecorded", row[7] != null ? ((Number) row[7]).intValue() : 0);
                        trends.add(monthData);
                    }
                    break;

                default: // daily
                    List<FootfallData> data = footfallRepository.findByDateBetween(startDate, endDate);
                    for (FootfallData footfall : data) {
                        Map<String, Object> dayData = new HashMap<>();
                        dayData.put("date", footfall.getDate().toString());
                        dayData.put("employeeCount", footfall.getEmployeeCount());
                        dayData.put("visitorCount", footfall.getVisitorCount());
                        dayData.put("totalFootfall", footfall.getTotalFootfall());
                        trends.add(dayData);
                    }
            }

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

            boolean exists = footfallRepository.existsByDate(date);

            result.put("success", true);
            result.put("date", date.toString());
            result.put("hasData", exists);

            if (exists) {
                Optional<FootfallData> footfall = footfallRepository.findByDate(date);
                footfall.ifPresent(data -> result.put("data", createFootfallResponse(data)));
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

    /**
     * Calculate per-employee consumption metrics - ENHANCED
     */
    public Map<String, Object> calculatePerEmployeeConsumption(
            LocalDate startDate, LocalDate endDate, Long categoryId, Long itemId) {

        Map<String, Object> result = new HashMap<>();

        try {
            // Set defaults
            if (endDate == null) endDate = LocalDate.now();
            if (startDate == null) startDate = endDate.minusDays(30);

            // Get total employee count for the period
            Long totalEmployees = footfallRepository.getTotalEmployeeCountInPeriod(startDate, endDate);
            Double avgEmployees = footfallRepository.getAverageEmployeeCountInPeriod(startDate, endDate);

            if (totalEmployees == null || totalEmployees == 0) {
                result.put("success", false);
                result.put("message", "No footfall data found for the specified period");
                return result;
            }

            Map<String, Object> metrics = new HashMap<>();
            metrics.put("totalEmployees", totalEmployees);
            metrics.put("averageEmployeesPerDay", avgEmployees != null ? Math.round(avgEmployees * 100.0) / 100.0 : 0.0);

            // If consumption repository is available, calculate consumption metrics
            if (consumptionRecordRepository != null) {
                List<ConsumptionRecord> consumptionRecords;

                if (categoryId != null) {
                    consumptionRecords = consumptionRecordRepository.findByCategoryIdAndDateRange(
                            categoryId, startDate, endDate);
                } else if (itemId != null) {
                    consumptionRecords = consumptionRecordRepository.findByItemIdsAndDateRange(
                            Collections.singletonList(itemId), startDate, endDate);
                } else {
                    consumptionRecords = consumptionRecordRepository.findByConsumptionDateBetween(
                            startDate, endDate);
                }

                // Calculate total consumption
                BigDecimal totalConsumption = consumptionRecords.stream()
                        .map(r -> r.getConsumedQuantity() != null ? r.getConsumedQuantity() : BigDecimal.ZERO)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);

                // Calculate per-employee consumption
                BigDecimal perEmployeeDaily = totalConsumption.divide(
                        BigDecimal.valueOf(totalEmployees), 4, RoundingMode.HALF_UP);

                metrics.put("totalConsumption", totalConsumption);
                metrics.put("perEmployeeConsumption", perEmployeeDaily);
                metrics.put("itemsConsumed", consumptionRecords.size());
            } else {
                metrics.put("message", "Consumption data integration required for full metrics");
            }

            result.put("success", true);
            result.put("period", Map.of(
                    "startDate", startDate.toString(),
                    "endDate", endDate.toString()
            ));
            result.put("metrics", metrics);

        } catch (Exception e) {
            result.put("success", false);
            result.put("error", "Error calculating per-employee consumption: " + e.getMessage());
            e.printStackTrace();
        }

        return result;
    }

    /**
     * Get footfall vs consumption correlation analysis - ENHANCED
     */
    public Map<String, Object> getFootfallConsumptionCorrelation(
            LocalDate startDate, LocalDate endDate, String period) {

        Map<String, Object> result = new HashMap<>();

        try {
            // Set defaults
            if (endDate == null) endDate = LocalDate.now();
            if (startDate == null) startDate = endDate.minusDays(30);

            List<FootfallData> footfallData = footfallRepository.findByDateBetween(startDate, endDate);

            List<Map<String, Object>> correlationData = new ArrayList<>();

            if (consumptionRecordRepository != null) {
                // Get consumption data for the same period
                List<ConsumptionRecord> consumptionData = consumptionRecordRepository
                        .findByConsumptionDateBetween(startDate, endDate);

                // Group consumption by date
                Map<LocalDate, BigDecimal> consumptionByDate = consumptionData.stream()
                        .collect(Collectors.groupingBy(
                                ConsumptionRecord::getConsumptionDate,
                                Collectors.mapping(
                                        cr -> cr.getConsumedQuantity() != null ? cr.getConsumedQuantity() : BigDecimal.ZERO,
                                        Collectors.reducing(BigDecimal.ZERO, BigDecimal::add)
                                )
                        ));

                // Create correlation data points
                for (FootfallData footfall : footfallData) {
                    Map<String, Object> point = new HashMap<>();
                    point.put("date", footfall.getDate().toString());
                    point.put("footfall", footfall.getTotalFootfall());
                    point.put("employees", footfall.getEmployeeCount());
                    point.put("consumption", consumptionByDate.getOrDefault(footfall.getDate(), BigDecimal.ZERO));
                    correlationData.add(point);
                }

                // Calculate correlation coefficient if we have data
                if (!correlationData.isEmpty()) {
                    double correlation = calculateCorrelation(footfallData, consumptionByDate);
                    result.put("correlationCoefficient", Math.round(correlation * 1000.0) / 1000.0);
                }
            }

            result.put("success", true);
            result.put("period", period);
            result.put("dateRange", Map.of(
                    "startDate", startDate.toString(),
                    "endDate", endDate.toString()
            ));
            result.put("correlationData", correlationData);

        } catch (Exception e) {
            result.put("success", false);
            result.put("error", "Error calculating correlation: " + e.getMessage());
            e.printStackTrace();
        }

        return result;
    }

    /**
     * Get aggregated footfall data
     */
    public Map<String, Object> getAggregatedFootfall(
            LocalDate startDate, LocalDate endDate, String aggregation) {

        return getFootfallTrends(aggregation, startDate, endDate);
    }

    /**
     * Analyze footfall trends and patterns - ENHANCED with repository methods
     */
    public Map<String, Object> analyzeFootfallTrends(LocalDate startDate, LocalDate endDate) {
        Map<String, Object> result = new HashMap<>();

        try {
            // Set defaults
            if (endDate == null) endDate = LocalDate.now();
            if (startDate == null) startDate = endDate.minusDays(30);

            Map<String, Object> trends = new HashMap<>();

            // Get day of week patterns using repository method
            List<Object[]> dayOfWeekData = footfallRepository.getDayOfWeekPattern(startDate, endDate);
            List<Map<String, Object>> dayPatterns = new ArrayList<>();

            for (Object[] row : dayOfWeekData) {
                Map<String, Object> dayPattern = new HashMap<>();
                dayPattern.put("dayOfWeek", row[1] != null ? row[1].toString() : "");
                dayPattern.put("averageEmployees", row[2] != null ? ((Number) row[2]).doubleValue() : 0.0);
                dayPattern.put("averageFootfall", row[3] != null ? ((Number) row[3]).doubleValue() : 0.0);
                dayPattern.put("recordCount", row[4] != null ? ((Number) row[4]).intValue() : 0);
                dayPatterns.add(dayPattern);
            }
            trends.put("dayOfWeekPatterns", dayPatterns);

            // Get monthly growth rates using repository method
            List<Object[]> growthData = footfallRepository.getMonthlyGrowthRates(startDate, endDate);
            List<Map<String, Object>> growthRates = new ArrayList<>();

            for (Object[] row : growthData) {
                Map<String, Object> growth = new HashMap<>();
                growth.put("month", row[0] != null ? row[0].toString() : "");
                growth.put("averageFootfall", row[1] != null ? ((Number) row[1]).doubleValue() : 0.0);
                growth.put("previousMonth", row[2] != null ? ((Number) row[2]).doubleValue() : 0.0);
                growth.put("growthRate", row[3] != null ? ((Number) row[3]).doubleValue() : 0.0);
                growthRates.add(growth);
            }
            trends.put("monthlyGrowthRates", growthRates);

            // Get department-wise comparison
            List<Object[]> deptData = footfallRepository.getDepartmentWiseFootfall(startDate, endDate);
            List<Map<String, Object>> departmentStats = new ArrayList<>();

            for (Object[] row : deptData) {
                Map<String, Object> dept = new HashMap<>();
                dept.put("department", row[0] != null ? row[0].toString() : "Unknown");
                dept.put("recordCount", row[1] != null ? ((Number) row[1]).intValue() : 0);
                dept.put("averageEmployees", row[2] != null ? ((Number) row[2]).doubleValue() : 0.0);
                dept.put("averageFootfall", row[3] != null ? ((Number) row[3]).doubleValue() : 0.0);
                dept.put("totalFootfall", row[4] != null ? ((Number) row[4]).intValue() : 0);
                departmentStats.add(dept);
            }
            trends.put("departmentWiseStats", departmentStats);

            result.put("success", true);
            result.put("trends", trends);
            result.put("dateRange", Map.of(
                    "startDate", startDate.toString(),
                    "endDate", endDate.toString()
            ));

        } catch (Exception e) {
            result.put("success", false);
            result.put("error", "Error analyzing trends: " + e.getMessage());
            e.printStackTrace();
        }

        return result;
    }

    /**
     * Get peak/off-peak analysis - ENHANCED with repository methods
     */
    public Map<String, Object> analyzePeakOffPeak(LocalDate startDate, LocalDate endDate) {
        Map<String, Object> result = new HashMap<>();

        try {
            // Set defaults
            if (endDate == null) endDate = LocalDate.now();
            if (startDate == null) startDate = endDate.minusDays(30);

            // Get average footfall for threshold calculation
            Double avgFootfall = footfallRepository.getAverageTotalFootfallInPeriod(startDate, endDate);
            if (avgFootfall == null) avgFootfall = 0.0;

            Integer highThreshold = (int) (avgFootfall * 1.2); // 20% above average
            Integer lowThreshold = (int) (avgFootfall * 0.8);  // 20% below average

            // Get high footfall days
            List<FootfallData> peakDays = footfallRepository.getHighFootfallDays(startDate, endDate, highThreshold);
            List<Map<String, Object>> peakDaysList = peakDays.stream()
                    .map(this::createFootfallResponse)
                    .collect(Collectors.toList());

            // Get low footfall days
            List<FootfallData> offPeakDays = footfallRepository.getLowFootfallDays(startDate, endDate, lowThreshold);
            List<Map<String, Object>> offPeakDaysList = offPeakDays.stream()
                    .map(this::createFootfallResponse)
                    .collect(Collectors.toList());

            // Get normal days
            List<FootfallData> allDays = footfallRepository.findByDateBetween(startDate, endDate);
            List<Map<String, Object>> normalDaysList = allDays.stream()
                    .filter(d -> d.getTotalFootfall() > lowThreshold && d.getTotalFootfall() < highThreshold)
                    .map(this::createFootfallResponse)
                    .collect(Collectors.toList());

            result.put("success", true);
            result.put("analysis", Map.of(
                    "averageFootfall", Math.round(avgFootfall * 100.0) / 100.0,
                    "peakThreshold", highThreshold,
                    "offPeakThreshold", lowThreshold,
                    "peakDays", peakDaysList,
                    "offPeakDays", offPeakDaysList,
                    "normalDays", normalDaysList,
                    "peakDayCount", peakDaysList.size(),
                    "offPeakDayCount", offPeakDaysList.size(),
                    "normalDayCount", normalDaysList.size()
            ));
            result.put("dateRange", Map.of(
                    "startDate", startDate.toString(),
                    "endDate", endDate.toString()
            ));

        } catch (Exception e) {
            result.put("success", false);
            result.put("error", "Error analyzing peak periods: " + e.getMessage());
            e.printStackTrace();
        }

        return result;
    }

    /**
     * Delete footfall data for a specific date
     */
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

    /**
     * Calculate enhanced statistics using repository methods
     */
    private Map<String, Object> calculateEnhancedStatistics(List<FootfallData> data, LocalDate startDate, LocalDate endDate) {
        Map<String, Object> stats = new HashMap<>();

        if (data.isEmpty()) {
            return getEmptyStatistics();
        }

        // Use repository methods for accurate calculations
        Double avgEmployees = footfallRepository.getAverageEmployeeCountInPeriod(startDate, endDate);
        Double avgVisitors = footfallRepository.getAverageVisitorCountInPeriod(startDate, endDate);
        Double avgTotal = footfallRepository.getAverageTotalFootfallInPeriod(startDate, endDate);
        Long totalEmployees = footfallRepository.getTotalEmployeeCountInPeriod(startDate, endDate);
        Long totalFootfall = footfallRepository.getTotalFootfallInPeriod(startDate, endDate);

        stats.put("totalDays", data.size());
        stats.put("totalEmployees", totalEmployees != null ? totalEmployees : 0L);
        stats.put("totalFootfall", totalFootfall != null ? totalFootfall : 0L);
        stats.put("averageEmployeesPerDay", avgEmployees != null ? Math.round(avgEmployees * 100.0) / 100.0 : 0.0);
        stats.put("averageVisitorsPerDay", avgVisitors != null ? Math.round(avgVisitors * 100.0) / 100.0 : 0.0);
        stats.put("averageTotalPerDay", avgTotal != null ? Math.round(avgTotal * 100.0) / 100.0 : 0.0);

        // Find peak and low days
        FootfallData maxDay = data.stream()
                .max(Comparator.comparingInt(FootfallData::getTotalFootfall))
                .orElse(null);
        FootfallData minDay = data.stream()
                .filter(f -> f.getTotalFootfall() > 0)
                .min(Comparator.comparingInt(FootfallData::getTotalFootfall))
                .orElse(null);

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

    /**
     * Calculate correlation coefficient between footfall and consumption
     */
    private double calculateCorrelation(List<FootfallData> footfallData, Map<LocalDate, BigDecimal> consumptionByDate) {
        if (footfallData.size() < 2) return 0.0;

        double[] footfall = new double[footfallData.size()];
        double[] consumption = new double[footfallData.size()];

        for (int i = 0; i < footfallData.size(); i++) {
            FootfallData fd = footfallData.get(i);
            footfall[i] = fd.getTotalFootfall();
            consumption[i] = consumptionByDate.getOrDefault(fd.getDate(), BigDecimal.ZERO).doubleValue();
        }

        // Calculate correlation coefficient
        double sumX = 0, sumY = 0, sumXY = 0, sumX2 = 0, sumY2 = 0;
        int n = footfall.length;

        for (int i = 0; i < n; i++) {
            sumX += footfall[i];
            sumY += consumption[i];
            sumXY += footfall[i] * consumption[i];
            sumX2 += footfall[i] * footfall[i];
            sumY2 += consumption[i] * consumption[i];
        }

        double correlation = (n * sumXY - sumX * sumY) /
                Math.sqrt((n * sumX2 - sumX * sumX) * (n * sumY2 - sumY * sumY));

        return Double.isNaN(correlation) ? 0.0 : correlation;
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
            footfall.setCreatedAt(LocalDateTime.now());
            footfall.setUpdatedAt(LocalDateTime.now());
            footfallRepository.save(footfall);
            return true; // Created
        }
    }

    // Excel parsing helper methods remain the same
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
                    String strValue = cell.getStringCellValue().trim().replaceAll("[^0-9-]", "");
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
}