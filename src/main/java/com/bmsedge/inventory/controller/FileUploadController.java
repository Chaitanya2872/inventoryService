package com.bmsedge.inventory.controller;

import com.bmsedge.inventory.service.FileUploadService;
import com.bmsedge.inventory.service.ConsumptionDataImportService;
// import com.bmsedge.inventory.service.FootfallService; // Uncomment when you have FootfallService
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/upload")
@CrossOrigin(origins = "*")
public class FileUploadController {

    @Autowired
    private FileUploadService fileUploadService;

    // Add this service to your existing controller
    @Autowired
    private ConsumptionDataImportService consumptionDataImportService;

    // Your existing endpoint - NO CHANGES
    @PostMapping("/items")
    public ResponseEntity<Map<String, Object>> uploadItems(
            @RequestParam("file") MultipartFile file,
            HttpServletRequest request) {

        try {
            Long userId = (Long) request.getAttribute("userId");
            if (userId == null) {
                userId = 1L; // Default user for testing
            }

            Map<String, Object> result = fileUploadService.uploadItemsFromFile(file, userId);

            boolean success = (boolean) result.getOrDefault("success", false);
            HttpStatus status = success ? HttpStatus.OK : HttpStatus.PARTIAL_CONTENT;

            return ResponseEntity.status(status).body(result);

        } catch (IOException e) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("message", "Error processing file: " + e.getMessage());
            errorResponse.put("error", e.getMessage());
            return ResponseEntity.badRequest().body(errorResponse);

        } catch (Exception e) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("message", "Unexpected error: " + e.getMessage());
            errorResponse.put("error", e.getMessage());
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }

    // NEW ENDPOINT: Import consumption records from Excel
    @PostMapping("/consumption")
    public ResponseEntity<Map<String, Object>> uploadConsumption(
            @RequestParam("file") MultipartFile file) {

        try {
            // Check if file is Excel format
            String fileName = file.getOriginalFilename();
            if (fileName == null || (!fileName.toLowerCase().endsWith(".xlsx") &&
                    !fileName.toLowerCase().endsWith(".xls"))) {
                Map<String, Object> errorResponse = new HashMap<>();
                errorResponse.put("success", false);
                errorResponse.put("message", "Please upload an Excel file (.xlsx or .xls) with daily consumption data");
                return ResponseEntity.badRequest().body(errorResponse);
            }

            Map<String, Object> result = consumptionDataImportService.importConsumptionData(file);

            boolean success = (boolean) result.getOrDefault("success", false);
            HttpStatus status = success ? HttpStatus.OK : HttpStatus.PARTIAL_CONTENT;

            return ResponseEntity.status(status).body(result);

        } catch (IOException e) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("message", "Error processing consumption file: " + e.getMessage());
            return ResponseEntity.badRequest().body(errorResponse);
        } catch (Exception e) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("message", "Unexpected error: " + e.getMessage());
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }

    // NEW ENDPOINT: Complete inventory import (Items + Consumption)
    @PostMapping("/inventory-complete")
    public ResponseEntity<Map<String, Object>> uploadCompleteInventory(
            @RequestParam("file") MultipartFile file,
            HttpServletRequest request) {

        Map<String, Object> response = new HashMap<>();

        try {
            // Step 1: Upload items
            Long userId = (Long) request.getAttribute("userId");
            if (userId == null) {
                userId = 1L;
            }

            Map<String, Object> itemsResult = fileUploadService.uploadItemsFromFile(file, userId);
            response.put("itemsImport", itemsResult);

            // Step 2: Import consumption records
            Map<String, Object> consumptionResult = consumptionDataImportService.importConsumptionData(file);
            response.put("consumptionImport", consumptionResult);

            // Overall success if either succeeded
            boolean overallSuccess =
                    (boolean) itemsResult.getOrDefault("success", false) ||
                            (boolean) consumptionResult.getOrDefault("success", false);

            response.put("success", overallSuccess);

            if (overallSuccess) {
                int itemsCreated = (int) itemsResult.getOrDefault("itemsCreated", 0);
                int consumptionCreated = (int) consumptionResult.getOrDefault("recordsCreated", 0);

                response.put("message", String.format(
                        "Import complete: %d items created, %d consumption records created",
                        itemsCreated, consumptionCreated
                ));
                return ResponseEntity.ok(response);
            } else {
                response.put("message", "Import partially failed. Check individual results.");
                return ResponseEntity.status(HttpStatus.PARTIAL_CONTENT).body(response);
            }

        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "Failed to import inventory: " + e.getMessage());
            response.put("error", e.getMessage());
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    // Your existing template endpoint - NO CHANGES
    @GetMapping("/template")
    public ResponseEntity<Map<String, Object>> getUploadTemplate() {
        Map<String, Object> template = fileUploadService.getUploadTemplate();
        return ResponseEntity.ok(template);
    }

    // Your existing test endpoint - NO CHANGES
    @GetMapping("/test")
    public ResponseEntity<Map<String, Object>> testEndpoint() {
        Map<String, Object> response = new HashMap<>();
        response.put("status", "OK");
        response.put("message", "Upload service is running");
        response.put("endpoints", new String[]{
                "/api/upload/items - Upload inventory items",
                "/api/upload/consumption - Upload consumption records",
                "/api/upload/inventory-complete - Upload items + consumption"
        });
        response.put("timestamp", System.currentTimeMillis());
        return ResponseEntity.ok(response);
    }
}