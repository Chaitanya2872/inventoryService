package com.bmsedge.inventory.service;

import com.bmsedge.inventory.exception.BusinessException;
import com.bmsedge.inventory.model.ConsumptionRecord;
import com.bmsedge.inventory.model.Item;
import com.bmsedge.inventory.repository.ConsumptionRecordRepository;
import com.bmsedge.inventory.repository.ItemRepository;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

@Service
public class ConsumptionUploadService {

    private static final Logger logger = LoggerFactory.getLogger(ConsumptionUploadService.class);

    @Autowired
    private ItemRepository itemRepository;

    @Autowired
    private ConsumptionRecordRepository consumptionRecordRepository;

    @Transactional
    public Map<String, Object> uploadConsumptionFromFile(MultipartFile file, Long userId) throws IOException {
        Map<String, Object> result = new HashMap<>();
        String fileName = file.getOriginalFilename();

        if (fileName == null || file.isEmpty()) {
            throw new BusinessException("File cannot be empty");
        }

        if (!fileName.toLowerCase().endsWith(".xlsx") && !fileName.toLowerCase().endsWith(".xls")) {
            throw new BusinessException("Only Excel files (.xlsx, .xls) are supported");
        }

        List<ConsumptionRecordRequest> requests = new ArrayList<>();
        List<String> parseErrors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        int totalRows = 0;

        // Parse Excel file
        try {
            Map<String, Object> parseResult = parseConsumptionExcel(file, parseErrors);
            requests = (List<ConsumptionRecordRequest>) parseResult.get("records");
            totalRows = (Integer) parseResult.get("totalRows");
        } catch (Exception e) {
            throw new BusinessException("Error parsing file: " + e.getMessage());
        }

        result.put("totalRowsProcessed", totalRows);
        result.put("successfullyParsed", requests.size());
        result.put("parseErrors", parseErrors);

        if (requests.isEmpty()) {
            result.put("success", false);
            result.put("message", "No valid consumption records found in the file.");
            return result;
        }

        // Validate items exist and create consumption records
        List<Map<String, Object>> createdRecords = new ArrayList<>();
        List<String> creationErrors = new ArrayList<>();
        Set<String> missingItems = new LinkedHashSet<>();

        for (ConsumptionRecordRequest request : requests) {
            try {
                // Find item by name and SKU
                Optional<Item> itemOpt = findItem(request.itemName, request.itemSku);

                if (!itemOpt.isPresent()) {
                    String itemIdentifier = request.itemSku != null && !request.itemSku.isEmpty()
                            ? request.itemName + " (" + request.itemSku + ")"
                            : request.itemName;
                    missingItems.add(itemIdentifier);
                    continue;
                }

                Item item = itemOpt.get();

                // Check if record already exists for this item and date
                Optional<ConsumptionRecord> existingRecord = consumptionRecordRepository
                        .findByItemIdAndConsumptionDate(item.getId(), request.consumptionDate);

                ConsumptionRecord record;
                if (existingRecord.isPresent()) {
                    // Update existing record
                    record = existingRecord.get();
                    warnings.add("Updated existing record for " + item.getFullDisplayName() +
                            " on " + request.consumptionDate);
                } else {
                    // Create new record
                    record = new ConsumptionRecord();
                    record.setItem(item);
                    record.setConsumptionDate(request.consumptionDate);
                }

                // Set values
                if (request.openingStock != null) {
                    record.setOpeningStock(request.openingStock);
                }
                if (request.receivedQuantity != null) {
                    record.setReceivedQuantity(request.receivedQuantity);
                }
                if (request.consumedQuantity != null) {
                    record.setConsumedQuantity(request.consumedQuantity);
                }
                if (request.closingStock != null) {
                    record.setClosingStock(request.closingStock);
                }
                if (request.department != null) {
                    record.setDepartment(request.department);
                }
                if (request.costCenter != null) {
                    record.setCostCenter(request.costCenter);
                }
                if (request.employeeCount != null) {
                    record.setEmployeeCount(request.employeeCount);
                }
                if (request.notes != null) {
                    record.setNotes(request.notes);
                }

                // Save record (this will trigger statistics updates via database triggers or listeners)
                record = consumptionRecordRepository.save(record);

                // DON'T add entity directly - convert immediately to avoid circular refs
                // createdRecords.add(record);  ← This causes circular reference!

                // Instead, convert to simple map right away
                Map<String, Object> simpleRecord = toSimpleResponse(record);
                createdRecords.add(simpleRecord);

            } catch (Exception e) {
                creationErrors.add("Error creating record for " + request.itemName + ": " + e.getMessage());
                logger.error("Error creating consumption record", e);
            }
        }

        // Build warnings for missing items
        if (!missingItems.isEmpty()) {
            warnings.add("⚠️  ITEMS NOT FOUND - Create these items first using /api/upload/items:");
            warnings.addAll(missingItems);
        }

        result.put("success", !createdRecords.isEmpty() || missingItems.isEmpty());
        result.put("recordsCreated", createdRecords.size());
        result.put("records", createdRecords);  // Already simple maps, no conversion needed
        result.put("creationErrors", creationErrors);
        result.put("warnings", warnings);
        result.put("missingItemsCount", missingItems.size());

        if (!createdRecords.isEmpty()) {
            result.put("message", String.format(
                    "Successfully created/updated %d consumption records. %s",
                    createdRecords.size(),
                    missingItems.isEmpty() ? "" : missingItems.size() + " items not found (see warnings)."
            ));
        } else if (!missingItems.isEmpty()) {
            result.put("message", "No records created. All items are missing. Please create items first.");
        } else {
            result.put("message", "Failed to create any consumption records. Check errors for details.");
        }

        return result;
    }

