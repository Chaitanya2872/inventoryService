package com.bmsedge.inventory.controller;

import com.bmsedge.inventory.service.ItemsUploadService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * REST Controller for bulk items import from Excel files
 * Supports flexible Excel formats with automatic SKU detection
 */
@RestController
@RequestMapping("/api/upload")
@CrossOrigin(origins = "*", maxAge = 3600)
public class ItemsUploadController {

    private static final Logger logger = LoggerFactory.getLogger(ItemsUploadController.class);

    @Autowired
    private ItemsUploadService itemsUploadService;

    /**
     * Bulk import items from Excel file
     * POST /api/upload/items
     *
     * Features:
     * - Auto-detects Excel structure (any column arrangement)
     * - Supports item SKU variants (125ml, 500ml, etc.)
     * - Multiple sheets support
     * - Auto-creates missing categories
     * - Different SKUs = Different items
     */
    @PostMapping("/items")
    public ResponseEntity<Map<String, Object>> uploadItems(
            @RequestParam("file") MultipartFile file,
            @RequestHeader(value = "X-User-Id", required = false, defaultValue = "1") Long userId) {

        logger.info("Received items upload request: file={}, size={}KB",
                file.getOriginalFilename(), file.getSize() / 1024);

        try {
            Map<String, Object> result = itemsUploadService.uploadItemsFromFile(file, userId);

            boolean success = (boolean) result.get("success");
            HttpStatus status = success ? HttpStatus.OK : HttpStatus.BAD_REQUEST;

            logger.info("Upload result: success={}, itemsCreated={}, errors={}",
                    success,
                    result.get("itemsCreated"),
                    ((java.util.List<?>) result.getOrDefault("creationErrors", java.util.Collections.emptyList())).size());

            return ResponseEntity.status(status).body(result);

        } catch (IOException e) {
            logger.error("File upload failed: {}", e.getMessage(), e);

            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("message", "Failed to process file: " + e.getMessage());
            errorResponse.put("error", e.getClass().getSimpleName());

            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }

    /**
     * Validate items file without importing
     * POST /api/upload/items/validate
     */
    @PostMapping("/items/validate")
    public ResponseEntity<Map<String, Object>> validateItemsFile(
            @RequestParam("file") MultipartFile file) {

        logger.info("Validating items file: {}", file.getOriginalFilename());

        try {
            Map<String, Object> validation = itemsUploadService.validateItemsFile(file);
            return ResponseEntity.ok(validation);

        } catch (IOException e) {
            logger.error("Validation failed: {}", e.getMessage(), e);

            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("valid", false);
            errorResponse.put("message", "Failed to validate file: " + e.getMessage());

            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorResponse);
        }
    }

    /**
     * Get upload template information
     * GET /api/upload/template
     */
    @GetMapping("/template")
    public ResponseEntity<Map<String, Object>> getUploadTemplate() {
        Map<String, Object> template = itemsUploadService.getUploadTemplate();
        return ResponseEntity.ok(template);
    }

    /**
     * Get upload instructions
     * GET /api/upload/instructions
     */
    @GetMapping("/instructions")
    public ResponseEntity<Map<String, Object>> getUploadInstructions() {
        Map<String, Object> instructions = new HashMap<>();

        instructions.put("title", "Bulk Items Upload Instructions");

        instructions.put("fileFormat", Map.of(
                "supported", new String[]{"Excel (.xlsx)", "Excel (.xls)"},
                "maxSize", "10MB",
                "encoding", "UTF-8"
        ));

        instructions.put("excelStructure", Map.of(
                "headerDetection", "Automatic - detects column names from any row",
                "multipleSheets", "Supported - all sheets are processed",
                "flexibleFormat", "Works with any column arrangement"
        ));

        instructions.put("requiredColumns", new String[]{
                "Category (or cat, category name, etc.)",
                "Item Name (or item, item description, item code, etc.)",
                "Stock Quantity (opening, current, closing, or SIH)"
        });

        instructions.put("optionalColumns", new String[]{
                "Item SKU - for variants (125ml, 500ml, 1L, etc.)",
                "UOM - unit of measurement (Bottle, Liters, pcs, etc.)",
                "Price - unit price",
                "Reorder Level - minimum stock level"
        });

        instructions.put("skuHandling", Map.of(
                "separateColumn", "Can be in 'Item SKU' or 'SKU' column",
                "inItemName", "Auto-extracts from item name (e.g., 'Pril-Dishwash 125ml')",
                "formats", new String[]{
                        "125ml, 500ml, 1L, 2L",
                        "100g, 500g, 1kg, 2kg",
                        "10pcs, 20pc, 100pieces",
                        "12-roll, 100 sachets, 200 packets",
                        "(125ml), (500g) - parentheses removed"
                },
                "behavior", "Items with same name but different SKU = separate items"
        ));

        instructions.put("examples", Map.of(
                "withSKU", Map.of(
                        "description", "Items with SKU variants",
                        "columns", "Category | Item Name | Item SKU | UOM | Price | Stock",
                        "example1", "HK Chemicals | Pril-Dishwash | 125ml | Bottle | 17.00 | 50",
                        "example2", "HK Chemicals | Pril-Dishwash | 500ml | Bottle | 52.00 | 30",
                        "note", "Creates 2 separate items"
                ),
                "skuInName", Map.of(
                        "description", "SKU embedded in item name",
                        "input", "Pril-Dishwash 125ml",
                        "result", "Item Name: 'Pril-Dishwash', SKU: '125ml'"
                )
        ));

        instructions.put("bestPractices", new String[]{
                "1. Use clear column headers in the first few rows",
                "2. Keep item names consistent across uploads",
                "3. Use SKU for variants (different sizes/packages)",
                "4. Remove empty rows and columns",
                "5. Test with small file first (5-10 items)",
                "6. Different SKUs will create separate items"
        });

        instructions.put("apiUsage", Map.of(
                "endpoint", "POST /api/upload/items",
                "method", "multipart/form-data",
                "parameter", "file (Excel file)",
                "header", "X-User-Id (optional, defaults to 1)",
                "response", "JSON with success status, created items count, and errors"
        ));

        return ResponseEntity.ok(instructions);
    }

    /**
     * Health check
     * GET /api/upload/health
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> healthCheck() {
        Map<String, String> response = new HashMap<>();
        response.put("status", "UP");
        response.put("service", "Items Upload Service");
        response.put("features", "SKU Support, Auto-detection, Multi-sheet");
        response.put("timestamp", java.time.LocalDateTime.now().toString());
        return ResponseEntity.ok(response);
    }

    /**
     * Get supported file formats
     * GET /api/upload/formats
     */
    @GetMapping("/formats")
    public ResponseEntity<Map<String, Object>> getSupportedFormats() {
        Map<String, Object> formats = new HashMap<>();

        formats.put("supported", new String[]{
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet (.xlsx)",
                "application/vnd.ms-excel (.xls)"
        });

        formats.put("maxFileSize", "10MB");

        formats.put("recommendations", new String[]{
                "Use .xlsx for better compatibility",
                "Keep file size under 5MB for faster processing",
                "Remove unnecessary sheets and formatting"
        });

        return ResponseEntity.ok(formats);
    }
}