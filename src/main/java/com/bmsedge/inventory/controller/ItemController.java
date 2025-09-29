package com.bmsedge.inventory.controller;

import com.bmsedge.inventory.dto.ItemRequest;
import com.bmsedge.inventory.dto.ItemResponse;
import com.bmsedge.inventory.dto.ConsumptionRequest;
import com.bmsedge.inventory.dto.ReceiptRequest;
import com.bmsedge.inventory.service.ItemService;
import com.bmsedge.inventory.exception.ResourceNotFoundException;
import com.bmsedge.inventory.exception.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/items")
@CrossOrigin(origins = "*")
public class ItemController {

    @Autowired
    private ItemService itemService;

    @PostMapping
    public ResponseEntity<?> createItem(@RequestBody ItemRequest itemRequest,
                                        HttpServletRequest request) {
        try {
            Long userId = (Long) request.getAttribute("userId");
            if (userId == null) {
                userId = 1L; // Default user ID for testing
            }

            // Pre-validate and fix the request
            validateAndFixItemRequest(itemRequest);

            ItemResponse response = itemService.createItem(itemRequest, userId);
            return ResponseEntity.ok(response);

        } catch (ResourceNotFoundException e) {
            return ResponseEntity.badRequest()
                    .body(createErrorResponse("Category not found with ID: " + itemRequest.getCategoryId()));
        } catch (BusinessException e) {
            return ResponseEntity.badRequest()
                    .body(createErrorResponse(e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                    .body(createErrorResponse(e.getMessage()));
        } catch (Exception e) {
            e.printStackTrace(); // Log the full stack trace
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(createErrorResponse("An unexpected error occurred: " + e.getMessage()));
        }
    }

    @GetMapping
    public ResponseEntity<?> getAllItems() {
        try {
            List<ItemResponse> items = itemService.getAllItems();
            return ResponseEntity.ok(items);
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(createErrorResponse("Failed to fetch items: " + e.getMessage()));
        }
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getItemById(@PathVariable("id") Long id) {
        try {
            ItemResponse item = itemService.getItemById(id);
            return ResponseEntity.ok(item);
        } catch (ResourceNotFoundException e) {
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(createErrorResponse("Failed to fetch item: " + e.getMessage()));
        }
    }

    @GetMapping("/category/{categoryId}")
    public ResponseEntity<?> getItemsByCategory(@PathVariable("categoryId") Long categoryId) {
        try {
            List<ItemResponse> items = itemService.getItemsByCategory(categoryId);
            return ResponseEntity.ok(items);
        } catch (ResourceNotFoundException e) {
            return ResponseEntity.badRequest()
                    .body(createErrorResponse("Category not found with ID: " + categoryId));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(createErrorResponse("Failed to fetch items by category: " + e.getMessage()));
        }
    }

    @GetMapping("/search")
    public ResponseEntity<?> searchItems(@RequestParam("q") String query) {
        try {
            if (query == null || query.trim().isEmpty()) {
                return ResponseEntity.badRequest()
                        .body(createErrorResponse("Search query cannot be empty"));
            }
            List<ItemResponse> items = itemService.searchItems(query.trim());
            return ResponseEntity.ok(items);
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(createErrorResponse("Failed to search items: " + e.getMessage()));
        }
    }

    @GetMapping("/low-stock")
    public ResponseEntity<?> getLowStockItems(@RequestParam(value = "threshold", required = false) Integer threshold) {
        try {
            if (threshold != null && threshold < 0) {
                return ResponseEntity.badRequest()
                        .body(createErrorResponse("Threshold cannot be negative"));
            }
            List<ItemResponse> items = itemService.getLowStockItems(threshold);
            return ResponseEntity.ok(items);
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(createErrorResponse("Failed to fetch low stock items: " + e.getMessage()));
        }
    }

    @GetMapping("/expiring")
    public ResponseEntity<?> getExpiringItems(@RequestParam(value = "days", defaultValue = "7") int days) {
        try {
            List<ItemResponse> items = itemService.getExpiringItems(days);
            return ResponseEntity.ok(items);
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(createErrorResponse("Failed to fetch expiring items: " + e.getMessage()));
        }
    }

    @GetMapping("/expired")
    public ResponseEntity<?> getExpiredItems() {
        try {
            List<ItemResponse> items = itemService.getExpiredItems();
            return ResponseEntity.ok(items);
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(createErrorResponse("Failed to fetch expired items: " + e.getMessage()));
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> updateItem(@PathVariable("id") Long id,
                                        @RequestBody ItemRequest itemRequest,
                                        HttpServletRequest request) {
        try {
            Long userId = (Long) request.getAttribute("userId");
            if (userId == null) {
                userId = 1L; // Default user ID for testing
            }

            // Pre-validate and fix the request
            validateAndFixItemRequest(itemRequest);

            ItemResponse response = itemService.updateItem(id, itemRequest, userId);
            return ResponseEntity.ok(response);

        } catch (ResourceNotFoundException e) {
            return ResponseEntity.notFound().build();
        } catch (BusinessException e) {
            return ResponseEntity.badRequest()
                    .body(createErrorResponse(e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                    .body(createErrorResponse(e.getMessage()));
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.internalServerError()
                    .body(createErrorResponse("Failed to update item: " + e.getMessage()));
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteItem(@PathVariable("id") Long id, HttpServletRequest request) {
        try {
            Long userId = (Long) request.getAttribute("userId");
            if (userId == null) {
                userId = 1L; // Default user ID for testing
            }

            itemService.deleteItem(id, userId);
            return ResponseEntity.noContent().build();
        } catch (ResourceNotFoundException e) {
            return ResponseEntity.notFound().build();
        } catch (BusinessException e) {
            return ResponseEntity.badRequest()
                    .body(createErrorResponse(e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(createErrorResponse("Failed to delete item: " + e.getMessage()));
        }
    }

    // Stock movement endpoints
    @PostMapping("/{id}/consume")
    public ResponseEntity<?> recordConsumption(@PathVariable("id") Long id,
                                               @RequestBody ConsumptionRequest request,
                                               HttpServletRequest httpRequest) {
        try {
            Long userId = (Long) httpRequest.getAttribute("userId");
            if (userId == null) {
                userId = 1L;
            }

            ItemResponse response = itemService.recordConsumption(
                    id,
                    request.getQuantity(),
                    request.getDepartment(),
                    userId
            );

            return ResponseEntity.ok(response);
        } catch (ResourceNotFoundException e) {
            return ResponseEntity.notFound().build();
        } catch (BusinessException e) {
            return ResponseEntity.badRequest()
                    .body(createErrorResponse(e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(createErrorResponse("Failed to record consumption: " + e.getMessage()));
        }
    }

    @PostMapping("/{id}/receive")
    public ResponseEntity<?> recordReceipt(@PathVariable("id") Long id,
                                           @RequestBody ReceiptRequest request,
                                           HttpServletRequest httpRequest) {
        try {
            Long userId = (Long) httpRequest.getAttribute("userId");
            if (userId == null) {
                userId = 1L;
            }

            ItemResponse response = itemService.recordReceipt(
                    id,
                    request.getQuantity(),
                    request.getUnitPrice(),
                    request.getReferenceNumber(),
                    userId
            );

            return ResponseEntity.ok(response);
        } catch (ResourceNotFoundException e) {
            return ResponseEntity.notFound().build();
        } catch (BusinessException e) {
            return ResponseEntity.badRequest()
                    .body(createErrorResponse(e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(createErrorResponse("Failed to record receipt: " + e.getMessage()));
        }
    }

    // Helper methods
    private void validateAndFixItemRequest(ItemRequest itemRequest) {
        // Validate and fix reorder level
        if (itemRequest.getReorderLevel() == null || itemRequest.getReorderLevel().compareTo(BigDecimal.ZERO) <= 0) {
            BigDecimal currentQty = itemRequest.getCurrentQuantity() != null
                    ? new BigDecimal(itemRequest.getCurrentQuantity())
                    : BigDecimal.ZERO;

            // Default reorder level: either 20% of current quantity or 10, whichever is greater
            itemRequest.setReorderLevel(currentQty.multiply(BigDecimal.valueOf(0.2))
                    .max(BigDecimal.TEN));
        }

        // Validate and fix reorder quantity
        if (itemRequest.getReorderQuantity() == null || itemRequest.getReorderQuantity().compareTo(BigDecimal.ZERO) <= 0) {
            // Default reorder quantity = 50 units
            itemRequest.setReorderQuantity(BigDecimal.valueOf(50));
        }

        // Validate category ID
        if (itemRequest.getCategoryId() == null || itemRequest.getCategoryId() == 0) {
            throw new IllegalArgumentException("Valid category ID is required");
        }

        // Fix unit of measurement
        if (itemRequest.getUnitOfMeasurement() == null || itemRequest.getUnitOfMeasurement().trim().isEmpty()) {
            itemRequest.setUnitOfMeasurement("pcs");
        }

        // Validate item name
        if (itemRequest.getItemName() == null || itemRequest.getItemName().trim().isEmpty()) {
            throw new IllegalArgumentException("Item name is required");
        }

        // Validate current quantity
        if (itemRequest.getCurrentQuantity() == null || itemRequest.getCurrentQuantity() < 0) {
            throw new IllegalArgumentException("Current quantity must be a non-negative number");
        }
    }


    private Map<String, Object> createErrorResponse(String message) {
        Map<String, Object> error = new HashMap<>();
        error.put("error", message);
        error.put("timestamp", System.currentTimeMillis());
        return error;
    }
}