    /**
     * Parse consumption Excel file with flexible structure detection
     */
    private Map<String, Object> parseConsumptionExcel(MultipartFile file, List<String> errors) throws IOException {
        Map<String, Object> result = new HashMap<>();
        List<ConsumptionRecordRequest> allRecords = new ArrayList<>();
        int totalRows = 0;

        try (Workbook workbook = new XSSFWorkbook(file.getInputStream())) {
            logger.info("Parsing consumption Excel file: {} sheets", workbook.getNumberOfSheets());

            for (int sheetIdx = 0; sheetIdx < workbook.getNumberOfSheets(); sheetIdx++) {
                Sheet sheet = workbook.getSheetAt(sheetIdx);
                String sheetName = sheet.getSheetName();

                logger.info("Processing sheet: {}", sheetName);

                if (sheet.getPhysicalNumberOfRows() == 0) {
                    continue;
                }

                try {
                    SheetStructure structure = analyzeConsumptionSheet(sheet);

                    if (!structure.isValid) {
                        errors.add("Sheet '" + sheetName + "': " + structure.errorMessage);
                        continue;
                    }

                    logger.info("  Detected columns: {}", structure.columnMap.keySet());

                    for (int rowIdx = structure.dataStartRow; rowIdx <= sheet.getLastRowNum(); rowIdx++) {
                        Row row = sheet.getRow(rowIdx);
                        if (row == null || isRowEmpty(row)) continue;

                        try {
                            ConsumptionRecordRequest record = parseConsumptionRow(row, structure, sheetName);
                            if (record != null) {
                                allRecords.add(record);
                                totalRows++;
                            }
                        } catch (Exception e) {
                            errors.add(String.format("Sheet '%s' Row %d: %s",
                                    sheetName, rowIdx + 1, e.getMessage()));
                        }
                    }

                } catch (Exception e) {
                    errors.add("Error processing sheet '" + sheetName + "': " + e.getMessage());
                }
            }

            logger.info("Total consumption records parsed: {}", allRecords.size());

        } catch (Exception e) {
            throw new IOException("Failed to read Excel file: " + e.getMessage(), e);
        }

        result.put("records", allRecords);
        result.put("totalRows", totalRows);
        return result;
    }

