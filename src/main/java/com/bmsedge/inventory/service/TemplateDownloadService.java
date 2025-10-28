package com.bmsedge.inventory.service;

import com.bmsedge.inventory.model.Item;
import com.bmsedge.inventory.repository.ConsumptionRecordRepository;
import com.bmsedge.inventory.repository.ItemRepository;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.CellRangeAddressList;
import org.apache.poi.xssf.usermodel.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
public class TemplateDownloadService {

    private static final Logger logger = LoggerFactory.getLogger(TemplateDownloadService.class);

    @Autowired
    private ItemRepository itemRepository;

    @Autowired
    private ConsumptionRecordRepository consumptionRecordRepository;

    /**
     * Generate Items Upload Template (UNCHANGED)
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
     * MODIFIED: Generate Consumption Upload Template - HORIZONTAL FORMAT
     * - One row per item (not per day)
     * - Columns for each day of the month
     * - Month dropdown with automatic date updates
     * - Locked cells (only consumption cells editable)
     * - Auto-calculation: Opening + Received - SUM(Daily) = Closing
     */
    public byte[] generateConsumptionTemplate(int daysToGenerate, Long categoryId) throws Exception {
        XSSFWorkbook workbook = new XSSFWorkbook();

        // Get next consumption date to determine default month/year
        LocalDate nextDate = getNextConsumptionDate();
        int year = nextDate.getYear();
        int month = nextDate.getMonthValue();

        logger.info("Generating horizontal consumption template for {}-{}", year, month);

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

        // Create main sheet with horizontal format
        XSSFSheet sheet = workbook.createSheet("Consumption_Upload");

        // Calculate days in the default month
        YearMonth yearMonth = YearMonth.of(year, month);
        int daysInMonth = yearMonth.lengthOfMonth();

        // Create styles
        Map<String, CellStyle> styles = createConsumptionStyles(workbook);

        // Set column widths
        setConsumptionColumnWidths(sheet, daysInMonth);

        // Create month selector row (Row 0)
        createMonthSelectorRow(sheet, year, month, daysInMonth, styles);

        // Create title row (Row 1)
        createConsumptionTitleRow(sheet, yearMonth, daysInMonth, styles);

        // Create header row (Row 2)
        createConsumptionHeaderRow(sheet, yearMonth, daysInMonth, styles);

        // Populate items (Starting from Row 3)
        populateConsumptionItems(sheet, items, yearMonth, daysInMonth, styles);

        // Add data validation for month dropdown
        addMonthDropdown(sheet, daysInMonth);

        // Protect sheet (lock all except consumption cells and month selector)
        protectConsumptionSheet(sheet, items.size(), daysInMonth);

        // Create instructions sheet
        createHorizontalConsumptionInstructions(workbook, yearMonth, items.size());

        // Write to byte array
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        workbook.write(outputStream);
        workbook.close();

        logger.info("Generated horizontal consumption template: {} items, {} days", items.size(), daysInMonth);

        return outputStream.toByteArray();
    }

    /**
     * Create month selector row with dropdown
     */
    private void createMonthSelectorRow(XSSFSheet sheet, int year, int month, int daysInMonth,
                                        Map<String, CellStyle> styles) {
        Row selectorRow = sheet.createRow(0);
        selectorRow.setHeight((short) 400);

        // Label cell
        Cell labelCell = selectorRow.createCell(0);
        labelCell.setCellValue("Select Month:");
        labelCell.setCellStyle(styles.get("monthLabel"));

        // Month dropdown cell (unlocked for editing)
        Cell monthCell = selectorRow.createCell(1);
        monthCell.setCellValue(YearMonth.of(year, month).format(DateTimeFormatter.ofPattern("yyyy-MM")));
        CellStyle unlocked = styles.get("monthDropdown");
        monthCell.setCellStyle(unlocked);

        // Instructions
        Cell instructionCell = selectorRow.createCell(3);
        instructionCell.setCellValue("Change month to update all dates automatically →");
        instructionCell.setCellStyle(styles.get("instruction"));

        // Merge instruction cells
        sheet.addMergedRegion(new CellRangeAddress(0, 0, 3, 6));
    }

