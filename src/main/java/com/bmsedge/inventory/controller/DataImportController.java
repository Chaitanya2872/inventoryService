package com.bmsedge.inventory.controller;

import com.bmsedge.inventory.service.ItemService;
import com.bmsedge.inventory.service.ItemCorrelationService;
import com.bmsedge.inventory.service.DataImportService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/data-import")
@CrossOrigin(origins = "*")
public class DataImportController {

    @Autowired
    private DataImportService dataImportService;

    @Autowired
    private ItemCorrelationService correlationService;

    @Autowired
    private ItemService itemService;

    /**
     * Import Excel file with inventory data
     */
    @PostMapping("/excel")
    public ResponseEntity<Map<String, Object>> importExcelFile(
            @RequestParam("file") MultipartFile file,
            @RequestParam(defaultValue = "1") Long userId) {
        try {
            Map<String, Object> result = dataImportService.importExcelFile(file, userId);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            Map<String, Object> error = new HashMap<>();
            error.put("error", "Failed to import Excel file");
            error.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        }
    }

    /**
     * Import all sheets from Excel file (for multi-sheet files like jack_sheet.xlsx)
     */
    @PostMapping("/excel/all-sheets")
    public ResponseEntity<Map<String, Object>> importAllSheetsFromExcel(
            @RequestParam("file") MultipartFile file,
            @RequestParam(defaultValue = "1") Long userId) {
        try {
            Map<String, Object> result = dataImportService.importAllSheetsFromExcel(file, userId);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            Map<String, Object> error = new HashMap<>();
            error.put("error", "Failed to import all sheets from Excel file");
            error.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        }
    }

    /**
     * Force recalculate all correlations
     */
    @PostMapping("/correlations/recalculate")
    public ResponseEntity<Map<String, Object>> forceRecalculateCorrelations() {
        try {
            Map<String, Object> result = correlationService.forceRecalculateAllCorrelations();
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            Map<String, Object> error = new HashMap<>();
            error.put("error", "Failed to recalculate correlations");
            error.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        }
    }

    /**
     * Get correlation debug information
     */
    @GetMapping("/correlations/debug")
    public ResponseEntity<Map<String, Object>> getCorrelationDebugInfo() {
        try {
            Map<String, Object> debug = correlationService.getCorrelationDebugInfo();
            return ResponseEntity.ok(debug);
        } catch (Exception e) {
            Map<String, Object> error = new HashMap<>();
            error.put("error", "Failed to get debug info");
            error.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        }
    }

    /**
     * Validate Excel file structure
     */
    @PostMapping("/excel/validate")
    public ResponseEntity<Map<String, Object>> validateExcelFile(
            @RequestParam("file") MultipartFile file) {
        try {
            Map<String, Object> result = dataImportService.validateExcelFile(file);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            Map<String, Object> error = new HashMap<>();
            error.put("error", "Failed to validate Excel file");
            error.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        }
    }

    /**
     * Get import templates
     */
    @GetMapping("/templates")
    public ResponseEntity<Map<String, Object>> getImportTemplates() {
        Map<String, Object> templates = new HashMap<>();

        Map<String, String> inventoryTemplate = new HashMap<>();
        inventoryTemplate.put("description", "Monthly inventory data with daily consumption");
        inventoryTemplate.put("requiredColumns", "item_name,category,opening_stock,received_stock,consumption,sih,day_1,day_2,...,day_31");
        inventoryTemplate.put("format", "Excel (.xlsx)");

        templates.put("inventory", inventoryTemplate);
        templates.put("message", "Use these templates for data import");

        return ResponseEntity.ok(templates);
    }

    /**
     * Import sample data for testing
     */
    @PostMapping("/sample-data")
    public ResponseEntity<Map<String, Object>> importSampleData(@RequestParam(defaultValue = "1") Long userId) {
        try {
            Map<String, Object> result = dataImportService.importSampleData(userId);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            Map<String, Object> error = new HashMap<>();
            error.put("error", "Failed to import sample data");
            error.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        }
    }

    /**
     * Get import status
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getImportStatus() {
        Map<String, Object> status = dataImportService.getImportStatus();
        return ResponseEntity.ok(status);
    }
}