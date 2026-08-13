package com.github.obhen233.core.database.entity;

import jakarta.persistence.*;
import java.io.Serializable;
import java.util.Objects;

@Entity
@Table(name = "snapshot_files")
@IdClass(SnapshotFileEntity.SnapshotFileId.class)
public class SnapshotFileEntity {

    @Id
    @Column(name = "snapshot_id", nullable = false)
    private Integer snapshotId;

    @Id
    @Column(name = "file_snapshot_id", nullable = false)
    private Integer fileSnapshotId;

    public Integer getSnapshotId() { return snapshotId; }
    public void setSnapshotId(Integer snapshotId) { this.snapshotId = snapshotId; }
    public Integer getFileSnapshotId() { return fileSnapshotId; }
    public void setFileSnapshotId(Integer fileSnapshotId) { this.fileSnapshotId = fileSnapshotId; }

    public static class SnapshotFileId implements Serializable {
        private Integer snapshotId;
        private Integer fileSnapshotId;

        public SnapshotFileId() {}
        public SnapshotFileId(Integer snapshotId, Integer fileSnapshotId) {
            this.snapshotId = snapshotId;
            this.fileSnapshotId = fileSnapshotId;
        }

        public Integer getSnapshotId() { return snapshotId; }
        public void setSnapshotId(Integer snapshotId) { this.snapshotId = snapshotId; }
        public Integer getFileSnapshotId() { return fileSnapshotId; }
        public void setFileSnapshotId(Integer fileSnapshotId) { this.fileSnapshotId = fileSnapshotId; }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            SnapshotFileId that = (SnapshotFileId) o;
            return Objects.equals(snapshotId, that.snapshotId) &&
                   Objects.equals(fileSnapshotId, that.fileSnapshotId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(snapshotId, fileSnapshotId);
        }
    }
}
