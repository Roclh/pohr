package org.Roclh.repository.edge;

import org.Roclh.model.edge.EdgeSite;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface EdgeSiteRepository extends JpaRepository<EdgeSite, UUID> {

    List<EdgeSite> findAllByOrderByDomainAsc();

    List<EdgeSite> findByEnabledTrueOrderByDomainAsc();

    Optional<EdgeSite> findByDomain(String domain);

    boolean existsByDomain(String domain);
}