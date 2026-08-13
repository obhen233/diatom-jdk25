package com.github.obhen233.core.database.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Entity mapping for the {@code hibernate_sequences} table used by
 * {@link com.github.obhen233.core.database.DiatomIdGenerator}.
 * <p>
 * Registered with Hibernate schema update so the table is auto-created.
 */
@Entity
@Table(name = "hibernate_sequences")
public class HibernateSequenceEntity {

    @Id
    @Column(name = "sequence_name", length = 255)
    private String sequenceName;

    @Column(name = "next_val")
    private Long nextVal;

    public HibernateSequenceEntity() {}

    public HibernateSequenceEntity(String sequenceName, Long nextVal) {
        this.sequenceName = sequenceName;
        this.nextVal = nextVal;
    }

    public String getSequenceName() { return sequenceName; }
    public void setSequenceName(String sequenceName) { this.sequenceName = sequenceName; }
    public Long getNextVal() { return nextVal; }
    public void setNextVal(Long nextVal) { this.nextVal = nextVal; }
}
