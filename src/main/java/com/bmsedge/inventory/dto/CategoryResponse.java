package com.bmsedge.inventory.dto;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Setter
@Getter
public class CategoryResponse {
    // Getters and Setters
    private Long id;
    private String categoryName;
    private String categoryDescription;
    private Long createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Integer itemCount;

    public CategoryResponse() {}

    public CategoryResponse(Long id, String categoryName, String categoryDescription,
                            Long createdBy, LocalDateTime createdAt, LocalDateTime updatedAt, Integer itemCount) {
        this.id = id;
        this.categoryName = categoryName;
        this.categoryDescription = categoryDescription;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.itemCount = itemCount;
    }

}