    /**
     * Create title row
     */
    private void createConsumptionTitleRow(XSSFSheet sheet, YearMonth yearMonth, int daysInMonth,
                                           Map<String, CellStyle> styles) {
        Row titleRow = sheet.createRow(1);
        titleRow.setHeight((short) 600);

        Cell titleCell = titleRow.createCell(0);
        titleCell.setCellValue("Monthly Consumption Template - " +
                yearMonth.format(DateTimeFormatter.ofPattern("MMMM yyyy")));
        titleCell.setCellStyle(styles.get("title"));

        // Merge title across all columns
        sheet.addMergedRegion(new CellRangeAddress(1, 1, 0, 6 + daysInMonth));
    }

    /**
     * Create header row with fixed columns and date columns
     */
    private void createConsumptionHeaderRow(XSSFSheet sheet, YearMonth yearMonth, int daysInMonth,
                                            Map<String, CellStyle> styles) {
        Row headerRow = sheet.createRow(2);
        headerRow.setHeight((short) 450);

        // Fixed column headers
        String[] fixedHeaders = {
                "Category", "Item Name", "UOM", "Unit Price", "Opening Stock", "Total Received"
        };

        for (int i = 0; i < fixedHeaders.length; i++) {
            Cell cell = headerRow.createCell(i);
            cell.setCellValue(fixedHeaders[i]);
            cell.setCellStyle(styles.get("header"));
        }

        // Date column headers (Day 1, Day 2, ... Day 31)
        int baseCol = fixedHeaders.length;
        LocalDate startDate = yearMonth.atDay(1);

        for (int day = 1; day <= daysInMonth; day++) {
            Cell cell = headerRow.createCell(baseCol + day - 1);
            LocalDate date = startDate.plusDays(day - 1);

            // Formula to make dates dynamic based on month selector
            // =DATE(YEAR(B1), MONTH(B1), [day])
            String dateFormula = "TEXT(DATE(YEAR($B$1),MONTH($B$1)," + day + "),\"DD-MMM\")";
            cell.setCellFormula(dateFormula);
            cell.setCellStyle(styles.get("dateHeader"));
        }

        // Closing stock header
        Cell closingHeader = headerRow.createCell(baseCol + daysInMonth);
        closingHeader.setCellValue("Closing Stock");
        closingHeader.setCellStyle(styles.get("header"));
    }

    /**
     * Populate items with horizontal format
     */
    private void populateConsumptionItems(XSSFSheet sheet, List<Item> items, YearMonth yearMonth,
                                          int daysInMonth, Map<String, CellStyle> styles) {
        int rowNum = 3; // Start after month selector, title, and header

        for (Item item : items) {
            Row row = sheet.createRow(rowNum++);
            row.setHeight((short) 350);

            // Fixed columns (locked)
            int col = 0;

            // Category
            Cell categoryCell = row.createCell(col++);
            categoryCell.setCellValue(item.getCategory() != null ?
                    item.getCategory().getCategoryName() : "");
            categoryCell.setCellStyle(styles.get("locked"));

            // Item Name
            Cell itemNameCell = row.createCell(col++);
            itemNameCell.setCellValue(item.getItemName());
            itemNameCell.setCellStyle(styles.get("locked"));

            // UOM
            Cell uomCell = row.createCell(col++);
            uomCell.setCellValue(item.getUnitOfMeasurement() != null ?
                    item.getUnitOfMeasurement() : "Units");
            uomCell.setCellStyle(styles.get("locked"));

            // Unit Price
            Cell priceCell = row.createCell(col++);
            if (item.getUnitPrice() != null) {
                priceCell.setCellValue(item.getUnitPrice().doubleValue());
            } else {
                priceCell.setCellValue(0);
            }
            priceCell.setCellStyle(styles.get("locked"));

            // Opening Stock (editable)
            Cell openingCell = row.createCell(col++);
            if (item.getCurrentQuantity() != null) {
                openingCell.setCellValue(item.getCurrentQuantity().doubleValue());
            } else {
                openingCell.setCellValue(0);
            }
            openingCell.setCellStyle(styles.get("editable"));

            // Total Received (editable)
            Cell receivedCell = row.createCell(col++);
            receivedCell.setCellValue(0);
            receivedCell.setCellStyle(styles.get("editable"));

            // Daily consumption columns (editable)
            int baseCol = 6; // After fixed columns
            for (int day = 0; day < daysInMonth; day++) {
                Cell dayCell = row.createCell(baseCol + day);
                dayCell.setCellValue(0);
                dayCell.setCellStyle(styles.get("editable"));
            }

            // Closing stock formula (locked)
            Cell closingCell = row.createCell(baseCol + daysInMonth);

            // Formula: =E{row}+F{row}-SUM(G{row}:..{row})
            String rowRef = String.valueOf(rowNum);
            String openingRef = "E" + rowRef;
            String receivedRef = "F" + rowRef;
            String firstDayCol = getColumnLetter(baseCol);
            String lastDayCol = getColumnLetter(baseCol + daysInMonth - 1);

            String formula = openingRef + "+" + receivedRef + "-SUM(" +
                    firstDayCol + rowRef + ":" + lastDayCol + rowRef + ")";
            closingCell.setCellFormula(formula);
            closingCell.setCellStyle(styles.get("formula"));
        }

        // Freeze panes (freeze first 6 columns and first 3 rows)
        sheet.createFreezePane(6, 3);
    }

