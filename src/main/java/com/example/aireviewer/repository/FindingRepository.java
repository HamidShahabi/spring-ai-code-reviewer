package com.example.aireviewer.repository;

import com.example.aireviewer.domain.Finding;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface FindingRepository extends JpaRepository<Finding, UUID> {
}
