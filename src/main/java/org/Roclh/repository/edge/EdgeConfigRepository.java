package org.Roclh.repository.edge;

import org.Roclh.model.edge.EdgeConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface EdgeConfigRepository extends JpaRepository<EdgeConfig, String> {
}