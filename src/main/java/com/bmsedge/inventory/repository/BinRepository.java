package com.bmsedge.inventory.repository;

import com.bmsedge.inventory.model.Bin;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface BinRepository extends JpaRepository<Bin, Long> {

    Optional<Bin> findByBinCode(String binCode);

    @Query("SELECT b FROM Bin b WHERE b.capacity > " +
            "(SELECT COALESCE(SUM(i.currentQuantity), 0) FROM Item i WHERE i.primaryBinId = b.id) " +
            "ORDER BY b.capacity DESC")
    List<Bin> findAvailableBins();
}