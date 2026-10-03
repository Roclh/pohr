package org.Roclh.repository;

import org.Roclh.model.EnrollmentToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface EnrollmentTokenRepository extends JpaRepository<EnrollmentToken, String> {
    List<EnrollmentToken> findByExpiresAtBeforeAndUsedAtIsNull(Instant threshold);
    List<EnrollmentToken> findByNodeNameOrderByCreatedAtDesc(String nodeName);
}