    /**
     * Analyze sheet structure for consumption data
     */
    private SheetStructure analyzeConsumptionSheet(Sheet sheet) {
        SheetStructure structure = new SheetStructure();

        for (int rowIdx = 0; rowIdx < Math.min(5, sheet.getPhysicalNumberOfRows()); rowIdx++) {
            Row row = sheet.getRow(rowIdx);
            if (row == null) continue;

            int headerScore = 0;
            Map<String, Integer> columnMap = new HashMap<>();

            for (Cell cell : row) {
                String value = getCellValueAsString(cell);
                if (value == null || value.trim().isEmpty()) continue;

                String lower = value.toLowerCase().trim();

                // Item name detection
                if (lower.equals("item") || lower.equals("item_name") || lower.equals("itemname") ||
                        (lower.contains("item") && (lower.contains("name") || lower.contains("description")))) {
                    if (!lower.contains("sku")) {
                        columnMap.put("item", cell.getColumnIndex());
                        headerScore += 3;
                    }
                }

                // SKU detection
                if (lower.equals("sku") || lower.equals("item_sku") || lower.equals("itemsku")) {
                    columnMap.put("sku", cell.getColumnIndex());
                    headerScore += 2;
                }

                // Date detection (single column)
                if (lower.equals("date") || lower.contains("consumption") && lower.contains("date") ||
                        lower.equals("consumption_date") || lower.equals("consumptiondate")) {
                    columnMap.put("date", cell.getColumnIndex());
                    headerScore += 3;
                }

                // Date detection (split columns - day/month/year)
                if (lower.equals("day") || lower.equals("dd")) {
                    columnMap.put("day", cell.getColumnIndex());
                    headerScore += 1;
                }
                if (lower.equals("month") || lower.equals("mm")) {
                    columnMap.put("month", cell.getColumnIndex());
                    headerScore += 1;
                }
                if (lower.equals("year") || lower.equals("yyyy")) {
                    columnMap.put("year", cell.getColumnIndex());
                    headerScore += 1;
                }

                // Opening stock
                if (lower.contains("opening") && lower.contains("stock")) {
                    columnMap.put("openingStock", cell.getColumnIndex());
                    headerScore += 2;
                }

                // Received quantity
                if (lower.contains("received") || (lower.contains("receipt") && lower.contains("qty"))) {
                    columnMap.put("receivedQuantity", cell.getColumnIndex());
                    headerScore += 2;
                }

                // Consumed quantity
                if ((lower.contains("consumed") || lower.contains("consumption")) &&
                        (lower.contains("qty") || lower.contains("quantity"))) {
                    columnMap.put("consumedQuantity", cell.getColumnIndex());
                    headerScore += 3;
                } else if (lower.equals("consumed") || lower.equals("consumption") || lower.equals("quantity")) {
                    columnMap.put("consumedQuantity", cell.getColumnIndex());
                    headerScore += 3;
                }

                // Closing stock
                if (lower.contains("closing") && lower.contains("stock")) {
                    columnMap.put("closingStock", cell.getColumnIndex());
                    headerScore += 2;
                }

                // Department
                if (lower.equals("department") || lower.equals("dept")) {
                    columnMap.put("department", cell.getColumnIndex());
                    headerScore += 1;
                }

                // Cost center
                if (lower.contains("cost") && lower.contains("center")) {
                    columnMap.put("costCenter", cell.getColumnIndex());
                    headerScore += 1;
                }

                // Employee count
                if (lower.contains("employee") && lower.contains("count")) {
                    columnMap.put("employeeCount", cell.getColumnIndex());
                    headerScore += 1;
                }

                // Notes
                if (lower.equals("notes") || lower.equals("remarks") || lower.equals("comments")) {
                    columnMap.put("notes", cell.getColumnIndex());
                    headerScore += 1;
                }
            }

            // Valid if we have item, date (or day/month/year), and consumed quantity
            boolean hasDate = columnMap.containsKey("date") ||
                    (columnMap.containsKey("day") && columnMap.containsKey("month") && columnMap.containsKey("year"));

            if (headerScore >= 6 && columnMap.containsKey("item") &&
                    hasDate && columnMap.containsKey("consumedQuantity")) {
                structure.isValid = true;
                structure.type = "HEADER_BASED";
                structure.headerRow = rowIdx;
                structure.dataStartRow = rowIdx + 1;
                structure.columnMap = columnMap;
                return structure;
            }
        }

        structure.isValid = false;
        structure.errorMessage = "Could not detect valid consumption structure. Required: Item Name, Date (or day/month/year), Consumed Quantity";
        return structure;
    }