    /**
     * Add month dropdown validation
     */
    private void addMonthDropdown(XSSFSheet sheet, int daysInMonth) {
        DataValidationHelper validationHelper = sheet.getDataValidationHelper();

        // Create list of months (yyyy-MM format)
        String[] months = new String[12];
        LocalDate baseDate = LocalDate.now();
        for (int i = 0; i < 12; i++) {
            LocalDate monthDate = baseDate.withMonth(i + 1);
            months[i] = monthDate.format(DateTimeFormatter.ofPattern("yyyy-MM"));
        }

        DataValidationConstraint constraint = validationHelper.createExplicitListConstraint(months);
        CellRangeAddressList addressList = new CellRangeAddressList(0, 0, 1, 1); // Cell B1
        DataValidation validation = validationHelper.createValidation(constraint, addressList);

        validation.setShowErrorBox(true);
        validation.createErrorBox("Invalid Month", "Please select a valid month from the dropdown.");
        validation.setShowPromptBox(true);
        validation.createPromptBox("Select Month", "Choose a month. Dates will update automatically.");

        sheet.addValidationData(validation);
    }

    /**
     * Protect sheet - lock all except consumption cells and month selector
     */
    private void protectConsumptionSheet(XSSFSheet sheet, int itemCount, int daysInMonth) {
        // Protect the sheet
        sheet.protectSheet("inventory2025");

        // The cells with "editable" style will be unlocked
        // The cells with "locked" style will be locked
        // Month selector cell (B1) is already unlocked via style

        logger.info("Sheet protected. Editable cells: Month selector (B1), Opening Stock (E4-E{}), " +
                        "Total Received (F4-F{}), Daily consumption (G4-{}{})",
                itemCount + 3, itemCount + 3, getColumnLetter(6 + daysInMonth - 1), itemCount + 3);
    }

    /**
     * Set column widths for horizontal format
     */
    private void setConsumptionColumnWidths(XSSFSheet sheet, int daysInMonth) {
        sheet.setColumnWidth(0, 4000);  // Category
        sheet.setColumnWidth(1, 6000);  // Item Name
        sheet.setColumnWidth(2, 2000);  // UOM
        sheet.setColumnWidth(3, 3000);  // Unit Price
        sheet.setColumnWidth(4, 3500);  // Opening Stock
        sheet.setColumnWidth(5, 3500);  // Total Received

        // Date columns
        int baseCol = 6;
        for (int i = 0; i < daysInMonth; i++) {
            sheet.setColumnWidth(baseCol + i, 2800);
        }

        // Closing stock
        sheet.setColumnWidth(baseCol + daysInMonth, 3500);
    }

