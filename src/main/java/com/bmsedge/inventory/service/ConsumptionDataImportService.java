package com.bmsedge.inventory.service;

import com.bmsedge.inventory.model.Item;
import com.bmsedge.inventory.model.ConsumptionRecord;
import com.bmsedge.inventory.repository.ItemRepository;
import com.bmsedge.inventory.repository.ConsumptionRecordRepository;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@Transactional
public class ConsumptionDataImportService {

    @Autowired
    private ItemRepository itemRepository;

    @Autowired
    private ConsumptionRecordRepository consumptionRecordRepository;

    /**
     * Import consumption data from Excel file with proper date parsing from column headers
     */
    public Map<String, Object> importConsumptionData(MultipartFile file) throws IOException {
        Map<String, Object> result = new HashMap<>();
        List<String> errors = new ArrayList<>();
        int totalRecordsProcessed = 0;
        int recordsCreated = 0;
        int recordsUpdated = 0;
        int recordsSkipped = 0;

        // Define sheet names to process (as per your Excel structure)
        String[] sheetNames = {"Jan 25", "Feb 25", " Mar 25", " Apr 25", " May 25", " Jun 25", " Jul 25"};

        // Map to track which month/year we're processing
        Map<String, Integer> monthYearMap = new HashMap<>();
        monthYearMap.put("Jan 25", 2025);
        monthYearMap.put("Feb 25", 2025);
        monthYearMap.put(" Mar 25", 2025);
        monthYearMap.put(" Apr 25", 2025);
        monthYearMap.put(" May 25", 2025);
        monthYearMap.put(" Jun 25", 2025);
        monthYearMap.put(" Jul 25", 2025);

        try (Workbook workbook = new XSSFWorkbook(file.getInputStream())) {
            for (String sheetName : sheetNames) {
                Sheet sheet = workbook.getSheet(sheetName);
                if (sheet == null) {
                    System.out.println("Sheet not found: " + sheetName);
                    continue;
                }

                System.out.println("Processing consumption data from sheet: " + sheetName);

                // Extract year and month from sheet name
                int year = monthYearMap.getOrDefault(sheetName.trim(), 2025);
                String monthStr = sheetName.trim().split(" ")[0];
                int month = getMonthNumber(monthStr);

                // Process the sheet
                Map<String, Object> sheetResult = processConsumptionSheet(sheet, year, month, errors);

                totalRecordsProcessed += (int) sheetResult.get("processed");
                recordsCreated += (int) sheetResult.get("created");
                recordsUpdated += (int) sheetResult.get("updated");
                recordsSkipped += (int) sheetResult.get("skipped");
            }
        } catch (Exception e) {
            errors.add("Error processing file: " + e.getMessage());
            e.printStackTrace();
        }

        // Build response
        result.put("success", recordsCreated > 0 || recordsUpdated > 0);
        result.put("totalRecordsProcessed", totalRecordsProcessed);
        result.put("recordsCreated", recordsCreated);
        result.put("recordsUpdated", recordsUpdated);
        result.put("recordsSkipped", recordsSkipped);
        result.put("errors", errors);

        if (recordsCreated > 0 || recordsUpdated > 0) {
            result.put("message", String.format(
                    "Successfully imported consumption data: %d created, %d updated, %d skipped",
                    recordsCreated, recordsUpdated, recordsSkipped
            ));
        } else {
            result.put("message", "No consumption records were imported. Check errors for details.");
        }

        return result;
    }

