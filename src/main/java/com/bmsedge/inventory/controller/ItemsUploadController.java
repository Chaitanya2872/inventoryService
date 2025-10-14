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
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.HashMap;
import java.util.Map;

/**
 * IMPROVED VERSION with better error handling
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
     * IMPROVED: Bulk import items from Excel file with detailed error reporting
     * POST /api/upload/items
     */
    @PostMapping("/items")
    public ResponseEntity<Map<String, Object>> uploadItems(
            @RequestParam("file") MultipartFile file,
            @RequestHeader(value = "X-User-Id", required = false, defaultValue = "1") Long userId) {

        logger.info("================================================");
        logger.info("UPLOAD REQUEST RECEIVED");
        logger.info("File: {}", file.getOriginalFilename());
        logger.info("Size: {} KB", file.getSize() / 1024);
        logger.info("Content Type: {}", file.getContentType());
        logger.info("User ID: {}", userId);
        logger.info("================================================");

        // Validate file first
        if (file.isEmpty()) {
            logger.error("File is empty");
            return createErrorResponse("File is empty", "EMPTY_FILE", HttpStatus.BAD_REQUEST);
        }

        if (file.getOriginalFilename() == null) {
            logger.error("File name is null");
            return createErrorResponse("File name is missing", "NULL_FILENAME", HttpStatus.BAD_REQUEST);
        }

        String fileName = file.getOriginalFilename().toLowerCase();
        if (!fileName.endsWith(".xlsx") && !fileName.endsWith(".xls")) {
            logger.error("Invalid file format: {}", fileName);
            return createErrorResponse(
                    "Only Excel files (.xlsx, .xls) are supported. Got: " + fileName,
                    "INVALID_FORMAT",
                    HttpStatus.BAD_REQUEST
            );
        }

        try {
            logger.info("Starting file processing...");

            // Process the file
            Map<String, Object> result = itemsUploadService.uploadItemsFromFile(file, userId);

            boolean success = (boolean) result.get("success");
            HttpStatus status = success ? HttpStatus.OK : HttpStatus.BAD_REQUEST;

            logger.info("================================================");
            logger.info("UPLOAD COMPLETED");
            logger.info("Success: {}", success);
            logger.info("Items Created: {}", result.get("itemsCreated"));
            logger.info("Total Rows: {}", result.get("totalRowsProcessed"));
            logger.info("Parse Errors: {}",
                    ((java.util.List<?>) result.getOrDefault("parseErrors", java.util.Collections.emptyList())).size());
            logger.info("Creation Errors: {}",
                    ((java.util.List<?>) result.getOrDefault("creationErrors", java.util.Collections.emptyList())).size());
            logger.info("================================================");

            return ResponseEntity.status(status).body(result);

        } catch (IOException e) {
            logger.error("================================================");
            logger.error("IOException OCCURRED");
            logger.error("Message: {}", e.getMessage());
            logger.error("Cause: {}", e.getCause() != null ? e.getCause().getMessage() : "None");
            logger.error("Stack trace:", e);
            logger.error("================================================");

            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("message", "Failed to read or process Excel file: " + e.getMessage());
            errorResponse.put("error", "IO_ERROR");
            errorResponse.put("errorType", e.getClass().getSimpleName());
            errorResponse.put("fileName", file.getOriginalFilename());

            if (e.getCause() != null) {
                errorResponse.put("rootCause", e.getCause().getMessage());
            }

            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);

        } catch (IllegalArgumentException e) {
            logger.error("================================================");
            logger.error("IllegalArgumentException OCCURRED");
            logger.error("Message: {}", e.getMessage());
            logger.error("Stack trace:", e);
            logger.error("================================================");

            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("message", "Invalid input: " + e.getMessage());
            errorResponse.put("error", "INVALID_INPUT");
            errorResponse.put("errorType", e.getClass().getSimpleName());

            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorResponse);

        } catch (NullPointerException e) {
            logger.error("================================================");
            logger.error("NullPointerException OCCURRED");
            logger.error("Message: {}", e.getMessage());
            logger.error("Location: {}", e.getStackTrace()[0]);
            logger.error("Full stack trace:", e);
            logger.error("================================================");

            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("message", "Null value encountered: " + e.getMessage());
            errorResponse.put("error", "NULL_POINTER");
            errorResponse.put("errorType", e.getClass().getSimpleName());
            errorResponse.put("location", e.getStackTrace()[0].toString());
            errorResponse.put("hint", "Check if all required services are properly injected");

            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);

        } catch (RuntimeException e) {
            logger.error("================================================");
            logger.error("RuntimeException OCCURRED");
            logger.error("Message: {}", e.getMessage());
            logger.error("Cause: {}", e.getCause() != null ? e.getCause().getMessage() : "None");
            logger.error("Stack trace:", e);
            logger.error("================================================");

            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("message", "Runtime error: " + e.getMessage());
            errorResponse.put("error", "RUNTIME_ERROR");
            errorResponse.put("errorType", e.getClass().getSimpleName());

            if (e.getCause() != null) {
                errorResponse.put("rootCause", e.getCause().getMessage());
                errorResponse.put("rootCauseType", e.getCause().getClass().getSimpleName());
            }

            // Add first few stack trace elements
            StackTraceElement[] trace = e.getStackTrace();
            if (trace.length > 0) {
                errorResponse.put("topStackTrace", trace[0].toString());
            }

            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);

        } catch (Exception e) {
            logger.error("================================================");
            logger.error("UNEXPECTED EXCEPTION OCCURRED");
            logger.error("Type: {}", e.getClass().getName());
            logger.error("Message: {}", e.getMessage());
            logger.error("Cause: {}", e.getCause() != null ? e.getCause().getMessage() : "None");
            logger.error("Full stack trace:", e);
            logger.error("================================================");

            // Get full stack trace as string
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            e.printStackTrace(pw);
            String stackTrace = sw.toString();

            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("message", "Unexpected error: " + e.getMessage());
            errorResponse.put("error", "UNEXPECTED_ERROR");
            errorResponse.put("errorType", e.getClass().getName());
            errorResponse.put("stackTrace", stackTrace);

            if (e.getCause() != null) {
                errorResponse.put("rootCause", e.getCause().getMessage());
                errorResponse.put("rootCauseType", e.getCause().getClass().getName());
            }

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
            errorResponse.put("error", e.getClass().getSimpleName());

            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorResponse);
        } catch (Exception e) {
            logger.error("Unexpected validation error: {}", e.getMessage(), e);

            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("valid", false);
            errorResponse.put("message", "Unexpected error: " + e.getMessage());
            errorResponse.put("error", e.getClass().getName());

            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }

    /**
     * Get upload template information
     */
    @GetMapping("/template")
    public ResponseEntity<Map<String, Object>> getUploadTemplate() {
        try {
            Map<String, Object> template = itemsUploadService.getUploadTemplate();
            return ResponseEntity.ok(template);
        } catch (Exception e) {
            logger.error("Failed to get template info: {}", e.getMessage(), e);
            return createErrorResponse("Failed to get template info", "TEMPLATE_ERROR", HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * Health check with dependency verification
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> healthCheck() {
        Map<String, Object> response = new HashMap<>();
        response.put("status", "UP");
        response.put("service", "Items Upload Service");
        response.put("timestamp", java.time.LocalDateTime.now().toString());

        // Check if service is injected
        if (itemsUploadService == null) {
            response.put("serviceInjected", false);
            response.put("error", "ItemsUploadService is not injected");
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(response);
        }

        response.put("serviceInjected", true);
        response.put("features", "SKU Support, Auto-detection, Multi-sheet");

        return ResponseEntity.ok(response);
    }

    /**
     * Debug endpoint to check configuration
     */
    @GetMapping("/debug")
    public ResponseEntity<Map<String, Object>> debugInfo() {
        Map<String, Object> debug = new HashMap<>();
        debug.put("timestamp", java.time.LocalDateTime.now().toString());
        debug.put("serviceInjected", itemsUploadService != null);
        debug.put("javaVersion", System.getProperty("java.version"));
        debug.put("osName", System.getProperty("os.name"));
        debug.put("maxMemory", Runtime.getRuntime().maxMemory() / (1024 * 1024) + " MB");
        debug.put("freeMemory", Runtime.getRuntime().freeMemory() / (1024 * 1024) + " MB");

        return ResponseEntity.ok(debug);
    }

    // Helper method to create error responses
    private ResponseEntity<Map<String, Object>> createErrorResponse(
            String message, String errorCode, HttpStatus status) {
        Map<String, Object> errorResponse = new HashMap<>();
        errorResponse.put("success", false);
        errorResponse.put("message", message);
        errorResponse.put("error", errorCode);
        errorResponse.put("timestamp", java.time.LocalDateTime.now().toString());
        errorResponse.put("status", status.value());
        return ResponseEntity.status(status).body(errorResponse);
    }

    /**
     * Get upload instructions (existing method - unchanged)
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

        return ResponseEntity.ok(instructions);
    }

    /**
     * Get supported file formats
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