    /**
     * Create styles for consumption template
     */
    private Map<String, CellStyle> createConsumptionStyles(XSSFWorkbook workbook) {
        Map<String, CellStyle> styles = new HashMap<>();

        // Month label style
        CellStyle monthLabelStyle = workbook.createCellStyle();
        Font boldFont = workbook.createFont();
        boldFont.setBold(true);
        boldFont.setFontHeightInPoints((short) 12);
        monthLabelStyle.setFont(boldFont);
        monthLabelStyle.setAlignment(HorizontalAlignment.RIGHT);
        monthLabelStyle.setVerticalAlignment(VerticalAlignment.CENTER);
        monthLabelStyle.setLocked(true);
        styles.put("monthLabel", monthLabelStyle);

        // Month dropdown style (unlocked)
        CellStyle monthDropdownStyle = workbook.createCellStyle();
        monthDropdownStyle.setFillForegroundColor(IndexedColors.LIGHT_CORNFLOWER_BLUE.getIndex());
        monthDropdownStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        monthDropdownStyle.setBorderBottom(BorderStyle.MEDIUM);
        monthDropdownStyle.setBorderTop(BorderStyle.MEDIUM);
        monthDropdownStyle.setBorderLeft(BorderStyle.MEDIUM);
        monthDropdownStyle.setBorderRight(BorderStyle.MEDIUM);
        monthDropdownStyle.setAlignment(HorizontalAlignment.CENTER);
        monthDropdownStyle.setLocked(false); // UNLOCKED
        Font dropdownFont = workbook.createFont();
        dropdownFont.setBold(true);
        dropdownFont.setFontHeightInPoints((short) 11);
        monthDropdownStyle.setFont(dropdownFont);
        styles.put("monthDropdown", monthDropdownStyle);

        // Instruction style
        CellStyle instructionStyle = workbook.createCellStyle();
        Font italicFont = workbook.createFont();
        italicFont.setItalic(true);
        italicFont.setColor(IndexedColors.GREY_50_PERCENT.getIndex());
        instructionStyle.setFont(italicFont);
        instructionStyle.setAlignment(HorizontalAlignment.LEFT);
        instructionStyle.setLocked(true);
        styles.put("instruction", instructionStyle);

        // Title style
        CellStyle titleStyle = workbook.createCellStyle();
        Font titleFont = workbook.createFont();
        titleFont.setBold(true);
        titleFont.setFontHeightInPoints((short) 14);
        titleFont.setColor(IndexedColors.WHITE.getIndex());
        titleStyle.setFont(titleFont);
        titleStyle.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
        titleStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        titleStyle.setAlignment(HorizontalAlignment.CENTER);
        titleStyle.setVerticalAlignment(VerticalAlignment.CENTER);
        titleStyle.setLocked(true);
        styles.put("title", titleStyle);

        // Header style
        CellStyle headerStyle = workbook.createCellStyle();
        Font headerFont = workbook.createFont();
        headerFont.setBold(true);
        headerFont.setFontHeightInPoints((short) 11);
        headerFont.setColor(IndexedColors.WHITE.getIndex());
        headerStyle.setFont(headerFont);
        headerStyle.setFillForegroundColor(IndexedColors.DARK_TEAL.getIndex());
        headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        headerStyle.setAlignment(HorizontalAlignment.CENTER);
        headerStyle.setVerticalAlignment(VerticalAlignment.CENTER);
        headerStyle.setBorderBottom(BorderStyle.THIN);
        headerStyle.setBorderTop(BorderStyle.THIN);
        headerStyle.setBorderLeft(BorderStyle.THIN);
        headerStyle.setBorderRight(BorderStyle.THIN);
        headerStyle.setWrapText(true);
        headerStyle.setLocked(true);
        styles.put("header", headerStyle);

        // Date header style
        CellStyle dateHeaderStyle = workbook.createCellStyle();
        dateHeaderStyle.cloneStyleFrom(headerStyle);
        dateHeaderStyle.setFillForegroundColor(IndexedColors.ROYAL_BLUE.getIndex());
        dateHeaderStyle.setLocked(true);
        styles.put("dateHeader", dateHeaderStyle);

        // Locked cell style (read-only)
        CellStyle lockedStyle = workbook.createCellStyle();
        lockedStyle.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        lockedStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        lockedStyle.setBorderBottom(BorderStyle.THIN);
        lockedStyle.setBorderTop(BorderStyle.THIN);
        lockedStyle.setBorderLeft(BorderStyle.THIN);
        lockedStyle.setBorderRight(BorderStyle.THIN);
        lockedStyle.setAlignment(HorizontalAlignment.LEFT);
        lockedStyle.setLocked(true); // LOCKED
        styles.put("locked", lockedStyle);

        // Editable cell style (user input)
        CellStyle editableStyle = workbook.createCellStyle();
        editableStyle.setBorderBottom(BorderStyle.THIN);
        editableStyle.setBorderTop(BorderStyle.THIN);
        editableStyle.setBorderLeft(BorderStyle.THIN);
        editableStyle.setBorderRight(BorderStyle.THIN);
        editableStyle.setAlignment(HorizontalAlignment.RIGHT);
        editableStyle.setLocked(false); // UNLOCKED
        styles.put("editable", editableStyle);

        // Formula cell style (auto-calculated)
        CellStyle formulaStyle = workbook.createCellStyle();
        formulaStyle.setFillForegroundColor(IndexedColors.LIGHT_GREEN.getIndex());
        formulaStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        formulaStyle.setBorderBottom(BorderStyle.THIN);
        formulaStyle.setBorderTop(BorderStyle.THIN);
        formulaStyle.setBorderLeft(BorderStyle.THIN);
        formulaStyle.setBorderRight(BorderStyle.THIN);
        formulaStyle.setAlignment(HorizontalAlignment.RIGHT);
        Font formulaFont = workbook.createFont();
        formulaFont.setItalic(true);
        formulaFont.setColor(IndexedColors.DARK_BLUE.getIndex());
        formulaStyle.setFont(formulaFont);
        formulaStyle.setLocked(true); // LOCKED
        styles.put("formula", formulaStyle);

        return styles;
    }