    private Map<String, Object> processConsumptionSheet(Sheet sheet, int year, int month, List<String> errors) {
        Map<String, Object> sheetResult = new HashMap<>();
        int processed = 0;
        int created = 0;
        int updated = 0;
        int skipped = 0;

        try {
            // Header is at row 1 (index 1)
            Row headerRow = sheet.getRow(1);
            if (headerRow == null) {
                errors.add("No header row found in sheet");
                sheetResult.put("processed", 0);
                sheetResult.put("created", 0);
                sheetResult.put("updated", 0);
                sheetResult.put("skipped", 0);
                return sheetResult;
            }

            // Find essential columns
            int categoryCol = findColumnByName(headerRow, "Category");
            int itemDescCol = findColumnByName(headerRow, "ITEM DESCRIPTION");

            // Find daily consumption columns (they should be numbered 1, 2, 3... for days of month)
            Map<Integer, Integer> dayToColumnMap = new HashMap<>();

            // Look for columns with day numbers (1-31) or date patterns
            for (Cell cell : headerRow) {
                String cellValue = getCellValueAsString(cell);
                if (cellValue != null && !cellValue.trim().isEmpty()) {
                    // Try to parse as day number
                    Integer dayNum = extractDayFromHeader(cellValue);
                    if (dayNum != null && dayNum >= 1 && dayNum <= 31) {
                        dayToColumnMap.put(dayNum, cell.getColumnIndex());
                        System.out.println("Found day " + dayNum + " at column " + cell.getColumnIndex());
                    }
                }
            }

            if (dayToColumnMap.isEmpty()) {
                errors.add("No daily consumption columns found in sheet for month " + month + "/" + year);
                sheetResult.put("processed", 0);
                sheetResult.put("created", 0);
                sheetResult.put("updated", 0);
                sheetResult.put("skipped", 0);
                return sheetResult;
            }

            // Process data rows (starting from row 2, index 2)
            for (int rowIndex = 2; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                Row row = sheet.getRow(rowIndex);
                if (row == null || isRowEmpty(row)) {
                    continue;
                }

                try {
                    String category = getCellValueAsString(row.getCell(categoryCol));
                    String itemDesc = getCellValueAsString(row.getCell(itemDescCol));

                    // Skip total rows or empty items
                    if (itemDesc == null || itemDesc.trim().isEmpty() ||
                            itemDesc.toLowerCase().contains("total") ||
                            category == null || category.trim().isEmpty()) {
                        continue;
                    }

                    // Find the item in database
                    Item item = findItemByNameAndCategory(itemDesc.trim(), category.trim());
                    if (item == null) {
                        System.out.println("Item not found: " + itemDesc + " in category " + category);
                        skipped++;
                        continue;
                    }

                    // Process consumption for each day
                    for (Map.Entry<Integer, Integer> entry : dayToColumnMap.entrySet()) {
                        int dayOfMonth = entry.getKey();
                        int columnIndex = entry.getValue();

                        // Create date for this consumption
                        LocalDate consumptionDate;
                        try {
                            consumptionDate = LocalDate.of(year, month, dayOfMonth);
                        } catch (Exception e) {
                            // Invalid date (e.g., Feb 30), skip
                            continue;
                        }

                        // Get consumption value
                        BigDecimal consumptionValue = getCellValueAsBigDecimal(row.getCell(columnIndex));

                        if (consumptionValue != null && consumptionValue.compareTo(BigDecimal.ZERO) > 0) {
                            // Check if record already exists
                            Optional<ConsumptionRecord> existingRecord =
                                    consumptionRecordRepository.findByItemAndConsumptionDate(item, consumptionDate);

                            if (existingRecord.isPresent()) {
                                // Update existing record
                                ConsumptionRecord record = existingRecord.get();
                                record.setQuantityConsumed(consumptionValue);
                                record.setNotes("Updated from bulk import");
                                consumptionRecordRepository.save(record);
                                updated++;
                            } else {
                                // Create new record
                                ConsumptionRecord record = new ConsumptionRecord();
                                record.setItem(item);
                                record.setConsumptionDate(consumptionDate);
                                record.setQuantityConsumed(consumptionValue);
                                record.setNotes("Imported from Excel - " + sheetName(month, year));
                                consumptionRecordRepository.save(record);
                                created++;
                            }
                            processed++;
                        }
                    }

                } catch (Exception e) {
                    errors.add("Error processing row " + (rowIndex + 1) + ": " + e.getMessage());
                }
            }

        } catch (Exception e) {
            errors.add("Error processing sheet: " + e.getMessage());
            e.printStackTrace();
        }

        sheetResult.put("processed", processed);
        sheetResult.put("created", created);
        sheetResult.put("updated", updated);
        sheetResult.put("skipped", skipped);

        System.out.println(String.format("Sheet processing complete: %d processed, %d created, %d updated, %d skipped",
                processed, created, updated, skipped));

        return sheetResult;
    }

    /**
     * Extract day number from column header
     * Handles formats like "1", "01", "Day 1", "1st", etc.
     */
    private Integer extractDayFromHeader(String header) {
        if (header == null || header.trim().isEmpty()) {
            return null;
        }

        String cleaned = header.trim();

        // First, try simple numeric parse
        try {
            int day = Integer.parseInt(cleaned);
            if (day >= 1 && day <= 31) {
                return day;
            }
        } catch (NumberFormatException e) {
            // Not a simple number, try other patterns
        }

        // Try patterns like "Day 1", "1st", "2nd", etc.
        Pattern pattern = Pattern.compile("\\b(\\d{1,2})(?:st|nd|rd|th)?\\b");
        Matcher matcher = pattern.matcher(cleaned);
        if (matcher.find()) {
            try {
                int day = Integer.parseInt(matcher.group(1));
                if (day >= 1 && day <= 31) {
                    return day;
                }
            } catch (NumberFormatException e) {
                // Ignore
            }
        }

        return null;
    }

