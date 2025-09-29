package com.bmsedge.inventory.service;

import com.bmsedge.inventory.model.*;
import com.bmsedge.inventory.repository.*;
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
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.IntStream;

@Service
@Transactional
public class DataImportService {
    private static final Logger logger = LoggerFactory.getLogger(DataImportService.class);

    @Autowired
    private ItemRepository itemRepository;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private ConsumptionRecordRepository consumptionRecordRepository;

    @Autowired
    private StockMovementRepository stockMovementRepository;

    @Autowired(required = false)
    private ItemCorrelationService correlationService;

    @Autowired(required = false)
    private StatisticalAnalysisService statisticalAnalysisService;

    /**
     * Import Excel file with inventory data - FIXED VERSION
     */
    @Transactional
    public Map<String, Object> importExcelFile(MultipartFile file, Long userId) {
        Map<String, Object> result = new HashMap<>();

        if (file.isEmpty()) {
            throw new RuntimeException("File is empty");
        }

        try (Workbook workbook = new XSSFWorkbook(file.getInputStream())) {
            logger.info("Excel file has {} sheets", workbook.getNumberOfSheets());

            // For jack sheet, we'll process the first sheet only (but could extend to all sheets)
            Sheet sheet = workbook.getSheetAt(0);

            if (sheet == null) {
                throw new RuntimeException("No worksheet found");
            }

            logger.info("Processing sheet: {}", sheet.getSheetName());

            // Find header row - check rows 0, 1, and 2
            Row headerRow = null;
            List<String> headers = new ArrayList<>();

            for (int rowIndex = 0; rowIndex <= 2 && rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                Row candidateRow = sheet.getRow(rowIndex);
                if (candidateRow == null) continue;

                List<String> candidateHeaders = new ArrayList<>();
                for (int i = 0; i < candidateRow.getLastCellNum(); i++) {
                    Cell cell = candidateRow.getCell(i);
                    String value = getCellValueAsString(cell);
                    candidateHeaders.add(value != null ? value.trim() : "");
                }

                // Check if this row contains our expected headers
                boolean hasItemDescription = candidateHeaders.stream()
                        .anyMatch(h -> h.contains("ITEM DESCRIPTION"));
                boolean hasSrNo = candidateHeaders.stream()
                        .anyMatch(h -> h.contains("Sr.No"));
                boolean hasItemName = candidateHeaders.stream()
                        .anyMatch(h -> h.contains("item_name"));

                logger.info("Row {}: hasItemDescription={}, hasSrNo={}, hasItemName={}",
                        rowIndex, hasItemDescription, hasSrNo, hasItemName);
                logger.info("Row {} headers: {}", rowIndex, candidateHeaders);

                if (hasItemDescription || hasItemName) {
                    headerRow = candidateRow;
                    headers = candidateHeaders;
                    logger.info("Found header row at index: {}", rowIndex);
                    break;
                }
            }

            if (headerRow == null || headers.isEmpty()) {
                throw new RuntimeException("No header row found. Expected headers with 'ITEM DESCRIPTION' or 'item_name'");
            }

            logger.info("Using headers: {}", headers);

            // Detect format type with more flexible matching
            boolean isBMSEnhanced = headers.stream().anyMatch(h -> h.contains("item_name")) &&
                    headers.stream().anyMatch(h -> h.contains("day_1"));

            boolean isJackSheet = headers.stream().anyMatch(h -> h.contains("ITEM DESCRIPTION")) &&
                    headers.stream().anyMatch(h -> h.contains("Opening Stock"));

            if (isBMSEnhanced) {
                logger.info("Detected BMS Enhanced format");
                result = importBMSEnhancedFormat(sheet, userId);
            } else if (isJackSheet) {
                logger.info("Detected Jack Sheet format");
                result = importJackSheetFormat(sheet, userId, headerRow.getRowNum());
            } else {
                throw new RuntimeException("Unknown file format. Found headers: " + headers +
                        ". Expected either 'item_name' + 'day_1' (BMS Enhanced) or 'ITEM DESCRIPTION' + 'Opening Stock' (Jack Sheet)");
            }

            // Add sheet information to result
            result.put("sheetName", sheet.getSheetName());
            result.put("totalSheets", workbook.getNumberOfSheets());

            // List all sheet names for reference
            List<String> sheetNames = new ArrayList<>();
            for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
                sheetNames.add(workbook.getSheetAt(i).getSheetName());
            }
            result.put("availableSheets", sheetNames);

            // Trigger correlation calculation after import
            if (correlationService != null) {
                try {
                    Map<String, Object> corrResult = correlationService.calculateAllCorrelations();
                    result.put("correlationsCalculated", true);
                    result.put("correlationSummary", corrResult);
                } catch (Exception e) {
                    logger.error("Failed to calculate correlations after import: {}", e.getMessage());
                    result.put("correlationsCalculated", false);
                    result.put("correlationError", e.getMessage());
                }
            }

        } catch (IOException e) {
            throw new RuntimeException("Failed to read Excel file: " + e.getMessage());
        }