    /**
     * Parse a consumption record row
     */
    private ConsumptionRecordRequest parseConsumptionRow(Row row, SheetStructure structure, String sheetName) {
        String itemName = getColumnValue(row, structure.columnMap, "item");
        String itemSku = getColumnValue(row, structure.columnMap, "sku");

        // Handle date - either single column or split (day/month/year)
        LocalDate date = null;
        if (structure.columnMap.containsKey("date")) {
            date = getColumnValueAsDate(row, structure.columnMap, "date");
        } else if (structure.columnMap.containsKey("day") &&
                structure.columnMap.containsKey("month") &&
                structure.columnMap.containsKey("year")) {
            date = parseSplitDate(row, structure.columnMap);
        }

        BigDecimal consumedQty = getColumnValueAsNumber(row, structure.columnMap, "consumedQuantity");

        // Skip if essential fields are missing
        if ((itemName == null || itemName.trim().isEmpty()) || date == null) {
            return null;
        }

        // Skip total rows
        if (itemName.toLowerCase().contains("total")) return null;

        ConsumptionRecordRequest request = new ConsumptionRecordRequest();
        request.itemName = itemName.trim();
        request.itemSku = itemSku != null ? itemSku.trim() : null;
        request.consumptionDate = date;
        request.consumedQuantity = consumedQty != null ? consumedQty : BigDecimal.ZERO;
        request.openingStock = getColumnValueAsNumber(row, structure.columnMap, "openingStock");
        request.receivedQuantity = getColumnValueAsNumber(row, structure.columnMap, "receivedQuantity");
        request.closingStock = getColumnValueAsNumber(row, structure.columnMap, "closingStock");
        request.department = getColumnValue(row, structure.columnMap, "department");
        request.costCenter = getColumnValue(row, structure.columnMap, "costCenter");

        BigDecimal empCount = getColumnValueAsNumber(row, structure.columnMap, "employeeCount");
        request.employeeCount = empCount != null ? empCount.intValue() : null;

        request.notes = getColumnValue(row, structure.columnMap, "notes");

        return request;
    }

