package com.bmsedge.inventory.service;

import com.bmsedge.inventory.model.Item;
import com.bmsedge.inventory.repository.ConsumptionRecordRepository;
import com.bmsedge.inventory.repository.ItemRepository;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddressList;
import org.apache.poi.xssf.usermodel.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class TemplateDownloadService {

    private static final Logger logger = LoggerFactory.getLogger(TemplateDownloadService.class);

    @Autowired
    private ItemRepository itemRepository;

    @Autowired
    private ConsumptionRecordRepository consumptionRecordRepository;

    /**
     * Generate Items Upload Template
     */
    public byte[] generateItemsTemplate(boolean includeSampleData) throws Exception {
        XSSFWorkbook workbook = new XSSFWorkbook();

        // Create main sheet
        XSSFSheet sheet = workbook.createSheet("Items_Upload");

        // Create styles
        CellStyle headerStyle = createHeaderStyle(workbook);
        CellStyle dataStyle = createDataStyle(workbook);

        // Create header row
        Row headerRow = sheet.createRow(0);
        String[] headers = {
                "item", "item_sku", "category", "uom", "unit_price",
                "current_quantity", "opening_stock", "reorder_level"
        };

        for (int i = 0; i < headers.length; i++) {
            Cell cell = headerRow.createCell(i);
            cell.setCellValue(headers[i]);
            cell.setCellStyle(headerStyle);
            sheet.setColumnWidth(i, 4000);
        }

        // Add sample data if requested
        if (includeSampleData) {
            addItemsSampleData(sheet, dataStyle);
        }

        // Create instructions sheet
        createItemsInstructionsSheet(workbook);

        // Write to byte array
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        workbook.write(outputStream);
        workbook.close();

        logger.info("Generated items template with {} rows", includeSampleData ? "sample data" : "headers only");

        return outputStream.toByteArray();
    }

    /**
     * Generate Consumption Upload Template
     * - All items as dropdowns
     * - Dates start from last consumption date + 1
     */
    public byte[] generateConsumptionTemplate(int daysToGenerate, Long categoryId) throws Exception {
        XSSFWorkbook workbook = new XSSFWorkbook();

        // Get all items (filtered by category if provided)
        List<Item> items;
        if (categoryId != null) {
            items = itemRepository.findByCategoryId(categoryId);
        } else {
            items = itemRepository.findAll();
        }

        if (items.isEmpty()) {
            throw new RuntimeException("No items found in database. Please upload items first.");
        }

        logger.info("Found {} items for template", items.size());

        // Get max consumption date
        LocalDate startDate = getNextConsumptionDate();
        logger.info("Consumption template will start from: {}", startDate);

        // Create main sheet
        XSSFSheet sheet = workbook.createSheet("Consumption_Upload");

        // Create styles
        CellStyle headerStyle = createHeaderStyle(workbook);
        CellStyle dateStyle = createDateStyle(workbook);
        CellStyle dataStyle = createDataStyle(workbook);
        CellStyle lockedStyle = createLockedStyle(workbook);

        // Create header row
        Row headerRow = sheet.createRow(0);
        String[] headers = {
                "day", "month", "year", "item_id", "item", "item_sku",
                "category", "uom", "unit_price", "consumption"
        };

        for (int i = 0; i < headers.length; i++) {
            Cell cell = headerRow.createCell(i);
            cell.setCellValue(headers[i]);
            cell.setCellStyle(headerStyle);
            sheet.setColumnWidth(i, 4000);
        }

        // Create hidden sheet for item data
        XSSFSheet itemDataSheet = createItemDataSheet(workbook, items);

        // Generate rows for each day
        int rowNum = 1;
        for (int day = 0; day < daysToGenerate; day++) {
            LocalDate currentDate = startDate.plusDays(day);

            // Create one row per item per day
            for (int itemIdx = 0; itemIdx < items.size(); itemIdx++) {
                Item item = items.get(itemIdx);
                Row row = sheet.createRow(rowNum);

                // Day (formatted date)
                Cell dayCell = row.createCell(0);
                dayCell.setCellValue(currentDate.format(DateTimeFormatter.ofPattern("yyyy-MM-dd")));
                dayCell.setCellStyle(dateStyle);

                // Month (short name)
                Cell monthCell = row.createCell(1);
                monthCell.setCellValue(currentDate.format(DateTimeFormatter.ofPattern("MMM")));
                monthCell.setCellStyle(lockedStyle);

                // Year
                Cell yearCell = row.createCell(2);
                yearCell.setCellValue(currentDate.getYear());
                yearCell.setCellStyle(lockedStyle);

                // Item ID (pre-filled, locked)
                Cell itemIdCell = row.createCell(3);
                itemIdCell.setCellValue(item.getId());
                itemIdCell.setCellStyle(lockedStyle);

                // Item Name (dropdown)
                Cell itemNameCell = row.createCell(4);
                itemNameCell.setCellValue(item.getItemName());
                itemNameCell.setCellStyle(dataStyle);

                // Item SKU (pre-filled based on item)
                Cell skuCell = row.createCell(5);
                skuCell.setCellValue(item.getItemSku() != null ? item.getItemSku() : "");
                skuCell.setCellStyle(lockedStyle);

                // Category (pre-filled, locked)
                Cell categoryCell = row.createCell(6);
                categoryCell.setCellValue(item.getCategory() != null ?
                        item.getCategory().getCategoryName() : "");
                categoryCell.setCellStyle(lockedStyle);

                // UOM (pre-filled, locked)
                Cell uomCell = row.createCell(7);
                uomCell.setCellValue(item.getUnitOfMeasurement());
                uomCell.setCellStyle(lockedStyle);

                // Unit Price (pre-filled, locked)
                Cell priceCell = row.createCell(8);
                if (item.getUnitPrice() != null) {
                    priceCell.setCellValue(item.getUnitPrice().doubleValue());
                }
                priceCell.setCellStyle(lockedStyle);

                // Consumption (editable - user fills this)
                Cell consumptionCell = row.createCell(9);
                consumptionCell.setCellValue(0);
                consumptionCell.setCellStyle(dataStyle);

                rowNum++;
            }
        }

        // Add data validation for item names (dropdown)
        addItemNameDropdown(sheet, itemDataSheet, 1, rowNum - 1);

        // Create instructions sheet
        createConsumptionInstructionsSheet(workbook, startDate, daysToGenerate, items.size());

        // Write to byte array
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        workbook.write(outputStream);
        workbook.close();

        logger.info("Generated consumption template: {} days, {} items, {} total rows",
                daysToGenerate, items.size(), rowNum - 1);

        return outputStream.toByteArray();
    }

    /**
     * Get next consumption date (max consumption date + 1 day)
     */
    private LocalDate getNextConsumptionDate() {
        try {
            // Query for max consumption date
            Optional<LocalDate> maxDate = consumptionRecordRepository.findAll().stream()
                    .map(record -> record.getConsumptionDate())
                    .max(Comparator.naturalOrder());

            if (maxDate.isPresent()) {
                LocalDate nextDate = maxDate.get().plusDays(1);
                logger.info("Last consumption date: {}, starting from: {}", maxDate.get(), nextDate);
                return nextDate;
            } else {
                // No consumption records yet, start from today
                logger.info("No consumption records found, starting from today");
                return LocalDate.now();
            }
        } catch (Exception e) {
            logger.warn("Error getting max consumption date, defaulting to today: {}", e.getMessage());
            return LocalDate.now();
        }
    }

    /**
     * Create hidden sheet with item data for lookups
     */
    private XSSFSheet createItemDataSheet(XSSFWorkbook workbook, List<Item> items) {
        XSSFSheet sheet = workbook.createSheet("ItemData");
        workbook.setSheetHidden(workbook.getSheetIndex(sheet), true);

        // Create headers
        Row headerRow = sheet.createRow(0);
        headerRow.createCell(0).setCellValue("item_id");
        headerRow.createCell(1).setCellValue("item_name");
        headerRow.createCell(2).setCellValue("item_sku");
        headerRow.createCell(3).setCellValue("category");
        headerRow.createCell(4).setCellValue("uom");
        headerRow.createCell(5).setCellValue("unit_price");

        // Add item data
        int rowNum = 1;
        for (Item item : items) {
            Row row = sheet.createRow(rowNum++);
            row.createCell(0).setCellValue(item.getId());
            row.createCell(1).setCellValue(item.getItemName());
            row.createCell(2).setCellValue(item.getItemSku() != null ? item.getItemSku() : "");
            row.createCell(3).setCellValue(item.getCategory() != null ?
                    item.getCategory().getCategoryName() : "");
            row.createCell(4).setCellValue(item.getUnitOfMeasurement());
            if (item.getUnitPrice() != null) {
                row.createCell(5).setCellValue(item.getUnitPrice().doubleValue());
            }
        }

        return sheet;
    }

    /**
     * Add dropdown validation for item names
     */
    private void addItemNameDropdown(XSSFSheet sheet, XSSFSheet itemDataSheet,
                                     int firstRow, int lastRow) {
        DataValidationHelper validationHelper = sheet.getDataValidationHelper();

        // Create formula for dropdown (reference to ItemData sheet)
        String formula = "ItemData!$B$2:$B$" + (itemDataSheet.getLastRowNum() + 1);
        DataValidationConstraint constraint = validationHelper.createFormulaListConstraint(formula);

        // Apply to item name column (column E, index 4)
        CellRangeAddressList addressList = new CellRangeAddressList(firstRow, lastRow, 4, 4);
        DataValidation validation = validationHelper.createValidation(constraint, addressList);

        // Configure validation
        validation.setShowErrorBox(true);
        validation.setErrorStyle(DataValidation.ErrorStyle.STOP);
        validation.setEmptyCellAllowed(false);
        validation.createErrorBox("Invalid Item", "Please select an item from the dropdown list.");
        validation.setShowPromptBox(true);
        validation.createPromptBox("Select Item", "Choose an item from the dropdown list.");

        sheet.addValidationData(validation);
    }

    /**
     * Add sample data to items template
     */
    private void addItemsSampleData(XSSFSheet sheet, CellStyle dataStyle) {
        String[][] sampleData = {
                {"Pril-Dishwash", "125ml", "HK Chemicals", "Bottle", "17.00", "50", "50", "10"},
                {"Pril-Dishwash", "500ml", "HK Chemicals", "Bottle", "52.00", "30", "30", "5"},
                {"Rice", "1kg", "Groceries", "Packet", "65.00", "100", "100", "20"},
                {"Hand Soap", "100ml", "HK Consumables", "Bottle", "15.00", "75", "75", "15"}
        };

        for (int i = 0; i < sampleData.length; i++) {
            Row row = sheet.createRow(i + 1);
            for (int j = 0; j < sampleData[i].length; j++) {
                Cell cell = row.createCell(j);

                // Try to parse as number for numeric columns
                if (j >= 4) {
                    try {
                        cell.setCellValue(Double.parseDouble(sampleData[i][j]));
                    } catch (NumberFormatException e) {
                        cell.setCellValue(sampleData[i][j]);
                    }
                } else {
                    cell.setCellValue(sampleData[i][j]);
                }
                cell.setCellStyle(dataStyle);
            }
        }
    }

    /**
     * Create instructions sheet for items template
     */
    private void createItemsInstructionsSheet(XSSFWorkbook workbook) {
        XSSFSheet sheet = workbook.createSheet("Instructions");
        CellStyle titleStyle = createTitleStyle(workbook);
        CellStyle normalStyle = createDataStyle(workbook);

        int rowNum = 0;

        // Title
        Row titleRow = sheet.createRow(rowNum++);
        Cell titleCell = titleRow.createCell(0);
        titleCell.setCellValue("Items Upload Template - Instructions");
        titleCell.setCellStyle(titleStyle);

        rowNum++; // Empty row

        // Instructions
        String[] instructions = {
                "Required Columns:",
                "- item: Item name",
                "- category: Category name (will be auto-created if doesn't exist)",
                "- current_quantity: Current stock level",
                "",
                "Optional Columns:",
                "- item_sku: SKU for variants (125ml, 500ml, etc.)",
                "- uom: Unit of measurement (Bottle, Liters, pcs, etc.)",
                "- unit_price: Unit price",
                "- opening_stock: Opening stock",
                "- reorder_level: Minimum stock level",
                "",
                "Important Notes:",
                "- Different SKUs = Different items (Pril 125ml and Pril 500ml are separate)",
                "- SKU can be in separate column or embedded in item name",
                "- System auto-extracts SKU from names like 'Pril-Dishwash 125ml'",
                "- Categories are auto-created if they don't exist",
                "- Remove empty rows before uploading",
                "",
                "After filling the template:",
                "1. Save the file",
                "2. Upload via POST /api/upload/items",
                "3. Check response for any errors"
        };

        for (String instruction : instructions) {
            Row row = sheet.createRow(rowNum++);
            Cell cell = row.createCell(0);
            cell.setCellValue(instruction);
            cell.setCellStyle(normalStyle);
        }

        sheet.setColumnWidth(0, 15000);
    }

    /**
     * Create instructions sheet for consumption template
     */
    private void createConsumptionInstructionsSheet(XSSFWorkbook workbook,
                                                    LocalDate startDate,
                                                    int daysGenerated,
                                                    int itemCount) {
        XSSFSheet sheet = workbook.createSheet("Instructions");
        CellStyle titleStyle = createTitleStyle(workbook);
        CellStyle normalStyle = createDataStyle(workbook);

        int rowNum = 0;

        // Title
        Row titleRow = sheet.createRow(rowNum++);
        Cell titleCell = titleRow.createCell(0);
        titleCell.setCellValue("Consumption Upload Template - Instructions");
        titleCell.setCellStyle(titleStyle);

        rowNum++; // Empty row

        // Template info
        Row infoRow1 = sheet.createRow(rowNum++);
        infoRow1.createCell(0).setCellValue("Template Generated: " + LocalDate.now());

        Row infoRow2 = sheet.createRow(rowNum++);
        infoRow2.createCell(0).setCellValue("Start Date: " + startDate);

        Row infoRow3 = sheet.createRow(rowNum++);
        infoRow3.createCell(0).setCellValue("Days Generated: " + daysGenerated);

        Row infoRow4 = sheet.createRow(rowNum++);
        infoRow4.createCell(0).setCellValue("Items Included: " + itemCount);

        rowNum++; // Empty row

        // Instructions
        String[] instructions = {
                "How to Use:",
                "1. The 'consumption' column (last column) is the ONLY column you need to fill",
                "2. All other columns are pre-filled and locked",
                "3. Enter the consumed quantity for each item for each day",
                "4. Leave as 0 if no consumption for that item on that day",
                "5. Save the file and upload via POST /api/upload/consumption",
                "",
                "Pre-filled Columns (Locked):",
                "- day: Date in yyyy-MM-dd format",
                "- month: Month name (auto-filled)",
                "- year: Year (auto-filled)",
                "- item_id: Item ID (for system reference)",
                "- item: Item name (pre-filled from database)",
                "- item_sku: Item SKU (pre-filled)",
                "- category: Category name (pre-filled)",
                "- uom: Unit of measurement (pre-filled)",
                "- unit_price: Unit price (pre-filled)",
                "",
                "Editable Column:",
                "- consumption: Enter consumed quantity here",
                "",
                "Important Notes:",
                "- Dates start from " + startDate + " (day after last consumption record)",
                "- One row per item per day",
                "- All items from database are included",
                "- System validates items exist before importing",
                "- Upload triggers automatic statistics calculation",
                "",
                "After Filling:",
                "1. Fill consumption quantities",
                "2. Save the file",
                "3. Upload via POST /api/upload/consumption",
                "4. Check response for any errors or warnings"
        };

        for (String instruction : instructions) {
            Row row = sheet.createRow(rowNum++);
            Cell cell = row.createCell(0);
            cell.setCellValue(instruction);
            cell.setCellStyle(normalStyle);
        }

        sheet.setColumnWidth(0, 15000);
    }

    // ==================== STYLE HELPERS ====================

    private CellStyle createHeaderStyle(XSSFWorkbook workbook) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setBold(true);
        font.setFontHeightInPoints((short) 11);
        font.setColor(IndexedColors.WHITE.getIndex());
        style.setFont(font);
        style.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
        style.setAlignment(HorizontalAlignment.CENTER);
        style.setVerticalAlignment(VerticalAlignment.CENTER);
        return style;
    }

    private CellStyle createDataStyle(XSSFWorkbook workbook) {
        CellStyle style = workbook.createCellStyle();
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
        style.setAlignment(HorizontalAlignment.LEFT);
        style.setVerticalAlignment(VerticalAlignment.CENTER);
        return style;
    }

    private CellStyle createDateStyle(XSSFWorkbook workbook) {
        CellStyle style = workbook.createCellStyle();
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
        style.setAlignment(HorizontalAlignment.CENTER);
        style.setVerticalAlignment(VerticalAlignment.CENTER);
        return style;
    }

    private CellStyle createLockedStyle(XSSFWorkbook workbook) {
        CellStyle style = workbook.createCellStyle();
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
        style.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setAlignment(HorizontalAlignment.LEFT);
        style.setVerticalAlignment(VerticalAlignment.CENTER);
        style.setLocked(true);
        return style;
    }

    private CellStyle createTitleStyle(XSSFWorkbook workbook) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setBold(true);
        font.setFontHeightInPoints((short) 14);
        style.setFont(font);
        return style;
    }
}