        return result;
    }

    /**
     * Import BMS Enhanced format
     */
    private Map<String, Object> importBMSEnhancedFormat(Sheet sheet, Long userId) {
        Map<String, Object> result = new HashMap<>();
        int imported = 0;
        int updated = 0;
        int failed = 0;
        List<String> errors = new ArrayList<>();

        Row headerRow = sheet.getRow(0);
        Map<String, Integer> columnMap = createColumnMap(headerRow);

        for (int i = 1; i <= sheet.getLastRowNum(); i++) {
            Row row = sheet.getRow(i);
            if (row == null) continue;

            try {
                String itemName = getCellValueAsString(row.getCell(columnMap.get("item_name")));
                String categoryName = getCellValueAsString(row.getCell(columnMap.get("category")));

                if (itemName == null || itemName.trim().isEmpty()) {
                    continue;
                }

                BigDecimal openingStock = getCellValueAsBigDecimal(row.getCell(columnMap.get("opening_stock")));
                BigDecimal receivedStock = getCellValueAsBigDecimal(row.getCell(columnMap.get("received_stock")));
                BigDecimal consumption = getCellValueAsBigDecimal(row.getCell(columnMap.get("consumption")));
                BigDecimal sih = getCellValueAsBigDecimal(row.getCell(columnMap.get("sih")));

                // Find or create category
                Category category = findOrCreateCategory(categoryName, userId);

                // Find or create item
                Optional<Item> existingItem = itemRepository.findAll().stream()
                        .filter(item -> item.getItemName().equalsIgnoreCase(itemName.trim()))
                        .findFirst();

                Item item;
                if (existingItem.isPresent()) {
                    item = existingItem.get();
                    updated++;
                } else {
                    item = new Item();
                    item.setItemName(itemName.trim());
                    item.setCreatedBy(userId);
                    imported++;
                }

                item.setCategory(category);
                item.setCurrentQuantity(sih);
                item.setOpeningStock(openingStock);
                item.setTotalReceivedStock(receivedStock);
                item.setTotalConsumedStock(consumption);

                itemRepository.save(item);

                // Import daily consumption data
                importDailyConsumptionData(row, columnMap, item, userId);

            } catch (Exception e) {
                failed++;
                errors.add("Row " + (i + 1) + ": " + e.getMessage());
                logger.error("Failed to import row {}: {}", i + 1, e.getMessage());
            }
        }

        result.put("format", "BMS Enhanced");
        result.put("imported", imported);
        result.put("updated", updated);
        result.put("failed", failed);
        result.put("errors", errors);

        return result;
    }

    /**
     * Import Jack Sheet format - COMPLETELY FIXED VERSION
     */
    private Map<String, Object> importJackSheetFormat(Sheet sheet, Long userId, int headerRowIndex) {
        Map<String, Object> result = new HashMap<>();
        int imported = 0;
        int updated = 0;
        int failed = 0;
        List<String> errors = new ArrayList<>();

        Row headerRow = sheet.getRow(headerRowIndex);
        if (headerRow == null) {
            throw new RuntimeException("Header row is null at index " + headerRowIndex);
        }

        Map<String, Integer> columnMap = createJackSheetColumnMap(headerRow);
        logger.info("Jack Sheet column mapping: {}", columnMap);

        // Validate required columns exist
        String[] requiredColumns = {"ITEM DESCRIPTION", "Category", "Opening Stock", "Received Stock", "Consumption", "SIH"};
        for (String requiredCol : requiredColumns) {
            if (!columnMap.containsKey(requiredCol)) {
                logger.error("Required column '{}' not found in headers: {}", requiredCol, columnMap.keySet());
                throw new RuntimeException("Required column '" + requiredCol + "' not found. Available columns: " + columnMap.keySet());
            }
        }

        // Process data rows starting after header
        for (int i = headerRowIndex + 1; i <= sheet.getLastRowNum(); i++) {
            Row row = sheet.getRow(i);
            if (row == null) continue;

            try {
                // Safely get column values with null checking
                String itemName = safeGetStringValue(row, columnMap, "ITEM DESCRIPTION");
                String categoryName = safeGetStringValue(row, columnMap, "Category");

                // Skip empty rows
                if (itemName == null || itemName.trim().isEmpty()) {
                    logger.debug("Skipping empty row {}", i + 1);
                    continue;
                }

                // Skip total/summary rows
                if (itemName.toLowerCase().contains("total") ||
                        itemName.toLowerCase().contains("grand")) {
                    logger.debug("Skipping total/summary row {}: {}", i + 1, itemName);
                    continue;
                }

                BigDecimal openingStock = safeGetBigDecimalValue(row, columnMap, "Opening Stock");
                BigDecimal receivedStock = safeGetBigDecimalValue(row, columnMap, "Received Stock");
                BigDecimal totalConsumption = safeGetBigDecimalValue(row, columnMap, "Consumption");
                BigDecimal sih = safeGetBigDecimalValue(row, columnMap, "SIH");
                String uom = safeGetStringValue(row, columnMap, "UOM");
                BigDecimal price = safeGetBigDecimalValue(row, columnMap, "Price");

                logger.debug("Processing item: {} - Opening: {}, Received: {}, Consumption: {}, SIH: {}",
                        itemName, openingStock, receivedStock, totalConsumption, sih);

                // Find or create category
                Category category = findOrCreateCategory(categoryName, userId);

                // Find or create item
                Optional<Item> existingItem = itemRepository.findAll().stream()
                        .filter(item -> item.getItemName().equalsIgnoreCase(itemName.trim()))
                        .findFirst();

                Item item;
                if (existingItem.isPresent()) {
                    item = existingItem.get();
                    updated++;
                    logger.debug("Updating existing item: {}", itemName);
                } else {
                    item = new Item();
                    item.setItemName(itemName.trim());
                    item.setCreatedBy(userId);
                    imported++;
                    logger.debug("Creating new item: {}", itemName);
                }

                item.setCategory(category);
                item.setCurrentQuantity(sih != null ? sih : BigDecimal.ZERO);
                item.setOpeningStock(openingStock != null ? openingStock : BigDecimal.ZERO);

                // Update total stocks (add to existing values)
                if (receivedStock != null && receivedStock.compareTo(BigDecimal.ZERO) > 0) {
                    BigDecimal currentReceived = item.getTotalReceivedStock() != null ?
                            item.getTotalReceivedStock() : BigDecimal.ZERO;
                    item.setTotalReceivedStock(currentReceived.add(receivedStock));

                    BigDecimal currentMonthReceived = item.getMonthReceivedStock() != null ?
                            item.getMonthReceivedStock() : BigDecimal.ZERO;
                    item.setMonthReceivedStock(currentMonthReceived.add(receivedStock));
                }

                if (totalConsumption != null && totalConsumption.compareTo(BigDecimal.ZERO) > 0) {
                    BigDecimal currentConsumed = item.getTotalConsumedStock() != null ?
                            item.getTotalConsumedStock() : BigDecimal.ZERO;
                    item.setTotalConsumedStock(currentConsumed.add(totalConsumption));

                    BigDecimal currentMonthConsumed = item.getMonthConsumedStock() != null ?
                            item.getMonthConsumedStock() : BigDecimal.ZERO;
                    item.setMonthConsumedStock(currentMonthConsumed.add(totalConsumption));
                }

                item.setUnitOfMeasurement(uom != null && !uom.trim().isEmpty() ? uom.trim() : "pcs");
                if (price != null && price.compareTo(BigDecimal.ZERO) > 0) {
                    item.setUnitPrice(price);
                }

                // Set default values if null
                if (item.getTotalReceivedStock() == null) {
                    item.setTotalReceivedStock(BigDecimal.ZERO);
                }
                if (item.getTotalConsumedStock() == null) {
                    item.setTotalConsumedStock(BigDecimal.ZERO);
                }
                if (item.getMonthReceivedStock() == null) {
                    item.setMonthReceivedStock(BigDecimal.ZERO);
                }
                if (item.getMonthConsumedStock() == null) {
                    item.setMonthConsumedStock(BigDecimal.ZERO);
                }

                itemRepository.save(item);

                // CRITICAL FIX: Create consumption record with received quantity
                if (receivedStock != null && receivedStock.compareTo(BigDecimal.ZERO) > 0) {
                    LocalDate firstDayOfMonth = LocalDate.now().withDayOfMonth(1);

                    // Create or update consumption record for first day of month with received stock
                    Optional<ConsumptionRecord> existingRecord = consumptionRecordRepository
                            .findByItemAndConsumptionDate(item, firstDayOfMonth);

                    ConsumptionRecord record;
                    if (existingRecord.isPresent()) {
                        record = existingRecord.get();
                        BigDecimal currentReceived = record.getReceivedQuantity() != null ?
                                record.getReceivedQuantity() : BigDecimal.ZERO;
                        record.setReceivedQuantity(currentReceived.add(receivedStock));
                    } else {
                        record = new ConsumptionRecord(item, firstDayOfMonth, openingStock);
                        record.setReceivedQuantity(receivedStock);
                    }

                    record.setNotes("Imported from Excel - " + sheet.getSheetName());
                    consumptionRecordRepository.save(record);

                    logger.debug("Created consumption record with received stock {} for item {}",
                            receivedStock, itemName);
                }

                // Import daily consumption data (days 1-31)
                importJackSheetDailyData(row, columnMap, item, userId);

                // Create stock movements
                if (receivedStock != null && receivedStock.compareTo(BigDecimal.ZERO) > 0) {
                    StockMovement receipt = new StockMovement(item, "RECEIPT", LocalDate.now(), receivedStock, userId);
                    receipt.setReferenceNumber("Import-" + sheet.getSheetName() + "-" + System.currentTimeMillis());
                    receipt.setUnitPrice(price);
                    stockMovementRepository.save(receipt);
                }

                logger.debug("Successfully processed item: {}", itemName);

            } catch (Exception e) {
                failed++;
                String errorMsg = "Row " + (i + 1) + ": " + e.getMessage();
                errors.add(errorMsg);
                logger.error("Failed to import row {}: {}", i + 1, e.getMessage(), e);
            }
        }

        result.put("format", "Jack Sheet");
        result.put("imported", imported);
        result.put("updated", updated);
        result.put("failed", failed);
        result.put("errors", errors);
        result.put("headerRowIndex", headerRowIndex);

        logger.info("Jack Sheet import completed: imported={}, updated={}, failed={}", imported, updated, failed);

        return result;
    }

    /**
     * Safe method to get string value from row with null checking
     */
    private String safeGetStringValue(Row row, Map<String, Integer> columnMap, String columnName) {
        Integer columnIndex = columnMap.get(columnName);
        if (columnIndex == null) {
            logger.debug("Column '{}' not found in columnMap", columnName);
            return null;
        }

        if (columnIndex >= row.getLastCellNum()) {
            logger.debug("Column index {} out of bounds for row with {} cells", columnIndex, row.getLastCellNum());
            return null;
        }

        Cell cell = row.getCell(columnIndex);
        return getCellValueAsString(cell);
    }

    /**
     * Safe method to get BigDecimal value from row with null checking
     */
    private BigDecimal safeGetBigDecimalValue(Row row, Map<String, Integer> columnMap, String columnName) {
        Integer columnIndex = columnMap.get(columnName);
        if (columnIndex == null) {
            logger.debug("Column '{}' not found in columnMap", columnName);
            return BigDecimal.ZERO;
        }

        if (columnIndex >= row.getLastCellNum()) {
            logger.debug("Column index {} out of bounds for row with {} cells", columnIndex, row.getLastCellNum());
            return BigDecimal.ZERO;
        }

        Cell cell = row.getCell(columnIndex);
        return getCellValueAsBigDecimal(cell);
    }

    /**
     * Import daily consumption data for BMS Enhanced format
     */
    private void importDailyConsumptionData(Row row, Map<String, Integer> columnMap, Item item, Long userId) {
        for (int day = 1; day <= 31; day++) {
            String dayColumn = "day_" + day;
            if (columnMap.containsKey(dayColumn)) {
                BigDecimal dailyConsumption = getCellValueAsBigDecimal(row.getCell(columnMap.get(dayColumn)));

                if (dailyConsumption != null && dailyConsumption.compareTo(BigDecimal.ZERO) > 0) {
                    try {
                        LocalDate consumptionDate = LocalDate.now().withDayOfMonth(day);
                        createConsumptionRecord(item, consumptionDate, dailyConsumption, null);
                    } catch (Exception e) {
                        logger.warn("Could not create consumption record for day {}: {}", day, e.getMessage());
                    }
                }
            }
        }
    }

    /**
     * Import Jack Sheet daily consumption data - FIXED VERSION
     */
    private void importJackSheetDailyData(Row row, Map<String, Integer> columnMap, Item item, Long userId) {
        // In Jack Sheet, days are numbered columns (1, 2, 3, ... 31) between "Total Stock" and "Consumption"
        Integer totalStockIndex = columnMap.get("Total Stock");
        Integer consumptionIndex = columnMap.get("Consumption");

        if (totalStockIndex == null || consumptionIndex == null) {
            logger.warn("Could not find Total Stock or Consumption columns for daily data import");
            return;
        }

        LocalDate baseDate = LocalDate.now().withDayOfMonth(1); // Start of current month

        // Days should be between Total Stock and Consumption columns
        for (int dayCol = totalStockIndex + 1; dayCol < consumptionIndex; dayCol++) {
            if (dayCol >= row.getLastCellNum()) break;

            Cell dayCell = row.getCell(dayCol);
            BigDecimal dailyConsumption = getCellValueAsBigDecimal(dayCell);

            if (dailyConsumption != null && dailyConsumption.compareTo(BigDecimal.ZERO) > 0) {
                try {
                    // Calculate which day this column represents
                    int dayOfMonth = dayCol - totalStockIndex;

                    if (dayOfMonth > 0 && dayOfMonth <= 31) {
                        LocalDate consumptionDate = baseDate.withDayOfMonth(dayOfMonth);

                        // Create consumption record
                        createConsumptionRecord(item, consumptionDate, dailyConsumption, null);

                        // Create stock movement for consumption
                        StockMovement consumption = new StockMovement(item, "CONSUMPTION",
                                consumptionDate, dailyConsumption, userId);
                        consumption.setDepartment("Import");
                        stockMovementRepository.save(consumption);

                        logger.debug("Created consumption record for {} on day {}: {}",
                                item.getItemName(), dayOfMonth, dailyConsumption);
                    }
                } catch (Exception e) {
                    logger.warn("Could not create consumption record for column {}: {}", dayCol, e.getMessage());
                }
            }
        }
    }

    /**
     * Create or update consumption record
     */
    private void createConsumptionRecord(Item item, LocalDate date, BigDecimal consumption, BigDecimal received) {
        Optional<ConsumptionRecord> existing = consumptionRecordRepository
                .findByItemAndConsumptionDate(item, date);

        ConsumptionRecord record;
        if (existing.isPresent()) {
            record = existing.get();
        } else {
            record = new ConsumptionRecord(item, date, item.getCurrentQuantity());
        }

        record.setConsumedQuantity(consumption);
        if (received != null) {
            record.setReceivedQuantity(received);
        }

        consumptionRecordRepository.save(record);
    }

    /**
     * Find or create category
     */
    private Category findOrCreateCategory(String categoryName, Long userId) {
        if (categoryName == null || categoryName.trim().isEmpty()) {
            categoryName = "General";
        }

        String finalCategoryName = categoryName;
        String finalCategoryName1 = categoryName;
        return categoryRepository.findAll().stream()
                .filter(cat -> cat.getCategoryName().equalsIgnoreCase(finalCategoryName.trim()))
                .findFirst()
                .orElseGet(() -> {
                    Category newCategory = new Category();
                    newCategory.setCategoryName(finalCategoryName1.trim());
                    newCategory.setCategoryDescription("Auto-created during import");
                    newCategory.setCreatedBy(userId);
                    return categoryRepository.save(newCategory);
                });
    }

    /**
     * Create column mapping for BMS Enhanced format
     */
    private Map<String, Integer> createColumnMap(Row headerRow) {
        Map<String, Integer> columnMap = new HashMap<>();

        for (int i = 0; i < headerRow.getLastCellNum(); i++) {
            Cell cell = headerRow.getCell(i);
            if (cell != null) {
                String header = getCellValueAsString(cell);
                if (header != null && !header.trim().isEmpty()) {
                    columnMap.put(header.trim(), i);
                }
            }
        }

        return columnMap;
    }

    /**
     * Create column mapping for Jack Sheet format - IMPROVED VERSION
     */
    private Map<String, Integer> createJackSheetColumnMap(Row headerRow) {
        Map<String, Integer> columnMap = new HashMap<>();

        logger.info("Creating column map from header row with {} cells", headerRow.getLastCellNum());

        for (int i = 0; i < headerRow.getLastCellNum(); i++) {
            Cell cell = headerRow.getCell(i);
            if (cell != null) {
                String header = getCellValueAsString(cell);
                if (header != null && !header.trim().isEmpty()) {
                    String cleanHeader = header.trim();
                    columnMap.put(cleanHeader, i);
                    logger.debug("Column mapping: '{}' -> {}", cleanHeader, i);
                }
            }
        }

        // Log all found headers for debugging
        logger.info("Found {} columns: {}", columnMap.size(), columnMap.keySet());

        // Check for required columns and log what we found vs what we expect
        String[] expectedColumns = {
                "Sr.No.", "Category", "ITEM DESCRIPTION", "UOM", "Price",
                "Opening Stock ", "Received Stock", "Total Stock",
                "Consumption", "SIH", "Total Cost"
        };

        logger.info("Checking for expected columns:");
        for (String expected : expectedColumns) {
            boolean found = columnMap.containsKey(expected);
            logger.info("  {} -> {}", expected, found ? "FOUND" : "MISSING");
        }

        // Also check for similar column names (case-insensitive, ignoring spaces)
        if (!columnMap.containsKey("ITEM DESCRIPTION")) {
            for (String key : columnMap.keySet()) {
                if (key.toLowerCase().contains("item") && key.toLowerCase().contains("description")) {
                    logger.info("Found similar column for ITEM DESCRIPTION: '{}'", key);
                }
            }
        }

        if (!columnMap.containsKey("Opening Stock ")) {
            for (String key : columnMap.keySet()) {
                if (key.toLowerCase().contains("opening") && key.toLowerCase().contains("stock")) {
                    logger.info("Found similar column for Opening Stock: '{}'", key);
                }
            }
        }

        return columnMap;
    }

    /**
     * Find day column index in Jack Sheet (numeric columns after Total Stock)
     */
    private Integer findDayColumnIndex(Map<String, Integer> columnMap, int day) {
        // In Jack Sheet, day columns are numeric (1, 2, 3, ... 31) after "Total Stock"
        Integer totalStockIndex = columnMap.get("Total Stock");
        if (totalStockIndex != null) {
            // Days start right after "Total Stock" column
            return totalStockIndex + day;
        }
        return null;
    }

    /**
     * Utility methods
     */
    private String getCellValueAsString(Cell cell) {
        if (cell == null) return null;

        switch (cell.getCellType()) {
            case STRING:
                return cell.getStringCellValue();
            case NUMERIC:
                if (DateUtil.isCellDateFormatted(cell)) {
                    return cell.getDateCellValue().toString();
                }
                return String.valueOf((long) cell.getNumericCellValue());
            case BOOLEAN:
                return String.valueOf(cell.getBooleanCellValue());
            case FORMULA:
                return cell.getCellFormula();
            default:
                return null;
        }
    }

    private BigDecimal getCellValueAsBigDecimal(Cell cell) {
        if (cell == null) return BigDecimal.ZERO;

        switch (cell.getCellType()) {
            case NUMERIC:
                return BigDecimal.valueOf(cell.getNumericCellValue());
            case STRING:
                try {
                    String value = cell.getStringCellValue().trim();
                    if (value.isEmpty()) return BigDecimal.ZERO;
                    return new BigDecimal(value);
                } catch (NumberFormatException e) {
                    return BigDecimal.ZERO;
                }
            default:
                return BigDecimal.ZERO;
        }
    }

    /**
     * Validate Excel file structure
     */
    public Map<String, Object> validateExcelFile(MultipartFile file) {
        Map<String, Object> result = new HashMap<>();

        try (Workbook workbook = new XSSFWorkbook(file.getInputStream())) {
            Sheet sheet = workbook.getSheetAt(0);
            Row headerRow = sheet.getRow(0);
            if (headerRow == null) {
                headerRow = sheet.getRow(1);
            }

            List<String> headers = new ArrayList<>();
            if (headerRow != null) {
                for (Cell cell : headerRow) {
                    headers.add(getCellValueAsString(cell));
                }
            }

            result.put("isValid", !headers.isEmpty());
            result.put("headers", headers);
            result.put("rowCount", sheet.getLastRowNum() + 1);

            if (headers.contains("item_name")) {
                result.put("format", "BMS Enhanced");
            } else if (headers.contains("ITEM DESCRIPTION")) {
                result.put("format", "Jack Sheet");
            } else {
                result.put("format", "Unknown");
            }

        } catch (IOException e) {
            result.put("isValid", false);
            result.put("error", e.getMessage());
        }

        return result;
    }

    /**
     * Import sample data for testing
     */
    public Map<String, Object> importSampleData(Long userId) {
        Map<String, Object> result = new HashMap<>();

        try {
            // Create sample categories
            Category chemicalsCategory = findOrCreateCategory("HK Chemicals", userId);
            Category suppliesCategory = findOrCreateCategory("Supplies", userId);

            // Create sample items with consumption data
            String[] itemNames = {"R1", "R2", "R3", "R4", "R5"};
            BigDecimal[] openingStocks = {
                    BigDecimal.valueOf(40), BigDecimal.valueOf(75), BigDecimal.valueOf(40),
                    BigDecimal.valueOf(45), BigDecimal.valueOf(20)
            };
            BigDecimal[] receivedStocks = {
                    BigDecimal.valueOf(25), BigDecimal.valueOf(0), BigDecimal.valueOf(20),
                    BigDecimal.valueOf(0), BigDecimal.valueOf(20)
            };

            int imported = 0;
            for (int i = 0; i < itemNames.length; i++) {
                Item item = new Item();
                item.setItemName(itemNames[i]);
                item.setCategory(chemicalsCategory);
                item.setCurrentQuantity(openingStocks[i].add(receivedStocks[i]));
                item.setOpeningStock(openingStocks[i]);
                item.setTotalReceivedStock(receivedStocks[i]);
                item.setUnitOfMeasurement("LITERS");
                item.setCreatedBy(userId);

                itemRepository.save(item);

                // Create sample consumption records
                Random random = new Random(i); // Seed for reproducible results
                LocalDate startDate = LocalDate.now().minusDays(30);

                for (int day = 0; day < 30; day++) {
                    LocalDate date = startDate.plusDays(day);
                    BigDecimal dailyConsumption = BigDecimal.valueOf(random.nextInt(10)); // 0-9 consumption

                    if (dailyConsumption.compareTo(BigDecimal.ZERO) > 0) {
                        createConsumptionRecord(item, date, dailyConsumption, null);
                    }
                }

                imported++;
            }

            result.put("imported", imported);
            result.put("message", "Sample data imported successfully");

        } catch (Exception e) {
            result.put("error", "Failed to import sample data: " + e.getMessage());
        }

        return result;
    }

    /**
     * Import all sheets from Excel file (for jack_sheet.xlsx with multiple months)
     */
    @Transactional
    public Map<String, Object> importAllSheetsFromExcel(MultipartFile file, Long userId) {
        Map<String, Object> result = new HashMap<>();
        List<Map<String, Object>> sheetResults = new ArrayList<>();

        if (file.isEmpty()) {
            throw new RuntimeException("File is empty");
        }

        try (Workbook workbook = new XSSFWorkbook(file.getInputStream())) {
            logger.info("Processing all {} sheets from Excel file", workbook.getNumberOfSheets());

            int totalImported = 0;
            int totalUpdated = 0;
            int totalFailed = 0;
            List<String> allErrors = new ArrayList<>();

            for (int sheetIndex = 0; sheetIndex < workbook.getNumberOfSheets(); sheetIndex++) {
                Sheet sheet = workbook.getSheetAt(sheetIndex);
                String sheetName = sheet.getSheetName();

                logger.info("Processing sheet {}: {}", sheetIndex + 1, sheetName);

                try {
                    // Find header row for this sheet
                    Row headerRow = null;
                    int headerRowIndex = -1;

                    for (int rowIndex = 0; rowIndex <= 2 && rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                        Row candidateRow = sheet.getRow(rowIndex);
                        if (candidateRow == null) continue;

                        List<String> candidateHeaders = new ArrayList<>();
                        for (int i = 0; i < candidateRow.getLastCellNum(); i++) {
                            Cell cell = candidateRow.getCell(i);
                            String value = getCellValueAsString(cell);
                            candidateHeaders.add(value != null ? value.trim() : "");
                        }

                        boolean hasItemDescription = candidateHeaders.stream()
                                .anyMatch(h -> h.contains("ITEM DESCRIPTION"));

                        if (hasItemDescription) {
                            headerRow = candidateRow;
                            headerRowIndex = rowIndex;
                            break;
                        }
                    }

                    if (headerRow == null) {
                        logger.warn("No header row found in sheet: {}", sheetName);
                        continue;
                    }

                    // Import this sheet as Jack Sheet format
                    Map<String, Object> sheetResult = importJackSheetFormat(sheet, userId, headerRowIndex);
                    sheetResult.put("sheetName", sheetName);
                    sheetResult.put("sheetIndex", sheetIndex);

                    // Aggregate totals
                    totalImported += (Integer) sheetResult.getOrDefault("imported", 0);
                    totalUpdated += (Integer) sheetResult.getOrDefault("updated", 0);
                    totalFailed += (Integer) sheetResult.getOrDefault("failed", 0);

                    List<String> sheetErrors = (List<String>) sheetResult.getOrDefault("errors", new ArrayList<>());
                    for (String error : sheetErrors) {
                        allErrors.add(sheetName + ": " + error);
                    }

                    sheetResults.add(sheetResult);

                } catch (Exception e) {
                    logger.error("Failed to process sheet {}: {}", sheetName, e.getMessage());
                    Map<String, Object> errorResult = new HashMap<>();
                    errorResult.put("sheetName", sheetName);
                    errorResult.put("sheetIndex", sheetIndex);
                    errorResult.put("error", e.getMessage());
                    errorResult.put("imported", 0);
                    errorResult.put("updated", 0);
                    errorResult.put("failed", 1);
                    sheetResults.add(errorResult);

                    totalFailed++;
                    allErrors.add(sheetName + ": " + e.getMessage());
                }
            }

            result.put("format", "Jack Sheet (Multiple Sheets)");
            result.put("totalSheets", workbook.getNumberOfSheets());
            result.put("processedSheets", sheetResults.size());
            result.put("totalImported", totalImported);
            result.put("totalUpdated", totalUpdated);
            result.put("totalFailed", totalFailed);
            result.put("allErrors", allErrors);
            result.put("sheetResults", sheetResults);

            // Trigger correlation calculation after import
            if (correlationService != null && (totalImported > 0 || totalUpdated > 0)) {
                try {
                    Map<String, Object> corrResult = correlationService.calculateAllCorrelations();
                    result.put("correlationsCalculated", true);
                    result.put("correlationSummary", corrResult);
                } catch (Exception e) {
                    logger.error("Failed to calculate correlations after import: {}", e.getMessage());
                    result.put("correlationsCalculated", false);
                    result.put("correlationError", e.getMessage());
                }
            }

            logger.info("All sheets processed: imported={}, updated={}, failed={}",
                    totalImported, totalUpdated, totalFailed);

        } catch (IOException e) {
            throw new RuntimeException("Failed to read Excel file: " + e.getMessage());
        }

        return result;
    }

    /**
     * Get import status and system statistics
     */
    public Map<String, Object> getImportStatus() {
        Map<String, Object> status = new HashMap<>();

        long totalItems = itemRepository.count();
        long totalConsumptionRecords = consumptionRecordRepository.count();
        long totalCategories = categoryRepository.count();
        long totalStockMovements = stockMovementRepository.count();

        status.put("totalItems", totalItems);
        status.put("totalConsumptionRecords", totalConsumptionRecords);
        status.put("totalCategories", totalCategories);
        status.put("totalStockMovements", totalStockMovements);
        status.put("timestamp", LocalDateTime.now());

        // Recent activity statistics
        LocalDate last30Days = LocalDate.now().minusDays(30);
        LocalDate last7Days = LocalDate.now().minusDays(7);

        try {
            List<ConsumptionRecord> recentRecords = consumptionRecordRepository
                    .findByConsumptionDateBetween(last30Days, LocalDate.now());

            List<ConsumptionRecord> weekRecords = consumptionRecordRepository
                    .findByConsumptionDateBetween(last7Days, LocalDate.now());

            status.put("consumptionRecordsLast30Days", recentRecords.size());
            status.put("consumptionRecordsLast7Days", weekRecords.size());

            // Calculate total consumption values
            BigDecimal totalConsumption30Days = recentRecords.stream()
                    .map(r -> r.getConsumedQuantity() != null ? r.getConsumedQuantity() : BigDecimal.ZERO)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            BigDecimal totalReceived30Days = recentRecords.stream()
                    .map(r -> r.getReceivedQuantity() != null ? r.getReceivedQuantity() : BigDecimal.ZERO)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            status.put("totalConsumptionLast30Days", totalConsumption30Days);
            status.put("totalReceivedLast30Days", totalReceived30Days);

        } catch (Exception e) {
            logger.error("Error calculating recent activity: {}", e.getMessage());
            status.put("recentActivityError", e.getMessage());
        }

        // Item statistics
        try {
            List<Item> allItems = itemRepository.findAll();

            long itemsWithStock = allItems.stream()
                    .filter(item -> item.getCurrentQuantity() != null &&
                            item.getCurrentQuantity().compareTo(BigDecimal.ZERO) > 0)
                    .count();

            long lowStockItems = allItems.stream()
                    .filter(Item::needsReorder)
                    .count();

            BigDecimal totalInventoryValue = allItems.stream()
                    .map(item -> item.getTotalValue() != null ? item.getTotalValue() : BigDecimal.ZERO)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            status.put("itemsWithStock", itemsWithStock);
            status.put("lowStockItems", lowStockItems);
            status.put("totalInventoryValue", totalInventoryValue);

        } catch (Exception e) {
            logger.error("Error calculating item statistics: {}", e.getMessage());
            status.put("itemStatsError", e.getMessage());
        }

        // Data quality metrics
        Map<String, Object> dataQuality = new HashMap<>();
        try {
            long itemsWithoutCategory = itemRepository.findAll().stream()
                    .filter(item -> item.getCategory() == null)
                    .count();

            long itemsWithoutPrice = itemRepository.findAll().stream()
                    .filter(item -> item.getUnitPrice() == null ||
                            item.getUnitPrice().compareTo(BigDecimal.ZERO) == 0)
                    .count();

            dataQuality.put("itemsWithoutCategory", itemsWithoutCategory);
            dataQuality.put("itemsWithoutPrice", itemsWithoutPrice);
            dataQuality.put("dataQualityScore", calculateDataQualityScore(totalItems, itemsWithoutCategory, itemsWithoutPrice));

        } catch (Exception e) {
            logger.error("Error calculating data quality: {}", e.getMessage());
            dataQuality.put("error", e.getMessage());
        }

        status.put("dataQuality", dataQuality);
        status.put("systemStatus", "OPERATIONAL");

        return status;
    }

    /**
     * Calculate data quality score (0-100)
     */
    private int calculateDataQualityScore(long totalItems, long itemsWithoutCategory, long itemsWithoutPrice) {
        if (totalItems == 0) return 100;

        double categoryCompleteness = 1.0 - ((double) itemsWithoutCategory / totalItems);
        double priceCompleteness = 1.0 - ((double) itemsWithoutPrice / totalItems);

        // Weighted average: category 40%, price 60%
        double overallScore = (categoryCompleteness * 0.4) + (priceCompleteness * 0.6);

        return (int) Math.round(overallScore * 100);
    }
}