    /**
     * Find item by name and category
     */
    private Item findItemByNameAndCategory(String itemName, String categoryName) {
        List<Item> items = itemRepository.findByItemNameContainingIgnoreCase(itemName);

        for (Item item : items) {
            if (item.getCategory() != null &&
                    item.getCategory().getCategoryName().equalsIgnoreCase(categoryName)) {
                return item;
            }
        }

        // If exact match not found, try partial match
        items = itemRepository.findByCategoryName(categoryName);
        for (Item item : items) {
            if (item.getItemName().toLowerCase().contains(itemName.toLowerCase()) ||
                    itemName.toLowerCase().contains(item.getItemName().toLowerCase())) {
                return item;
            }
        }

        return null;
    }

    /**
     * Convert month name to number
     */
    private int getMonthNumber(String monthName) {
        Map<String, Integer> months = new HashMap<>();
        months.put("Jan", 1);
        months.put("Feb", 2);
        months.put("Mar", 3);
        months.put("Apr", 4);
        months.put("May", 5);
        months.put("Jun", 6);
        months.put("Jul", 7);
        months.put("Aug", 8);
        months.put("Sep", 9);
        months.put("Oct", 10);
        months.put("Nov", 11);
        months.put("Dec", 12);

        return months.getOrDefault(monthName, 1);
    }

    /**
     * Generate sheet name from month and year
     */
    private String sheetName(int month, int year) {
        String[] monthNames = {"Jan", "Feb", "Mar", "Apr", "May", "Jun",
                "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"};
        return monthNames[month - 1] + " " + (year % 100);
    }

    /**
     * Find column by header name
     */
    private int findColumnByName(Row headerRow, String columnName) {
        for (Cell cell : headerRow) {
            String cellValue = getCellValueAsString(cell);
            if (cellValue != null && cellValue.trim().equalsIgnoreCase(columnName.trim())) {
                return cell.getColumnIndex();
            }
        }
        return -1;
    }

    /**
     * Get cell value as BigDecimal
     */
    private BigDecimal getCellValueAsBigDecimal(Cell cell) {
        if (cell == null) return null;

        try {
            switch (cell.getCellType()) {
                case NUMERIC:
                    double value = cell.getNumericCellValue();
                    return value > 0 ? BigDecimal.valueOf(value) : null;
                case STRING:
                    String strValue = cell.getStringCellValue().trim();
                    if (strValue.isEmpty() || strValue.equals("-") || strValue.equals("0")) {
                        return null;
                    }
                    // Remove any non-numeric characters except decimal point
                    strValue = strValue.replaceAll("[^0-9.]", "");
                    if (strValue.isEmpty()) return null;
                    BigDecimal bd = new BigDecimal(strValue);
                    return bd.compareTo(BigDecimal.ZERO) > 0 ? bd : null;
                case FORMULA:
                    try {
                        double formulaValue = cell.getNumericCellValue();
                        return formulaValue > 0 ? BigDecimal.valueOf(formulaValue) : null;
                    } catch (Exception e) {
                        return null;
                    }
                case BLANK:
                    return null;
                default:
                    return null;
            }
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Get cell value as String
     */
    private String getCellValueAsString(Cell cell) {
        if (cell == null) return "";

        switch (cell.getCellType()) {
            case STRING:
                return cell.getStringCellValue();
            case NUMERIC:
                if (DateUtil.isCellDateFormatted(cell)) {
                    return cell.getLocalDateTimeCellValue().toString();
                }
                double numericValue = cell.getNumericCellValue();
                if (numericValue == (int) numericValue) {
                    return String.valueOf((int) numericValue);
                } else {
                    return String.valueOf(numericValue);
                }
            case BOOLEAN:
                return String.valueOf(cell.getBooleanCellValue());
            case FORMULA:
                try {
                    return String.valueOf(cell.getNumericCellValue());
                } catch (Exception e) {
                    return cell.getCellFormula();
                }
            case BLANK:
                return "";
            default:
                return "";
        }
    }

    /**
     * Check if row is empty
     */
    private boolean isRowEmpty(Row row) {
        for (int cellNum = 0; cellNum < row.getLastCellNum(); cellNum++) {
            Cell cell = row.getCell(cellNum);
            if (cell != null && !getCellValueAsString(cell).trim().isEmpty()) {
                return false;
            }
        }
        return true;
    }
}