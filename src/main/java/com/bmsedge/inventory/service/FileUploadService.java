package com.bmsedge.inventory.service;

import com.bmsedge.inventory.dto.CategoryRequest;
import com.bmsedge.inventory.dto.ItemRequest;
import com.bmsedge.inventory.dto.ItemResponse;
import com.bmsedge.inventory.model.Category;
import com.bmsedge.inventory.repository.CategoryRepository;
import com.bmsedge.inventory.exception.BusinessException;
import com.opencsv.CSVReader;
import com.opencsv.exceptions.CsvException;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;

@Service
public class FileUploadService {

    @Autowired
    private ItemService itemService;

    @Autowired
    private CategoryService categoryService;

    @Autowired
    private CategoryRepository categoryRepository;

    public Map<String, Object> uploadItemsFromFile(MultipartFile file, Long userId) throws IOException {
        Map<String, Object> result = new HashMap<>();
        String fileName = file.getOriginalFilename();

        if (fileName == null) {
            throw new BusinessException("File name cannot be null");
        }

        if (file.isEmpty()) {
            throw new BusinessException("File cannot be empty");
        }

        List<ItemRequest> itemRequests = new ArrayList<>();
        List<String> parseErrors = new ArrayList<>();
        int totalRows = 0;
        int successfullyParsed = 0;

        try {
            if (fileName.toLowerCase().endsWith(".csv")) {
                Map<String, Object> parseResult = parseCSVFile(file, parseErrors);
                itemRequests = (List<ItemRequest>) parseResult.get("items");
                totalRows = (Integer) parseResult.get("totalRows");
                successfullyParsed = itemRequests.size();
            } else if (fileName.toLowerCase().endsWith(".xlsx") || fileName.toLowerCase().endsWith(".xls")) {
                Map<String, Object> parseResult = parseExcelFileFixed(file, parseErrors);
                itemRequests = (List<ItemRequest>) parseResult.get("items");
                totalRows = (Integer) parseResult.get("totalRows");
                successfullyParsed = itemRequests.size();
            } else {
                throw new BusinessException("Unsupported file format. Please upload CSV (.csv) or Excel (.xlsx, .xls) files only.");
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("Error processing file: " + e.getMessage());
        }

        // Prepare detailed response
        result.put("totalRowsProcessed", totalRows);
        result.put("successfullyParsed", successfullyParsed);
        result.put("parseErrors", parseErrors);

        if (itemRequests.isEmpty()) {
            result.put("success", false);
            result.put("message", "No valid items found in the file. " +
                    (parseErrors.isEmpty() ? "Please check the format and content." :
                            "Errors: " + String.join("; ", parseErrors.subList(0, Math.min(3, parseErrors.size())))));
            return result;
        }

        // Now create items
        List<ItemResponse> createdItems = new ArrayList<>();
        List<String> creationErrors = new ArrayList<>();

        for (int i = 0; i < itemRequests.size(); i++) {
            try {
                ItemRequest itemRequest = itemRequests.get(i);
                validateAndFixItemRequest(itemRequest);
                ItemResponse itemResponse = itemService.createItem(itemRequest, userId);
                createdItems.add(itemResponse);
            } catch (Exception e) {
                String errorMsg = "Row " + (i + 2) + ": " + e.getMessage();
                creationErrors.add(errorMsg);
                System.err.println(errorMsg);
            }
        }

        // Build response
        result.put("success", !createdItems.isEmpty());
        result.put("itemsCreated", createdItems.size());
        result.put("items", createdItems);
        result.put("creationErrors", creationErrors);

        if (!createdItems.isEmpty()) {
            result.put("message", String.format("Successfully created %d out of %d items",
                    createdItems.size(), itemRequests.size()));
        } else {
            result.put("message", "Failed to create any items. Check errors for details.");
        }

        return result;
    }

    /**
     * FIXED: Parse Excel file with correct column mappings for your inventory sheet structure
     */
    private Map<String, Object> parseExcelFileFixed(MultipartFile file, List<String> errors) throws IOException {
        Map<String, Object> result = new HashMap<>();
        List<ItemRequest> allItems = new ArrayList<>();
        int totalRows = 0;

        // Process all monthly sheets
        String[] sheetNames = {"Jan 25", "Feb 25", " Mar 25", " Apr 25", " May 25"};

        try (Workbook workbook = new XSSFWorkbook(file.getInputStream())) {
            for (String sheetName : sheetNames) {
                try {
                    Sheet sheet = workbook.getSheet(sheetName);
                    if (sheet == null) {
                        System.out.println("Sheet not found: " + sheetName);
                        continue;
                    }

                    System.out.println("Processing sheet: " + sheetName);

                    // Header is at row 1 (index 1), data starts at row 2 (index 2)
                    Row headerRow = sheet.getRow(1);
                    if (headerRow == null) {
                        errors.add("No header row in sheet: " + sheetName);
                        continue;
                    }

                    // Find column indices based on header names
                    int categoryCol = findColumnByName(headerRow, "Category");
                    int itemDescCol = findColumnByName(headerRow, "ITEM DESCRIPTION");
                    int uomCol = findColumnByName(headerRow, "UOM");
                    int priceCol = findColumnByName(headerRow, "Price");
                    int openingStockCol = findColumnByName(headerRow, "Opening Stock");
                    int totalStockCol = findColumnByName(headerRow, "Total Stock");

                    // Find SIH column (Stock in Hand) - this is usually the last or second-to-last column
                    int sihCol = -1;
                    for (int i = headerRow.getLastCellNum() - 1; i >= 0; i--) {
                        Cell cell = headerRow.getCell(i);
                        if (cell != null) {
                            String value = getCellValueAsString(cell);
                            if (value != null && value.trim().equalsIgnoreCase("SIH")) {
                                sihCol = i;
                                break;
                            }
                        }
                    }

                    System.out.println("Column indices - Category: " + categoryCol + ", Item: " + itemDescCol +
                            ", UOM: " + uomCol + ", Total Stock: " + totalStockCol + ", SIH: " + sihCol);

                    // Process data rows (starting from row 2, index 2)
                    for (int i = 2; i <= sheet.getLastRowNum(); i++) {
                        Row row = sheet.getRow(i);
                        if (row == null || isRowEmpty(row)) {
                            continue;
                        }

                        totalRows++;

                        try {
                            String category = getCellValueAsString(row.getCell(categoryCol));
                            String itemDesc = getCellValueAsString(row.getCell(itemDescCol));
                            String uom = getCellValueAsString(row.getCell(uomCol));

                            // Skip total rows or empty item descriptions
                            if (itemDesc == null || itemDesc.trim().isEmpty() ||
                                    itemDesc.toLowerCase().contains("total") ||
                                    category == null || category.trim().isEmpty()) {
                                continue;
                            }

                            // Get current quantity - prefer SIH over Total Stock
                            BigDecimal currentQty = BigDecimal.ZERO;
                            if (sihCol >= 0) {
                                currentQty = getCellValueAsBigDecimal(row.getCell(sihCol));
                            }
                            if (currentQty.compareTo(BigDecimal.ZERO) == 0 && totalStockCol >= 0) {
                                currentQty = getCellValueAsBigDecimal(row.getCell(totalStockCol));
                            }

                            // Get opening stock
                            BigDecimal openingStock = BigDecimal.ZERO;
                            if (openingStockCol >= 0) {
                                openingStock = getCellValueAsBigDecimal(row.getCell(openingStockCol));
                            }

                            // Get price
                            BigDecimal price = null;
                            if (priceCol >= 0) {
                                price = getCellValueAsBigDecimal(row.getCell(priceCol));
                            }

                            // Create ItemRequest
                            ItemRequest itemRequest = new ItemRequest();
                            itemRequest.setItemName(itemDesc.trim());
                            itemRequest.setItemDescription(category + " - " + itemDesc);
                            itemRequest.setCategoryId(findOrCreateCategory(category));
                            itemRequest.setCurrentQuantity(currentQty.intValue());
                            itemRequest.setOldStockQuantity(openingStock.intValue());
                            itemRequest.setUnitOfMeasurement(uom != null && !uom.trim().isEmpty() ? uom.trim() : "pcs");

                            // Set min/max stock levels based on current quantity
                            int minStock = Math.max(5, currentQty.intValue() / 10);
                            int maxStock = Math.max(100, currentQty.intValue() * 2);


                            if (price != null && price.compareTo(BigDecimal.ZERO) > 0) {
                                itemRequest.setUnitPrice(price);
                            }

                            allItems.add(itemRequest);

                        } catch (Exception e) {
                            errors.add("Sheet " + sheetName + ", Row " + (i + 1) + ": " + e.getMessage());
                        }
                    }

                } catch (Exception e) {
                    errors.add("Error processing sheet " + sheetName + ": " + e.getMessage());
                    System.err.println("Error in sheet " + sheetName + ": " + e.getMessage());
                }
            }
        }

        // Remove duplicates - keep only the latest entry for each item
        Map<String, ItemRequest> uniqueItems = new HashMap<>();
        for (ItemRequest item : allItems) {
            String key = item.getItemName() + "_" + item.getCategoryId();
            uniqueItems.put(key, item);
        }

        result.put("items", new ArrayList<>(uniqueItems.values()));
        result.put("totalRows", totalRows);
        System.out.println("Total unique items found: " + uniqueItems.size());

        return result;
    }

    private int findColumnByName(Row headerRow, String columnName) {
        for (Cell cell : headerRow) {
            String cellValue = getCellValueAsString(cell);
            if (cellValue != null && cellValue.trim().equalsIgnoreCase(columnName.trim())) {
                return cell.getColumnIndex();
            }
            // Handle variations in column names
            if (columnName.equals("Opening Stock") && cellValue != null &&
                    cellValue.trim().toLowerCase().contains("opening")) {
                return cell.getColumnIndex();
            }
        }
        return -1;
    }

    private BigDecimal getCellValueAsBigDecimal(Cell cell) {
        if (cell == null) return BigDecimal.ZERO;

        try {
            switch (cell.getCellType()) {
                case NUMERIC:
                    return BigDecimal.valueOf(cell.getNumericCellValue());
                case STRING:
                    String strValue = cell.getStringCellValue().trim();
                    if (strValue.isEmpty()) return BigDecimal.ZERO;
                    // Remove any non-numeric characters except decimal point
                    strValue = strValue.replaceAll("[^0-9.-]", "");
                    if (strValue.isEmpty()) return BigDecimal.ZERO;
                    return new BigDecimal(strValue);
                case FORMULA:
                    try {
                        return BigDecimal.valueOf(cell.getNumericCellValue());
                    } catch (Exception e) {
                        return BigDecimal.ZERO;
                    }
                case BLANK:
                    return BigDecimal.ZERO;
                default:
                    return BigDecimal.ZERO;
            }
        } catch (Exception e) {
            return BigDecimal.ZERO;
        }
    }

    private boolean isRowEmpty(Row row) {
        for (int cellNum = 0; cellNum < row.getLastCellNum(); cellNum++) {
            Cell cell = row.getCell(cellNum);
            if (cell != null && !getCellValueAsString(cell).trim().isEmpty()) {
                return false;
            }
        }
        return true;
    }

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

    private Long findOrCreateCategory(String categoryName) {
        try {
            Optional<Category> categoryOpt = categoryRepository.findByCategoryNameIgnoreCase(categoryName);

            if (categoryOpt.isPresent()) {
                return categoryOpt.get().getId();
            } else {
                // Create new category
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

    private void validateAndFixItemRequest(ItemRequest itemRequest) {
        // Fix reorder level
        if (itemRequest.getReorderLevel() == null ||
                itemRequest.getReorderLevel().compareTo(BigDecimal.ZERO) == 0) {

            Integer currentQty = itemRequest.getCurrentQuantity();
            int fallbackLevel = (currentQty != null) ? Math.max(currentQty / 10, 5) : 5;
            itemRequest.setReorderLevel(BigDecimal.valueOf(fallbackLevel));
        }

        // Fix stock alert level
        if (itemRequest.getStockAlertLevel() == null || itemRequest.getStockAlertLevel().trim().isEmpty()) {
            if (itemRequest.getCurrentQuantity() != null && itemRequest.getReorderLevel() != null) {
                BigDecimal currentQtyBD = BigDecimal.valueOf(itemRequest.getCurrentQuantity());
                if (currentQtyBD.compareTo(itemRequest.getReorderLevel()) <= 0) {
                    itemRequest.setStockAlertLevel("WARNING");
                } else {
                    itemRequest.setStockAlertLevel("NORMAL");
                }
            } else {
                itemRequest.setStockAlertLevel("NORMAL");
            }
        }

        // Fix unit of measurement
        if (itemRequest.getUnitOfMeasurement() == null || itemRequest.getUnitOfMeasurement().trim().isEmpty()) {
            itemRequest.setUnitOfMeasurement("pcs");
        }

        // Ensure current quantity is not null
        if (itemRequest.getCurrentQuantity() == null) {
            itemRequest.setCurrentQuantity(0);
        }
    }



    // Keep the CSV parsing method as is
    private Map<String, Object> parseCSVFile(MultipartFile file, List<String> errors) throws IOException {
        // Your existing CSV parsing logic
        Map<String, Object> result = new HashMap<>();
        List<ItemRequest> itemRequests = new ArrayList<>();
        result.put("items", itemRequests);
        result.put("totalRows", 0);
        return result;
    }

    public Map<String, Object> getUploadTemplate() {
        Map<String, Object> template = new HashMap<>();
        template.put("format", "Excel (.xlsx) with monthly sheets or CSV");
        template.put("excelStructure", "Sheets: Jan 25, Feb 25, Mar 25, etc.");
        template.put("requiredColumns", new String[]{
                "Category",
                "ITEM DESCRIPTION",
                "UOM",
                "Total Stock or SIH"
        });
        template.put("notes", "The service reads from monthly sheets and uses SIH (Stock in Hand) or Total Stock as current quantity");
        return template;
    }
}