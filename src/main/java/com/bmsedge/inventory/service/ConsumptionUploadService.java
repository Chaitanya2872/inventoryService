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
                // Find item by name
                Optional<Item> itemOpt = findItem(request.itemName, request.category);

                if (!itemOpt.isPresent()) {
                    String itemIdentifier = request.category != null
                            ? request.category + " - " + request.itemName
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

                // Save record
                record = consumptionRecordRepository.save(record);

                // Convert to simple map
                Map<String, Object> simpleRecord = toSimpleResponse(record);
                createdRecords.add(simpleRecord);

            } catch (Exception e) {
                creationErrors.add("Error creating record for " + request.itemName + ": " + e.getMessage());
                logger.error("Error creating consumption record", e);
            }
        }

        // Build warnings for missing items
        if (!missingItems.isEmpty()) {
            warnings.add("⚠️  ITEMS NOT FOUND - Create these items first:");
            warnings.addAll(missingItems);
        }

        result.put("success", !createdRecords.isEmpty() || missingItems.isEmpty());
        result.put("recordsCreated", createdRecords.size());
        result.put("records", createdRecords);
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
     * Parse consumption Excel file - supports both TALL and WIDE formats
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
                    SheetStructure structure = analyzeSheetStructure(sheet);

                    if (!structure.isValid) {
                        errors.add("Sheet '" + sheetName + "': " + structure.errorMessage);
                        continue;
                    }

                    logger.info("  Format detected: {} with {} columns", structure.type, structure.columnMap.size());

                    if ("WIDE".equals(structure.type)) {
                        // Wide format: one row per item, dates as columns
                        allRecords.addAll(parseWideFormat(sheet, structure, sheetName, errors));
                    } else {
                        // Tall format: one row per item per date
                        allRecords.addAll(parseTallFormat(sheet, structure, sheetName, errors));
                    }

                    totalRows += (sheet.getLastRowNum() - structure.dataStartRow + 1);

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
     * Analyze sheet structure - detect WIDE or TALL format
     */
    private SheetStructure analyzeSheetStructure(Sheet sheet) {
        SheetStructure structure = new SheetStructure();

        // Check first 5 rows for headers
        for (int rowIdx = 0; rowIdx < Math.min(5, sheet.getPhysicalNumberOfRows()); rowIdx++) {
            Row row = sheet.getRow(rowIdx);
            if (row == null) continue;

            // Try detecting WIDE format first
            SheetStructure wideStructure = detectWideFormat(row, rowIdx, sheet);
            if (wideStructure.isValid) {
                return wideStructure;
            }

            // Try detecting TALL format
            SheetStructure tallStructure = detectTallFormat(row, rowIdx);
            if (tallStructure.isValid) {
                return tallStructure;
            }
        }

        structure.isValid = false;
        structure.errorMessage = "Could not detect valid format. Supports: " +
                "WIDE (Category|Item|UOM|Opening|Received|Date columns|Closing) or " +
                "TALL (Item Name|Date|Consumed Quantity)";
        return structure;
    }

    /**
     * Detect WIDE format structure
     */
    private SheetStructure detectWideFormat(Row row, int rowIdx, Sheet sheet) {
        SheetStructure structure = new SheetStructure();
        Map<String, Integer> columnMap = new HashMap<>();
        List<DateColumn> dateColumns = new ArrayList<>();
        int headerScore = 0;

        for (Cell cell : row) {
            String value = getCellValueAsString(cell);
            if (value == null || value.trim().isEmpty()) continue;

            String lower = value.toLowerCase().trim();
            int colIdx = cell.getColumnIndex();

            // Fixed columns detection
            if (lower.equals("category") || lower.equals("cat")) {
                columnMap.put("category", colIdx);
                headerScore += 2;
            }
            if (lower.equals("item") || lower.equals("item name") || lower.equals("item_name") ||
                    lower.equals("itemname") || lower.contains("item") && lower.contains("name")) {
                columnMap.put("item", colIdx);
                headerScore += 3;
            }
            if (lower.equals("uom") || lower.equals("unit")) {
                columnMap.put("uom", colIdx);
                headerScore += 1;
            }
            if (lower.contains("opening") && lower.contains("stock")) {
                columnMap.put("openingStock", colIdx);
                headerScore += 2;
            }
            if (lower.contains("received") || lower.contains("total") && lower.contains("received")) {
                columnMap.put("receivedQuantity", colIdx);
                headerScore += 2;
            }
            if (lower.contains("closing") && lower.contains("stock")) {
                columnMap.put("closingStock", colIdx);
                headerScore += 2;
            }

            // Date column detection (DD/MM/YYYY or similar)
            LocalDate parsedDate = parseDateString(value);
            if (parsedDate != null) {
                DateColumn dc = new DateColumn();
                dc.columnIndex = colIdx;
                dc.date = parsedDate;
                dateColumns.add(dc);
                headerScore += 1;
            }
        }

        // Valid WIDE format if we have: item name, at least 3 date columns
        if (headerScore >= 8 && columnMap.containsKey("item") && dateColumns.size() >= 3) {
            structure.isValid = true;
            structure.type = "WIDE";
            structure.headerRow = rowIdx;
            structure.dataStartRow = rowIdx + 1;
            structure.columnMap = columnMap;
            structure.dateColumns = dateColumns;

            logger.info("  WIDE format detected: {} fixed columns, {} date columns",
                    columnMap.size(), dateColumns.size());
            return structure;
        }

        structure.isValid = false;
        return structure;
    }

    /**
     * Detect TALL format structure
     */
    private SheetStructure detectTallFormat(Row row, int rowIdx) {
        SheetStructure structure = new SheetStructure();
        Map<String, Integer> columnMap = new HashMap<>();
        int headerScore = 0;

        for (Cell cell : row) {
            String value = getCellValueAsString(cell);
            if (value == null || value.trim().isEmpty()) continue;

            String lower = value.toLowerCase().trim();

            // Item name
            if (lower.equals("item") || lower.equals("item_name") || lower.equals("itemname") ||
                    (lower.contains("item") && (lower.contains("name") || lower.contains("description")))) {
                if (!lower.contains("sku")) {
                    columnMap.put("item", cell.getColumnIndex());
                    headerScore += 3;
                }
            }

            // Date
            if (lower.equals("date") || lower.contains("consumption") && lower.contains("date")) {
                columnMap.put("date", cell.getColumnIndex());
                headerScore += 3;
            }

            // Consumed quantity
            if ((lower.contains("consumed") || lower.contains("consumption")) &&
                    (lower.contains("qty") || lower.contains("quantity"))) {
                columnMap.put("consumedQuantity", cell.getColumnIndex());
                headerScore += 3;
            }

            // Optional columns
            if (lower.contains("opening") && lower.contains("stock")) {
                columnMap.put("openingStock", cell.getColumnIndex());
                headerScore += 1;
            }
            if (lower.contains("received")) {
                columnMap.put("receivedQuantity", cell.getColumnIndex());
                headerScore += 1;
            }
            if (lower.contains("closing") && lower.contains("stock")) {
                columnMap.put("closingStock", cell.getColumnIndex());
                headerScore += 1;
            }
        }

        if (headerScore >= 6 && columnMap.containsKey("item") &&
                columnMap.containsKey("date") && columnMap.containsKey("consumedQuantity")) {
            structure.isValid = true;
            structure.type = "TALL";
            structure.headerRow = rowIdx;
            structure.dataStartRow = rowIdx + 1;
            structure.columnMap = columnMap;
            return structure;
        }

        structure.isValid = false;
        return structure;
    }

    /**
     * Parse WIDE format data
     */
    private List<ConsumptionRecordRequest> parseWideFormat(Sheet sheet, SheetStructure structure,
                                                           String sheetName, List<String> errors) {
        List<ConsumptionRecordRequest> records = new ArrayList<>();

        for (int rowIdx = structure.dataStartRow; rowIdx <= sheet.getLastRowNum(); rowIdx++) {
            Row row = sheet.getRow(rowIdx);
            if (row == null || isRowEmpty(row)) continue;

            try {
                String category = getColumnValue(row, structure.columnMap, "category");
                String itemName = getColumnValue(row, structure.columnMap, "item");

                if (itemName == null || itemName.trim().isEmpty()) continue;
                if (itemName.toLowerCase().contains("total")) continue;

                BigDecimal openingStock = getColumnValueAsNumber(row, structure.columnMap, "openingStock");
                BigDecimal receivedQty = getColumnValueAsNumber(row, structure.columnMap, "receivedQuantity");
                BigDecimal closingStock = getColumnValueAsNumber(row, structure.columnMap, "closingStock");

                // Create one record per date column
                for (DateColumn dateCol : structure.dateColumns) {
                    Cell cell = row.getCell(dateCol.columnIndex);
                    BigDecimal consumed = getCellValueAsBigDecimal(cell);

                    // Skip if no consumption or zero
                    if (consumed == null || consumed.compareTo(BigDecimal.ZERO) == 0) {
                        continue;
                    }

                    ConsumptionRecordRequest request = new ConsumptionRecordRequest();
                    request.category = category;
                    request.itemName = itemName.trim();
                    request.consumptionDate = dateCol.date;
                    request.consumedQuantity = consumed;
                    request.openingStock = openingStock;
                    request.receivedQuantity = receivedQty;
                    request.closingStock = closingStock;

                    records.add(request);
                }

            } catch (Exception e) {
                errors.add(String.format("Sheet '%s' Row %d: %s", sheetName, rowIdx + 1, e.getMessage()));
            }
        }

        return records;
    }

    /**
     * Parse TALL format data
     */
    private List<ConsumptionRecordRequest> parseTallFormat(Sheet sheet, SheetStructure structure,
                                                           String sheetName, List<String> errors) {
        List<ConsumptionRecordRequest> records = new ArrayList<>();

        for (int rowIdx = structure.dataStartRow; rowIdx <= sheet.getLastRowNum(); rowIdx++) {
            Row row = sheet.getRow(rowIdx);
            if (row == null || isRowEmpty(row)) continue;

            try {
                String itemName = getColumnValue(row, structure.columnMap, "item");
                LocalDate date = getColumnValueAsDate(row, structure.columnMap, "date");
                BigDecimal consumedQty = getColumnValueAsNumber(row, structure.columnMap, "consumedQuantity");

                if (itemName == null || itemName.trim().isEmpty() || date == null) continue;
                if (itemName.toLowerCase().contains("total")) continue;

                ConsumptionRecordRequest request = new ConsumptionRecordRequest();
                request.itemName = itemName.trim();
                request.consumptionDate = date;
                request.consumedQuantity = consumedQty != null ? consumedQty : BigDecimal.ZERO;
                request.openingStock = getColumnValueAsNumber(row, structure.columnMap, "openingStock");
                request.receivedQuantity = getColumnValueAsNumber(row, structure.columnMap, "receivedQuantity");
                request.closingStock = getColumnValueAsNumber(row, structure.columnMap, "closingStock");

                records.add(request);

            } catch (Exception e) {
                errors.add(String.format("Sheet '%s' Row %d: %s", sheetName, rowIdx + 1, e.getMessage()));
            }
        }

        return records;
    }

    /**
     * Find item by name and optional category
     */
    private Optional<Item> findItem(String itemName, String category) {
        List<Item> items = itemRepository.findByItemNameContainingIgnoreCase(itemName);

        // Exact match first
        Optional<Item> exactMatch = items.stream()
                .filter(item -> item.getItemName().equalsIgnoreCase(itemName))
                .findFirst();

        if (exactMatch.isPresent()) {
            return exactMatch;
        }

        // If category provided, try matching with category
        if (category != null && !category.isEmpty()) {
            return items.stream()
                    .filter(item -> item.getCategory() != null &&
                            item.getCategory().getCategoryName().equalsIgnoreCase(category))
                    .findFirst();
        }

        return items.isEmpty() ? Optional.empty() : Optional.of(items.get(0));
    }

    /**
     * Parse date string in various formats
     */
    private LocalDate parseDateString(String dateStr) {
        if (dateStr == null || dateStr.trim().isEmpty()) return null;

        String[] patterns = {
                "dd/MM/yyyy",
                "d/M/yyyy",
                "yyyy-MM-dd",
                "dd-MM-yyyy",
                "MM/dd/yyyy"
        };

        for (String pattern : patterns) {
            try {
                return LocalDate.parse(dateStr, java.time.format.DateTimeFormatter.ofPattern(pattern));
            } catch (Exception ignored) {
            }
        }

        return null;
    }

    /**
     * Convert ConsumptionRecord to simple response
     */
    private Map<String, Object> toSimpleResponse(ConsumptionRecord record) {
        Map<String, Object> response = new HashMap<>();
        response.put("id", record.getId());
        response.put("consumptionDate", record.getConsumptionDate());
        response.put("openingStock", record.getOpeningStock());
        response.put("receivedQuantity", record.getReceivedQuantity());
        response.put("consumedQuantity", record.getConsumedQuantity());
        response.put("closingStock", record.getClosingStock());

        if (record.getItem() != null) {
            Map<String, Object> itemInfo = new HashMap<>();
            itemInfo.put("id", record.getItem().getId());
            itemInfo.put("itemName", record.getItem().getItemName());
            itemInfo.put("currentQuantity", record.getItem().getCurrentQuantity());

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

    // ==================== UTILITY CLASSES ====================

    private static class SheetStructure {
        boolean isValid = false;
        String type = "UNKNOWN";
        int headerRow = -1;
        int dataStartRow = 0;
        Map<String, Integer> columnMap = new HashMap<>();
        List<DateColumn> dateColumns = new ArrayList<>();
        String errorMessage = "";
    }

    private static class DateColumn {
        int columnIndex;
        LocalDate date;
    }

    private static class ConsumptionRecordRequest {
        String category;
        String itemName;
        LocalDate consumptionDate;
        BigDecimal openingStock;
        BigDecimal receivedQuantity;
        BigDecimal consumedQuantity;
        BigDecimal closingStock;
    }

    // ==================== UTILITY METHODS ====================

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
                    Date date = DateUtil.getJavaDate(cell.getNumericCellValue());
                    return date.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
                case STRING:
                    return parseDateString(cell.getStringCellValue().trim());
                default:
                    return null;
            }
        } catch (Exception e) {
            logger.warn("Failed to parse date: {}", e.getMessage());
            return null;
        }
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

    public Map<String, Object> validateConsumptionFile(MultipartFile file) throws IOException {
        Map<String, Object> validation = new HashMap<>();
        List<String> errors = new ArrayList<>();

        Map<String, Object> parseResult = parseConsumptionExcel(file, errors);
        List<ConsumptionRecordRequest> requests = (List<ConsumptionRecordRequest>) parseResult.get("records");

        Set<String> missingItems = new LinkedHashSet<>();
        int validRecords = 0;

        for (ConsumptionRecordRequest request : requests) {
            Optional<Item> itemOpt = findItem(request.itemName, request.category);
            if (itemOpt.isPresent()) {
                validRecords++;
            } else {
                String itemIdentifier = request.category != null
                        ? request.category + " - " + request.itemName
                        : request.itemName;
                missingItems.add(itemIdentifier);
            }
        }

        validation.put("valid", errors.isEmpty() && !requests.isEmpty());
        validation.put("totalRecords", requests.size());
        validation.put("validRecords", validRecords);
        validation.put("missingItems", new ArrayList<>(missingItems));
        validation.put("parseErrors", errors);
        validation.put("message", String.format(
                "Found %d records. %d valid, %d items missing.",
                requests.size(), validRecords, missingItems.size()
        ));

        return validation;
    }

    public Map<String, Object> getConsumptionTemplate() {
        Map<String, Object> template = new HashMap<>();
        template.put("format", "Excel (.xlsx) - Supports WIDE and TALL formats");
        template.put("wideFormat", new String[]{
                "Category | Item Name | UOM | Opening Stock | Total Received | [Date columns] | Closing Stock"
        });
        template.put("tallFormat", new String[]{
                "Item Name | Date | Consumed Quantity | Opening Stock | Received Quantity | Closing Stock"
        });
        template.put("notes", new String[]{
                "WIDE format: One row per item, dates as column headers (DD/MM/YYYY)",
                "TALL format: One row per item per date",
                "Items must exist before importing consumption",
                "Date columns with zero or empty consumption are skipped"
        });
        return template;
    }
}