    /**
     * Create instructions for horizontal consumption template
     */
    private void createHorizontalConsumptionInstructions(XSSFWorkbook workbook, YearMonth yearMonth,
                                                         int itemCount) {
        XSSFSheet sheet = workbook.createSheet("Instructions");
        sheet.setColumnWidth(0, 15000);

        CellStyle titleStyle = createTitleStyle(workbook);
        CellStyle normalStyle = createDataStyle(workbook);

        int rowNum = 0;

        // Title
        Row titleRow = sheet.createRow(rowNum++);
        Cell titleCell = titleRow.createCell(0);
        titleCell.setCellValue("Horizontal Consumption Template - Instructions");
        titleCell.setCellStyle(titleStyle);
        rowNum++;

        // Instructions
        String[] instructions = {
                "TEMPLATE OVERVIEW:",
                "- Horizontal format: One row per item, columns for each day of the month",
                "- Sheet is PROTECTED: Only specific cells can be edited",
                "- Auto-calculation: Closing Stock = Opening + Received - Total Daily Consumption",
                "",
                "HOW TO USE:",
                "1. CHANGE MONTH (Optional):",
                "   - Click on cell B1 (blue cell)",
                "   - Select month from dropdown (format: yyyy-MM)",
                "   - All date columns will update automatically",
                "",
                "2. ENTER OPENING STOCK:",
                "   - Column E: Opening Stock (editable - white cells)",
                "   - Pre-filled with current stock, adjust if needed",
                "",
                "3. ENTER TOTAL RECEIVED:",
                "   - Column F: Total Received during the month (editable)",
                "   - Enter total quantity received, not daily",
                "",
                "4. ENTER DAILY CONSUMPTION:",
                "   - Columns G onwards: Daily consumption for each day (editable)",
                "   - Enter consumed quantity for each day",
                "   - Leave as 0 if no consumption",
                "",
                "5. CLOSING STOCK AUTO-CALCULATED:",
                "   - Last column: Closing Stock (green - locked)",
                "   - Formula: Opening + Received - SUM(all daily consumption)",
                "   - DO NOT edit this column",
                "",
                "COLOR CODING:",
                "- Blue cell (B1): Month selector - EDITABLE via dropdown",
                "- Gray cells: Fixed data (Category, Item, UOM, Price) - LOCKED",
                "- White cells: User input (Opening, Received, Daily Consumption) - EDITABLE",
                "- Green cells: Auto-calculated (Closing Stock) - LOCKED",
                "",
                "IMPORTANT NOTES:",
                "- Sheet is protected with password: 'inventory2025'",
                "- Only white cells and month selector can be edited",
                "- Negative closing stock = stock shortage (review entries)",
                "- Opening + Received should be >= Total Consumption",
                "- Month change updates all date headers automatically",
                "",
                "VALIDATION:",
                "- If Closing Stock is negative, review your daily consumption entries",
                "- Ensure Opening Stock is accurate at start of month",
                "- Total Received should reflect all stock received during the month",
                "",
                "UPLOAD:",
                "1. Complete all daily consumption entries",
                "2. Verify closing stock values are correct",
                "3. Save the file",
                "4. Upload via the file upload interface",
                "5. System will validate and import the data",
                "",
                "TEMPLATE INFO:",
                "- Default Month: " + yearMonth.format(DateTimeFormatter.ofPattern("MMMM yyyy")),
                "- Days in Month: " + yearMonth.lengthOfMonth(),
                "- Total Items: " + itemCount,
                "- Format: Horizontal (one row per item)",
                "",
                "TROUBLESHOOTING:",
                "- Cannot edit cell? Check if it's gray/green (locked)",
                "- Dates not updating? Ensure B1 has valid yyyy-MM format",
                "- Formula error? Do not delete or modify formula cells",
                "- Need help? Contact system administrator"
        };

        for (String instruction : instructions) {
            Row row = sheet.createRow(rowNum++);
            Cell cell = row.createCell(0);
            cell.setCellValue(instruction);

            if (instruction.endsWith(":")) {
                CellStyle boldStyle = workbook.createCellStyle();
                Font boldFont = workbook.createFont();
                boldFont.setBold(true);
                boldStyle.setFont(boldFont);
                cell.setCellStyle(boldStyle);
            } else {
                cell.setCellStyle(normalStyle);
            }
        }
    }

