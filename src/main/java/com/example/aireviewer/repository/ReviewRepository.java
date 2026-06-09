package com.example.aireviewer.repository;

import com.example.aireviewer.domain.MrReview;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ReviewRepository extends JpaRepository<MrReview, UUID> {

    /** Returns all review sessions for a given MR, most recent first. */
    List<MrReview> findByProjectIdAndMrIidOrderByReviewedAtDesc(long projectId, long mrIid);
}
