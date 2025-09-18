package com.bmsedge.inventory.controller;

import com.bmsedge.inventory.service.ItemService;
import com.bmsedge.inventory.util.UnitOfMeasurementUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.HashMap;

@RestController
@RequestMapping("/api/units")
@CrossOrigin(origins = "*")
public class UnitController {

    @Autowired
    private ItemService itemService;

    @GetMapping
    public ResponseEntity<Map<String, Object>> getAllUnits() {
        Map<String, Object> response = new HashMap<>();
        response.put("standardUnits", UnitOfMeasurementUtil.getStandardUnits());
        response.put("count", UnitOfMeasurementUtil.getStandardUnits().size());
        return ResponseEntity.ok(response);
    }

    @GetMapping("/suggestions")
    public ResponseEntity<Map<String, Object>> getUnitSuggestions(@RequestParam(required = false) String input) {
        List<String> suggestions = UnitOfMeasurementUtil.getSuggestions(input);
        Map<String, Object> response = new HashMap<>();
        response.put("suggestions", suggestions);
        response.put("count", suggestions.size());
        if (input != null) {
            response.put("normalized", UnitOfMeasurementUtil.normalizeUnit(input));
        }
        return ResponseEntity.ok(response);
    }

    @PostMapping("/normalize")
    public ResponseEntity<Map<String, Object>> normalizeUnit(@RequestBody Map<String, String> request) {
        String input = request.get("unit");
        String normalized = UnitOfMeasurementUtil.normalizeUnit(input);
        String displayName = UnitOfMeasurementUtil.getDisplayName(normalized);
        boolean isValid = UnitOfMeasurementUtil.isValidUnit(input);

        Map<String, Object> response = new HashMap<>();
        response.put("input", input);
        response.put("normalized", normalized);
        response.put("displayName", displayName);
        response.put("isValid", isValid);

        return ResponseEntity.ok(response);
    }
}