    /**
     * Parse date from split day/month/year columns
     */
    private LocalDate parseSplitDate(Row row, Map<String, Integer> columnMap) {
        try {
            String dayStr = getColumnValue(row, columnMap, "day");
            String monthStr = getColumnValue(row, columnMap, "month");
            String yearStr = getColumnValue(row, columnMap, "year");

            if (dayStr == null || monthStr == null || yearStr == null) {
                return null;
            }

            // Try parsing dayStr as a full date first (e.g., "2025-01-02")
            if (dayStr.contains("-") || dayStr.contains("/")) {
                return parseStringDate(dayStr);
            }

            // Otherwise parse as separate components
            int day = Integer.parseInt(dayStr.trim());
            int year = Integer.parseInt(yearStr.trim());

            // Parse month (can be numeric or text like "Jan")
            int month;
            if (monthStr.matches("\\d+")) {
                month = Integer.parseInt(monthStr.trim());
            } else {
                // Text month like "Jan", "January"
                month = parseMonthName(monthStr.trim());
            }

            return LocalDate.of(year, month, day);
        } catch (Exception e) {
            logger.warn("Failed to parse split date: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Parse month name to number (Jan=1, Feb=2, etc.)
     */
    private int parseMonthName(String monthStr) {
        String lower = monthStr.toLowerCase();
        if (lower.startsWith("jan")) return 1;
        if (lower.startsWith("feb")) return 2;
        if (lower.startsWith("mar")) return 3;
        if (lower.startsWith("apr")) return 4;
        if (lower.startsWith("may")) return 5;
        if (lower.startsWith("jun")) return 6;
        if (lower.startsWith("jul")) return 7;
        if (lower.startsWith("aug")) return 8;
        if (lower.startsWith("sep")) return 9;
        if (lower.startsWith("oct")) return 10;
        if (lower.startsWith("nov")) return 11;
        if (lower.startsWith("dec")) return 12;
        throw new IllegalArgumentException("Invalid month name: " + monthStr);
    }

    /**
     * Find item by name and SKU
     */
    private Optional<Item> findItem(String itemName, String itemSku) {
        if (itemSku != null && !itemSku.isEmpty()) {
            // Search by name + SKU
            return itemRepository.findByItemNameAndItemSku(itemName, itemSku);
        } else {
            // Search by name only (find first match with null SKU)
            List<Item> items = itemRepository.findByItemNameContainingIgnoreCase(itemName);
            return items.stream()
                    .filter(item -> item.getItemName().equalsIgnoreCase(itemName))
                    .filter(item -> item.getItemSku() == null || item.getItemSku().isEmpty())
                    .findFirst();
        }
    }

    /**
     * Validate consumption file without importing
     */
    public Map<String, Object> validateConsumptionFile(MultipartFile file) throws IOException {
        Map<String, Object> validation = new HashMap<>();
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        Map<String, Object> parseResult = parseConsumptionExcel(file, errors);
        List<ConsumptionRecordRequest> requests = (List<ConsumptionRecordRequest>) parseResult.get("records");

        Set<String> missingItems = new LinkedHashSet<>();
        int validRecords = 0;

        for (ConsumptionRecordRequest request : requests) {
            Optional<Item> itemOpt = findItem(request.itemName, request.itemSku);
            if (itemOpt.isPresent()) {
                validRecords++;
            } else {
                String itemIdentifier = request.itemSku != null && !request.itemSku.isEmpty()
                        ? request.itemName + " (" + request.itemSku + ")"
                        : request.itemName;
                missingItems.add(itemIdentifier);
            }
        }

        if (!missingItems.isEmpty()) {
            warnings.add("Missing items (create these first):");
            warnings.addAll(missingItems);
        }

        validation.put("valid", errors.isEmpty() && !requests.isEmpty());
        validation.put("totalRecords", requests.size());
        validation.put("validRecords", validRecords);
        validation.put("missingItems", new ArrayList<>(missingItems));
        validation.put("parseErrors", errors);
        validation.put("warnings", warnings);
        validation.put("message", String.format(
                "Found %d records. %d valid, %d items missing.",
                requests.size(), validRecords, missingItems.size()
        ));

        return validation;
    }

    /**
     * Get upload template information
     */
    public Map<String, Object> getConsumptionTemplate() {
        Map<String, Object> template = new HashMap<>();
        template.put("format", "Excel (.xlsx) - Flexible structure");
        template.put("requiredColumns", new String[]{
                "Item Name (or similar)",
                "Consumption Date",
                "Consumed Quantity"
        });
        template.put("optionalColumns", new String[]{
                "Item SKU (for variants)",
                "Opening Stock",
                "Received Quantity",
                "Closing Stock",
                "Department",
                "Cost Center",
                "Employee Count",
                "Notes"
        });
        template.put("notes", new String[]{
                "Items must exist before importing consumption",
                "Items identified by: Name + SKU (if provided)",
                "Missing items will be listed in warnings",
                "Automatically triggers statistics and correlations"
        });
        return template;
    }

    /**
     * Convert ConsumptionRecord to simple response (no circular references)
     */
    private Map<String, Object> toSimpleResponse(ConsumptionRecord record) {
        Map<String, Object> response = new HashMap<>();
        response.put("id", record.getId());
        response.put("consumptionDate", record.getConsumptionDate());
        response.put("openingStock", record.getOpeningStock());
        response.put("receivedQuantity", record.getReceivedQuantity());
        response.put("consumedQuantity", record.getConsumedQuantity());
        response.put("closingStock", record.getClosingStock());
        response.put("department", record.getDepartment());
        response.put("costCenter", record.getCostCenter());
        response.put("employeeCount", record.getEmployeeCount());
        response.put("notes", record.getNotes());

        // Add item info (simplified, no circular references)
        if (record.getItem() != null) {
            Map<String, Object> itemInfo = new HashMap<>();
            itemInfo.put("id", record.getItem().getId());
            itemInfo.put("itemName", record.getItem().getItemName());
            itemInfo.put("itemSku", record.getItem().getItemSku());
            itemInfo.put("currentQuantity", record.getItem().getCurrentQuantity());
            itemInfo.put("unitOfMeasurement", record.getItem().getUnitOfMeasurement());

            // Add category info (without items list)
            if (record.getItem().getCategory() != null) {
                Map<String, Object> categoryInfo = new HashMap<>();
                categoryInfo.put("id", record.getItem().getCategory().getId());
                categoryInfo.put("categoryName", record.getItem().getCategory().getCategoryName());
                itemInfo.put("category", categoryInfo);
            }

            response.put("item", itemInfo);
        }

        return response;
    }

    // ==================== UTILITY METHODS ====================

    private static class SheetStructure {
        boolean isValid = false;
        String type = "UNKNOWN";
        int headerRow = -1;
        int dataStartRow = 0;
        Map<String, Integer> columnMap = new HashMap<>();
        String errorMessage = "";
    }

    private static class ConsumptionRecordRequest {
        String itemName;
        String itemSku;
        LocalDate consumptionDate;
        BigDecimal openingStock;
        BigDecimal receivedQuantity;
        BigDecimal consumedQuantity;
        BigDecimal closingStock;
        String department;
        String costCenter;
        Integer employeeCount;
        String notes;
    }

    private String getColumnValue(Row row, Map<String, Integer> columnMap, String columnName) {
        Integer colIdx = columnMap.get(columnName);
        if (colIdx == null) return null;
        Cell cell = row.getCell(colIdx);
        return getCellValueAsString(cell);
    }

    private BigDecimal getColumnValueAsNumber(Row row, Map<String, Integer> columnMap, String columnName) {
        Integer colIdx = columnMap.get(columnName);
        if (colIdx == null) return null;
        Cell cell = row.getCell(colIdx);
        return getCellValueAsBigDecimal(cell);
    }

    private LocalDate getColumnValueAsDate(Row row, Map<String, Integer> columnMap, String columnName) {
        Integer colIdx = columnMap.get(columnName);
        if (colIdx == null) return null;
        Cell cell = row.getCell(colIdx);
        return getCellValueAsDate(cell);
    }

    private String getCellValueAsString(Cell cell) {
        if (cell == null) return "";
        try {
            switch (cell.getCellType()) {
                case STRING:
                    return cell.getStringCellValue().trim();
                case NUMERIC:
                    if (DateUtil.isCellDateFormatted(cell)) {
                        return cell.getLocalDateTimeCellValue().toString();
                    }
                    double numericValue = cell.getNumericCellValue();
                    if (numericValue == (int) numericValue) {
                        return String.valueOf((int) numericValue);
                    }
                    return String.valueOf(numericValue);
                case BOOLEAN:
                    return String.valueOf(cell.getBooleanCellValue());
                case FORMULA:
                    try {
                        return String.valueOf(cell.getNumericCellValue());
                    } catch (Exception e) {
                        return cell.getCellFormula();
                    }
                default:
                    return "";
            }
        } catch (Exception e) {
            return "";
        }
    }

    private BigDecimal getCellValueAsBigDecimal(Cell cell) {
        if (cell == null) return null;
        try {
            switch (cell.getCellType()) {
                case NUMERIC:
                    return BigDecimal.valueOf(cell.getNumericCellValue());
                case STRING:
                    String strValue = cell.getStringCellValue().trim();
                    if (strValue.isEmpty()) return null;
                    strValue = strValue.replaceAll("[^0-9.-]", "");
                    if (strValue.isEmpty()) return null;
                    return new BigDecimal(strValue);
                case FORMULA:
                    return BigDecimal.valueOf(cell.getNumericCellValue());
                default:
                    return null;
            }
        } catch (Exception e) {
            return null;
        }
    }

    private LocalDate getCellValueAsDate(Cell cell) {
        if (cell == null) return null;
        try {
            switch (cell.getCellType()) {
                case NUMERIC:
                    if (DateUtil.isCellDateFormatted(cell)) {
                        return cell.getLocalDateTimeCellValue().toLocalDate();
                    }
                    // Try to parse as Excel date number
                    Date date = DateUtil.getJavaDate(cell.getNumericCellValue());
                    return date.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
                case STRING:
                    return parseStringDate(cell.getStringCellValue().trim());
                default:
                    return null;
            }
        } catch (Exception e) {
            logger.warn("Failed to parse date: {}", e.getMessage());
            return null;
        }
    }

    private LocalDate parseStringDate(String dateStr) {
        if (dateStr == null || dateStr.isEmpty()) return null;

        // Try common date formats
        String[] patterns = {
                "yyyy-MM-dd",
                "dd/MM/yyyy",
                "MM/dd/yyyy",
                "dd-MM-yyyy",
                "yyyy/MM/dd"
        };

        for (String pattern : patterns) {
            try {
                return LocalDate.parse(dateStr, java.time.format.DateTimeFormatter.ofPattern(pattern));
            } catch (Exception ignored) {
            }
        }

        return null;
    }

    private boolean isRowEmpty(Row row) {
        if (row == null) return true;
        for (int cellNum = 0; cellNum < Math.min(row.getLastCellNum(), 20); cellNum++) {
            Cell cell = row.getCell(cellNum);
            String value = getCellValueAsString(cell);
            if (value != null && !value.trim().isEmpty()) {
                return false;
            }
        }
        return true;
    }
}