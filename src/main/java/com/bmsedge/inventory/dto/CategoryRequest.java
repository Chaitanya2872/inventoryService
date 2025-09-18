package com.bmsedge.inventory.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
public class CategoryRequest {
    // Getters and Setters
    @NotBlank
    @Size(max = 100)
    private String categoryName;

    @Size(max = 500)
    private String categoryDescription;

    public CategoryRequest() {}

    public CategoryRequest(String categoryName, String categoryDescription) {
        this.categoryName = categoryName;
        this.categoryDescription = categoryDescription;
    }

}