    /**
     * Get next consumption date (max consumption date + 1 day)
     */
    private LocalDate getNextConsumptionDate() {
        try {
            Optional<LocalDate> maxDate = consumptionRecordRepository.findAll().stream()
                    .map(record -> record.getConsumptionDate())
                    .max(Comparator.naturalOrder());

            if (maxDate.isPresent()) {
                LocalDate nextDate = maxDate.get().plusDays(1);
                logger.info("Last consumption date: {}, starting from: {}", maxDate.get(), nextDate);
                return nextDate;
            } else {
                logger.info("No consumption records found, starting from today");
                return LocalDate.now();
            }
        } catch (Exception e) {
            logger.warn("Error getting max consumption date, defaulting to today: {}", e.getMessage());
            return LocalDate.now();
        }
    }

    /**
     * Get Excel column letter from index (0-based)
     */
    private String getColumnLetter(int columnIndex) {
        StringBuilder columnLetter = new StringBuilder();
        while (columnIndex >= 0) {
            columnLetter.insert(0, (char) ('A' + (columnIndex % 26)));
            columnIndex = (columnIndex / 26) - 1;
        }
        return columnLetter.toString();
    }

    // ==================== EXISTING METHODS (UNCHANGED) ====================

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

    private void createItemsInstructionsSheet(XSSFWorkbook workbook) {
        XSSFSheet sheet = workbook.createSheet("Instructions");
        CellStyle titleStyle = createTitleStyle(workbook);
        CellStyle normalStyle = createDataStyle(workbook);

        int rowNum = 0;

        Row titleRow = sheet.createRow(rowNum++);
        Cell titleCell = titleRow.createCell(0);
        titleCell.setCellValue("Items Upload Template - Instructions");
        titleCell.setCellStyle(titleStyle);
        rowNum++;

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
        style.setLocked(false);
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