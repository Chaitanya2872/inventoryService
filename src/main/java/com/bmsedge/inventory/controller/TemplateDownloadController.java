package com.bmsedge.inventory.controller;

import com.bmsedge.inventory.service.TemplateDownloadService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * REST Controller for downloading Excel templates
 * - Items upload template
 * - Consumption upload template (with dropdowns and auto-dates)
 */
@RestController
@RequestMapping("/api/templates")
@CrossOrigin(origins = "*", maxAge = 3600)
public class TemplateDownloadController {

    private static final Logger logger = LoggerFactory.getLogger(TemplateDownloadController.class);

    @Autowired
    private TemplateDownloadService templateDownloadService;

    /**
     * Download items upload template
     * GET /api/templates/items
     *
     * Returns Excel file with:
     * - Pre-configured headers
     * - Sample data (optional)
     * - Instructions sheet
     */
    @GetMapping("/items")
    public ResponseEntity<ByteArrayResource> downloadItemsTemplate(
            @RequestParam(defaultValue = "false") boolean includeSampleData) {

        logger.info("Generating items template, includeSampleData={}", includeSampleData);

        try {
            byte[] excelBytes = templateDownloadService.generateItemsTemplate(includeSampleData);

            ByteArrayResource resource = new ByteArrayResource(excelBytes);

            String filename = "Items_Upload_Template_" +
                    LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd")) +
                    ".xlsx";

            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                    .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                    .contentLength(excelBytes.length)
                    .body(resource);

        } catch (Exception e) {
            logger.error("Failed to generate items template: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to generate template: " + e.getMessage());
        }
    }

    /**
     * Download consumption upload template
     * GET /api/templates/consumption
     *
     * Returns Excel file with:
     * - All items as dropdowns
     * - Item SKU, Category, Item ID columns
     * - Date columns (day/month/year) starting from last consumption date + 1
     * - Data validation dropdowns
     * - Pre-filled formulas
     */
    @GetMapping("/consumption")
    public ResponseEntity<ByteArrayResource> downloadConsumptionTemplate(
            @RequestParam(defaultValue = "30") int daysToGenerate,
            @RequestParam(required = false) Long categoryId) {

        logger.info("Generating consumption template, daysToGenerate={}, categoryId={}",
                daysToGenerate, categoryId);

        try {
            byte[] excelBytes = templateDownloadService.generateConsumptionTemplate(
                    daysToGenerate, categoryId);

            ByteArrayResource resource = new ByteArrayResource(excelBytes);

            String filename = "Consumption_Upload_Template_" +
                    LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd")) +
                    ".xlsx";

            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                    .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                    .contentLength(excelBytes.length)
                    .body(resource);

        } catch (Exception e) {
            logger.error("Failed to generate consumption template: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to generate template: " + e.getMessage());
        }
    }

    /**
     * Get template info
     * GET /api/templates/info
     */
    @GetMapping("/info")
    public ResponseEntity<Object> getTemplateInfo() {
        var info = new java.util.HashMap<String, Object>();

        info.put("itemsTemplate", java.util.Map.of(
                "endpoint", "GET /api/templates/items",
                "parameters", java.util.Map.of(
                        "includeSampleData", "boolean (default: false) - Include sample rows"
                ),
                "features", new String[]{
                        "Pre-configured headers",
                        "Optional sample data",
                        "Instructions sheet",
                        "Column validation rules"
                }
        ));

        info.put("consumptionTemplate", java.util.Map.of(
                "endpoint", "GET /api/templates/consumption",
                "parameters", java.util.Map.of(
                        "daysToGenerate", "int (default: 30) - Number of days to generate",
                        "categoryId", "Long (optional) - Filter items by category"
                ),
                "features", new String[]{
                        "All items in dropdowns",
                        "Item SKU and Category columns",
                        "Item ID for reference",
                        "Dates start from last consumption date + 1",
                        "Pre-filled formulas",
                        "Data validation dropdowns"
                }
        ));

        return ResponseEntity.ok(info);
    }
}