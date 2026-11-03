package dbamanager.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import org.hibernate.type.NumericBooleanConverter;

import java.time.OffsetDateTime;

@Entity
@Table(name = "executions")
public class Execution extends PanacheEntityBase {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    @ManyToOne(optional = false) @JoinColumn(name = "request_id") public ChangeRequest request;
    @Column(name = "executed_by") public String executedBy;
    /** AUTO (por la app) / MANUAL (el DBA fuera de la app). */
    @Column(name = "mode_") public String mode;
    public String status;
    @Lob public String log;
    @Lob public String notes;
    @ManyToOne @JoinColumn(name = "key_id") public DbaKey key;
    @Lob public String signature;
    @Column(name = "started_at") public OffsetDateTime startedAt = OffsetDateTime.now();
    @Column(name = "finished_at") public OffsetDateTime finishedAt;
}
