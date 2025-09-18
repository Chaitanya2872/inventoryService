package com.bmsedge.inventory.repository;

import com.bmsedge.inventory.model.Category;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CategoryRepository extends JpaRepository<Category, Long> {

    Optional<Category> findByCategoryNameIgnoreCase(String categoryName);

    List<Category> findByCreatedBy(Long createdBy);

    boolean existsByCategoryNameIgnoreCase(String categoryName);

    @Query("SELECT c FROM Category c WHERE c.categoryName LIKE %:name%")
    List<Category> findByCategoryNameContaining(@Param("name") String name);

    @Query("SELECT COUNT(i) FROM Item i WHERE i.category.id = :categoryId")
    Long countItemsByCategoryId(@Param("categoryId") Long categoryId);
}