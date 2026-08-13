package com.github.obhen233.compiler.repository;

import com.github.obhen233.compiler.entity.DebugConfiguration;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DebugConfigurationRepository extends JpaRepository<DebugConfiguration, String> {
    List<DebugConfiguration> findByProjectName(String projectName);
    List<DebugConfiguration> findAllByOrderByNameAsc();
}
