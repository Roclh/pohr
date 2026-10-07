package org.Roclh.repository.edge;

import org.Roclh.model.edge.EdgeRoute;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface EdgeRouteRepository extends JpaRepository<EdgeRoute, UUID> {

    List<EdgeRoute> findAllByOrderBySortOrderAscSniAsc();

    List<EdgeRoute> findByEnabledTrueOrderBySortOrderAsc();

    Optional<EdgeRoute> findBySni(String sni);

    boolean existsBySni(String sni);
}