package com.github.obhen233.compiler.entity;

import jakarta.persistence.*;

/**
 * IDE 配置键值对，持久化到 SQLite
 */
@Entity
@Table(name = "ide_settings")
public class IdeSetting {

    @Id
    @Column(name = "setting_key", length = 128)
    private String key;

    @Column(name = "setting_value", length = 1024)
    private String value;

    public IdeSetting() {}

    public IdeSetting(String key, String value) {
        this.key = key;
        this.value = value;
    }

    public String getKey() { return key; }
    public void setKey(String key) { this.key = key; }
    public String getValue() { return value; }
    public void setValue(String value) { this.value = value; }
}
