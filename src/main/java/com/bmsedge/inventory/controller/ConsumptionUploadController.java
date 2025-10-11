package com.bmsedge.inventory.controller;

import com.bmsedge.inventory.service.ConsumptionUploadService;
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
 * REST Controller for bulk consumption import from Excel files
 * Validates items exist before importing consumption data
 */
@RestController
@RequestMapping("/api/upload")
@CrossOrigin(origins = "*", maxAge = 3600)
public class ConsumptionUploadController {

    private static final Logger logger = LoggerFactory.getLogger(ConsumptionUploadController.class);

    @Autowired
    private ConsumptionUploadService consumptionUploadService;

    /**
     * Bulk import consumption records from Excel file
     * POST /api/upload/consumption
     *
     * Features:
     * - Auto-detects Excel structure
     * - Validates items exist (by name + SKU)
     * - Shows warnings for missing items
     * - Supports multiple sheets
     * - Automatically triggers stock movements, statistics, and correlations
     */
    @PostMapping("/consumption")
    public ResponseEntity<Map<String, Object>> uploadConsumption(
            @RequestParam("file") MultipartFile file,
            @RequestHeader(value = "X-User-Id", required = false, defaultValue = "1") Long userId) {

        logger.info("Received consumption upload request: file={}, size={}",
                file.getOriginalFilename(), file.getSize());

        try {
            Map<String, Object> result = consumptionUploadService.uploadConsumptionFromFile(file, userId);

            boolean success = (boolean) result.get("success");
            HttpStatus status = success ? HttpStatus.OK : HttpStatus.BAD_REQUEST;

            logger.info("Consumption upload result: success={}, recordsCreated={}, warnings={}",
                    success,
                    result.get("recordsCreated"),
                    result.getOrDefault("warnings", "none"));

            return ResponseEntity.status(status).body(result);

        } catch (IOException e) {
            logger.error("Consumption upload failed: {}", e.getMessage(), e);

            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("message", "Failed to process file: " + e.getMessage());
            errorResponse.put("error", e.getClass().getSimpleName());

            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }

    /**
     * Get consumption upload template information
     * GET /api/upload/consumption/template
     */
    @GetMapping("/consumption/template")
    public ResponseEntity<Map<String, Object>> getConsumptionTemplate() {
        logger.debug("Consumption template info requested");
        Map<String, Object> template = consumptionUploadService.getConsumptionTemplate();
        return ResponseEntity.ok(template);
    }

    /**
     * Get consumption upload instructions
     * GET /api/upload/consumption/instructions
     */
    @GetMapping("/consumption/instructions")
    public ResponseEntity<Map<String, Object>> getConsumptionInstructions() {
        Map<String, Object> instructions = new HashMap<>();

        instructions.put("title", "Bulk Consumption Upload Instructions");

        instructions.put("fileFormat", Map.of(
                "supported", new String[]{"Excel (.xlsx)", "Excel (.xls)"},
                "maxSize", "10MB",
                "encoding", "UTF-8"
        ));

        instructions.put("requiredColumns", new String[]{
                "Item Name (or item, item_name, etc.)",
                "Consumption Date (date, consumption_date, etc.)",
                "Consumed Quantity (consumed, quantity, consumption, etc.)"
        });

        instructions.put("optionalColumns", new String[]{
                "Item SKU - for identifying item variants",
                "Opening Stock",
                "Received Quantity",
                "Closing Stock",
                "Department",
                "Cost Center",
                "Employee Count",
                "Notes"
        });

        instructions.put("itemValidation", Map.of(
                "requirement", "Items MUST exist before importing consumption",
                "identification", "Items identified by: Item Name + Item SKU (if present)",
                "warning", "Missing items will be listed in warnings - create them first using /api/upload/items",
                "behavior", "Consumption records for missing items will be skipped"
        ));

        instructions.put("dateFormats", new String[]{
                "yyyy-MM-dd (2024-01-15)",
                "dd/MM/yyyy (15/01/2024)",
                "MM/dd/yyyy (01/15/2024)",
                "Excel date format (numeric dates)"
        });

        instructions.put("automaticTriggers", new String[]{
                "Stock movements are automatically updated",
                "Item statistics are recalculated",
                "Correlations are computed",
                "Coverage days are updated",
                "Stock alerts are refreshed"
        });

        instructions.put("examples", Map.of(
                "basic", Map.of(
                        "description", "Basic consumption record",
                        "columns", "Item Name | Consumption Date | Consumed Quantity",
                        "example", "Pril-Dishwash | 2024-01-15 | 5"
                ),
                "withSKU", Map.of(
                        "description", "Consumption with SKU for variant identification",
                        "columns", "Item Name | Item SKU | Consumption Date | Consumed Quantity",
                        "example", "Pril-Dishwash | 125ml | 2024-01-15 | 5"
                ),
                "complete", Map.of(
                        "description", "Complete consumption record",
                        "columns", "Item Name | Item SKU | Date | Opening | Received | Consumed | Closing | Department",
                        "example", "Pril-Dishwash | 125ml | 2024-01-15 | 50 | 10 | 5 | 55 | Kitchen"
                )
        ));

        instructions.put("bestPractices", new String[]{
                "1. Import items FIRST using /api/upload/items",
                "2. Ensure item names and SKUs match exactly",
                "3. Use consistent date formats",
                "4. One consumption record per item per day",
                "5. Provide opening stock for accuracy",
                "6. Test with small file first (5-10 records)",
                "7. Review warnings for missing items"
        });

        instructions.put("troubleshooting", Map.of(
                "itemNotFound", "Create the item first using /api/upload/items endpoint",
                "duplicateDate", "System updates existing record if item+date combination exists",
                "invalidDate", "Use supported date formats or Excel date format",
                "negativeStock", "Check opening stock and consumed quantities",
                "noRecordsCreated", "All items might be missing - check warnings"
        ));

        return ResponseEntity.ok(instructions);
    }

    /**
     * Validate consumption file without importing
     * POST /api/upload/consumption/validate
     */
    @PostMapping("/consumption/validate")
    public ResponseEntity<Map<String, Object>> validateConsumptionFile(
            @RequestParam("file") MultipartFile file) {

        logger.info("Validating consumption file: {}", file.getOriginalFilename());

        try {
            Map<String, Object> validation = consumptionUploadService.validateConsumptionFile(file);
            return ResponseEntity.ok(validation);

        } catch (IOException e) {
            logger.error("Validation failed: {}", e.getMessage(), e);

            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("valid", false);
            errorResponse.put("message", "Failed to validate file: " + e.getMessage());

            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorResponse);
        }
    }
}