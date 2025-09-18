package com.bmsedge.inventory.service;

import com.bmsedge.inventory.dto.CategoryRequest;
import com.bmsedge.inventory.dto.CategoryResponse;
import com.bmsedge.inventory.exception.BusinessException;
import com.bmsedge.inventory.exception.ResourceNotFoundException;
import com.bmsedge.inventory.model.Category;
import com.bmsedge.inventory.repository.CategoryRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Service
@Transactional
public class CategoryService {

    @Autowired
    private CategoryRepository categoryRepository;

    public CategoryResponse createCategory(CategoryRequest categoryRequest, Long userId) {
        // Check if category with this name already exists (case-insensitive)
        if (categoryRepository.existsByCategoryNameIgnoreCase(categoryRequest.getCategoryName())) {
            throw new BusinessException("Category with this name already exists");
        }

        Category category = new Category(
                categoryRequest.getCategoryName(),
                categoryRequest.getCategoryDescription(),
                userId
        );

        Category savedCategory = categoryRepository.save(category);
        return convertToCategoryResponse(savedCategory);
    }

    @Transactional(readOnly = true)
    public List<CategoryResponse> getAllCategories() {
        List<Category> categories = categoryRepository.findAll();
        return categories.stream()
                .map(this::convertToCategoryResponse)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public CategoryResponse getCategoryById(Long id) {
        Category category = categoryRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Category not found with ID: " + id));
        return convertToCategoryResponse(category);
    }

    public CategoryResponse updateCategory(Long id, CategoryRequest categoryRequest, Long userId) {
        Category existingCategory = categoryRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Category not found with ID: " + id));

        // Check if another category with this name already exists (excluding current one)
        categoryRepository.findByCategoryNameIgnoreCase(categoryRequest.getCategoryName())
                .ifPresent(category -> {
                    if (!category.getId().equals(id)) {
                        throw new BusinessException("Category with this name already exists");
                    }
                });

        existingCategory.setCategoryName(categoryRequest.getCategoryName());
        existingCategory.setCategoryDescription(categoryRequest.getCategoryDescription());
        existingCategory.setUpdatedAt(LocalDateTime.now());

        Category updatedCategory = categoryRepository.save(existingCategory);
        return convertToCategoryResponse(updatedCategory);
    }

    public void deleteCategory(Long id, Long userId) {
        Category category = categoryRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Category not found with ID: " + id));

        // Check if category has items
        Long itemCount = categoryRepository.countItemsByCategoryId(id);
        if (itemCount > 0) {
            throw new BusinessException("Cannot delete category that has " + itemCount + " items. Please move or delete all items first.");
        }

        categoryRepository.delete(category);
    }

    private CategoryResponse convertToCategoryResponse(Category category) {
        Long itemCount = categoryRepository.countItemsByCategoryId(category.getId());

        return new CategoryResponse(
                category.getId(),
                category.getCategoryName(),
                category.getCategoryDescription(),
                category.getCreatedBy(),
                category.getCreatedAt(),
                category.getUpdatedAt(),
                itemCount.intValue()
        );
    }
}