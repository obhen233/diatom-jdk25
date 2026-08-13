package com.github.obhen233.compiler.repository;

import com.github.obhen233.compiler.entity.IdeSetting;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface IdeSettingRepository extends JpaRepository<IdeSetting, String> {
}
