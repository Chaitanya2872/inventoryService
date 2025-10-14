package com.bmsedge.inventory.service;

import com.bmsedge.inventory.dto.CategoryRequest;
import com.bmsedge.inventory.dto.ItemRequest;
import com.bmsedge.inventory.dto.ItemResponse;
import com.bmsedge.inventory.exception.BusinessException;
import com.bmsedge.inventory.model.Category;
import com.bmsedge.inventory.repository.CategoryRepository;
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
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class ItemsUploadService {

    private static final Logger logger = LoggerFactory.getLogger(ItemsUploadService.class);

    @Autowired
    private ItemService itemService;

    @Autowired
    private CategoryService categoryService;

    @Autowired
    private CategoryRepository categoryRepository;


    public Map<String, Object> uploadItemsFromFile(MultipartFile file, Long userId) throws IOException {
        Map<String, Object> result = new HashMap<>();
        String fileName = file.getOriginalFilename();

        if (fileName == null || file.isEmpty()) {
            throw new BusinessException("File cannot be empty");
        }

        if (!fileName.toLowerCase().endsWith(".xlsx") && !fileName.toLowerCase().endsWith(".xls")) {
            throw new BusinessException("Only Excel files (.xlsx, .xls) are supported");
        }

        List<ItemRequest> itemRequests = new ArrayList<>();
        List<String> parseErrors = new ArrayList<>();
        int totalRows = 0;

        // Parse Excel file
        try {
            Map<String, Object> parseResult = parseExcelFlexible(file, parseErrors);
            itemRequests = (List<ItemRequest>) parseResult.get("items");
            totalRows = (Integer) parseResult.get("totalRows");
        } catch (Exception e) {
            throw new BusinessException("Error parsing file: " + e.getMessage());
        }

        result.put("totalRowsProcessed", totalRows);
        result.put("successfullyParsed", itemRequests.size());
        result.put("parseErrors", parseErrors);

        if (itemRequests.isEmpty()) {
            result.put("success", false);
            result.put("message", "No valid items found in file. Check format and content.");
            return result;
        }

        // Create items - each item creation is in its own transaction
        List<ItemResponse> createdItems = new ArrayList<>();
        List<String> creationErrors = new ArrayList<>();

        for (int i = 0; i < itemRequests.size(); i++) {
            try {
                ItemRequest itemRequest = itemRequests.get(i);
                validateAndFixItemRequest(itemRequest);

                // This method has @Transactional, so each item gets its own transaction
                ItemResponse itemResponse = itemService.createItem(itemRequest, userId);
                createdItems.add(itemResponse);

            } catch (Exception e) {
                String errorMsg = String.format("Row %d (%s %s): %s",
                        i + 2,
                        itemRequests.get(i).getItemName(),
                        itemRequests.get(i).getItemSku() != null ? "(" + itemRequests.get(i).getItemSku() + ")" : "",
                        e.getMessage());
                creationErrors.add(errorMsg);
                logger.error(errorMsg, e);
            }
        }

        result.put("success", !createdItems.isEmpty());
        result.put("itemsCreated", createdItems.size());
        result.put("items", createdItems);
        result.put("creationErrors", creationErrors);

        if (!createdItems.isEmpty()) {
            result.put("message", String.format(
                    "Successfully created %d out of %d items",
                    createdItems.size(), itemRequests.size()));
        } else {
            result.put("message", "Failed to create any items. Check errors for details.");
        }

        return result;
    }

    /**
     * FLEXIBLE EXCEL PARSER - Works with any Excel structure
     * Automatically detects headers and adapts to different formats
     * WITH SKU SUPPORT
     */
    private Map<String, Object> parseExcelFlexible(MultipartFile file, List<String> errors) throws IOException {
        Map<String, Object> result = new HashMap<>();
        List<ItemRequest> allItems = new ArrayList<>();
        int totalRows = 0;

        try (Workbook workbook = new XSSFWorkbook(file.getInputStream())) {
            logger.info("========================================");
            logger.info("FLEXIBLE EXCEL PARSER WITH SKU SUPPORT");
            logger.info("Total sheets: {}", workbook.getNumberOfSheets());
            logger.info("========================================");

            // Process ALL sheets automatically
            for (int sheetIdx = 0; sheetIdx < workbook.getNumberOfSheets(); sheetIdx++) {
                Sheet sheet = workbook.getSheetAt(sheetIdx);
                String sheetName = sheet.getSheetName();

                logger.info("Processing sheet: '{}'", sheetName);

                if (sheet.getPhysicalNumberOfRows() == 0) {
                    logger.warn("  ⚠ Skipping empty sheet");
                    continue;
                }

                try {
                    // Auto-detect structure
                    SheetStructure structure = analyzeSheetStructure(sheet);

                    if (!structure.isValid) {
                        logger.warn("  ⚠ Skipping invalid sheet: {}", structure.errorMessage);
                        errors.add("Sheet '" + sheetName + "': " + structure.errorMessage);
                        continue;
                    }

                    logger.info("  ✓ Structure: {}", structure.type);
                    logger.info("  ✓ Header row: {}", structure.headerRow);
                    logger.info("  ✓ Data starts at row: {}", structure.dataStartRow);
                    logger.info("  ✓ Columns: {}", structure.columnMap.keySet());

                    // Parse data based on detected structure
                    int sheetTotal = 0;
                    for (int rowIdx = structure.dataStartRow; rowIdx <= sheet.getLastRowNum(); rowIdx++) {
                        Row row = sheet.getRow(rowIdx);
                        if (row == null || isRowEmpty(row)) continue;

                        try {
                            ItemRequest item = parseRowFlexible(row, structure, sheetName);
                            if (item != null) {
                                allItems.add(item);
                                sheetTotal++;
                                totalRows++;
                            }
                        } catch (Exception e) {
                            errors.add(String.format("Sheet '%s' Row %d: %s",
                                    sheetName, rowIdx + 1, e.getMessage()));
                        }
                    }

                    logger.info("  ✓ Parsed {} items from this sheet", sheetTotal);

                } catch (Exception e) {
                    errors.add("Error processing sheet '" + sheetName + "': " + e.getMessage());
                    logger.error("  ✗ Error: {}", e.getMessage());
                }
            }

            logger.info("========================================");
            logger.info("Total items parsed: {}", allItems.size());
            logger.info("========================================");

        } catch (Exception e) {
            throw new IOException("Failed to read Excel file: " + e.getMessage(), e);
        }

        // Remove duplicates (by name + SKU + category)
        Map<String, ItemRequest> uniqueItems = new LinkedHashMap<>();
        for (ItemRequest item : allItems) {
            String sku = item.getItemSku() != null ? item.getItemSku() : "";
            String key = item.getItemName() + "_" + sku + "_" + item.getCategoryId();
            if (!uniqueItems.containsKey(key)) {
                uniqueItems.put(key, item);
            }
        }

        result.put("items", new ArrayList<>(uniqueItems.values()));
        result.put("totalRows", totalRows);
        return result;
    }

    /**
     * Analyze sheet structure and auto-detect format
     * DETECTS: category, item name, SKU, UOM, price, stock quantities
     */
    private SheetStructure analyzeSheetStructure(Sheet sheet) {
        SheetStructure structure = new SheetStructure();

        // Check first 5 rows to find headers
        for (int rowIdx = 0; rowIdx < Math.min(5, sheet.getPhysicalNumberOfRows()); rowIdx++) {
            Row row = sheet.getRow(rowIdx);
            if (row == null) continue;

            int headerScore = 0;
            Map<String, Integer> columnMap = new HashMap<>();

            for (Cell cell : row) {
                String value = getCellValueAsString(cell);
                if (value == null || value.trim().isEmpty()) continue;

                String lower = value.toLowerCase().trim();

                // Category detection
                if (lower.contains("category") || lower.equals("cat")) {
                    columnMap.put("category", cell.getColumnIndex());
                    headerScore += 3;
                }

                // Item name detection
                if (lower.equals("item") ||
                        lower.equals("item_name") ||
                        lower.equals("itemname") ||
                        (lower.contains("item") && lower.contains("name")) ||
                        (lower.contains("item") && lower.contains("description")) ||
                        (lower.contains("item") && lower.contains("code"))) {
                    if (!lower.contains("sku")) {
                        columnMap.put("item", cell.getColumnIndex());
                        headerScore += 3;
                    }
                }

                // SKU detection
                if (lower.equals("sku") ||
                        lower.equals("item_sku") ||
                        lower.equals("itemsku") ||
                        (lower.contains("sku") && lower.contains("item"))) {
                    columnMap.put("sku", cell.getColumnIndex());
                    headerScore += 2;
                }

                // UOM detection
                if (lower.contains("uom") ||
                        lower.equals("unit") ||
                        lower.contains("measurement")) {
                    columnMap.put("uom", cell.getColumnIndex());
                    headerScore += 2;
                }

                // Price detection
                if (lower.contains("price") ||
                        lower.contains("rate") ||
                        lower.contains("cost")) {
                    if (!lower.contains("consumption")) { // Avoid consumption_cost
                        columnMap.put("price", cell.getColumnIndex());
                        headerScore += 2;
                    }
                }

                // Stock detection
                if (lower.contains("stock") ||
                        lower.contains("quantity") ||
                        lower.equals("qty")) {
                    if (lower.contains("opening") || lower.contains("open")) {
                        columnMap.put("openingStock", cell.getColumnIndex());
                    } else if (lower.contains("closing") || lower.contains("close") || lower.contains("sih")) {
                        columnMap.put("closingStock", cell.getColumnIndex());
                    } else if (lower.contains("current") || lower.contains("total")) {
                        columnMap.put("currentStock", cell.getColumnIndex());
                    }
                    headerScore += 2;
                }

                // Reorder level detection
                if (lower.contains("reorder") && lower.contains("level")) {
                    columnMap.put("reorderLevel", cell.getColumnIndex());
                    headerScore += 1;
                }
            }

            // Valid if we found enough header keywords
            if (headerScore >= 5 && columnMap.containsKey("category") && columnMap.containsKey("item")) {
                structure.isValid = true;
                structure.type = "HEADER_BASED";
                structure.headerRow = rowIdx;
                structure.dataStartRow = rowIdx + 1;
                structure.columnMap = columnMap;
                return structure;
            }
        }

        structure.isValid = false;
        structure.errorMessage = "Could not detect valid structure. Required: Category and Item Name columns";
        return structure;
    }

    /**
     * Parse a row using detected structure
     * EXTRACTS: category, item name, SKU (from column or item name), UOM, price, stock
     */
    private ItemRequest parseRowFlexible(Row row, SheetStructure structure, String sheetName) {
        String category = getColumnValue(row, structure.columnMap, "category");
        String itemName = getColumnValue(row, structure.columnMap, "item");
        String itemSku = getColumnValue(row, structure.columnMap, "sku");
        String uom = getColumnValue(row, structure.columnMap, "uom");

        // Skip if essential fields are missing
        if ((category == null || category.trim().isEmpty()) &&
                (itemName == null || itemName.trim().isEmpty())) {
            return null;
        }

        // Skip total rows
        if (category != null && category.toLowerCase().contains("total")) return null;
        if (itemName != null && itemName.toLowerCase().contains("total")) return null;

        // Get numeric values
        BigDecimal price = getColumnValueAsNumber(row, structure.columnMap, "price");
        BigDecimal openingStock = getColumnValueAsNumber(row, structure.columnMap, "openingStock");
        BigDecimal currentStock = getColumnValueAsNumber(row, structure.columnMap, "currentStock");
        BigDecimal closingStock = getColumnValueAsNumber(row, structure.columnMap, "closingStock");
        BigDecimal reorderLevel = getColumnValueAsNumber(row, structure.columnMap, "reorderLevel");

        // Determine current quantity (priority: current > closing > opening)
        BigDecimal currentQty = BigDecimal.ZERO;
        if (currentStock != null && currentStock.compareTo(BigDecimal.ZERO) > 0) {
            currentQty = currentStock;
        } else if (closingStock != null && closingStock.compareTo(BigDecimal.ZERO) > 0) {
            currentQty = closingStock;
        } else if (openingStock != null && openingStock.compareTo(BigDecimal.ZERO) > 0) {
            currentQty = openingStock;
        }

        // Clean up item name
        if (itemName == null || itemName.trim().isEmpty()) {
            itemName = category + " Item";
        }
        itemName = itemName.trim();

        // Extract SKU from item name if not provided in separate column
        if ((itemSku == null || itemSku.trim().isEmpty()) && itemName != null) {
            itemSku = extractSkuFromName(itemName);
            if (itemSku != null) {
                // Clean the item name by removing the SKU part
                itemName = itemName.replace(" " + itemSku, "")
                        .replace("-" + itemSku, "")
                        .replace(itemSku, "")
                        .trim();
                logger.debug("Extracted SKU '{}' from item name, cleaned name: '{}'", itemSku, itemName);
            }
        }

        // Clean up SKU
        if (itemSku != null) {
            itemSku = itemSku.trim();
            if (itemSku.isEmpty()) {
                itemSku = null;
            }
        }

        // Clean up UOM
        if (uom != null) {
            uom = normalizeUom(uom);
        } else {
            uom = "pcs";
        }

        // Build item code
        String itemCode = null;
        if (category != null && !category.isEmpty()) {
            String catPrefix = category.substring(0, Math.min(3, category.length())).toUpperCase();
            String itemPrefix = itemName.substring(0, Math.min(3, itemName.length())).toUpperCase();
            itemCode = catPrefix + "-" + itemPrefix;
            if (itemSku != null) {
                itemCode += "-" + itemSku.replaceAll("[^a-zA-Z0-9]", "");
            }
        }

        // Create ItemRequest
        ItemRequest itemRequest = new ItemRequest();
        itemRequest.setItemCode(itemCode);
        itemRequest.setItemName(itemName);
        itemRequest.setItemSku(itemSku);

        String description = category + " - " + itemName;
        if (itemSku != null) {
            description += " (" + itemSku + ")";
        }
        description += " [Sheet: " + sheetName + "]";
        itemRequest.setItemDescription(description);

        itemRequest.setCategoryId(findOrCreateCategory(category != null ? category : "Uncategorized"));
        itemRequest.setCurrentQuantity(currentQty.intValue());
        itemRequest.setUnitOfMeasurement(uom);

        if (price != null && price.compareTo(BigDecimal.ZERO) > 0) {
            itemRequest.setUnitPrice(price);
        }

        if (reorderLevel != null && reorderLevel.compareTo(BigDecimal.ZERO) > 0) {
            itemRequest.setReorderLevel(reorderLevel);
        }

        return itemRequest;
    }

    /**
     * Extract SKU from item name
     * Examples:
     * "Pril-Dishwash 125ml" → "125ml"
     * "Coffee Beans 500g" → "500g"
     * "Hand Soap (100ml)" → "100ml"
     * "(Nestle)Water Cups 150ml (50)" → "150ml (50)"
     */
    private String extractSkuFromName(String itemName) {
        if (itemName == null) return null;

        // Common SKU patterns
        String[] patterns = {
                "\\d+\\s*ml\\s*\\(\\d+\\)",     // 150ml (50)
                "\\(\\d+[a-zA-Z]+\\)",           // (125ml), (500g)
                "\\d+\\s*ml",                    // 125ml, 500 ml
                "\\d+\\s*ML",                    // 125ML
                "\\d+\\s*[lL]",                  // 1l, 2L
                "\\d+\\s*g",                     // 500g
                "\\d+\\s*kg",                    // 1kg
                "\\d+\\s*KG",                    // 1KG
                "\\d+\\s*oz",                    // 16oz
                "\\d+\\s*pcs?",                  // 10pcs, 20pc
                "\\d+\\s*pieces?",               // 10pieces
                "\\d+\\s*sachets?",              // 100 sachets
                "\\d+\\s*packets?",              // 200 packets
                "\\d+\\s*roll",                  // 12roll
                "\\d+[-]roll",                   // 12-roll
                "\\d+x\\d+[a-zA-Z]*"             // 12x500ml
        };

        for (String pattern : patterns) {
            Pattern p = Pattern.compile(pattern, Pattern.CASE_INSENSITIVE);
            Matcher m = p.matcher(itemName);
            if (m.find()) {
                String sku = m.group().trim();
                // Remove parentheses if present
                sku = sku.replaceAll("[()]", "").trim();
                return sku;
            }
        }

        return null;
    }

    /**
     * Normalize UOM to standard values
     */
    private String normalizeUom(String uom) {
        if (uom == null) return "pcs";

        uom = uom.trim();
        String lower = uom.toLowerCase();

        if (lower.equals("ltr") || lower.equals("litres") ||
                lower.equals("liters") || lower.equals("lts") || lower.equals("l")) {
            return "Liters";
        }

        if (lower.equals("ml") || lower.equals("milliliters") || lower.equals("millilitres")) {
            return "ml";
        }

        if (lower.equals("kg") || lower.equals("kgs") || lower.equals("kilograms")) {
            return "kg";
        }

        if (lower.equals("g") || lower.equals("gm") || lower.equals("gms") || lower.equals("grams")) {
            return "grams";
        }

        if (lower.equals("pc") || lower.equals("pcs") || lower.equals("piece") ||
                lower.equals("pieces") || lower.equals("nos") || lower.equals("numbers")) {
            return "pcs";
        }

        if (lower.equals("bottle") || lower.equals("bottles") || lower.equals("btl")) {
            return "Bottle";
        }

        if (lower.equals("packet") || lower.equals("packets") ||
                lower.equals("pkt") || lower.equals("pkts")) {
            return "Packet";
        }

        return uom;
    }

    /**
     * Find or create category
     */
    private Long findOrCreateCategory(String categoryName) {
        try {
            Optional<Category> categoryOpt = categoryRepository.findByCategoryNameIgnoreCase(categoryName);

            if (categoryOpt.isPresent()) {
                return categoryOpt.get().getId();
            } else {
                CategoryRequest categoryRequest = new CategoryRequest();
                categoryRequest.setCategoryName(categoryName);
                categoryRequest.setCategoryDescription("Auto-created from bulk upload");

                var categoryResponse = categoryService.createCategory(categoryRequest, 1L);
                return categoryResponse.getId();
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to find or create category '" + categoryName + "': " + e.getMessage());
        }
    }

    /**
     * Validate and fix item request
     */
    private void validateAndFixItemRequest(ItemRequest itemRequest) {
        // Validate reorder level
        if (itemRequest.getReorderLevel() == null ||
                itemRequest.getReorderLevel().compareTo(BigDecimal.ZERO) == 0) {
            Integer currentQty = itemRequest.getCurrentQuantity();
            int fallbackLevel = (currentQty != null) ? Math.max(currentQty / 10, 5) : 5;
            itemRequest.setReorderLevel(BigDecimal.valueOf(fallbackLevel));
        }

        // Set reorder quantity
        if (itemRequest.getReorderQuantity() == null) {
            BigDecimal reorderLevel = itemRequest.getReorderLevel();
            itemRequest.setReorderQuantity(reorderLevel != null ?
                    reorderLevel.multiply(BigDecimal.valueOf(5)) :
                    BigDecimal.valueOf(50));
        }

        // Validate UOM
        if (itemRequest.getUnitOfMeasurement() == null ||
                itemRequest.getUnitOfMeasurement().trim().isEmpty()) {
            itemRequest.setUnitOfMeasurement("pcs");
        }

        // Validate current quantity
        if (itemRequest.getCurrentQuantity() == null) {
            itemRequest.setCurrentQuantity(0);
        }

        // Validate item name
        if (itemRequest.getItemName() == null || itemRequest.getItemName().trim().isEmpty()) {
            throw new BusinessException("Item name cannot be empty");
        }
    }

    /**
     * Validate items file without importing
     */
    public Map<String, Object> validateItemsFile(MultipartFile file) throws IOException {
        Map<String, Object> validation = new HashMap<>();
        List<String> errors = new ArrayList<>();

        Map<String, Object> parseResult = parseExcelFlexible(file, errors);
        List<ItemRequest> items = (List<ItemRequest>) parseResult.get("items");

        validation.put("valid", errors.isEmpty() && !items.isEmpty());
        validation.put("totalItems", items.size());
        validation.put("parseErrors", errors);
        validation.put("message", String.format(
                "Found %d valid items. %d parse errors.",
                items.size(), errors.size()
        ));

        return validation;
    }

    /**
     * Get upload template information
     */
    public Map<String, Object> getUploadTemplate() {
        Map<String, Object> template = new HashMap<>();
        template.put("format", "Excel (.xlsx) - Any structure supported");
        template.put("autoDetection", true);
        template.put("requiredColumns", new String[]{
                "Category",
                "Item Name",
                "Stock quantity (Opening/Current/Closing)"
        });
        template.put("optionalColumns", new String[]{
                "Item SKU (for variants)",
                "UOM/Unit",
                "Price",
                "Reorder Level"
        });
        template.put("skuSupport", new String[]{
                "SKU in separate column OR embedded in item name",
                "Different SKUs = Different items",
                "Auto-extracts: 125ml, 500g, 1kg, 100pcs, etc."
        });
        return template;
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