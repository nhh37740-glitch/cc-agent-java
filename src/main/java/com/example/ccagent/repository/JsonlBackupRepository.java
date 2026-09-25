package com.example.ccagent.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.ccagent.model.JsonlBackupEntity;

/**
 * jsonl_backup 表的数据库访问接口。
 *
 * Spring Data JPA 会在启动时自动生成实现类。
 */
public interface JsonlBackupRepository extends JpaRepository<JsonlBackupEntity, Long> {

    void deleteByConversationId(String conversationId);

    long countByConversationId(String conversationId);
}
